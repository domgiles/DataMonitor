package com.dom.datamonitor.db;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Converts a JDBC {@link ResultSet} into a {@link QueryResult}.
 *
 * <h3>Supported result-set shapes</h3>
 * <table border="1">
 *   <tr><th>Columns per row</th><th>Layout</th><th>Notes</th></tr>
 *   <tr><td>2</td><td>(key, value)</td><td>Duplet – multiple rows allowed</td></tr>
 *   <tr><td>3</td><td>(key, colour, value) <em>or</em> (key, value, colour)</td>
 *       <td>Auto-detected by JDBC type of column 2.  Multiple rows allowed.</td></tr>
 *   <tr><td>N × 2</td><td>(key₁, val₁, key₂, val₂, …)</td>
 *       <td>Single-row multi-duplet (legacy style)</td></tr>
 *   <tr><td>N × 3</td><td>(key₁, col₁, val₁, key₂, col₂, val₂, …)</td>
 *       <td>Single-row multi-triplet (legacy style)</td></tr>
 * </table>
 *
 * <p>The {@code divideBy} divisor is applied to every numeric value after parsing.
 */
public class QueryResultParser {

    private static final Logger log = Logger.getLogger(QueryResultParser.class.getName());

    private QueryResultParser() {}

    /**
     * Parses all rows from {@code rs} into a {@link QueryResult}.
     *
     * @param rs       open ResultSet positioned before the first row
     * @param divideBy divisor to apply to all numeric values (pass 1.0 for no scaling)
     */
    public static QueryResult parse(ResultSet rs, double divideBy) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int colCount = meta.getColumnCount();
        List<QueryRow> rows = new ArrayList<>();

        if (colCount == 2) {
            // ── Duplet rows: (key, value) ──────────────────────────────────
            while (rs.next()) {
                String key   = rs.getString(1);
                double value = toDouble(rs, 2) / divideBy;
                rows.add(new QueryRow(key, value));
            }

        } else if (colCount == 3) {
            // ── Triplet rows: auto-detect column order ────────────────────
            boolean isOldStyle = isStringType(meta, 2); // (key, colour, value)
            while (rs.next()) {
                if (isOldStyle) {
                    String key    = rs.getString(1);
                    String colour = rs.getString(2);
                    double value  = toDouble(rs, 3) / divideBy;
                    rows.add(new QueryRow(key, value, colour));
                } else {
                    String key    = rs.getString(1);
                    double value  = toDouble(rs, 2) / divideBy;
                    String colour = rs.getString(3);
                    rows.add(new QueryRow(key, value, colour));
                }
            }

        } else if (colCount > 3 && colCount % 3 == 0) {
            // ── Single-row multi-triplet: (k,c,v, k,c,v, …) ─────────────
            boolean isOldStyle = isStringType(meta, 2);
            if (rs.next()) {
                for (int i = 1; i <= colCount; i += 3) {
                    String key, colour;
                    double value;
                    if (isOldStyle) {
                        key    = rs.getString(i);
                        colour = rs.getString(i + 1);
                        value  = toDouble(rs, i + 2) / divideBy;
                    } else {
                        key    = rs.getString(i);
                        value  = toDouble(rs, i + 1) / divideBy;
                        colour = rs.getString(i + 2);
                    }
                    rows.add(new QueryRow(key, value, colour));
                }
            }

        } else if (colCount > 2 && colCount % 2 == 0) {
            // ── Single-row multi-duplet: (k,v, k,v, …) ────────────────────
            if (rs.next()) {
                for (int i = 1; i <= colCount; i += 2) {
                    String key   = rs.getString(i);
                    double value = toDouble(rs, i + 1) / divideBy;
                    rows.add(new QueryRow(key, value));
                }
            }

        } else {
            // ── Best-effort: treat column 1 as key, last as value ──────────
            log.warning("Unexpected column count " + colCount
                + " – attempting best-effort parse");
            while (rs.next()) {
                String key   = rs.getString(1);
                double value = toDouble(rs, colCount) / divideBy;
                rows.add(new QueryRow(key, value));
            }
        }

        return QueryResult.success(rows);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static boolean isStringType(ResultSetMetaData meta, int col) throws SQLException {
        return isStringType(meta.getColumnType(col));
    }

    /** True for character column types; used to tell (key, colour, value) from (key, value, colour). */
    static boolean isStringType(int type) {
        return type == Types.VARCHAR || type == Types.CHAR
            || type == Types.NVARCHAR || type == Types.NCHAR
            || type == Types.LONGVARCHAR;
    }

    private static double toDouble(ResultSet rs, int col) throws SQLException {
        Object val = rs.getObject(col);
        if (val == null) return 0.0;
        if (val instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(val.toString()); } catch (NumberFormatException e) { return 0.0; }
    }
}
