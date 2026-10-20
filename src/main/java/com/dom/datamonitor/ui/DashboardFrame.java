package com.dom.datamonitor.ui;

import com.dom.datamonitor.charts.Theme;
import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.ConfigWriter;
import com.dom.datamonitor.config.ConnectionConfig;
import com.dom.datamonitor.config.QModuleConfig;
import com.dom.datamonitor.db.ConnectionManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Main application window.
 *
 * <p>Layout (top to bottom):
 * <ol>
 *   <li>{@link HeaderBar}     – title, connection target, theme toggle</li>
 *   <li>{@link JScrollPane}  wrapping {@link DashboardPanel} – scrollable grid of QModules</li>
 * </ol>
 *
 * <p>The window is vertically scrollable (rows) but not horizontally: the minimum
 * width is enforced via the dashboard panel's minimum size.
 *
 * <p>Handles the QModule right-click actions (add / edit / delete). Every change is
 * saved straight back to the config file via {@link ConfigWriter}.
 */
public class DashboardFrame extends JFrame implements DashboardPanel.ModuleActions {

    private static final Logger log = Logger.getLogger(DashboardFrame.class.getName());

    private final AppConfig      config;
    private final File           configFile;
    private final ConfigWriter   configWriter;
    private       Theme          theme;
    private       DashboardPanel dashboardPanel;
    private       HeaderBar      headerBar;

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param configFile file the config was loaded from; edits are saved back to it
     * @param themeName  initial theme ({@code dark} / {@code light}); may differ from the
     *                   config when overridden on the command line
     */
    public DashboardFrame(AppConfig config, File configFile, String themeName) throws Exception {
        this.config       = config;
        this.configFile   = configFile;
        this.configWriter = new ConfigWriter(configFile);
        this.theme        = Theme.forName(themeName);

        buildUI();
        configureWindow();
        validateConnectivity();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void buildUI() throws Exception {
        setTitle(config.getSettings().getTitle());
        setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { shutdown(); }
        });

        // Header bar
        headerBar = new HeaderBar(config.getSettings(), theme);
        headerBar.setOnThemeToggle(this::toggleTheme);
        headerBar.setOnAddModule(this::addModule);

        // Dashboard panel (grid of QModules)
        dashboardPanel = new DashboardPanel(config, theme);
        dashboardPanel.setModuleActions(this);

        // Scrollable wrapper – vertical scroll only
        JScrollPane scroll = new JScrollPane(dashboardPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(theme.background);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // Right-clicking the empty area below the last row also offers "Add QModule…"
        scroll.getViewport().setComponentPopupMenu(dashboardPanel.getComponentPopupMenu());

        // Root content
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(theme.background);
        root.add(headerBar,    BorderLayout.NORTH);
        root.add(scroll,       BorderLayout.CENTER);

        setContentPane(root);
    }

    private void configureWindow() {
        // Default size: sensible for a 3-column dashboard
        int cols = config.getSettings().getColumns();
        setSize(Math.max(900, cols * 320 + 40), 700);
        setMinimumSize(new Dimension(
            cols * 100 + 2 * config.getSettings().getBorder(),
            400
        ));

        // Centre on screen
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        setLocation(
            Math.max(0, (screen.width  - getWidth())  / 2),
            Math.max(0, (screen.height - getHeight()) / 2)
        );
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Connectivity check
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Tests the default connection before starting the executors.
     * Shows an error dialog and exits if the database is unreachable.
     */
    private void validateConnectivity() {
        ConnectionConfig conn = config.getSettings().getEffectiveConnection();
        if (conn == null) return;

        headerBar.setStatusText("Testing connection…");

        // Run off-EDT so the UI paints first
        new Thread(() -> {
            try {
                ConnectionManager.getInstance().testConnection(conn);

                SwingUtilities.invokeLater(() -> {
                    headerBar.setConnectionTarget(conn.getConnectString());
                    headerBar.setStatusText("Connected");
                    dashboardPanel.startAll();
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    String msg = "Cannot connect to Oracle database:\n\n" + e.getMessage()
                        + "\n\nPlease check settings.connection in your config file"
                        + " and any --username / --password / --connect-string options.";
                    JOptionPane.showMessageDialog(DashboardFrame.this,
                        msg, "Connection Failed", JOptionPane.ERROR_MESSAGE);
                    shutdown();
                });
            }
        }, "dm-connect-test").start();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Theme toggle
    // ─────────────────────────────────────────────────────────────────────────

    private void toggleTheme() {
        theme = (theme == Theme.DARK) ? Theme.LIGHT : Theme.DARK;
        getContentPane().setBackground(theme.background);
        headerBar.setTheme(theme);
        dashboardPanel.setTheme(theme);
        // Force repaint of scroll viewport background
        ((JScrollPane) ((JPanel) getContentPane()).getComponent(1))
            .getViewport().setBackground(theme.background);
        revalidate();
        repaint();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // QModule actions (right-click menu and header "+" button)
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void addModule() {
        Optional<QModuleConfig> added = QModuleDialog.show(this, theme, config.getSettings(),
            null, dashboardPanel.occupancyExcluding(null));
        added.ifPresent(mc -> applyChange("add", () -> dashboardPanel.addModule(mc)));
    }

    @Override
    public void editModule(QModulePanel module) {
        Optional<QModuleConfig> edited = QModuleDialog.show(this, theme, config.getSettings(),
            module.getConfig(), dashboardPanel.occupancyExcluding(module));
        edited.ifPresent(mc -> applyChange("update", () -> dashboardPanel.replaceModule(module, mc)));
    }

    @Override
    public void deleteModule(QModulePanel module) {
        Object[] options = {"Delete", "Cancel"};
        int choice = JOptionPane.showOptionDialog(this,
            "Delete the QModule \"" + module.getTitle() + "\"?\n\n"
                + "Its query stops and it is removed from " + configFile.getName() + ".",
            "Delete QModule", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
            null, options, options[1]);
        if (choice == 0) {
            applyChange("delete", () -> dashboardPanel.removeModule(module));
        }
    }

    private interface Change { void apply() throws Exception; }

    /** Applies a dashboard change, then saves the config; reports failures in a dialog. */
    private void applyChange(String verb, Change change) {
        try {
            change.apply();
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not " + verb + " QModule", e);
            JOptionPane.showMessageDialog(this, "Could not " + verb + " the QModule:\n\n" + e.getMessage(),
                "QModule", JOptionPane.ERROR_MESSAGE);
            return;
        }
        try {
            configWriter.save(config);
            headerBar.setStatusText("Saved " + configFile.getName());
        } catch (IOException e) {
            log.log(Level.WARNING, "Could not save config", e);
            JOptionPane.showMessageDialog(this,
                "The dashboard was updated, but saving " + configFile.getAbsolutePath()
                    + " failed:\n\n" + e.getMessage() + "\n\nThe change will be lost when the application closes.",
                "Save Failed", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void shutdown() {
        log.fine("Shutting down");
        dashboardPanel.stopAll();
        try { ConnectionManager.getInstance().close(); } catch (Exception ignored) {}
        dispose();
        System.exit(0);
    }
}
