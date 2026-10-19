package com.dom.datamonitor.db;

import com.dom.datamonitor.config.ConnectionConfig;
import oracle.ucp.jdbc.PoolDataSource;
import oracle.ucp.jdbc.PoolDataSourceFactory;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Manages Oracle UCP connection pools, one pool per unique connection configuration.
 *
 * <p>Thread-safe. Obtain a connection via {@link #getConnection(ConnectionConfig)};
 * the pool is created lazily on first use and reused for subsequent requests with
 * the same username + connect-string combination.
 *
 * <p>Passwords may be stored as plain text or Base-64 encoded with an {@code enc:}
 * prefix (e.g. {@code enc:bXlwYXNzd29yZA==}).
 */
public class ConnectionManager implements AutoCloseable {

    private static final Logger log = Logger.getLogger(ConnectionManager.class.getName());

    /** Shared singleton instance. */
    private static final ConnectionManager INSTANCE = new ConnectionManager();

    /** Pools keyed by "username@connectString". */
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
                throw new RuntimeException("Failed to create connection pool for " + key, e);
            }
        });
        return pool.getConnection();
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
        log.fine("Creating connection pool for " + poolKey(config));

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

        log.fine("Pool created for " + poolKey(config));
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

    private static String poolKey(ConnectionConfig config) {
        return config.getUsername() + "@" + config.getConnectString();
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
