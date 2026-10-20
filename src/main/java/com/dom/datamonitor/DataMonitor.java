package com.dom.datamonitor;

import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.ConfigException;
import com.dom.datamonitor.config.ConfigLoader;
import com.dom.datamonitor.config.ConfigValidator;
import com.dom.datamonitor.ui.DashboardFrame;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Application entry point for the New Data Monitor.
 *
 * <h3>Usage</h3>
 * <pre>
 *   java -jar new-data-monitor.jar [-c &lt;config-file&gt;] [--theme dark|light]
 *        [-u &lt;user&gt;] [-p &lt;password&gt;] [--connect-string &lt;connect&gt;] [-h]
 *
 *   -c &lt;file&gt;            Path to the YAML configuration file (default: datamonitor.yaml)
 *   --theme              Override the theme setting from the config file (dark | light)
 *   -u, --username       Database username     } override settings.connection for this
 *   -p, --password       Database password     } run only; never written to the config
 *   --connect-string     TNS alias or //host:port/service  }
 *   -h, --help           Print this help and exit
 * </pre>
 */
public class DataMonitor {

    private static final Logger log = Logger.getLogger(DataMonitor.class.getName());

    private static final String DEFAULT_CONFIG = "datamonitor.yaml";
    /** Read from version.properties, which the build generates from build.xml's project.version. */
    private static final String APP_VERSION    = loadVersion();

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        // Parse command-line arguments
        String  configPath    = DEFAULT_CONFIG;
        String  themeOverride = null;
        String  username      = null;
        String  password      = null;
        String  connectString = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-c", "--config" -> {
                    if (i + 1 < args.length) configPath = args[++i];
                    else die("Missing value for -c / --config");
                }
                case "--theme" -> {
                    if (i + 1 < args.length) themeOverride = args[++i];
                    else die("Missing value for --theme");
                }
                case "-u", "--username" -> {
                    if (i + 1 < args.length) username = args[++i];
                    else die("Missing value for -u / --username");
                }
                case "-p", "--password" -> {
                    if (i + 1 < args.length) password = args[++i];
                    else die("Missing value for -p / --password");
                }
                case "--connect-string" -> {
                    if (i + 1 < args.length) connectString = args[++i];
                    else die("Missing value for --connect-string");
                }
                case "-h", "--help" -> { printUsage(); System.exit(0); }
                case "--version"    -> { System.out.println("New Data Monitor " + APP_VERSION); System.exit(0); }
                default -> System.err.println("Warning: unknown argument '" + args[i] + "'");
            }
        }

        // Banner
        System.out.println("New Data Monitor " + APP_VERSION);
        System.out.println("Loading config: " + configPath);

        // Load and validate config
        final AppConfig config;
        try {
            config = ConfigLoader.load(new File(configPath));
            // Like --theme, these apply to this run only and are never saved to the config
            config.getSettings().overrideConnection(username, password, connectString);
            new ConfigValidator().validate(config);

            // The CLI theme applies to this run only; it is not written into the config,
            // which is saved back to disk when QModules are edited
            if (themeOverride != null
                    && !themeOverride.equalsIgnoreCase("dark") && !themeOverride.equalsIgnoreCase("light")) {
                throw new ConfigException("--theme must be 'dark' or 'light', got: " + themeOverride);
            }

        } catch (ConfigException e) {
            System.err.println("\n" + e.getMessage() + "\n");
            System.exit(1);
            return; // unreachable, but satisfies the compiler
        } catch (Exception e) {
            System.err.println("Unexpected error loading config: " + e.getMessage());
            log.log(Level.SEVERE, "Config load failure", e);
            System.exit(1);
            return;
        }

        // Configure Swing
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext",                "true");

        // Launch on the Event Dispatch Thread
        final File   configFile = new File(configPath);
        final String themeName  = (themeOverride != null) ? themeOverride : config.getSettings().getTheme();
        SwingUtilities.invokeLater(() -> {
            try {
                DashboardFrame frame = new DashboardFrame(config, configFile, themeName);
                frame.setVisible(true);
            } catch (Exception e) {
                String msg = "Failed to create dashboard window: " + e.getMessage();
                System.err.println(msg);
                log.log(Level.SEVERE, "Dashboard creation failure", e);
                JOptionPane.showMessageDialog(null, msg, "Startup Error",
                    JOptionPane.ERROR_MESSAGE);
                System.exit(1);
            }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────

    private static void printUsage() {
        System.out.println("""
            New Data Monitor - Oracle Database dashboard

            Usage:
              datamonitor [-c <config>] [--theme dark|light]
                          [-u <user>] [-p <password>] [--connect-string <connect>] [-h]

            Options:
              -c, --config <file>         YAML configuration file (default: datamonitor.yaml)
              --theme <dark|light>        Override theme setting from config
              -u, --username <user>       Database username
              -p, --password <password>   Database password (plain, or enc:<base64>)
              --connect-string <connect>  TNS alias or //host:port/service
              --version                   Print version and exit
              -h, --help                  Show this help

            -u, -p and --connect-string override settings.connection for this run
            only and are never written to the config file. Each can be used on its
            own, e.g. keep the password out of the file and pass it with -p.
            Note: a password on the command line is visible to other users of this
            machine (e.g. via 'ps') and may be kept in your shell history.

            Config file format:
              settings:
                title: "My Dashboard"
                theme: dark              # dark | light
                refreshRate: 5           # seconds
                windowSize: 120          # seconds (LINE/AREA charts)
                border: 8               # px gap between modules
                columns: 3              # 1-3 columns
                connection:
                  username: admin
                  password: mypassword  # or enc:<base64> for encoded passwords
                  connectString: mydb_high
                  credentialsFile: /path/to/wallet  # ADB only

              qmodules:
                - title: "Tx/Sec"
                  grid: "R1,C1"          # R<row>,C<col|col1-col2|col*>
                  chartType: TEXT        # LINE|AREA|BAR|PIE|TEXT|TABLE
                  processingMode: DIFFERENCE  # NONE|DIFFERENCE|CHANGE_PER_MIN|CHANGE_PER_HOUR|CUMULATIVE_SINCE_START
                  sql: |
                    SELECT 'Transactions', sum(value)
                    FROM v$sysstat WHERE name = 'user commits'
            """);
    }

    private static String loadVersion() {
        try (InputStream in = DataMonitor.class.getResourceAsStream("version.properties")) {
            if (in == null) return "dev";
            Properties props = new Properties();
            props.load(in);
            return props.getProperty("version", "dev");
        } catch (IOException e) {
            return "dev";
        }
    }

    private static void die(String msg) {
        System.err.println("Error: " + msg);
        printUsage();
        System.exit(1);
    }
}
