package com.dom.datamonitor.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ConfigWriterTest {

    @Test
    void shippedSampleRoundTripsWithoutLosingAnything(@TempDir Path dir) throws Exception {
        AppConfig original = ConfigLoader.load(new File("datamonitor.yaml"));
        File out = dir.resolve("copy.yaml").toFile();
        new ConfigWriter(out).save(original);

        AppConfig reloaded = ConfigLoader.load(out);
        new ConfigValidator().validate(reloaded);
        assertEquals(ConfigWriter.toYaml(original), ConfigWriter.toYaml(reloaded));
        assertEquals(original.getQmodules().size(), reloaded.getQmodules().size());
    }

    @Test
    void writesSqlAsLiteralBlockAndOmitsUnsetOptionals() {
        QModuleConfig m = module("SELECT 'x',   \n       1 FROM dual   ");
        String yaml = ConfigWriter.toYaml(config(m));

        assertTrue(yaml.contains("sql: |\n"), yaml);
        assertTrue(yaml.contains("SELECT 'x',\n"), "trailing spaces stripped:\n" + yaml);
        assertFalse(yaml.contains("processingMode"), yaml);
        assertFalse(yaml.contains("divideBy"), yaml);
        assertFalse(yaml.contains("comment"), yaml);
    }

    @Test
    void writesWholeDivideByWithoutDecimal() {
        QModuleConfig m = module("SELECT 'x', 1 FROM dual");
        m.setDivideBy(1048576);
        assertTrue(ConfigWriter.toYaml(config(m)).contains("divideBy: 1048576\n"));
    }

    @Test
    void backsUpOriginalOnFirstSaveOnly(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("dm.yaml");
        Files.writeString(file, "# hand-written comment\n");
        ConfigWriter writer = new ConfigWriter(file.toFile());

        writer.save(config(module("SELECT 'a', 1 FROM dual")));
        writer.save(config(module("SELECT 'b', 2 FROM dual")));

        assertEquals("# hand-written comment\n", Files.readString(writer.backupPath()));
        String saved = Files.readString(file);
        assertTrue(saved.startsWith("# Saved by New Data Monitor"), saved);
        assertTrue(saved.contains("'b'"), saved);
        assertFalse(Files.exists(dir.resolve("dm.yaml.tmp")));
    }

    @Test
    void copyIsDeep() {
        QModuleConfig m = module("SELECT 'x', 1 FROM dual");
        ConnectionConfig c = new ConnectionConfig();
        c.setUsername("a");
        m.setConnection(c);

        QModuleConfig copy = m.copy();
        copy.setTitle("changed");
        copy.getConnection().setUsername("b");

        assertEquals("T", m.getTitle());
        assertEquals("a", m.getConnection().getUsername());
    }

    private static QModuleConfig module(String sql) {
        QModuleConfig m = new QModuleConfig();
        m.setTitle("T");
        m.setGrid("R1,C1");
        m.setChartType("LINE");
        m.setSql(sql);
        return m;
    }

    private static AppConfig config(QModuleConfig m) {
        ConnectionConfig conn = new ConnectionConfig();
        conn.setUsername("u");
        conn.setPassword("enc:cA==");
        conn.setConnectString("//h/s");
        AppConfig c = new AppConfig();
        c.getSettings().setConnection(conn);
        c.setQmodules(List.of(m));
        return c;
    }
}
