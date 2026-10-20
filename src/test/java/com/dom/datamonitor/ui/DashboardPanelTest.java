package com.dom.datamonitor.ui;

import com.dom.datamonitor.charts.Theme;
import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.QModuleConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Runtime add / replace / remove; executors are never started, so no database is needed. */
class DashboardPanelTest {

    private AppConfig      config;
    private DashboardPanel dashboard;

    @BeforeEach
    void setUp() throws Exception {
        config = new AppConfig();
        config.setQmodules(List.of(module("A", "R1,C1"), module("B", "R1,C2-3")));   // immutable on purpose
        dashboard = new DashboardPanel(config, Theme.DARK);
    }

    private static QModuleConfig module(String title, String grid) {
        QModuleConfig m = new QModuleConfig();
        m.setTitle(title);
        m.setGrid(grid);
        m.setChartType("TEXT");
        m.setSql("SELECT 'x', 1 FROM dual");
        return m;
    }

    private List<String> titles() {
        return config.getQmodules().stream().map(QModuleConfig::getTitle).toList();
    }

    @Test
    void addAppendsToScreenAndConfig() throws Exception {
        dashboard.addModule(module("C", "R2,C1"));
        assertEquals(List.of("A", "B", "C"), titles());
        assertEquals(3, dashboard.getModules().size());
        assertSame(dashboard.getModules().get(2).getConfig(), config.getQmodules().get(2));
    }

    @Test
    void replaceKeepsPositionInConfig() throws Exception {
        QModulePanel a = dashboard.getModules().get(0);
        dashboard.replaceModule(a, module("A2", "R3,C1"));

        assertEquals(List.of("A2", "B"), titles());
        assertFalse(dashboard.getModules().contains(a));
        assertEquals(3, dashboard.getModules().get(0).getGridLocation().row());
    }

    @Test
    void failedReplaceChangesNothing() {
        QModulePanel a = dashboard.getModules().get(0);
        assertThrows(Exception.class, () -> dashboard.replaceModule(a, module("bad", "nonsense")));
        assertEquals(List.of("A", "B"), titles());
        assertSame(a, dashboard.getModules().get(0));
    }

    @Test
    void removeDropsFromScreenAndConfig() {
        dashboard.removeModule(dashboard.getModules().get(1));
        assertEquals(List.of("A"), titles());
        assertEquals(1, dashboard.getModules().size());
    }

    @Test
    void occupancyCanExcludeTheModuleBeingEdited() {
        QModulePanel b = dashboard.getModules().get(1);
        assertFalse(dashboard.occupancyExcluding(null).isFree(1, 3));
        assertTrue(dashboard.occupancyExcluding(b).isFree(1, 3));
        assertEquals("A", dashboard.occupancyExcluding(b).ownerAt(1, 1));
    }

    @Test
    void everyModuleHasAnEditDeleteMenu() {
        for (QModulePanel m : dashboard.getModules()) {
            assertNotNull(m.getComponentPopupMenu(), m.getTitle());
        }
        assertNotNull(dashboard.getComponentPopupMenu());
    }

    @Test
    void emptyDashboardIsAllowed() throws Exception {
        AppConfig empty = new AppConfig();
        empty.setQmodules(null);
        DashboardPanel panel = new DashboardPanel(empty, Theme.DARK);
        panel.addModule(module("first", "R1,C1"));
        assertEquals(1, empty.getQmodules().size());
    }
}
