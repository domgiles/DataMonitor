package com.dom.datamonitor.db;

import com.dom.datamonitor.config.ConnectionConfig;
import com.dom.datamonitor.config.PasswordCipher;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.sql.SQLRecoverableException;

import static org.junit.jupiter.api.Assertions.*;

/** Error descriptions and pool keys; no database involved. */
class ConnectionManagerTest {

    /** Mimics UCP's wrapping: UCP-0 → UCP-45257 → the real ORA error. */
    private static SQLException ucpWrapped(SQLException oracle) {
        Exception ucp = new Exception("UCP-45257: Cannot get Connection from Datasource", oracle);
        return new SQLException("UCP-0: Unable to start the Universal Connection Pool", ucp);
    }

    @Test
    void describeSurfacesTheOracleErrorWithAHint() {
        SQLException e = ucpWrapped(new SQLException(
            "ORA-01017: invalid credential or not authorized; logon denied\nhttps://docs.oracle.com/error-help/db/ora-01017/",
            "72000", 1017));

        assertEquals("ORA-01017: invalid credential or not authorized; logon denied "
            + "(check the username and password)", ConnectionManager.describe(e));
    }

    @Test
    void describeDropsTheConnectionId() {
        SQLException e = ucpWrapped(new SQLRecoverableException(
            "ORA-12514: Cannot connect to database. Service x is not registered with the listener "
                + "at host localhost port 1521. (CONNECTION_ID=abc+/==)", null, 12514));

        String d = ConnectionManager.describe(e);
        assertFalse(d.contains("CONNECTION_ID"), d);
        assertTrue(d.startsWith("ORA-12514: Cannot connect"), d);
        assertTrue(d.endsWith("(check the service name in the connect string)"), d);
    }

    @Test
    void describeFallsBackToTheTopMessage() {
        assertEquals("Connection refused", ConnectionManager.describe(new SQLException("Connection refused")));
        assertEquals("SQLException", ConnectionManager.describe(new SQLException()));
    }

    @Test
    void poolKeyChangesWithPasswordButNeverContainsIt() {
        ConnectionConfig a = conn("secret1");
        ConnectionConfig b = conn("secret2");

        assertNotEquals(ConnectionManager.poolKey(a), ConnectionManager.poolKey(b));
        assertEquals(ConnectionManager.poolKey(a), ConnectionManager.poolKey(conn("secret1")));
        assertTrue(ConnectionManager.poolKey(a).startsWith("scott@//h/s#"));
        assertFalse(ConnectionManager.poolKey(a).contains("secret1"));
    }

    @Test
    void decodesEncodedPasswords() {
        assertEquals("tiger", ConnectionManager.decodePassword("enc:dGlnZXI="));
        assertEquals("tiger", ConnectionManager.decodePassword("tiger"));
        assertEquals("",      ConnectionManager.decodePassword(null));
        assertEquals("tiger", ConnectionManager.decodePassword(PasswordCipher.encrypt("tiger")));
    }

    private static ConnectionConfig conn(String password) {
        ConnectionConfig c = new ConnectionConfig();
        c.setUsername("scott");
        c.setPassword(password);
        c.setConnectString("//h/s");
        return c;
    }
}
