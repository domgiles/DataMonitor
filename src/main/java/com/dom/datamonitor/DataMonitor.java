package com.dom.datamonitor;

import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.ConfigException;
import com.dom.datamonitor.config.ConfigLoader;
import com.dom.datamonitor.config.ConfigValidator;
import com.dom.datamonitor.ui.DashboardFrame;

import javax.swing.*;
import java.io.File;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Application entry point for the New Data Monitor.
 *
 * <h3>Usage</h3>
 * <pre>
 *   java -jar new-data-monitor.jar [-c &lt;config-file&gt;] [--theme dark|light] [-h]
 *
 *   -c &lt;file&gt;     Path to the YAML configuration file (default: datamonitor.yaml)
 *   --theme       Override the theme setting from the config file (dark | light)
 *   -h, --help    Print this help and exit
 * </pre>
 */
public class DataMonitor {

    private static final Logger log = Logger.getLogger(DataMonitor.class.getName());

    private static final String DEFAULT_CONFIG = "datamonitor.yaml";
    private static final String APP_VERSION    = "1.0.0";

    // ─────────────────────────────────────────────────────────────────────────

    public static void main(String[] args) {
        // Parse command-line arguments
        String  configPath    = DEFAULT_CONFIG;
        String  themeOverride = null;

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

            // Apply CLI theme override
            if (themeOverride != null) config.getSettings().setTheme(themeOverride);

            new ConfigValidator().validate(config);

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
        SwingUtilities.invokeLater(() -> {
            try {
                DashboardFrame frame = new DashboardFrame(config);
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
              datamonitor [-c <config>] [--theme dark|light] [-h]

            Options:
              -c, --config <file>    YAML configuration file (default: datamonitor.yaml)
              --theme <dark|light>   Override theme setting from config
              --version              Print version and exit
              -h, --help             Show this help

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
                  processingMode: DIFFERENCE  # DIFFERENCE|NONE
                  sql: |
                    SELECT 'Transactions', sum(value)
                    FROM v$sysstat WHERE name = 'user commits'
            """);
    }

    private static void die(String msg) {
        System.err.println("Error: " + msg);
        printUsage();
        System.exit(1);
    }
}
