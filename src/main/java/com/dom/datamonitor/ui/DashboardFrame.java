package com.dom.datamonitor.ui;

import com.dom.datamonitor.charts.Theme;
import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.db.ConnectionManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
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
 */
public class DashboardFrame extends JFrame {

    private static final Logger log = Logger.getLogger(DashboardFrame.class.getName());

    private final AppConfig      config;
    private       Theme          theme;
    private       DashboardPanel dashboardPanel;
    private       HeaderBar      headerBar;

    // ─────────────────────────────────────────────────────────────────────────

    public DashboardFrame(AppConfig config) throws Exception {
        this.config = config;
        this.theme  = Theme.forName(config.getSettings().getTheme());

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

        // Dashboard panel (grid of QModules)
        dashboardPanel = new DashboardPanel(config, theme);

        // Scrollable wrapper – vertical scroll only
        JScrollPane scroll = new JScrollPane(dashboardPanel,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(theme.background);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

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
        if (config.getSettings().getConnection() == null) return;

        headerBar.setStatusText("Testing connection…");

        // Run off-EDT so the UI paints first
        new Thread(() -> {
            try {
                ConnectionManager.getInstance().testConnection(
                    config.getSettings().getConnection());

                SwingUtilities.invokeLater(() -> {
                    headerBar.setConnectionTarget(
                        config.getSettings().getConnection().getConnectString());
                    headerBar.setStatusText("Connected");
                    dashboardPanel.startAll();
                });

            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    String msg = "Cannot connect to Oracle database:\n\n" + e.getMessage()
                        + "\n\nPlease check settings.connection in your config file.";
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

    private void shutdown() {
        log.fine("Shutting down");
        dashboardPanel.stopAll();
        try { ConnectionManager.getInstance().close(); } catch (Exception ignored) {}
        dispose();
        System.exit(0);
    }
}
