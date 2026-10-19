package com.dom.datamonitor.model;

/**
 * A single result row from a monitored SQL query after processing.
 *
 * <p>The SQL must return either:
 * <ul>
 *   <li>Duplet  – {@code (key VARCHAR, value NUMERIC)}</li>
 *   <li>Triplet – {@code (key VARCHAR, colour VARCHAR, value NUMERIC)} (legacy/old-style)
 *              or {@code (key VARCHAR, value NUMERIC, colour VARCHAR)} (new-style)</li>
 * </ul>
 * Multi-series queries may return several rows (via UNION) or several groups of columns
 * in one row; {@link com.dom.datamonitor.db.QueryResultParser} handles both patterns.
 *
 * @param key    Series label (e.g. "Reads/sec")
 * @param value  Numeric data value after divideBy and processingMode transformations
 * @param color  Optional colour name / hex string (null if not specified in SQL)
 */
public record QueryRow(String key, double value, String color) {

    /** Convenience constructor when the SQL does not supply a colour. */
    public QueryRow(String key, double value) {
        this(key, value, null);
    }

    public boolean hasColor() {
        return color != null && !color.isBlank();
    }
}
