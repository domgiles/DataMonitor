package com.dom.datamonitor.config;

/**
 * Oracle database connection details.
 * Loaded directly by SnakeYAML; all fields are mutable during deserialisation
 * then treated as effectively-final once validated.
 *
 * <p>Passwords prefixed with {@code enc:} are decoded from Base-64 at runtime.
 */
public class ConnectionConfig {

    /** Oracle username. */
    private String username;

    /**
     * Plain-text password, or Base-64 encoded password prefixed with {@code enc:}.
     * Example: {@code enc:bXlwYXNzd29yZA==}
     */
    private String password;

    /**
     * TNS alias, Easy Connect string, or full JDBC URL fragment.
     * Examples: {@code mydb_high},  {@code //host:1521/orcl}
     */
    private String connectString;

    /**
     * Optional path to an Oracle Wallet directory (zip or unpacked) required for
     * Autonomous Database (ADB) mutual TLS connections.
     */
    private String credentialsFile;

    // --- JavaBeans accessors (required by SnakeYAML) ---

    public String getUsername()        { return username;        }
    public void   setUsername(String v){ this.username = v;      }

    public String getPassword()        { return password;        }
    public void   setPassword(String v){ this.password = v;      }

    public String getConnectString()        { return connectString;        }
    public void   setConnectString(String v){ this.connectString = v;      }

    public String getCredentialsFile()        { return credentialsFile;        }
    public void   setCredentialsFile(String v){ this.credentialsFile = v;      }

    @Override
    public String toString() {
        return username + "@" + connectString;
    }
}
