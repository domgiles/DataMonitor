package com.dom.datamonitor.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {

    private AppConfig config;

    @BeforeEach
    void validConfig() {
        ConnectionConfig conn = new ConnectionConfig();
        conn.setUsername("scott");
        conn.setPassword("tiger");
        conn.setConnectString("//localhost/freepdb1");

        config = new AppConfig();
        config.getSettings().setConnection(conn);
        config.setQmodules(new ArrayList<>(List.of(module("R1,C1"))));
    }

    private static QModuleConfig module(String grid) {
        QModuleConfig m = new QModuleConfig();
        m.setTitle("Module " + grid);
        m.setGrid(grid);
        m.setChartType("LINE");
        m.setSql("SELECT 'x', 1 FROM dual");
        return m;
    }

    private String errorsFor(AppConfig c) {
        return assertThrows(ConfigException.class, () -> new ConfigValidator().validate(c)).getMessage();
    }

    @Test
    void acceptsValidConfig() {
        assertDoesNotThrow(() -> new ConfigValidator().validate(config));
    }

    @Test
    void reportsEveryProblemInOnePass() {
        config.getSettings().setTheme("purple");
        config.getSettings().setColumns(4);
        config.getSettings().setRefreshRate(0);
        QModuleConfig m = config.getQmodules().get(0);
        m.setChartType("SCATTER");
        m.setProcessingMode("AVERAGE");
        m.setDivideBy(0);

        String msg = errorsFor(config);
        assertTrue(msg.contains("settings.theme"), msg);
        assertTrue(msg.contains("settings.columns"), msg);
        assertTrue(msg.contains("settings.refreshRate"), msg);
        assertTrue(msg.contains("qmodules[1].chartType"), msg);
        assertTrue(msg.contains("qmodules[1].processingMode"), msg);
        assertTrue(msg.contains("qmodules[1].divideBy"), msg);
    }

    @Test
    void requiresGlobalConnectionFields() {
        config.getSettings().getConnection().setPassword(" ");
        assertTrue(errorsFor(config).contains("settings.connection.password is required"));

        config.getSettings().setConnection(null);
        assertTrue(errorsFor(config).contains("settings.connection is required"));
    }

    @Test
    void allowsEmptyDashboard() {
        config.setQmodules(List.of());
        assertDoesNotThrow(() -> new ConfigValidator().validate(config));
    }

    @Test
    void detectsOverlappingCells() {
        config.getQmodules().add(module("R1,C1*"));
        assertTrue(errorsFor(config).contains("overlaps with another QModule at cell R1C1"));
    }

    @Test
    void detectsOverlapWithMultiRowModuleOnce() {
        config.getQmodules().add(module("R1-3,C2-3"));
        config.getQmodules().add(module("R2-3,C3"));
        String errors = errorsFor(config);
        assertTrue(errors.contains("qmodules[3].grid overlaps with another QModule at cells R2C3, R3C3"), errors);
        assertEquals(1, errors.split("overlaps").length - 1, errors);
    }

    @Test
    void acceptsMultiRowModulesThatTile() {
        config.getQmodules().add(module("R1-2,C2-3"));
        config.getQmodules().add(module("R2-4,C1"));
        config.getQmodules().add(module("R3,C2*"));
        assertDoesNotThrow(() -> new ConfigValidator().validate(config));
    }

    @Test
    void detectsColumnBeyondConfiguredColumns() {
        config.getSettings().setColumns(2);
        config.getQmodules().add(module("R2,C1-3"));
        assertTrue(errorsFor(config).contains("exceeds settings.columns"));
    }

    @Test
    void detectsSpanAllStartingBeyondConfiguredColumns() {
        config.getQmodules().add(module("R2,C4*"));
        assertTrue(errorsFor(config).contains("exceeds settings.columns"));
    }

    @Test
    void rejectsEncodedPasswordThatIsNotBase64() {
        config.getSettings().getConnection().setPassword("enc:not*base64!");
        assertTrue(errorsFor(config).contains("settings.connection.password starts with 'enc:'"));

        config.getSettings().getConnection().setPassword("enc:dGlnZXI=");
        assertDoesNotThrow(() -> new ConfigValidator().validate(config));
    }

    @Test
    void validatesModuleConnectionOnlyWhenPresent() {
        ConnectionConfig partial = new ConnectionConfig();
        partial.setUsername("other");
        config.getQmodules().get(0).setConnection(partial);

        String msg = errorsFor(config);
        assertTrue(msg.contains("qmodules[1].connection.password is required"), msg);
        assertTrue(msg.contains("qmodules[1].connection.connectString is required"), msg);
    }
}
