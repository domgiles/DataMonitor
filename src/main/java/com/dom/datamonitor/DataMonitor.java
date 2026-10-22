package com.dom.datamonitor;

import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.ConfigException;
import com.dom.datamonitor.config.ConfigLoader;
import com.dom.datamonitor.config.ConfigValidator;
import com.dom.datamonitor.config.ConfigWriter;
import com.dom.datamonitor.config.PasswordCipher;
import com.dom.datamonitor.db.ConnectionManager;
import com.dom.datamonitor.engine.MonitorEngine;
import com.dom.datamonitor.tui.TuiDashboard;
import com.dom.datamonitor.ui.DashboardFrame;
import com.dom.datamonitor.web.WebServer;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Application entry point for the Data Monitor.
 *
 * <h3>Usage</h3>
 * <pre>
 *   java -jar lib/datamonitor.jar [-c &lt;config-file&gt;] [--theme dark|light]
 *        [-u &lt;user&gt;] [-p &lt;password&gt;] [--connect-string &lt;connect&gt;] [-h]
 *
 *   -c &lt;file&gt;            Path to the YAML configuration file (default: datamonitor.yaml)
 *   --theme              Override the theme setting from the config file (dark | light)
 *   -u, --username       Database username     } override settings.connection for this
 *   -p, --password       Database password     } run only; never written to the config
 *   --connect-string     TNS alias or //host:port/service  }
 *   --web                Start the web front end instead of the desktop window
 *   --cl                 Show the dashboard in this terminal instead of a window
 *   --port &lt;n&gt;           Web port (default 8080)
 *   --bind &lt;address&gt;     Web listen address (default 127.0.0.1: this machine only)
 *   --encrypt [password] Print the password encrypted for the config file, then exit
 *   -h, --help           Print this help and exit
 * </pre>
 */
public class DataMonitor {

    private static final Logger log = Logger.getLogger(DataMonitor.class.getName());

    private static final String DEFAULT_CONFIG = "datamonitor.yaml";
    private static final int    DEFAULT_PORT   = 8080;
    private static final String DEFAULT_BIND   = "127.0.0.1";
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
        boolean web           = false;
        boolean cl            = false;
        int     port          = DEFAULT_PORT;
        String  bind          = DEFAULT_BIND;

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
                case "--web" -> web = true;
                case "--cl" -> cl = true;
                case "--port" -> {
                    if (i + 1 < args.length) port = parsePort(args[++i]);
                    else die("Missing value for --port");
                }
                case "--bind" -> {
                    if (i + 1 < args.length) bind = args[++i];
                    else die("Missing value for --bind");
                }
                case "--encrypt" -> {
                    String plain = (i + 1 < args.length) ? args[++i] : readPassword();
                    System.out.println(PasswordCipher.encrypt(plain));
                    System.exit(0);
                }
                case "-h", "--help" -> { printUsage(); System.exit(0); }
                case "--version"    -> { System.out.println("Data Monitor " + APP_VERSION); System.exit(0); }
                default -> System.err.println("Warning: unknown argument '" + args[i] + "'");
            }
        }

        // Banner
        System.out.println("Data Monitor " + APP_VERSION);
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

        final File   configFile = new File(configPath);

        // Passwords typed into the file by hand are encrypted there before anything else runs
        try {
            int n = ConfigWriter.encryptPlainPasswords(configFile);
            if (n > 0) System.out.println("Encrypted " + n + " plain-text password" + (n == 1 ? "" : "s")
                                          + " in " + configFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("Warning: could not encrypt the passwords in " + configFile.getAbsolutePath()
                               + ": " + e.getMessage() + ". They stay in plain text until it is writable.");
        }
        final String themeName  = (themeOverride != null) ? themeOverride : config.getSettings().getTheme();

        if (web && cl) die("Choose one of --web and --cl");
        if (web) {
            startWeb(config, configFile, themeName, bind, port);
            return;
        }
        if (cl) {
            startCommandLine(config, themeName);
            return;
        }

        // Configure Swing
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext",                "true");

        // Launch on the Event Dispatch Thread
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

    /**
     * Runs the dashboard headless and serves the web front end until the process is stopped.
     * Like the desktop app, it tests the connection first and exits if the database is unreachable.
     */
    /**
     * The dashboard in this terminal. Log output would scribble over the screen, so while it
     * runs, logging and anything written to stderr go to a file in the temp directory instead.
     */
    private static void startCommandLine(AppConfig config, String themeName) {
        System.setProperty("java.awt.headless", "true");
        if (System.console() == null) {
            fail("--cl needs a terminal; it can't run with its output redirected or from the launcher's child JVM");
        }
        testConnectionOrExit(config);

        File logFile = new File(System.getProperty("java.io.tmpdir"), "datamonitor-cl.log");
        java.io.PrintStream console = System.err;
        java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
        java.util.logging.Handler[] consoleHandlers = root.getHandlers();
        try (java.io.PrintStream logStream = new java.io.PrintStream(new java.io.FileOutputStream(logFile), true)) {
            for (java.util.logging.Handler h : consoleHandlers) root.removeHandler(h);
            java.util.logging.StreamHandler fileHandler =
                new java.util.logging.StreamHandler(logStream, new java.util.logging.SimpleFormatter()) {
                    @Override public synchronized void publish(java.util.logging.LogRecord r) { super.publish(r); flush(); }
                };
            root.addHandler(fileHandler);
            System.setErr(logStream);

            MonitorEngine engine = new MonitorEngine(config, null);   // read-only: nothing is saved
            engine.start();
            try {
                new TuiDashboard(engine, themeName, String.valueOf(config.getSettings().getEffectiveConnection())).run();
            } finally {
                engine.stop();
                ConnectionManager.getInstance().close();
                root.removeHandler(fileHandler);
                for (java.util.logging.Handler h : consoleHandlers) root.addHandler(h);
                System.setErr(console);
            }
        } catch (Exception e) {
            System.err.println("The terminal dashboard stopped: " + e.getMessage());
            log.log(Level.SEVERE, "Terminal dashboard failure", e);
            System.exit(1);
        }
        if (logFile.length() > 0) System.out.println("Messages logged while the dashboard ran are in " + logFile);
        System.exit(0);
    }

    /** Checks the dashboard connection before starting, so a wrong password gives a plain message. */
    private static void testConnectionOrExit(AppConfig config) {
        if (config.getSettings().getEffectiveConnection() == null) return;
        System.out.println("Testing connection…");
        try {
            ConnectionManager.getInstance().testConnection(config.getSettings().getEffectiveConnection());
        } catch (Exception e) {
            System.err.println("\nCannot connect to Oracle database:\n  " + e.getMessage()
                + "\n\nCheck settings.connection in your config file"
                + " and any --username / --password / --connect-string options.\n");
            ConnectionManager.getInstance().close();
            System.exit(1);
        }
    }

    private static void startWeb(AppConfig config, File configFile, String themeName, String bind, int port) {
        System.setProperty("java.awt.headless", "true");

        testConnectionOrExit(config);

        MonitorEngine engine = new MonitorEngine(config, new ConfigWriter(configFile));
        WebServer server;
        try {
            server = new WebServer(engine, APP_VERSION, themeName, bind, port, null);
        } catch (IOException e) {
            System.err.println("\nCannot start the web server on " + bind + ":" + port + ": " + e.getMessage()
                + "\nIs another program using that port? Try --port <n>.\n");
            System.exit(1);
            return;
        }
        engine.start();
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            engine.stop();
            ConnectionManager.getInstance().close();
        }, "dm-shutdown"));

        String host = (bind.equals("0.0.0.0") || bind.equals("::")) ? "localhost" : bind;
        System.out.println("\nWeb dashboard running. Open this link (it contains your access token):\n\n  "
            + "http://" + host + ":" + server.port() + "/?token=" + server.token() + "\n");
        if (!bind.equals(DEFAULT_BIND) && !bind.equals("localhost")) {
            System.out.println("Listening on " + bind + ": anyone who can reach this machine and has the link"
                + "\ncan view the dashboard, edit QModules and run SELECT queries as "
                + config.getSettings().getEffectiveConnection() + ".\n");
        }
        System.out.println("Press Ctrl+C to stop.");
    }

    private static int parsePort(String s) {
        try {
            int p = Integer.parseInt(s);
            if (p >= 0 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {
            // fall through
        }
        die("--port must be a number between 0 and 65535, got: " + s);
        return -1;
    }

    /** Reads a password from the terminal without echoing it. */
    private static String readPassword() {
        java.io.Console console = System.console();
        if (console == null) fail("--encrypt needs a terminal to prompt for the password; or pass it: --encrypt <password>");
        char[] first  = console.readPassword("Password: ");
        char[] second = console.readPassword("Again:    ");
        if (first == null || first.length == 0) fail("No password entered");
        if (!java.util.Arrays.equals(first, second)) fail("The passwords do not match");
        return new String(first);
    }

    /** Prints {@code msg} and exits, without the usage text {@link #die} adds. */
    private static void fail(String msg) {
        System.err.println("Error: " + msg);
        System.exit(1);
    }

    private static void printUsage() {
        System.out.println("""
            Data Monitor - Oracle Database dashboard

            Usage:
              datamonitor [-c <config>] [--theme dark|light]
                          [-u <user>] [-p <password>] [--connect-string <connect>] [-h]

            Options:
              -c, --config <file>         YAML configuration file (default: datamonitor.yaml)
              --theme <dark|light>        Override theme setting from config
              -u, --username <user>       Database username
              -p, --password <password>   Database password (plain, or enc(...))
              --connect-string <connect>  TNS alias or //host:port/service
              --web                       Start the web front end instead of the window
              --cl                        Show the dashboard in this terminal (q quits)
              --port <n>                  Web port (default 8080)
              --bind <address>            Web listen address (default 127.0.0.1, this
                                          machine only; 0.0.0.0 for all interfaces)
              --encrypt [password]        Print the password encrypted for the config
                                          file and exit; prompts when none is given
              --version                   Print version and exit
              -h, --help                  Show this help

            -u, -p and --connect-string override settings.connection for this run
            only and are never written to the config file. Each can be used on its
            own, e.g. keep the password out of the file and pass it with -p.
            Note: a password on the command line is visible to other users of this
            machine (e.g. via 'ps') and may be kept in your shell history.

            Passwords are never left in plain text: one typed into the config file is
            encrypted there, as enc(...), when the file is loaded, and the editors
            save them encrypted. 'datamonitor --encrypt' prints the enc(...) form of
            a password, e.g. for a config you are preparing for someone else.

            --web prints a link containing a random access token; the web UI and its
            API only work for browsers that opened that link.

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
                  password: welcome1    # encrypted in the file, as enc(...), on first load
                  connectString: mydb_high
                  credentialsFile: /path/to/wallet  # ADB only

              qmodules:
                - title: "Tx/Sec"
                  grid: "R1,C1"          # R<row|row1-row2>,C<col|col1-col2|col*>
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
