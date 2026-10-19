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

        public boolean isDarkTheme() {
            return !"light".equalsIgnoreCase(theme);
        }
    }
}
