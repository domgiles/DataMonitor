package com.dom.datamonitor.engine;

import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.ConfigException;
import com.dom.datamonitor.config.ConfigLoader;
import com.dom.datamonitor.config.ConfigWriter;
import com.dom.datamonitor.config.ConnectionConfig;
import com.dom.datamonitor.config.QModuleConfig;
import com.dom.datamonitor.db.SQLExecutor;
import com.dom.datamonitor.model.ProcessingMode;
import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class MonitorEngineTest {

    /** Executor that never touches a database; tests push results through {@link #emit}. */
    static final class FakeExecutor extends SQLExecutor {
        Consumer<QueryResult> handler = r -> {};
        boolean started, stopped;

        FakeExecutor() {
            super("SELECT 1 FROM dual", ProcessingMode.NONE, 1.0, null, 1);
        }

        @Override public SQLExecutor onResult(Consumer<QueryResult> h) { handler = h; return super.onResult(h); }
        @Override public void start() { started = true; }
        @Override public void stop()  { stopped = true; }

        void emit(double value) {
            handler.accept(QueryResult.success(List.of(new QueryRow("v", value))));
        }
    }

    private final Map<String, FakeExecutor> executors = new HashMap<>();   // by module title (latest)
    private final List<FakeExecutor>        created   = new ArrayList<>();  // every executor, in order
    private final List<String> events = new ArrayList<>();
    private MonitorEngine engine;
    private AppConfig config;
    private Path file;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        file = dir.resolve("dm.yaml");
        Files.copy(Path.of("src/test/resources/sample-datamonitor.yaml"), file);
        config = ConfigLoader.load(file.toFile());
        engine = new MonitorEngine(config, new ConfigWriter(file.toFile()), (m, conn, mode, rate) -> {
            FakeExecutor e = new FakeExecutor();
            executors.put(m.getTitle(), e);
            created.add(e);
            return e;
        });
        engine.addListener(new MonitorEngine.Listener() {
            @Override public void onResult(String id, MonitorEngine.Sample s) { events.add("result " + id); }
            @Override public void onLayoutChanged()                         { events.add("layout"); }
        });
        engine.start();
    }

    private MonitorEngine.ModuleView byTitle(String title) {
        return engine.modules().stream().filter(m -> m.config().getTitle().equals(title)).findFirst().orElseThrow();
    }

    private static QModuleConfig module(String title, String grid, String sql) {
        QModuleConfig m = new QModuleConfig();
        m.setTitle(title);
        m.setGrid(grid);
        m.setChartType("LINE");
        m.setSql(sql);
        return m;
    }

    @Test
    void startsOneExecutorPerModuleWithIdsInConfigOrder() {
        assertEquals(config.getQmodules().size(), created.size());
        assertTrue(created.stream().allMatch(e -> e.started));
        List<MonitorEngine.ModuleView> views = engine.modules();
        for (int i = 0; i < views.size(); i++) assertSame(config.getQmodules().get(i), views.get(i).config());
        assertEquals("m1", views.get(0).id());
    }

    @Test
    void keepsAWindowOfHistoryForTimeSeries() {
        MonitorEngine.ModuleView io = byTitle("I/O by File Type (MB/sec)");   // AREA, refresh 2, window 120
        FakeExecutor e = executors.get("I/O by File Type (MB/sec)");
        for (int i = 0; i < 100; i++) e.emit(i);

        List<MonitorEngine.Sample> history = engine.module(io.id()).history();
        assertEquals(120 / 2 + 1, history.size());
        assertEquals(99.0, history.get(history.size() - 1).rows().get(0).value());
        assertTrue(events.contains("result " + io.id()));
    }

    @Test
    void addValidatesSavesAndStarts() throws Exception {
        String id = engine.add(module("New one", "R9,C1", "SELECT 'n', 1 FROM dual"));

        assertEquals("New one", engine.module(id).config().getTitle());
        assertTrue(executors.get("New one").started);
        assertTrue(Files.readString(file).contains("title: New one"));
        assertTrue(Files.exists(file.resolveSibling("dm.yaml.bak")));
        assertTrue(events.contains("layout"));
    }

    @Test
    void rejectsOverlapsAndNonQueries() {
        assertThrows(ConfigException.class, () -> engine.add(module("Clash", "R1,C1", "SELECT 'x', 1 FROM dual")));
        assertThrows(ConfigException.class, () -> engine.add(module("DML", "R9,C1", "DELETE FROM t")));
        assertFalse(executors.containsKey("Clash"));
        assertFalse(events.contains("layout"));
    }

    @Test
    void updateReplacesInPlaceAndDropsLateResultsFromTheOldExecutor() throws Exception {
        MonitorEngine.ModuleView tx = byTitle("Tx/Sec");
        FakeExecutor old = executors.get("Tx/Sec");
        int index = config.getQmodules().indexOf(tx.config());

        QModuleConfig edited = tx.config().copy();
        edited.setTitle("Tx per second");
        engine.update(tx.id(), edited);

        assertTrue(old.stopped);
        assertEquals("Tx per second", config.getQmodules().get(index).getTitle());
        assertEquals("Tx per second", engine.module(tx.id()).config().getTitle());

        old.emit(42);   // a result that was still in flight when the module was replaced
        assertTrue(engine.module(tx.id()).history().isEmpty());
    }

    @Test
    void deleteStopsAndRemoves() throws Exception {
        MonitorEngine.ModuleView iops = byTitle("IOPs");
        engine.delete(iops.id());

        assertTrue(executors.get("IOPs").stopped);
        assertNull(engine.module(iops.id()));
        assertFalse(Files.readString(file).contains("title: IOPs"));
        assertThrows(ConfigException.class, () -> engine.delete(iops.id()));
    }

    @Test
    void moduleConnectionIsUsedForItsExecutor(@TempDir Path dir) throws Exception {
        List<ConnectionConfig> used = new ArrayList<>();
        AppConfig c = ConfigLoader.load(new File("src/test/resources/sample-datamonitor.yaml"));
        MonitorEngine e = new MonitorEngine(c, null, (m, conn, mode, rate) -> { used.add(conn); return new FakeExecutor(); });
        e.start();
        assertTrue(used.stream().allMatch(conn -> conn.getConnectString().equals("//localhost/soe")));

        QModuleConfig own = module("Other DB", "R9,C1", "SELECT 'x', 1 FROM dual");
        ConnectionConfig conn = new ConnectionConfig();
        conn.setUsername("u");
        conn.setPassword("p");
        conn.setConnectString("//other/db");
        own.setConnection(conn);
        e.add(own);
        assertEquals("//other/db", used.get(used.size() - 1).getConnectString());
    }
}
