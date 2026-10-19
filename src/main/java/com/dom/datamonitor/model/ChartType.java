package com.dom.datamonitor.model;

import com.dom.datamonitor.config.ConfigException;

/**
 * The visual rendering type for a QModule.
 * TIME_SERIES types (LINE, AREA) maintain a rolling data window.
 * SNAPSHOT types (BAR, PIE, TEXT, TABLE) display the latest query result only.
 */
public enum ChartType {
    LINE,
    AREA,
    BAR,
    PIE,
    TEXT,
    TABLE;

    public static ChartType fromString(String s) throws ConfigException {
        if (s == null || s.isBlank()) {
            throw new ConfigException("chartType is required. Valid values: LINE, AREA, BAR, PIE, TEXT, TABLE");
        }
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConfigException(
                "Unknown chartType '" + s + "'. Valid values: LINE, AREA, BAR, PIE, TEXT, TABLE");
        }
    }

    /** Returns true for time-series chart types that accumulate a rolling data window. */
    public boolean isTimeSeries() {
        return this == LINE || this == AREA;
    }
}
