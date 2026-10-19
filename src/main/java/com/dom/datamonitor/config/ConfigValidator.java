package com.dom.datamonitor.config;

import com.dom.datamonitor.model.ChartType;
import com.dom.datamonitor.model.GridLocation;
import com.dom.datamonitor.model.ProcessingMode;

import java.util.*;

/**
 * Validates a loaded {@link AppConfig} and returns user-friendly error messages.
 *
 * <p>Validation checks all fields at once and throws a single {@link ConfigException}
 * listing every problem found, so the operator can fix them all in one pass.
 */
public class ConfigValidator {

    public void validate(AppConfig config) throws ConfigException {
        List<String> errors = new ArrayList<>();

        validateSettings(config.getSettings(), errors);
        validateQModules(config.getQmodules(), config.getSettings(), errors);

        if (!errors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Configuration errors:\n");
            for (int i = 0; i < errors.size(); i++) {
                sb.append("  ").append(i + 1).append(") ").append(errors.get(i)).append('\n');
            }
            throw new ConfigException(sb.toString().stripTrailing());
        }
    }

    // -------------------------------------------------------------------------

    private void validateSettings(AppConfig.Settings s, List<String> errors) {
        if (s == null) {
            errors.add("settings section is missing");
            return;
        }

        if (s.getTitle() == null || s.getTitle().isBlank()) {
            errors.add("settings.title is required");
        }

        String theme = s.getTheme();
        if (theme != null && !theme.equalsIgnoreCase("dark") && !theme.equalsIgnoreCase("light")) {
            errors.add("settings.theme must be 'dark' or 'light', got: " + theme);
        }

        if (s.getRefreshRate() < 1) {
            errors.add("settings.refreshRate must be >= 1 second, got: " + s.getRefreshRate());
        }

        if (s.getWindowSize() < 1) {
            errors.add("settings.windowSize must be >= 1 second, got: " + s.getWindowSize());
        }

        if (s.getBorder() < 0) {
            errors.add("settings.border must be >= 0, got: " + s.getBorder());
        }

        int cols = s.getColumns();
        if (cols < 1 || cols > 3) {
            errors.add("settings.columns must be between 1 and 3, got: " + cols);
        }

        validateConnection(s.getConnection(), "settings.connection", true, errors);
    }

    private void validateConnection(ConnectionConfig conn, String path,
                                    boolean required, List<String> errors) {
        if (conn == null) {
            if (required) errors.add(path + " is required");
            return;
        }
        if (blank(conn.getUsername()))     errors.add(path + ".username is required");
        if (blank(conn.getPassword()))     errors.add(path + ".password is required");
        if (blank(conn.getConnectString())) errors.add(path + ".connectString is required");
    }

    private void validateQModules(List<QModuleConfig> modules,
                                  AppConfig.Settings settings,
                                  List<String> errors) {
        if (modules == null || modules.isEmpty()) {
            errors.add("qmodules list is required and must not be empty");
            return;
        }

        Set<String> usedCells = new HashSet<>();
        int totalColumns = (settings != null) ? settings.getColumns() : 3;

        for (int i = 0; i < modules.size(); i++) {
            QModuleConfig m = modules.get(i);
            String prefix   = "qmodules[" + (i + 1) + "]";

            if (blank(m.getTitle())) {
                errors.add(prefix + ".title is required");
            }

            if (blank(m.getGrid())) {
                errors.add(prefix + ".grid is required (e.g. R1,C1 or R2,C1*)");
            } else {
                try {
                    GridLocation loc = GridLocation.parse(m.getGrid());
                    // Check column bounds
                    int effectiveEnd = (loc.colEnd() == GridLocation.SPAN_ALL)
                        ? totalColumns : loc.colEnd();
                    if (effectiveEnd > totalColumns) {
                        errors.add(prefix + ".grid column " + effectiveEnd
                            + " exceeds settings.columns (" + totalColumns + ")");
                    }
                    // Check for duplicate cell occupation
                    for (int c = loc.colStart(); c <= effectiveEnd; c++) {
                        String cell = "R" + loc.row() + "C" + c;
                        if (!usedCells.add(cell)) {
                            errors.add(prefix + ".grid overlaps with another QModule at cell " + cell);
                        }
                    }
                } catch (ConfigException e) {
                    errors.add(prefix + ".grid: " + e.getMessage());
                }
            }

            if (blank(m.getChartType())) {
                errors.add(prefix + ".chartType is required (LINE, AREA, BAR, PIE, TEXT, TABLE)");
            } else {
                try {
                    ChartType.fromString(m.getChartType());
                } catch (ConfigException e) {
                    errors.add(prefix + ".chartType: " + e.getMessage());
                }
            }

            if (blank(m.getSql())) {
                errors.add(prefix + ".sql is required");
            }

            if (!blank(m.getProcessingMode())) {
                try {
                    ProcessingMode.fromString(m.getProcessingMode());
                } catch (ConfigException e) {
                    errors.add(prefix + ".processingMode: " + e.getMessage());
                }
            }

            if (m.getRefreshRate() != null && m.getRefreshRate() < 1) {
                errors.add(prefix + ".refreshRate must be >= 1 second, got: " + m.getRefreshRate());
            }

            if (m.getWindowSize() != null && m.getWindowSize() < 1) {
                errors.add(prefix + ".windowSize must be >= 1 second, got: " + m.getWindowSize());
            }

            if (m.getDivideBy() <= 0) {
                errors.add(prefix + ".divideBy must be > 0, got: " + m.getDivideBy());
            }

            // Module-level connection is optional; validate only when present
            if (m.getConnection() != null) {
                validateConnection(m.getConnection(), prefix + ".connection", false, errors);
            }
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
