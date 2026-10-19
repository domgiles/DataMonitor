package com.dom.datamonitor.db;

import com.dom.datamonitor.config.ConnectionConfig;
import com.dom.datamonitor.model.ProcessingMode;
import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Executes a SQL query on a fixed schedule and publishes results to registered listeners.
 *
 * <p>Each instance runs on its own daemon thread. Results are published on the scheduler
 * thread; use {@link javax.swing.SwingUtilities#invokeLater} in the listener when updating
 * Swing components.
 *
 * <h3>Rate-based processing (DIFFERENCE, CHANGE_PER_MIN, CHANGE_PER_HOUR)</h3>
 * The executor stores the previous raw value per series key and emits a time-normalised
 * delta.  The first execution always emits 0 for all series to avoid spurious spikes.
 * <ul>
 *   <li>DIFFERENCE      – delta / refreshRateSeconds  (per-second rate)</li>
 *   <li>CHANGE_PER_MIN  – delta * 60   / refreshRateSeconds  (per-minute rate)</li>
 *   <li>CHANGE_PER_HOUR – delta * 3600 / refreshRateSeconds  (per-hour rate)</li>
 * </ul>
 *
 * <h3>CUMULATIVE_SINCE_START processing</h3>
 * The executor records the initial raw value per series key on first observation and
 * emits {@code current − initial} on every subsequent execution.
 */
public class SQLExecutor {

    private static final Logger log = Logger.getLogger(SQLExecutor.class.getName());

    private final String           sql;
    private final ProcessingMode   processingMode;
    private final double           divideBy;
    private final ConnectionConfig connectionConfig;
    private final int              refreshRateSeconds;

    /** Callbacks – called on the scheduler thread. */
    private Consumer<QueryResult> onResult = r -> {};
    private Consumer<String>      onError  = e -> {};

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "SQLExecutor-" + System.nanoTime());
        t.setDaemon(true);
        return t;
    });

    private ScheduledFuture<?> future;

    /** Previous raw values per series key (for rate-based modes). */
    private final Map<String, Double> previousValues = new HashMap<>();
    /** Baseline values per series key captured on first observation (for CUMULATIVE_SINCE_START). */
    private final Map<String, Double> initialValues  = new HashMap<>();
    private boolean firstRun = true;

    // ─────────────────────────────────────────────────────────────────────────

    public SQLExecutor(String sql, ProcessingMode processingMode, double divideBy,
                       ConnectionConfig connectionConfig, int refreshRateSeconds) {
        this.sql                = sql;
        this.processingMode     = processingMode;
        this.divideBy           = divideBy;
        this.connectionConfig   = connectionConfig;
        this.refreshRateSeconds = refreshRateSeconds;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    /** Registers the result callback (called with the processed {@link QueryResult}). */
    public SQLExecutor onResult(Consumer<QueryResult> handler) {
        this.onResult = handler;
        return this;
    }

    /** Registers the error callback (called with a user-friendly error message). */
    public SQLExecutor onError(Consumer<String> handler) {
        this.onError = handler;
        return this;
    }

    /** Starts periodic query execution. */
    public void start() {
        future = scheduler.scheduleAtFixedRate(
            this::execute, 0, refreshRateSeconds, TimeUnit.SECONDS);
    }

    /** Stops execution and shuts down the scheduler thread. */
    public void stop() {
        if (future != null) future.cancel(false);
        scheduler.shutdownNow();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Execution
    // ─────────────────────────────────────────────────────────────────────────

    private void execute() {
        try (Connection conn = ConnectionManager.getInstance().getConnection(connectionConfig);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            QueryResult raw = QueryResultParser.parse(rs, divideBy);

            if (raw.isError()) {
                onError.accept(raw.errorMessage());
                return;
            }

            QueryResult processed = applyProcessingMode(raw);
            onResult.accept(processed);

        } catch (SQLException e) {
            String msg = "SQL error: " + e.getMessage();
            log.log(Level.WARNING, msg, e);
            onError.accept(msg);
        } catch (RuntimeException e) {
            String msg = "Connection error: " + e.getMessage();
            log.log(Level.WARNING, msg, e);
            onError.accept(msg);
        }
    }

    private QueryResult applyProcessingMode(QueryResult raw) {
        if (processingMode == ProcessingMode.NONE) {
            firstRun = false;
            return raw;
        }

        if (processingMode == ProcessingMode.CUMULATIVE_SINCE_START) {
            List<QueryRow> processed = new ArrayList<>(raw.size());
            for (QueryRow row : raw.rows()) {
                String key        = row.key();
                double currentRaw = row.value();
                initialValues.putIfAbsent(key, currentRaw);
                double cumulative = currentRaw - initialValues.get(key);
                processed.add(new QueryRow(key, cumulative, row.color()));
            }
            firstRun = false;
            return QueryResult.success(processed);
        }

        // Rate-based modes: DIFFERENCE, CHANGE_PER_MIN, CHANGE_PER_HOUR
        List<QueryRow> processed = new ArrayList<>(raw.size());
        for (QueryRow row : raw.rows()) {
            String key        = row.key();
            double currentRaw = row.value();
            double prevRaw    = previousValues.getOrDefault(key, currentRaw);
            double rawDelta   = firstRun ? 0.0 : Math.max(0.0, currentRaw - prevRaw);

            double displayValue = switch (processingMode) {
                case DIFFERENCE      -> rawDelta / refreshRateSeconds;
                case CHANGE_PER_MIN  -> rawDelta * 60.0   / refreshRateSeconds;
                case CHANGE_PER_HOUR -> rawDelta * 3600.0 / refreshRateSeconds;
                default              -> rawDelta;
            };

            previousValues.put(key, currentRaw);
            processed.add(new QueryRow(key, displayValue, row.color()));
        }
        firstRun = false;
        return QueryResult.success(processed);
    }
}
