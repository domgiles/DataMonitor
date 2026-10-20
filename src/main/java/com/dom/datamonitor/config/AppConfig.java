package com.dom.datamonitor.config;

import java.util.ArrayList;
import java.util.List;

/**
 * Root configuration object, mapped 1-to-1 from the YAML file by SnakeYAML.
 *
 * <pre>
 * settings:
 *   title: "My Dashboard"
 *   theme: dark           # dark | light
 *   refreshRate: 5        # global default refresh interval (seconds)
 *   windowSize: 120       # global default time window for LINE/AREA charts (seconds)
 *   border: 8             # gap (px) between QModules
 *   columns: 3            # number of dashboard columns (1-3)
 *   connection:
 *     username: admin
 *     password: mypassword
 *     connectString: mydb_high
 *     credentialsFile: /path/to/wallet.zip   # ADB only
 *
 * qmodules:
 *   - title: "Transactions/sec"
 *     ...
 * </pre>
 */
public class AppConfig {

    private Settings          settings  = new Settings();
    private List<QModuleConfig> qmodules = new ArrayList<>();

    // --- JavaBeans accessors ---

    public Settings getSettings()               { return settings;  }
    public void     setSettings(Settings v)     { this.settings = v; }

    public List<QModuleConfig> getQmodules()          { return qmodules;  }
    public void                setQmodules(List<QModuleConfig> v) { this.qmodules = v; }

    // =====================================================================
    // Inner Settings class
    // =====================================================================

    /**
     * Dashboard-level settings, nested under the {@code settings:} key.
     */
    public static class Settings {

        private String           title          = "Data Monitor";
        private String           theme          = "dark";
        private int              refreshRate    = 5;
        private int              windowSize     = 120;
        private int              border         = 8;
        private int              columns        = 3;
        private boolean          flashOnChange  = true;
        private ConnectionConfig connection;

        /** Command-line connection values; applied at runtime only and never saved to YAML. */
        private ConnectionConfig connectionOverride;

        public String getTitle()                    { return title;       }
        public void   setTitle(String v)            { this.title = v;     }

        public String getTheme()                    { return theme;       }
        public void   setTheme(String v)            { this.theme = v;     }

        public int  getRefreshRate()                { return refreshRate; }
        public void setRefreshRate(int v)           { this.refreshRate = v; }

        public int  getWindowSize()                 { return windowSize;  }
        public void setWindowSize(int v)            { this.windowSize = v; }

        public int  getBorder()                     { return border;      }
        public void setBorder(int v)                { this.border = v;    }

        public int  getColumns()                    { return columns;     }
        public void setColumns(int v)               { this.columns = v;   }

        public boolean isFlashOnChange()            { return flashOnChange;   }
        public void    setFlashOnChange(boolean v)  { this.flashOnChange = v; }

        public ConnectionConfig getConnection()               { return connection;  }
        public void             setConnection(ConnectionConfig v) { this.connection = v; }

        /**
         * Overrides parts of the connection for this run (from {@code --username},
         * {@code --password}, {@code --connect-string}). {@code null} arguments leave the
         * value from the config file in place. Not named as a setter so SnakeYAML ignores it.
         */
        public void overrideConnection(String username, String password, String connectString) {
            if (username == null && password == null && connectString == null) return;
            ConnectionConfig o = new ConnectionConfig();
            o.setUsername(username);
            o.setPassword(password);
            o.setConnectString(connectString);
            this.connectionOverride = o;
        }

        /**
         * The connection to use at runtime: {@code settings.connection} from the file with any
         * command-line overrides on top. May be {@code null} if neither supplies one.
         * {@link ConfigWriter} saves {@link #getConnection()} instead, so overrides never reach disk.
         */
        public ConnectionConfig getEffectiveConnection() {
            if (connectionOverride == null) return connection;
            ConnectionConfig c = (connection != null) ? connection.copy() : new ConnectionConfig();
            if (connectionOverride.getUsername()      != null) c.setUsername(connectionOverride.getUsername());
            if (connectionOverride.getPassword()      != null) c.setPassword(connectionOverride.getPassword());
            if (connectionOverride.getConnectString() != null) c.setConnectString(connectionOverride.getConnectString());
            return c;
        }

        public boolean isDarkTheme() {
            return !"light".equalsIgnoreCase(theme);
        }
    }
}
