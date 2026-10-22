package com.dom.datamonitor.tui;

import com.dom.datamonitor.engine.MonitorEngine.ModuleView;
import com.dom.datamonitor.engine.MonitorEngine.Sample;
import com.dom.datamonitor.model.QueryRow;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.dom.datamonitor.tui.TuiTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ChartsTest {

    private static final long T0 = 1_700_000_000_000L;

    /** {@code n} samples 2 s apart of two series: "Reads" rising, "Writes" constant. */
    private static List<Sample> twoSeries(int n) {
        List<Sample> h = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            h.add(sample(T0 + i * 2000L, new QueryRow("Reads", 10 * i, "green"), new QueryRow("Writes", 20)));
        }
        return h;
    }

    @Test
    void seriesKeepTheirFirstAppearanceOrderAndSkipErrors() {
        List<Sample> h = new ArrayList<>(twoSeries(3));
        h.add(error(T0 + 6000, "ORA-03113"));
        List<Charts.Series> s = Charts.series(h);
        assertEquals(List.of("Reads", "Writes"), s.stream().map(Charts.Series::name).toList());
        assertEquals(3, s.get(0).points().size());
        assertEquals(20, s.get(0).last());
        assertEquals("green", s.get(0).colour());
        assertEquals(1, s.get(1).index());
    }

    @Test
    void valuesBetweenReadingsAreInterpolated() {
        Charts.Series reads = Charts.series(twoSeries(3)).get(0);     // 0, 10, 20 at T0, +2 s, +4 s
        assertEquals(5.0,  Charts.valueAt(reads, T0 + 1000));
        assertEquals(20.0, Charts.valueAt(reads, T0 + 9000), "the newest reading carries on to the edge");
        assertNull(Charts.valueAt(reads, T0 - 1));
    }

    @Test
    void lineChartDrawsBrailleWithAxisLegendTimesAndLatestValues() {
        ModuleView m = module("Reads and writes", "LINE", "R1,C1", "DIFFERENCE", twoSeries(30));
        List<String> lines = render(m, 70, 12, T0 + 60_000);
        String all = text(lines);
        assertTrue(all.chars().anyMatch(c -> c > 0x2800 && c <= 0x28FF), "braille dots:\n" + all);
        assertTrue(lines.get(1).contains("■ Reads") && lines.get(1).contains("■ Writes"), lines.get(1));
        assertTrue(lines.get(2).contains("300"), "axis maximum (niceTicks of 290): " + lines.get(2));
        assertTrue(lines.get(9).matches("│ +0 .*"), "0 on the bottom row: " + lines.get(9));
        assertTrue(lines.get(10).matches("│ +\\d\\d:\\d\\d:\\d\\d .* \\d\\d:\\d\\d:\\d\\d +│"), "times: " + lines.get(10));
        assertTrue(all.contains(" 290 ") && all.contains(" 20 "), "latest values at the line ends:\n" + all);
    }

    @Test
    void areaChartStacksSeriesBottomToTopInTheirColours() {
        // Constant 10 and 10: the stack is 20 of an axis of 20, so each band fills half the plot
        List<Sample> h = new ArrayList<>();
        for (int i = 0; i < 10; i++) h.add(sample(T0 + i * 1000L, new QueryRow("A", 10, "red"), new QueryRow("B", 10, "blue")));
        Buffer buf = Buffer.empty(Rect.of(40, 8));
        assertTrue(Charts.timeSeries(h, 9, true, new Rect(0, 0, 40, 8), buf, THEME));

        // Rows 1-6 are the plot (legend above, times below); a column well inside the plot
        Color red = THEME.series("red", 0), blue = THEME.series("blue", 1);
        int x = 20;
        assertEquals(red,  buf.get(x, 6).style().fg().orElseThrow(), "A at the bottom");
        assertEquals(blue, buf.get(x, 2).style().fg().orElseThrow(), "B on top of A");
        assertEquals("█", buf.get(x, 6).symbol());
    }

    @Test
    void timeSeriesReportsWhenThereIsNoRoom() {
        Buffer buf = Buffer.empty(Rect.of(12, 3));
        assertFalse(Charts.timeSeries(twoSeries(5), 10, false, new Rect(0, 0, 12, 3), buf, THEME));
        assertFalse(Charts.timeSeries(List.of(), 10, false, new Rect(0, 0, 60, 10), buf, THEME));
    }

    @Test
    void pieDrawsADonutWithTheTotalAndShares() {
        ModuleView m = module("Waits", "PIE", "R1,C1", "NONE", List.of(sample(T0,
            new QueryRow("User I/O", 300), new QueryRow("Commit", 100))));
        List<String> lines = render(m, 50, 12, T0);
        String all = text(lines);
        assertTrue(all.contains("█") && all.contains("▀"), "donut:\n" + all);
        assertTrue(all.contains("400"), "total in the hole:\n" + all);
        assertTrue(all.contains("■ User I/O") && all.contains("75.0%") && all.contains("25.0%"), all);
    }

    @Test
    void pieSlicesStartAtTwelveOClockAndRunClockwise() {
        // Two equal slices: the first fills the right half, the second the left
        List<QueryRow> rows = List.of(new QueryRow("A", 1, "red"), new QueryRow("B", 1, "blue"));
        Buffer buf = Buffer.empty(Rect.of(40, 10));
        assertTrue(Charts.pie(rows, new Rect(0, 0, 40, 10), buf, THEME));
        String row = TuiTestSupport.lines(buf).get(5);
        int left = row.indexOf('█'), right = row.lastIndexOf('█');
        assertEquals(THEME.series("blue", 1), buf.get(left, 5).style().fg().orElseThrow());
        assertEquals(THEME.series("red", 0),  buf.get(right, 5).style().fg().orElseThrow());
    }

    @Test
    void brailleCanvasSetsTheRightDots() {
        BrailleCanvas c = new BrailleCanvas(1, 1);
        Color green = Color.rgb(0, 255, 0);
        c.line(0, 0, 1, 3, green);                // top-left to bottom-right of one cell
        Buffer buf = Buffer.empty(Rect.of(1, 1));
        c.render(0, 0, buf);
        // dots 1 (top left), 2, 6 (third row right) and 8 (bottom right) for this slope
        assertEquals(String.valueOf((char) (0x2800 | 0x01 | 0x02 | 0x20 | 0x80)), buf.get(0, 0).symbol());
    }
}
