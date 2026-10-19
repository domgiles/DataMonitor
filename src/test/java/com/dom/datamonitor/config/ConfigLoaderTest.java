package com.dom.datamonitor.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ConfigLoaderTest {

    @Test
    void appliesDefaultsForOmittedSettings() throws ConfigException {
        AppConfig config = ConfigLoader.loadFromResource("/minimal.yaml");
        AppConfig.Settings s = config.getSettings();

        assertEquals("Data Monitor", s.getTitle());
        assertEquals("dark", s.getTheme());
        assertEquals(5,   s.getRefreshRate());
        assertEquals(120, s.getWindowSize());
        assertEquals(8,   s.getBorder());
        assertEquals(3,   s.getColumns());
        assertTrue(s.isFlashOnChange());
        assertEquals("scott", s.getConnection().getUsername());

        QModuleConfig m = config.getQmodules().get(0);
        assertEquals("Sessions", m.getTitle());
        assertEquals(1.0, m.getDivideBy());
        assertNull(m.getProcessingMode());
        assertNull(m.getRefreshRate());

        new ConfigValidator().validate(config);
    }

    @Test
    void shippedSampleConfigLoadsAndValidates() throws ConfigException {
        // Tests run with the project root as the working directory (see build.xml 'test' target)
        AppConfig config = ConfigLoader.load(new File("datamonitor.yaml"));
        new ConfigValidator().validate(config);
        assertFalse(config.getQmodules().isEmpty());
    }

    @Test
    void missingFileIsReported() {
        ConfigException e = assertThrows(ConfigException.class,
            () -> ConfigLoader.load(new File("does-not-exist.yaml")));
        assertTrue(e.getMessage().contains("not found"));
    }

    @Test
    void emptyFileIsReported(@TempDir Path dir) throws IOException {
        File f = Files.writeString(dir.resolve("empty.yaml"), "").toFile();
        ConfigException e = assertThrows(ConfigException.class, () -> ConfigLoader.load(f));
        assertTrue(e.getMessage().contains("empty"));
    }

    @Test
    void unknownPropertyIsReported(@TempDir Path dir) throws IOException {
        File f = Files.writeString(dir.resolve("bad.yaml"), "settings:\n  colour: blue\n").toFile();
        assertThrows(ConfigException.class, () -> ConfigLoader.load(f));
    }

    @Test
    void duplicateKeysAreRejected(@TempDir Path dir) throws IOException {
        File f = Files.writeString(dir.resolve("dup.yaml"),
            "settings:\n  title: a\n  title: b\n").toFile();
        assertThrows(ConfigException.class, () -> ConfigLoader.load(f));
    }
}
