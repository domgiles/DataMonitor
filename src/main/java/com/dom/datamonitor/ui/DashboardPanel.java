package com.dom.datamonitor.ui;

import com.dom.datamonitor.charts.Theme;
import com.dom.datamonitor.config.AppConfig;
import com.dom.datamonitor.config.QModuleConfig;
import com.dom.datamonitor.model.GridLocation;
import com.dom.datamonitor.model.GridOccupancy;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The main scrollable content panel containing all {@link QModulePanel}s arranged
 * in a {@link GridBagLayout} matching each module's declared grid position.
 *
 * <p>The number of columns is determined by {@code settings.columns} (1-3).
 * The minimum window width is {@code columns × DEFAULT_CELL_MIN_WIDTH + 2 × border}.
 * Rows scroll vertically; columns are not independently scrollable.
 *
 * <p>Modules can be added, replaced and removed at runtime. Each change updates
 * {@code config.getQmodules()} in step, so the {@link AppConfig} always mirrors what is
 * on screen and can be saved as-is. Right-click menus forward to {@link ModuleActions}.
 */
public class DashboardPanel extends JPanel {

    /** Minimum pixel width per column (per spec: 100 "points" per column). */
    private static final int DEFAULT_CELL_MIN_WIDTH = 100;
    /** Default preferred height for each grid row. */
    private static final int DEFAULT_ROW_HEIGHT     = 200;

    /** Handlers for the right-click menu items; implemented by {@link DashboardFrame}. */
    public interface ModuleActions {
        void addModule();
        void editModule(QModulePanel module);
        void deleteModule(QModulePanel module);
    }

    private final AppConfig config;
    private final int       totalColumns;
    private final int       border;
    private Theme           theme;
    private boolean         running;
    private ModuleActions   actions;

    private final List<QModulePanel> modules = new ArrayList<>();

    // ─────────────────────────────────────────────────────────────────────────

    public DashboardPanel(AppConfig config, Theme theme) throws Exception {
        this.config       = config;
        this.totalColumns = config.getSettings().getColumns();
        this.border       = config.getSettings().getBorder();
        this.theme        = theme;

        // Ensure the list is mutable: modules are added and removed at runtime
        config.setQmodules(config.getQmodules() == null
            ? new ArrayList<>() : new ArrayList<>(config.getQmodules()));

        setLayout(new GridBagLayout());
        setBackground(theme.background);

        for (QModuleConfig mc : config.getQmodules()) modules.add(createPanel(mc));
        layoutModules();
        setMinimumSize(computeMinimumSize());

        setComponentPopupMenu(emptySpaceMenu());
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Starts SQL executors for all modules; modules added later start immediately. */
    public void startAll() {
        running = true;
        modules.forEach(QModulePanel::startExecutor);
    }

    /** Stops all SQL executors (call on application shutdown). */
    public void stopAll() {
        running = false;
        modules.forEach(QModulePanel::stopExecutor);
    }

    public void setTheme(Theme theme) {
        this.theme = theme;
        setBackground(theme.background);
        modules.forEach(m -> m.setTheme(theme));
        repaint();
    }

    public void setModuleActions(ModuleActions actions) {
        this.actions = actions;
    }

    public int getTotalColumns() {
        return totalColumns;
    }

    public List<QModulePanel> getModules() {
        return Collections.unmodifiableList(modules);
    }

    /**
     * Grid cells taken by every module except {@code exclude} (pass {@code null} to include all).
     * Used by the editor's location picker so a module being edited can stay where it is.
     */
    public GridOccupancy occupancyExcluding(QModulePanel exclude) {
        GridOccupancy occupancy = new GridOccupancy(totalColumns);
        for (QModulePanel m : modules) {
            if (m != exclude) occupancy.occupy(m.getGridLocation(), m.getTitle());
        }
        return occupancy;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Runtime changes
    // ─────────────────────────────────────────────────────────────────────────

    /** Adds a new module to the dashboard and to the config. */
    public void addModule(QModuleConfig mc) throws Exception {
        QModulePanel panel = createPanel(mc);
        modules.add(panel);
        config.getQmodules().add(mc);
        layoutModules();
        if (running) panel.startExecutor();
    }

    /**
     * Replaces {@code old} with a module built from {@code mc}, keeping its position in the
     * config list. The new panel is built first, so on failure nothing changes.
     */
    public void replaceModule(QModulePanel old, QModuleConfig mc) throws Exception {
        int index = modules.indexOf(old);
        if (index < 0) throw new IllegalArgumentException("Module is not on this dashboard");

        QModulePanel panel = createPanel(mc);
        old.stopExecutor();
        modules.set(index, panel);
        config.getQmodules().set(index, mc);
        layoutModules();
        if (running) panel.startExecutor();
    }

    /** Stops and removes {@code module} from the dashboard and the config. */
    public void removeModule(QModulePanel module) {
        int index = modules.indexOf(module);
        if (index < 0) return;

        module.stopExecutor();
        modules.remove(index);
        config.getQmodules().remove(index);
        layoutModules();
    }

    // ─────────────────────────────────────────────────────────────────────────

    private QModulePanel createPanel(QModuleConfig mc) throws Exception {
        QModulePanel panel = new QModulePanel(mc, config.getSettings(), theme);
        panel.setComponentPopupMenu(moduleMenu(panel));
        return panel;
    }

    /** (Re)builds the grid from {@link #modules}; called after every runtime change. */
    private void layoutModules() {
        removeAll();

        for (QModulePanel panel : modules) {
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
        JComponent strut = (JComponent) Box.createVerticalStrut(border);
        strut.setInheritsPopupMenu(true);
        add(strut, filler);

        revalidate();
        repaint();
    }

    private JPopupMenu moduleMenu(QModulePanel panel) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem heading = new JMenuItem(panel.getTitle());
        heading.setEnabled(false);
        menu.add(heading);
        menu.addSeparator();
        menu.add(menuItem("Edit…",   () -> actions.editModule(panel)));
        menu.add(menuItem("Delete…", () -> actions.deleteModule(panel)));
        menu.addSeparator();
        menu.add(menuItem("Add QModule…", () -> actions.addModule()));
        return menu;
    }

    private JPopupMenu emptySpaceMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem("Add QModule…", () -> actions.addModule()));
        return menu;
    }

    private JMenuItem menuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> { if (actions != null) action.run(); });
        return item;
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
