package com.dom.datamonitor.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Command-line connection overrides (--username / --password / --connect-string). */
class ConnectionOverrideTest {

    private static ConnectionConfig fileConnection() {
        ConnectionConfig c = new ConnectionConfig();
        c.setUsername("file_user");
        c.setPassword("file_pw");
        c.setConnectString("//file/host");
        c.setCredentialsFile("/wallet");
        return c;
    }

    private static AppConfig config(ConnectionConfig conn) {
        AppConfig config = new AppConfig();
        config.getSettings().setConnection(conn);
        QModuleConfig m = new QModuleConfig();
        m.setTitle("T");
        m.setGrid("R1,C1");
        m.setChartType("TEXT");
        m.setSql("SELECT 'x', 1 FROM dual");
        config.setQmodules(List.of(m));
        return config;
    }

    @Test
    void withoutOverridesTheFileConnectionIsUsedAsIs() {
        AppConfig.Settings s = config(fileConnection()).getSettings();
        s.overrideConnection(null, null, null);
        assertSame(s.getConnection(), s.getEffectiveConnection());
    }

    @Test
    void eachOverrideReplacesOnlyItsOwnField() {
        AppConfig.Settings s = config(fileConnection()).getSettings();
        s.overrideConnection(null, "cli_pw", null);

        ConnectionConfig c = s.getEffectiveConnection();
        assertEquals("file_user",   c.getUsername());
        assertEquals("cli_pw",      c.getPassword());
        assertEquals("//file/host", c.getConnectString());
        assertEquals("/wallet",     c.getCredentialsFile());
        assertEquals("file_pw", s.getConnection().getPassword(), "file connection is not modified");
    }

    @Test
    void overridesCanSupplyTheWholeConnection() throws ConfigException {
        AppConfig config = config(null);
        config.getSettings().overrideConnection("u", "p", "//cli/host");

        new ConfigValidator().validate(config);
        assertEquals("//cli/host", config.getSettings().getEffectiveConnection().getConnectString());
    }

    @Test
    void missingValuesMentionTheCommandLineOption() {
        ConnectionConfig partial = fileConnection();
        partial.setPassword(null);
        AppConfig config = config(partial);

        String msg = assertThrows(ConfigException.class, () -> new ConfigValidator().validate(config)).getMessage();
        assertTrue(msg.contains("settings.connection.password is required (or pass --password)"), msg);

        config.getSettings().overrideConnection(null, "cli_pw", null);
        assertDoesNotThrow(() -> new ConfigValidator().validate(config));
    }

    @Test
    void overridesAreNeverSaved() {
        AppConfig config = config(fileConnection());
        config.getSettings().overrideConnection("cli_user", "cli_secret", "//cli/host");

        String yaml = ConfigWriter.toYaml(config);
        assertFalse(yaml.contains("cli_"), yaml);
        assertFalse(yaml.contains("file_pw"), "saved encrypted: " + yaml);
        assertEquals("file_pw", PasswordCipher.reveal(yaml.replaceAll("(?s).*password: (\\S+).*", "$1")));
    }

    @Test
    void overridesWithNoFileConnectionSaveNoConnection() {
        AppConfig config = config(null);
        config.getSettings().overrideConnection("u", "secret", "//h/s");
        assertFalse(ConfigWriter.toYaml(config).contains("connection"));
    }
}
