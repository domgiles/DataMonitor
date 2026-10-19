package com.dom.datamonitor.db;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryResultParserTest {

    private static final int VARCHAR = Types.VARCHAR;
    private static final int NUMBER  = Types.NUMERIC;

    @Test
    void parsesDupletRows() throws SQLException {
        QueryResult r = QueryResultParser.parse(FakeResultSet.of(
            new int[]{VARCHAR, NUMBER},
            List.of(new Object[]{"Reads", new BigDecimal("10")},
                    new Object[]{"Writes", 20})), 1.0);

        assertFalse(r.isError());
        assertEquals(List.of(new QueryRow("Reads", 10), new QueryRow("Writes", 20)), r.rows());
    }

    @Test
    void parsesTripletWithColourInMiddle() throws SQLException {
        QueryResult r = QueryResultParser.parse(FakeResultSet.of(
            new int[]{VARCHAR, VARCHAR, NUMBER},
            List.<Object[]>of(new Object[]{"Reads", "green", 5})), 1.0);

        assertEquals(List.of(new QueryRow("Reads", 5, "green")), r.rows());
    }

    @Test
    void parsesTripletWithColourLast() throws SQLException {
        QueryResult r = QueryResultParser.parse(FakeResultSet.of(
            new int[]{VARCHAR, NUMBER, VARCHAR},
            List.<Object[]>of(new Object[]{"Reads", 5, "green"})), 1.0);

        assertEquals(List.of(new QueryRow("Reads", 5, "green")), r.rows());
    }

    @Test
    void parsesSingleRowMultiTriplet() throws SQLException {
        QueryResult r = QueryResultParser.parse(FakeResultSet.of(
            new int[]{VARCHAR, VARCHAR, NUMBER, VARCHAR, VARCHAR, NUMBER},
            List.<Object[]>of(new Object[]{"A", "red", 1, "B", "blue", 2})), 1.0);

        assertEquals(List.of(new QueryRow("A", 1, "red"), new QueryRow("B", 2, "blue")), r.rows());
    }

    @Test
    void parsesSingleRowMultiDuplet() throws SQLException {
        QueryResult r = QueryResultParser.parse(FakeResultSet.of(
            new int[]{VARCHAR, NUMBER, VARCHAR, NUMBER},
            List.<Object[]>of(new Object[]{"A", 1, "B", 2})), 1.0);

        assertEquals(List.of(new QueryRow("A", 1), new QueryRow("B", 2)), r.rows());
    }

    @Test
    void appliesDivideBy() throws SQLException {
        QueryResult r = QueryResultParser.parse(FakeResultSet.of(
            new int[]{VARCHAR, NUMBER},
            List.<Object[]>of(new Object[]{"MB", 1_048_576 * 3L})), 1_048_576);

        assertEquals(3.0, r.rows().get(0).value(), 1e-9);
    }

    @Test
    void nullAndNonNumericValuesBecomeZero() throws SQLException {
        QueryResult r = QueryResultParser.parse(FakeResultSet.of(
            new int[]{VARCHAR, NUMBER},
            List.of(new Object[]{"null", null},
                    new Object[]{"text", "abc"},
                    new Object[]{"numeric text", "2.5"})), 1.0);

        assertEquals(List.of(0.0, 0.0, 2.5), r.rows().stream().map(QueryRow::value).toList());
    }

    @Test
    void emptyResultSetGivesEmptyResult() throws SQLException {
        QueryResult r = QueryResultParser.parse(FakeResultSet.of(
            new int[]{VARCHAR, NUMBER}, List.of()), 1.0);

        assertTrue(r.isEmpty());
        assertFalse(r.isError());
    }
}
