package com.dom.datamonitor.db;

import com.dom.datamonitor.model.ChartType;
import com.dom.datamonitor.model.QueryRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlValidatorTest {

    private static final int S = Types.VARCHAR;
    private static final int N = Types.NUMERIC;

    @ParameterizedTest
    @ValueSource(strings = {
        "SELECT 1 FROM dual",
        "  select 'a', 1 from dual",
        "WITH x AS (SELECT 1 n FROM dual) SELECT 'n', n FROM x",
        "-- comment\nSELECT 1 FROM dual",
        "/* block */ (SELECT 1 FROM dual)",
    })
    void allowsReadOnlyQueries(String sql) {
        assertNull(SqlValidator.readOnlyProblem(sql));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DELETE FROM t", "update t set x = 1", "DROP TABLE t",
        "BEGIN NULL; END;", "-- SELECT\nDELETE FROM t", "/* SELECT */ INSERT INTO t VALUES (1)",
        "MERGE INTO t USING s ON (1=1) WHEN MATCHED THEN DELETE WHERE 1=1", "",
    })
    void rejectsAnythingElse(String sql) {
        assertNotNull(SqlValidator.readOnlyProblem(sql));
    }

    @Test
    void rejectsTrailingSemicolon() {
        assertTrue(SqlValidator.readOnlyProblem("SELECT 1 FROM dual;").contains("semicolon"));
    }

    @Test
    void acceptsSupportedLayouts() {
        assertTrue(SqlValidator.shapeProblems(new int[]{S, N}).isEmpty());
        assertTrue(SqlValidator.shapeProblems(new int[]{S, S, N}).isEmpty());
        assertTrue(SqlValidator.shapeProblems(new int[]{S, N, S}).isEmpty());
        assertTrue(SqlValidator.shapeProblems(new int[]{S, N, S, N}).isEmpty());
        assertTrue(SqlValidator.shapeProblems(new int[]{S, S, N, S, N, S}).isEmpty());
        assertTrue(SqlValidator.shapeProblems(new int[]{S, Types.INTEGER}).isEmpty());
    }

    @Test
    void rejectsUnsupportedLayouts() {
        assertFalse(SqlValidator.shapeProblems(new int[]{N}).isEmpty());
        assertFalse(SqlValidator.shapeProblems(new int[]{S, S}).isEmpty());
        assertFalse(SqlValidator.shapeProblems(new int[]{S, S, S}).isEmpty());
        assertFalse(SqlValidator.shapeProblems(new int[]{S, Types.DATE}).isEmpty());
        assertFalse(SqlValidator.shapeProblems(new int[]{S, N, S, N, S}).isEmpty());
    }

    @Test
    void warnsAboutEmptyResultsAndExtraTextRows() {
        assertFalse(SqlValidator.warningsFor(ChartType.LINE, List.of()).isEmpty());

        List<QueryRow> two = List.of(new QueryRow("a", 1), new QueryRow("b", 2));
        assertFalse(SqlValidator.warningsFor(ChartType.TEXT, two).isEmpty());
        assertTrue(SqlValidator.warningsFor(ChartType.BAR, two).isEmpty());
    }
}
