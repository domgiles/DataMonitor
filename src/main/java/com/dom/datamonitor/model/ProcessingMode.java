package com.dom.datamonitor.model;

import com.dom.datamonitor.config.ConfigException;

/**
 * How successive query values are processed before being sent to the chart.
 *
 * <ul>
 *   <li>NONE                  – raw value passed through unchanged</li>
 *   <li>DIFFERENCE            – per-second rate of change between successive results</li>
 *   <li>CHANGE_PER_MIN        – per-minute rate of change between successive results</li>
 *   <li>CHANGE_PER_HOUR       – per-hour rate of change between successive results</li>
 *   <li>CUMULATIVE_SINCE_START – total accumulated change from the value observed at startup</li>
 * </ul>
 */
public enum ProcessingMode {
    NONE,
    DIFFERENCE,
    CHANGE_PER_MIN,
    CHANGE_PER_HOUR,
    CUMULATIVE_SINCE_START;

    public static ProcessingMode fromString(String s) throws ConfigException {
        if (s == null || s.isBlank()) return NONE;
        try {
            return valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ConfigException(
                "Unknown processingMode '" + s + "'. Valid values: " +
                "NONE, DIFFERENCE, CHANGE_PER_MIN, CHANGE_PER_HOUR, CUMULATIVE_SINCE_START");
        }
    }
}
