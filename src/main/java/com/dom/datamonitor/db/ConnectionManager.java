package com.dom.datamonitor.db;

import com.dom.datamonitor.config.ConnectionConfig;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages Oracle UCP connection pools, one pool per unique connection configuration.
 *
 * <p>Thread-safe. Obtain a connection via {@link #getConnection(ConnectionConfig)};
 * the pool is created lazily on first use and reused for subsequent requests with
 * the same username, connect string, password and wallet. Including the password
 * means a corrected password gets a fresh pool instead of reusing the failing one.
 *
 * <p>Connection failures are re-thrown with the underlying Oracle error (e.g.
 * {@code ORA-01017}) instead of UCP's generic {@code UCP-0} wrapper; see {@link #describe}.
 *
 * <p>Passwords may be stored as plain text or Base-64 encoded with an {@code enc:}
 * prefix (e.g. {@code enc:bXlwYXNzd29yZA==}).
 */
public class ConnectionManager implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ConnectionManager.class.getName());

    /** Shared singleton instance. */
    private static final ConnectionManager INSTANCE = new ConnectionManager();

    /** Pools keyed by {@link #poolKey}. */
    private final Map<String, PoolDataSource> pools = new ConcurrentHashMap<>();

    private ConnectionManager() {}

    public static ConnectionManager getInstance() { return INSTANCE; }

    // -------------------------------------------------------------------------

    /**
     * Returns a JDBC {@link Connection} from the pool matching the given config.
     * The pool is created if it does not already exist.
     */
    public Connection getConnection(ConnectionConfig config) throws SQLException {
        String key = poolKey(config);
        PoolDataSource pool = pools.computeIfAbsent(key, k -> {
            try {
                return createPool(config);
            } catch (Exception e) {
                throw new RuntimeException("Failed to create connection pool for " + label(config)
                    + ": " + e.getMessage(), e);
            }
        });
        try {
            return pool.getConnection();
        } catch (SQLException e) {
            // UCP reports every failure as "UCP-0: Unable to start the Universal Connection Pool";
            // surface the Oracle error underneath so the user can see what is actually wrong
            SQLException root = rootSqlException(e);
            throw new SQLException(describe(e), root.getSQLState(), root.getErrorCode(), e);
        }
    }

    /**
     * Validates connectivity using the provided config.
     * Throws {@link SQLException} with a descriptive message if the connection cannot be established.
     */
    public void testConnection(ConnectionConfig config) throws SQLException {
        try (Connection c = getConnection(config)) {
            // SELECT 1 FROM DUAL is the lightest possible Oracle round-trip
            try (var ps = c.prepareStatement("SELECT 1 FROM DUAL");
                 var rs = ps.executeQuery()) {
                rs.next();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Pool creation
    // -------------------------------------------------------------------------

    private PoolDataSource createPool(ConnectionConfig config) throws Exception {
        log.fine("Creating connection pool for " + label(config));

        String url = buildUrl(config.getConnectString());
        configureWalletIfNeeded(config);

        PoolDataSource pds = PoolDataSourceFactory.getPoolDataSource();
        pds.setConnectionFactoryClassName("oracle.jdbc.pool.OracleDataSource");
        pds.setConnectionPoolName("dm-pool-" + System.nanoTime());
        pds.setURL(url);
        pds.setUser(config.getUsername());
        pds.setPassword(decodePassword(config.getPassword()));
        pds.setInitialPoolSize(2);
        pds.setMinPoolSize(1);
        pds.setMaxPoolSize(10);
        pds.setConnectionWaitDuration(java.time.Duration.ofSeconds(15));
        pds.setMaxStatements(50);
        pds.setValidateConnectionOnBorrow(true);

        log.fine("Pool created for " + label(config));
        return pds;
    }

    private String buildUrl(String connectString) {
        // If already a full JDBC URL, use as-is
        if (connectString.startsWith("jdbc:")) {
            return connectString;
        }
        // EZConnect or TNS alias
        return "jdbc:oracle:thin:@" + connectString;
    }

    private void configureWalletIfNeeded(ConnectionConfig config) {
        String walletPath = config.getCredentialsFile();
        if (walletPath == null || walletPath.isBlank()) return;

        // Strip .zip suffix to get the directory (UCP needs the dir)
        String dir = walletPath.endsWith(".zip")
            ? walletPath.substring(0, walletPath.lastIndexOf('/'))
            : walletPath;

        log.fine("Configuring Oracle Wallet at: " + dir);
        System.setProperty("oracle.net.wallet_location",
            "(SOURCE=(METHOD=FILE)(METHOD_DATA=(DIRECTORY=" + dir + ")))");
        System.setProperty("oracle.net.tns_admin", dir);
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    /**
     * Decodes a password that may be plain-text or Base-64-encoded with an {@code enc:} prefix.
     */
    static String decodePassword(String raw) {
        if (raw == null) return "";
        if (raw.startsWith("enc:")) {
            byte[] decoded = Base64.getDecoder().decode(raw.substring(4).trim());
            return new String(decoded, StandardCharsets.UTF_8);
        }
        return raw;
    }

    /**
     * Map key for a pool. The password is included as a SHA-256 hash, so a changed password
     * gets a new pool while the key (which may be logged) never contains the password itself.
     */
    static String poolKey(ConnectionConfig config) {
        return label(config) + "#" + sha256(config.getPassword() + "\u0000" + config.getCredentialsFile());
    }

    /** Human-readable {@code username@connectString}, for logs and messages. */
    private static String label(ConnectionConfig config) {
        return config.getUsername() + "@" + config.getConnectString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    // -------------------------------------------------------------------------
    // Error messages
    // -------------------------------------------------------------------------

    private static final Pattern ORACLE_ERROR  = Pattern.compile("^ORA-\\d+.*");
    private static final Pattern CONNECTION_ID = Pattern.compile("\\s*\\(CONNECTION_ID=[^)]*\\)");

    /**
     * One-line description of a connection failure: the first {@code ORA-} error in the cause
     * chain (falling back to the top-level message), without Oracle's trailing connection id,
     * plus a hint for the most common mistakes.
     */
    static String describe(Throwable t) {
        String message = null;
        for (Throwable x = t; x != null; x = x.getCause()) {
            String first = firstLine(x.getMessage());
            if (first != null && ORACLE_ERROR.matcher(first).matches()) {
                message = first;
                break;
            }
        }
        if (message == null) message = firstLine(t.getMessage());
        if (message == null) message = t.getClass().getSimpleName();
        message = CONNECTION_ID.matcher(message).replaceAll("");

        String hint = hint(message);
        return (hint != null) ? message + " (" + hint + ")" : message;
    }

    private static String hint(String message) {
        if (message.startsWith("ORA-01017")) return "check the username and password";
        if (message.startsWith("ORA-28000")) return "the account is locked";
        if (message.startsWith("ORA-28001")) return "the password has expired";
        if (message.startsWith("ORA-12514") || message.startsWith("ORA-12505")) return "check the service name in the connect string";
        if (message.startsWith("ORA-12541")) return "check the host and port in the connect string";
        if (message.startsWith("ORA-17868")) return "check the host name in the connect string";
        return null;
    }

    private static String firstLine(String s) {
        return (s == null || s.isBlank()) ? null : s.strip().lines().findFirst().orElse(null);
    }

    /** Deepest {@link SQLException} in the cause chain, for its error code and SQL state. */
    private static SQLException rootSqlException(SQLException e) {
        SQLException root = e;
        for (Throwable x = e.getCause(); x != null; x = x.getCause()) {
            if (x instanceof SQLException s && s.getErrorCode() != 0) {
                root = s;
                break;
            }
        }
        return root;
    }

    // -------------------------------------------------------------------------

    @Override
    public void close() {
        oracle.ucp.admin.UniversalConnectionPoolManager ucpManager;
        try {
            ucpManager = oracle.ucp.admin.UniversalConnectionPoolManagerImpl
                .getUniversalConnectionPoolManager();
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not obtain UCP pool manager", e);
            pools.clear();
            return;
        }

        for (Map.Entry<String, PoolDataSource> entry : pools.entrySet()) {
            try {
                ucpManager.destroyConnectionPool(entry.getValue().getConnectionPoolName());
            } catch (Exception e) {
                log.log(Level.WARNING, "Failed to close pool " + entry.getKey(), e);
            }
        }
        pools.clear();
    }
}
