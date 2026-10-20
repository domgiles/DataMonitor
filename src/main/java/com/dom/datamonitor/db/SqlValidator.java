package com.dom.datamonitor.db;

import com.dom.datamonitor.config.ConnectionConfig;
import com.dom.datamonitor.model.ChartType;
import com.dom.datamonitor.model.QueryRow;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Checks a QModule's SQL before it is accepted in the editor dialog.
 *
 * <p>{@link #validate} runs the query once (read-only statements only, limited rows,
 * with a timeout), checks that the column layout is one {@link QueryResultParser}
 * understands, and returns the parsed rows as a preview.
 *
 * <p>The static checks ({@link #readOnlyProblem}, {@link #shapeProblems},
 * {@link #warningsFor}) need no database and are unit tested directly.
 */
public final class SqlValidator {

    /** Rows fetched for the preview. */
    public static final int PREVIEW_ROWS          = 20;
    private static final int QUERY_TIMEOUT_SECONDS = 15;

    /** Oracle-specific JDBC type codes for BINARY_FLOAT / BINARY_DOUBLE. */
    private static final int ORACLE_BINARY_FLOAT  = 100;
    private static final int ORACLE_BINARY_DOUBLE = 101;

    /**
     * Outcome of a validation run.
     *
     * @param ok        true if the SQL ran and its columns suit the dashboard
     * @param message   one-line summary (the error when {@code ok} is false)
     * @param warnings  non-blocking advice, e.g. "query returned no rows"
     * @param preview   parsed rows (after divideBy), as the chart would receive them
     */
    public record Result(boolean ok, String message, List<String> warnings, List<QueryRow> preview) {
        static Result failed(String message) {
            return new Result(false, message, List.of(), List.of());
        }
    }

    private SqlValidator() {}

    // ─────────────────────────────────────────────────────────────────────────

    /** Runs {@code sql} once against {@code conn}. Call off the EDT. */
    public static Result validate(String sql, ConnectionConfig conn, ChartType chartType, double divideBy) {
        String problem = readOnlyProblem(sql);
        if (problem != null) return Result.failed(problem);

        try (Connection c = ConnectionManager.getInstance().getConnection(conn)) {
            try (PreparedStatement ps = c.prepareStatement(sql.strip())) {
                ps.setMaxRows(PREVIEW_ROWS);
                ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int[] types = new int[meta.getColumnCount()];
                    for (int i = 0; i < types.length; i++) types[i] = meta.getColumnType(i + 1);

                    List<String> shape = shapeProblems(types);
                    if (!shape.isEmpty()) return Result.failed(String.join(" ", shape));

                    List<QueryRow> rows = QueryResultParser.parse(rs, divideBy).rows();
                    String summary = "Valid: " + types.length + " columns, "
                        + rows.size() + " series"
                        + (rows.size() >= PREVIEW_ROWS ? " (preview limited to " + PREVIEW_ROWS + ")" : "");
                    return new Result(true, summary, warningsFor(chartType, rows), rows);
                }
            } finally {
                // Belt and braces: never leave anything from a validation run uncommitted
                if (!c.getAutoCommit()) c.rollback();
            }
        } catch (SQLException e) {
            // Oracle appends a help URL on a second line; the first line is the useful part
            return Result.failed(e.getMessage().strip().lines().findFirst().orElse("SQL error"));
        } catch (RuntimeException e) {
            return Result.failed("Connection error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Static checks
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns why {@code sql} must not be run, or {@code null} if it is a single read-only query.
     * Only statements starting with SELECT or WITH (after comments and brackets) are allowed.
     */
    public static String readOnlyProblem(String sql) {
        if (sql == null || sql.isBlank()) return "Enter a SQL query.";
        String body = stripLeadingNoise(sql);
        String firstWord = body.split("[\\s(]+", 2)[0].toUpperCase(Locale.ROOT);
        if (!firstWord.equals("SELECT") && !firstWord.equals("WITH")) {
            return "Only SELECT (or WITH … SELECT) queries can be used in a QModule.";
        }
        if (sql.strip().endsWith(";")) {
            return "Remove the trailing semicolon; Oracle JDBC rejects it.";
        }
        return null;
    }

    /** Removes leading whitespace, {@code --} and block comments, and opening brackets. */
    private static String stripLeadingNoise(String sql) {
        String s = sql.strip();
        while (true) {
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                s = (nl < 0) ? "" : s.substring(nl + 1).strip();
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                s = (end < 0) ? "" : s.substring(end + 2).strip();
            } else if (s.startsWith("(")) {
                s = s.substring(1).strip();
            } else {
                return s;
            }
        }
    }

    /**
     * Checks the column layout against the formats {@link QueryResultParser} supports.
     *
     * @param types JDBC type code of each column
     * @return problems that make the result unusable (empty if the layout is fine)
     */
    public static List<String> shapeProblems(int[] types) {
        List<String> problems = new ArrayList<>();
        int n = types.length;

        if (n < 2) {
            problems.add("The query must return at least 2 columns: a label and a value.");
        } else if (n == 2 || (n % 3 != 0 && n % 2 == 0)) {
            // Duplets: (label, value) repeated
            for (int v = 2; v <= n; v += 2) requireNumeric(types, v, problems);
        } else if (n % 3 == 0) {
            // Triplets: (label, colour, value) or (label, value, colour), repeated
            for (int k = 1; k <= n; k += 3) {
                // Same rule as the parser: a text column 2 means (label, colour, value)
                int valueCol = QueryResultParser.isStringType(types[k]) ? k + 2 : k + 1;
                requireNumeric(types, valueCol, problems);
            }
        } else {
            problems.add("Unsupported layout of " + n + " columns. Use (label, value), "
                + "(label, colour, value), or repeats of those.");
        }
        return problems;
    }

    /** Non-blocking advice about how {@code chartType} will show these rows. */
    public static List<String> warningsFor(ChartType chartType, List<QueryRow> rows) {
        List<String> warnings = new ArrayList<>();
        if (rows.isEmpty()) {
            warnings.add("The query returned no rows, so the chart will be empty until it does.");
        } else if (chartType == ChartType.TEXT && rows.size() > 1) {
            warnings.add("TEXT shows only the first row; " + (rows.size() - 1) + " more will be ignored.");
        }
        return warnings;
    }

    private static void requireNumeric(int[] types, int col, List<String> problems) {
        if (!isNumeric(types[col - 1])) {
            problems.add("Column " + col + " should be numeric (it holds the value).");
        }
    }

    private static boolean isNumeric(int type) {
        return switch (type) {
            case Types.NUMERIC, Types.DECIMAL, Types.INTEGER, Types.BIGINT, Types.SMALLINT,
                 Types.TINYINT, Types.FLOAT, Types.REAL, Types.DOUBLE,
                 ORACLE_BINARY_FLOAT, ORACLE_BINARY_DOUBLE -> true;
            default -> false;
        };
    }
}
