package com.dom.datamonitor.engine;

import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.ConfigException;
import com.dom.datamonitor.config.ConfigValidator;
import com.dom.datamonitor.config.ConfigWriter;
import com.dom.datamonitor.config.ConnectionConfig;
import com.dom.datamonitor.config.QModuleConfig;
import com.dom.datamonitor.db.SQLExecutor;
import com.dom.datamonitor.db.SqlValidator;
import com.dom.datamonitor.model.ChartType;
import com.dom.datamonitor.model.GridLocation;
import com.dom.datamonitor.model.ProcessingMode;
import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * UI-independent dashboard runtime used by the web front end.
 *
 * <p>Owns one {@link SQLExecutor} per QModule, keeps a rolling history of results per
 * module (so a browser that connects later still sees the recent time series), and
 * applies add / update / delete requests: each is validated with {@link ConfigValidator}
 * against the whole dashboard, applied, and saved via {@link ConfigWriter}.
 *
 * <p>Modules get a short id (e.g. {@code m3}) for the lifetime of the process; ids are
 * not saved. Thread-safe: structural changes are synchronized, results arrive on the
 * executor threads and are passed to {@link Listener}s on those threads.
 */
public class MonitorEngine {

    private static final Logger log = Logger.getLogger(MonitorEngine.class.getName());

    /** One execution result as kept in the history: epoch millis + rows, or an error. */
    public record Sample(long time, List<QueryRow> rows, String error) {}

    /** Snapshot of a module for the API: its config, effective settings and history. */
    public record ModuleView(String id, QModuleConfig config, GridLocation location,
                             int refreshRate, int windowSize, List<Sample> history) {}

    /** Receives results and layout changes; called on executor / request threads. */
    public interface Listener {
        void onResult(String moduleId, Sample sample);
        void onLayoutChanged();
    }

    /** Creates the executor for a module; replaced in tests so no database is needed. */
    @FunctionalInterface
    public interface ExecutorFactory {
        SQLExecutor create(QModuleConfig module, ConnectionConfig connection, ProcessingMode mode, int refreshRate);
    }

    private final AppConfig       config;
    private final ConfigWriter    writer;         // null: changes are not saved (tests)
    private final ExecutorFactory executorFactory;
    private final AtomicLong      nextId = new AtomicLong(1);

    /** Insertion order matches {@code config.getQmodules()}. */
    private final Map<String, Runtime> modules   = new LinkedHashMap<>();
    private final List<Listener>       listeners = new CopyOnWriteArrayList<>();
    private boolean running;

    // ─────────────────────────────────────────────────────────────────────────

    public MonitorEngine(AppConfig config, ConfigWriter writer) {
        this(config, writer, (m, conn, mode, rate) ->
            new SQLExecutor(m.getSql().trim(), mode, m.getDivideBy(), conn, rate));
    }

    public MonitorEngine(AppConfig config, ConfigWriter writer, ExecutorFactory executorFactory) {
        this.config          = config;
        this.writer          = writer;
        this.executorFactory = executorFactory;

        config.setQmodules(config.getQmodules() == null
            ? new ArrayList<>() : new ArrayList<>(config.getQmodules()));
        for (QModuleConfig m : config.getQmodules()) {
            Runtime r = new Runtime(newId(), m);
            modules.put(r.id, r);
        }
    }

    public AppConfig.Settings settings() {
        return config.getSettings();
    }

    public void addListener(Listener l)    { listeners.add(l);    }
    public void removeListener(Listener l) { listeners.remove(l); }

    public synchronized void start() {
        running = true;
        modules.values().forEach(Runtime::start);
    }

    public synchronized void stop() {
        running = false;
        modules.values().forEach(Runtime::stop);
    }

    /** Current modules in dashboard order, with their history. */
    public synchronized List<ModuleView> modules() {
        List<ModuleView> views = new ArrayList<>();
        for (Runtime r : modules.values()) views.add(r.view());
        return views;
    }

    public synchronized ModuleView module(String id) {
        Runtime r = modules.get(id);
        return (r != null) ? r.view() : null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Changes
    // ─────────────────────────────────────────────────────────────────────────

    /** Adds a module. @return its id */
    public String add(QModuleConfig module) throws ConfigException, IOException {
        String id;
        synchronized (this) {
            checkReadOnly(module);
            List<QModuleConfig> candidate = new ArrayList<>(config.getQmodules());
            candidate.add(module);
            validate(candidate);

            Runtime r = new Runtime(newId(), module);
            modules.put(r.id, r);
            config.getQmodules().add(module);
            if (running) r.start();
            id = r.id;
            save();
        }
        fireLayoutChanged();
        return id;
    }

    /** Replaces module {@code id}, keeping its position. History restarts, as the query may differ. */
    public void update(String id, QModuleConfig module) throws ConfigException, IOException {
        synchronized (this) {
            Runtime old = require(id);
            checkReadOnly(module);
            int index = config.getQmodules().indexOf(old.config);
            List<QModuleConfig> candidate = new ArrayList<>(config.getQmodules());
            candidate.set(index, module);
            validate(candidate);

            old.stop();
            Runtime r = new Runtime(id, module);
            modules.put(id, r);     // same key: keeps its position in the map
            config.getQmodules().set(index, module);
            if (running) r.start();
            save();
        }
        fireLayoutChanged();
    }

    public void delete(String id) throws ConfigException, IOException {
        synchronized (this) {
            Runtime r = require(id);
            r.stop();
            modules.remove(id);
            config.getQmodules().remove(r.config);
            save();
        }
        fireLayoutChanged();
    }

    /** Runs {@link SqlValidator#validate} with the dashboard connection unless {@code connection} is given. */
    public SqlValidator.Result validateSql(String sql, ConnectionConfig connection, ChartType type, double divideBy) {
        ConnectionConfig conn = (connection != null) ? connection : config.getSettings().getEffectiveConnection();
        if (conn == null) return new SqlValidator.Result(false, "No connection configured.", List.of(), List.of());
        return SqlValidator.validate(sql, conn, type, divideBy);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Runtime require(String id) throws ConfigException {
        Runtime r = modules.get(id);
        if (r == null) throw new ConfigException("No QModule with id " + id);
        return r;
    }

    /** The scheduler runs a module's SQL forever, so only read-only queries are accepted. */
    private static void checkReadOnly(QModuleConfig module) throws ConfigException {
        String problem = SqlValidator.readOnlyProblem(module.getSql());
        if (problem != null) throw new ConfigException(problem);
    }

    /** Validates the whole dashboard as it would be after the change. */
    private void validate(List<QModuleConfig> candidate) throws ConfigException {
        AppConfig probe = new AppConfig();
        probe.setSettings(config.getSettings());
        probe.setQmodules(candidate);
        new ConfigValidator().validate(probe);
    }

    private void save() throws IOException {
        if (writer != null) writer.save(config);
    }

    private String newId() {
        return "m" + nextId.getAndIncrement();
    }

    private void fireLayoutChanged() {
        for (Listener l : listeners) {
            try {
                l.onLayoutChanged();
            } catch (RuntimeException e) {
                log.log(Level.WARNING, "Listener failed", e);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** A module's executor and history. */
    private final class Runtime {
        final String        id;
        final QModuleConfig config;
        final GridLocation  location;
        final int           refreshRate;
        final int           windowSize;
        final int           capacity;
        final ArrayDeque<Sample> history = new ArrayDeque<>();
        SQLExecutor executor;
        /** Cleared on stop, so a result still in flight from a replaced module is dropped. */
        volatile boolean active;

        Runtime(String id, QModuleConfig config) {
            this.id     = id;
            this.config = config;
            GridLocation loc;
            try {
                loc = GridLocation.parse(config.getGrid());
            } catch (ConfigException e) {
                loc = new GridLocation(1, 1, 1);   // validated configs never get here
            }
            this.location    = loc;
            this.refreshRate = config.getRefreshRate() != null ? config.getRefreshRate() : settings().getRefreshRate();
            this.windowSize  = config.getWindowSize()  != null ? config.getWindowSize()  : settings().getWindowSize();
            // Time series keep a window of history, and TEXT tiles keep one for their trend
            // sparkline; other charts only need the latest result
            ChartType type = chartType();
            boolean windowed = type.isTimeSeries() || type == ChartType.TEXT;
            this.capacity = windowed ? Math.max(2, windowSize / Math.max(1, refreshRate) + 1) : 1;
        }

        ChartType chartType() {
            try {
                return ChartType.fromString(config.getChartType());
            } catch (ConfigException e) {
                return ChartType.LINE;
            }
        }

        void start() {
            ProcessingMode mode;
            try {
                mode = ProcessingMode.fromString(config.getProcessingMode());
            } catch (ConfigException e) {
                mode = ProcessingMode.NONE;
            }
            ConnectionConfig conn = config.getConnection() != null
                ? config.getConnection() : settings().getEffectiveConnection();

            active   = true;
            executor = executorFactory.create(config, conn, mode, refreshRate);
            executor.onResult(this::record);
            executor.onError(msg -> record(QueryResult.error(msg)));
            executor.start();
        }

        void stop() {
            active = false;
            if (executor != null) executor.stop();
            executor = null;
        }

        void record(QueryResult result) {
            if (!active) return;
            Sample s = new Sample(result.timestamp().toEpochMilli(),
                result.isError() ? List.of() : result.rows(),
                result.isError() ? result.errorMessage() : null);
            synchronized (history) {
                if (history.size() >= capacity) history.pollFirst();
                history.addLast(s);
            }
            for (Listener l : listeners) {
                try {
                    l.onResult(id, s);
                } catch (RuntimeException e) {
                    log.log(Level.WARNING, "Listener failed", e);
                }
            }
        }

        ModuleView view() {
            List<Sample> copy;
            synchronized (history) {
                copy = new ArrayList<>(history);
            }
            return new ModuleView(id, config, location, refreshRate, windowSize, copy);
        }
    }
}
