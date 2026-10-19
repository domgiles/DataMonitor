package com.dom.datamonitor.db;

import com.dom.datamonitor.model.ProcessingMode;
import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises {@link SQLExecutor#applyProcessingMode} directly; no database or scheduler involved. */
class SQLExecutorProcessingTest {

    private static final double EPS = 1e-9;

    private static SQLExecutor executor(ProcessingMode mode, int refreshRateSeconds) {
        return new SQLExecutor("SELECT 1 FROM dual", mode, 1.0, null, refreshRateSeconds);
    }

    private static QueryResult rows(QueryRow... rows) {
        return QueryResult.success(Arrays.asList(rows));
    }

    private static double valueOf(QueryResult r, String key) {
        return r.rows().stream().filter(q -> q.key().equals(key)).findFirst().orElseThrow().value();
    }

    @Test
    void noneReturnsValuesUnchanged() {
        SQLExecutor ex = executor(ProcessingMode.NONE, 2);
        QueryResult in = rows(new QueryRow("a", 42, "red"));
        assertEquals(in.rows(), ex.applyProcessingMode(in).rows());
    }

    @Test
    void differenceIsPerSecondAndFirstRunIsZero() {
        SQLExecutor ex = executor(ProcessingMode.DIFFERENCE, 2);
        assertEquals(0.0,  valueOf(ex.applyProcessingMode(rows(new QueryRow("a", 100))), "a"), EPS);
        assertEquals(10.0, valueOf(ex.applyProcessingMode(rows(new QueryRow("a", 120))), "a"), EPS);
        assertEquals(5.0,  valueOf(ex.applyProcessingMode(rows(new QueryRow("a", 130))), "a"), EPS);
    }

    @Test
    void changePerMinuteAndPerHourScaleTheRate() {
        SQLExecutor perMin  = executor(ProcessingMode.CHANGE_PER_MIN, 5);
        SQLExecutor perHour = executor(ProcessingMode.CHANGE_PER_HOUR, 5);
        for (SQLExecutor ex : List.of(perMin, perHour)) ex.applyProcessingMode(rows(new QueryRow("a", 0)));

        assertEquals(120.0,  valueOf(perMin.applyProcessingMode(rows(new QueryRow("a", 10))), "a"), EPS);
        assertEquals(7200.0, valueOf(perHour.applyProcessingMode(rows(new QueryRow("a", 10))), "a"), EPS);
    }

    @Test
    void negativeDeltaIsClampedToZeroAndBecomesNewBaseline() {
        SQLExecutor ex = executor(ProcessingMode.DIFFERENCE, 1);
        ex.applyProcessingMode(rows(new QueryRow("a", 1000)));
        assertEquals(0.0, valueOf(ex.applyProcessingMode(rows(new QueryRow("a", 10))), "a"), EPS);
        assertEquals(5.0, valueOf(ex.applyProcessingMode(rows(new QueryRow("a", 15))), "a"), EPS);
    }

    @Test
    void ratesAreTrackedPerSeriesKeyAndNewKeysStartAtZero() {
        SQLExecutor ex = executor(ProcessingMode.DIFFERENCE, 1);
        ex.applyProcessingMode(rows(new QueryRow("a", 10)));

        QueryResult r = ex.applyProcessingMode(rows(new QueryRow("a", 13), new QueryRow("b", 500)));
        assertEquals(3.0, valueOf(r, "a"), EPS);
        assertEquals(0.0, valueOf(r, "b"), EPS);

        r = ex.applyProcessingMode(rows(new QueryRow("a", 14), new QueryRow("b", 507)));
        assertEquals(1.0, valueOf(r, "a"), EPS);
        assertEquals(7.0, valueOf(r, "b"), EPS);
    }

    @Test
    void cumulativeSinceStartMeasuresFromFirstObservation() {
        SQLExecutor ex = executor(ProcessingMode.CUMULATIVE_SINCE_START, 3);
        assertEquals(0.0,  valueOf(ex.applyProcessingMode(rows(new QueryRow("a", 50))), "a"), EPS);
        assertEquals(25.0, valueOf(ex.applyProcessingMode(rows(new QueryRow("a", 75))), "a"), EPS);
        assertEquals(40.0, valueOf(ex.applyProcessingMode(rows(new QueryRow("a", 90))), "a"), EPS);
    }

    @Test
    void processingPreservesColour() {
        SQLExecutor ex = executor(ProcessingMode.DIFFERENCE, 1);
        assertEquals("teal", ex.applyProcessingMode(rows(new QueryRow("a", 1, "teal"))).rows().get(0).color());
    }
}
