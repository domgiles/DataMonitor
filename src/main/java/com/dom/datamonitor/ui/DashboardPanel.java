package com.dom.datamonitor.ui;

import com.dom.datamonitor.charts.Theme;
import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.QModuleConfig;
import com.dom.datamonitor.model.GridLocation;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The main scrollable content panel containing all {@link QModulePanel}s arranged
 * in a {@link GridBagLayout} matching each module's declared grid position.
 *
 * <p>The number of columns is determined by {@code settings.columns} (1-3).
 * The minimum window width is {@code columns × DEFAULT_CELL_MIN_WIDTH + 2 × border}.
 * Rows scroll vertically; columns are not independently scrollable.
 */
public class DashboardPanel extends JPanel {

    private static final Logger log = Logger.getLogger(DashboardPanel.class.getName());

    /** Minimum pixel width per column (per spec: 100 "points" per column). */
    private static final int DEFAULT_CELL_MIN_WIDTH = 100;
    /** Default preferred height for each grid row. */
    private static final int DEFAULT_ROW_HEIGHT     = 200;

    private final int      totalColumns;
    private final int      border;
    private Theme          theme;

    private final List<QModulePanel> modules = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────

    public DashboardPanel(AppConfig config, Theme theme) throws Exception {
        this.totalColumns = config.getSettings().getColumns();
        this.border       = config.getSettings().getBorder();
        this.theme        = theme;

        setLayout(new GridBagLayout());
        setBackground(theme.background);

        buildModules(config);
        setMinimumSize(computeMinimumSize());
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Starts SQL executors for all modules. */
    public void startAll() {
        modules.forEach(QModulePanel::startExecutor);
    }

    /** Stops all SQL executors (call on application shutdown). */
    public void stopAll() {
        modules.forEach(QModulePanel::stopExecutor);
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
        setBackground(theme.background);
        modules.forEach(m -> m.setTheme(theme));
        repaint();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private void buildModules(AppConfig config) throws Exception {
        for (QModuleConfig mc : config.getQmodules()) {
            QModulePanel panel = new QModulePanel(mc, config.getSettings(), theme);
            modules.add(panel);

            GridLocation loc = panel.getGridLocation();
            int span = loc.columnSpan(totalColumns);

            GridBagConstraints gbc = new GridBagConstraints();
            gbc.gridx      = loc.gridBagX();
            gbc.gridy      = loc.gridBagY();
            gbc.gridwidth  = span;
            gbc.gridheight = 1;
            gbc.fill       = GridBagConstraints.BOTH;
            gbc.weightx    = span;
            gbc.weighty    = 1.0;
            gbc.insets     = new Insets(border, border, 0, border);

            // Preferred size: height from config or default
            int preferredW = span * DEFAULT_CELL_MIN_WIDTH;
            panel.setPreferredSize(new Dimension(preferredW, DEFAULT_ROW_HEIGHT));

            add(panel, gbc);
        }

        // Add invisible row-weight filler at the bottom so rows don't stretch
        GridBagConstraints filler = new GridBagConstraints();
        filler.gridx     = 0;
        filler.gridy     = highestRow() + 1;
        filler.gridwidth = totalColumns;
        filler.weighty   = 0.001; // tiny – pushes content to top
        filler.fill      = GridBagConstraints.VERTICAL;
        add(Box.createVerticalStrut(border), filler);
    }

    private int highestRow() {
        return modules.stream()
            .mapToInt(m -> m.getGridLocation().row())
            .max().orElse(1);
    }

    private Dimension computeMinimumSize() {
        // Per spec: minWidth = columns × 100 + 2 × border
        int minW = totalColumns * DEFAULT_CELL_MIN_WIDTH + 2 * border;
        return new Dimension(minW, DEFAULT_ROW_HEIGHT);
    }
}
