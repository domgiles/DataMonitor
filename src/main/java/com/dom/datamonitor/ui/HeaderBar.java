package com.dom.datamonitor.ui;

import com.dom.datamonitor.charts.Theme;
import com.dom.datamonitor.config.AppConfig;

import javax.swing.*;
import java.awt.*;

/**
 * Horizontal info bar displayed at the top of the dashboard window.
 *
 * <p>Shows the dashboard title, Oracle connection target, and (once connected)
 * a brief summary of the database being monitored.  A theme-toggle button sits
 * on the far right.
 */
public class HeaderBar extends JPanel {

    private Theme theme;

    private final JLabel titleLabel      = new JLabel();
    private final JLabel connectionLabel = new JLabel();
    private final JLabel statusLabel     = new JLabel("Connecting…");
    private final JButton themeToggle    = new JButton();
    private final JButton addButton      = new JButton();

    /** Fired when the user clicks the theme toggle button. */
    private Runnable onThemeToggle = () -> {};
    /** Fired when the user clicks the add-QModule button. */
    private Runnable onAddModule   = () -> {};

    // ─────────────────────────────────────────────────────────────────────────

    public HeaderBar(AppConfig.Settings settings, Theme theme) {
        this.theme = theme;
        buildLayout(settings);
        applyTheme();
    }

    // ─────────────────────────────────────────────────────────────────────────

    public void setConnectionTarget(String target) {
        connectionLabel.setText("  ⬡  " + target);
    }

    public void setStatusText(String status) {
        statusLabel.setText(status);
    }

    public void setOnThemeToggle(Runnable r) {
        this.onThemeToggle = r;
    }

    public void setOnAddModule(Runnable r) {
        this.onAddModule = r;
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
        applyTheme();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void buildLayout(AppConfig.Settings settings) {
        setLayout(new BorderLayout(8, 0));
        setPreferredSize(new Dimension(0, 38));
        setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));

        titleLabel.setText(settings.getTitle());

        if (settings.getEffectiveConnection() != null) {
            connectionLabel.setText("  ⬡  " + settings.getEffectiveConnection().getConnectString());
        }

        themeToggle.setText("◑");
        themeToggle.setFocusable(false);
        themeToggle.setToolTipText("Toggle dark / light theme");
        themeToggle.addActionListener(e -> onThemeToggle.run());
        // Bypass macOS Aqua native painting so background/border are fully controlled
        themeToggle.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        themeToggle.setOpaque(true);

        addButton.setText("+");
        addButton.setFocusable(false);
        addButton.setToolTipText("Add a QModule");
        addButton.addActionListener(e -> onAddModule.run());
        addButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        addButton.setOpaque(true);

        // Left cluster: title + connection
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(titleLabel);
        left.add(connectionLabel);

        // Right cluster: status + toggle
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        right.add(statusLabel);
        right.add(addButton);
        right.add(themeToggle);

        add(left,  BorderLayout.WEST);
        add(right, BorderLayout.EAST);
    }

    private void applyTheme() {
        setBackground(new Color(
            Math.min(255, theme.background.getRed()   + 6),
            Math.min(255, theme.background.getGreen() + 8),
            Math.min(255, theme.background.getBlue()  + 12)));

        titleLabel.setFont(theme.titleFont.deriveFont(Font.BOLD, 18f));
        titleLabel.setForeground(theme.foreground);

        connectionLabel.setFont(theme.axisFont);
        connectionLabel.setForeground(theme.axisForeground);

        statusLabel.setFont(theme.axisFont);
        statusLabel.setForeground(theme.axisForeground);

        for (JButton b : new JButton[]{addButton, themeToggle}) {
            b.setBackground(theme.background);
            b.setForeground(theme.foreground);
            b.setFont(theme.axisFont.deriveFont(Font.BOLD, 13f));
            b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.accent, 1),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        }

        repaint();
    }
}
