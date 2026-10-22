package com.dom.datamonitor.tui;

import com.dom.datamonitor.engine.MonitorEngine.ModuleView;
import com.dom.datamonitor.model.QueryRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dom.datamonitor.tui.TuiTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class PanelsTest {

    private static final long NOW = 1_000_000;

    @Test
    void titleIsUpperCaseWithModeAndRefreshOnTheBorder() {
        ModuleView m = module("Tx/Sec", "TEXT", "R1,C1", "DIFFERENCE", List.of(sample(NOW, new QueryRow("Tx", 5))));
        String top = render(m, 40, 10, NOW).get(0);
        assertTrue(top.startsWith("╭ TX/SEC "), top);
        assertTrue(top.endsWith(" Δ/s  2s ╮"), top);
    }

    @Test
    void textShowsTheValueInBigDigitsWithTheChangeAndATrend() {
        ModuleView m = module("Tx/Sec", "TEXT", "R1,C1", "NONE", List.of(
            sample(NOW - 4000, new QueryRow("Tx/Sec", 400)),
            sample(NOW - 2000, new QueryRow("Tx/Sec", 417)),
            sample(NOW,        new QueryRow("Tx/Sec", 462))));
        List<String> lines = render(m, 40, 12, NOW + 5000);
        String all = text(lines);
        assertTrue(all.contains("█"), all);
        assertFalse(all.contains("462"), "drawn as big digits, not text:\n" + all);
        String footer = lines.get(10);
        assertTrue(footer.contains("▲ +45"), footer);
        assertTrue(footer.matches(".*[▁▂▃▄▅▆▇█].*"), "sparkline: " + footer);
        assertFalse(all.contains("Tx/Sec"), "a label repeating the title is left out:\n" + all);
    }

    @Test
    void textShowsALabelThatDiffersFromTheTitle() {
        ModuleView m = module("Checkpoints since start", "TEXT", "R1,C1", "NONE",
                              List.of(sample(NOW, new QueryRow("Checkpoints", 3))));
        assertTrue(render(m, 40, 12, NOW).get(1).contains("Checkpoints"));
    }

    @Test
    void textFallsBackToPlainDigitsWhenThereIsNoRoom() {
        ModuleView m = module("T", "TEXT", "R1,C1", "NONE", List.of(sample(NOW, new QueryRow("T", 8192))));
        String all = text(render(m, 12, 3, NOW));
        assertTrue(all.contains("8,192"), all);
    }

    @Test
    void barsAreProportionalToARoundAxisMaximum() {
        ModuleView m = module("Commits", "BAR", "R1,C1", "NONE", List.of(sample(NOW,
            new QueryRow("User Commits", 400), new QueryRow("User Rollbacks", 0))));
        List<String> lines = render(m, 50, 7, NOW);
        String commits   = lines.stream().filter(l -> l.contains("User Commits")).findFirst().orElseThrow();
        String rollbacks = lines.stream().filter(l -> l.contains("User Rollbacks")).findFirst().orElseThrow();
        assertTrue(commits.contains("█") && commits.contains(" 400"), commits);
        assertFalse(rollbacks.contains("█"), rollbacks);
        assertTrue(rollbacks.contains(" 0"), rollbacks);
    }

    @Test
    void barWidthsAreExactToAnEighth() {
        assertEquals("█████", Panels.bar(0.5, 10));
        assertEquals("██▌",   Panels.bar(0.25, 10));
        assertEquals("",      Panels.bar(0, 10));
        assertEquals("████",  Panels.bar(2, 4), "clamped to the width");
    }

    @Test
    void tableListsRowsWithValuesOnTheRight() {
        ModuleView m = module("Top DB Parameters", "TABLE", "R1,C1", "NONE", List.of(sample(NOW,
            new QueryRow("cpu_count", 2), new QueryRow("db_block_size", 8192))));
        List<String> lines = render(m, 40, 8, NOW);
        assertTrue(lines.get(1).contains("METRIC") && lines.get(1).contains("VALUE"), lines.get(1));
        assertTrue(lines.get(3).matches("│ ▬ db_block_size +8,192 │"), lines.get(3));
    }

    @Test
    void tableSaysHowManyRowsDidNotFit() {
        QueryRow[] rows = new QueryRow[10];
        for (int i = 0; i < rows.length; i++) rows[i] = new QueryRow("row" + i, i);
        String all = text(render(module("T", "TABLE", "R1,C1", "NONE", List.of(sample(NOW, rows))), 30, 7, NOW));
        assertTrue(all.contains("+7 more"), all);
    }

    @Test
    void pieRowsShowTheirShareOfTheTotal() {
        ModuleView m = module("Waits", "PIE", "R1,C1", "NONE", List.of(sample(NOW,
            new QueryRow("User I/O", 30), new QueryRow("Commit", 10))));
        String all = text(render(m, 50, 8, NOW));
        assertTrue(all.contains("75.0%") && all.contains("25.0%"), all);
    }

    @Test
    void lineChartsListTheirLatestValuesForNow() {
        ModuleView m = module("Redo", "LINE", "R1,C1", "DIFFERENCE", List.of(sample(NOW, new QueryRow("Redo MB/sec", 1.37))));
        String all = text(render(m, 60, 8, NOW));
        assertTrue(all.contains("Redo MB/sec") && all.contains("1.37"), all);
        assertTrue(all.contains("drawn in the desktop and web versions"), all);
    }

    @Test
    void showsConnectingErrorsAndEmptyResults() {
        assertTrue(text(render(module("A", "TEXT", "R1,C1", null, List.of()), 30, 5, NOW)).contains("Connecting…"));

        String error = text(render(module("A", "BAR", "R1,C1", null,
            List.of(error(NOW, "ORA-00942: table or view does not exist"))), 30, 6, NOW));
        assertTrue(error.contains("ORA-00942: table or view") && error.contains("does not exist"), error);

        assertTrue(text(render(module("A", "TABLE", "R1,C1", null, List.of(sample(NOW))), 30, 5, NOW)).contains("No rows"));
    }

    @Test
    void sparkDataSpansMinimumToMaximumOfTheShownReadings() {
        assertArrayEquals(new long[] { 1, 51, 101 }, Panels.sparkData(List.of(999.0, 10.0, 15.0, 20.0), 3));
        assertArrayEquals(new long[] { 50, 50 }, Panels.sparkData(List.of(7.0, 7.0), 10));
    }

    @Test
    void modeBadgesMatchTheWebTiles() {
        assertEquals("Δ/s",   Panels.modeBadge("DIFFERENCE"));
        assertEquals("Δ/min", Panels.modeBadge("change_per_min"));
        assertEquals("Σ",     Panels.modeBadge("CUMULATIVE_SINCE_START"));
        assertEquals("",      Panels.modeBadge(null));
        assertEquals("▼ -3",  Panels.deltaText(-3));     // as on the web tile
        assertEquals("± 0",   Panels.deltaText(0));
    }

    @Test
    void longTitlesAreShortenedWithAnEllipsis() {
        ModuleView m = module("Checkpoints since the instance started", "TEXT", "R1,C1", null,
                              List.of(sample(NOW, new QueryRow("x", 1))));
        String top = render(m, 24, 6, NOW).get(0);
        assertTrue(top.contains("…") && top.endsWith("╮") && top.length() == 24, top);
    }
}
