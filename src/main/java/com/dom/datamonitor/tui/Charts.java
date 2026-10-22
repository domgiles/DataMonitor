package com.dom.datamonitor.tui;

import com.dom.datamonitor.engine.MonitorEngine.Sample;
import com.dom.datamonitor.model.QueryRow;
import com.dom.datamonitor.util.NumberFormats;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LINE, AREA and PIE charts drawn in a panel's inner area, laid out like the web charts.
 *
 * <ul>
 *   <li>LINE: braille dots, one colour per series, with each line's latest value at its right.</li>
 *   <li>AREA: series stacked bottom to top, in half-block pixels so band edges keep their colours.</li>
 *   <li>Both: a legend line when there is more than one series, y-axis labels at round values
 *       from 0 (as {@link NumberFormats#niceTicks}), and times along the bottom.</li>
 *   <li>PIE: a donut in half-block pixels with the total in the hole and a legend with each
 *       slice's share.</li>
 * </ul>
 *
 * <p>Parts are dropped as the panel gets smaller (times first, then the legend); each method
 * returns false when there isn't room for a chart at all, and the caller lists the values instead.
 */
final class Charts {

    /** Plot size below which a time series isn't worth drawing. */
    static final int MIN_PLOT_COLS = 10, MIN_PLOT_ROWS = 2;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Charts() {}

    /** One series over time: its readings, oldest first, as {epoch ms, value}. */
    record Series(String name, String colour, int index, List<double[]> points) {
        double last() { return points.get(points.size() - 1)[1]; }
    }

    /** The series in the order they first appear, from samples without errors. */
    static List<Series> series(List<Sample> history) {
        Map<String, Series> byName = new LinkedHashMap<>();
        for (Sample s : history) {
            if (s.error() != null) continue;
            for (QueryRow r : s.rows()) {
                String name = r.key() == null ? "" : r.key();
                Series series = byName.computeIfAbsent(name,
                    n -> new Series(n, r.color(), byName.size(), new ArrayList<>()));
                series.points().add(new double[] { s.time(), r.value() });
            }
        }
        return new ArrayList<>(byName.values());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LINE and AREA

    static boolean timeSeries(List<Sample> history, int windowSeconds, boolean stacked,
                              Rect inner, Buffer buf, TuiTheme t) {
        List<Series> series = series(history);
        if (series.isEmpty()) return false;

        long end   = history.get(history.size() - 1).time();
        long start = end - Math.max(1, windowSeconds) * 1000L;

        int top = inner.y(), bottom = inner.bottom();
        boolean legend = series.size() > 1 && inner.height() >= 5;
        boolean times  = inner.height() >= 6;
        if (legend) top++;
        if (times)  bottom--;

        double max = 0;
        if (stacked) {
            for (double total : totals(history)) max = Math.max(max, total);
        } else {
            for (Series s : series) for (double[] p : s.points()) max = Math.max(max, p[1]);
        }
        double[] ticks  = NumberFormats.niceTicks(max);
        double axisMax  = ticks[ticks.length - 1];

        int plotRows = bottom - top;
        List<String> labels = new ArrayList<>();
        for (double tick : ticks) labels.add(NumberFormats.formatCompact(tick));
        int labelW = 0;
        for (String l : labels) labelW = Math.max(labelW, l.length());

        // Latest values at the right of the lines, when there's room for them
        int valueW = 0;
        if (!stacked) for (Series s : series) valueW = Math.max(valueW, NumberFormats.formatCompact(s.last()).length());
        int left = inner.x() + 1 + labelW + 1;
        int plotCols = inner.right() - 1 - left;
        if (valueW > 0 && plotCols - (valueW + 1) >= MIN_PLOT_COLS * 2) plotCols -= valueW + 1;
        else valueW = 0;
        if (plotRows < MIN_PLOT_ROWS || plotCols < MIN_PLOT_COLS) return false;

        if (legend) legend(series, inner.x() + 1, inner.y(), inner.width() - 2, buf, t);
        yLabels(ticks, labels, axisMax, inner.x() + 1, labelW, top, plotRows, buf, t);
        if (times) timeLabels(start, end, left, plotCols, bottom, buf, t);

        if (stacked) {
            area(series, start, end, axisMax, left, top, plotCols, plotRows, buf, t);
        } else {
            line(series, start, end, axisMax, left, top, plotCols, plotRows, buf, t);
            if (valueW > 0) lastValues(series, axisMax, left + plotCols + 1, top, plotRows, buf, t);
        }
        return true;
    }

    private static void line(List<Series> series, long start, long end, double axisMax,
                             int left, int top, int cols, int rows, Buffer buf, TuiTheme t) {
        BrailleCanvas canvas = new BrailleCanvas(cols, rows);
        for (Series s : series) {
            Color colour = t.series(s.colour(), s.index());
            int px = -1, py = -1;
            for (double[] p : s.points()) {
                int x = (int) Math.round((p[0] - start) / (end - start) * (canvas.width() - 1));
                int y = (int) Math.round((1 - Math.max(0, p[1]) / axisMax) * (canvas.height() - 1));
                if (px < 0) canvas.set(x, y, colour); else canvas.line(px, py, x, y, colour);
                px = x; py = y;
            }
        }
        canvas.render(left, top, buf);
    }

    private static void area(List<Series> series, long start, long end, double axisMax,
                             int left, int top, int cols, int rows, Buffer buf, TuiTheme t) {
        PixelCanvas canvas = new PixelCanvas(cols, rows);
        for (int x = 0; x < cols; x++) {
            double time = start + (x + 0.5) / cols * (end - start);
            double sum = 0;
            int filled = 0;                               // pixels filled from the bottom so far
            for (Series s : series) {
                Double v = valueAt(s, time);
                if (v == null) continue;
                sum += Math.max(0, v);
                int to = (int) Math.round(Math.min(1, sum / axisMax) * canvas.height);
                Color colour = t.series(s.colour(), s.index());
                for (int y = filled; y < to; y++) canvas.set(x, canvas.height - 1 - y, colour);
                filled = Math.max(filled, to);
            }
        }
        canvas.render(left, top, buf);
    }

    /** The series' value at {@code time}, interpolated between readings; null outside them. */
    static Double valueAt(Series s, double time) {
        List<double[]> pts = s.points();
        if (pts.isEmpty() || time < pts.get(0)[0] || time > pts.get(pts.size() - 1)[0]) {
            // The newest reading covers the right-hand edge up to "now"
            return !pts.isEmpty() && time > pts.get(pts.size() - 1)[0] ? pts.get(pts.size() - 1)[1] : null;
        }
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            if (time <= b[0]) {
                double f = b[0] == a[0] ? 1 : (time - a[0]) / (b[0] - a[0]);
                return a[1] + f * (b[1] - a[1]);
            }
        }
        return pts.get(pts.size() - 1)[1];
    }

    /** The stacked total of each sample, for the area chart's axis. */
    private static List<Double> totals(List<Sample> history) {
        List<Double> out = new ArrayList<>();
        for (Sample s : history) {
            if (s.error() != null) continue;
            double sum = 0;
            for (QueryRow r : s.rows()) sum += Math.max(0, r.value());
            out.add(sum);
        }
        return out;
    }

    private static void legend(List<Series> series, int x, int y, int width, Buffer buf, TuiTheme t) {
        int end = x + width;
        for (int i = 0; i < series.size(); i++) {
            Series s = series.get(i);
            String item = s.name();
            int need = 2 + item.length() + (i < series.size() - 1 ? 2 : 0);
            if (x + need > end) {
                String more = "+" + (series.size() - i);
                if (x + more.length() <= end) buf.setString(x, y, more, t.muted);
                return;
            }
            buf.setString(x, y, "■ ", Style.EMPTY.fg(t.series(s.colour(), s.index())));
            buf.setString(x + 2, y, item, t.muted);
            x += need;
        }
    }

    /** Tick labels right-aligned before the plot; 0 on the bottom row, the axis maximum on the top. */
    private static void yLabels(double[] ticks, List<String> labels, double axisMax, int x, int width,
                                int top, int rows, Buffer buf, TuiTheme t) {
        boolean[] used = new boolean[rows];
        // Top and bottom first, so they always show; then the ones between where there's a free row
        int[] order = new int[ticks.length];
        order[0] = ticks.length - 1;
        order[1 % ticks.length] = 0;
        for (int i = 1, k = 2; i < ticks.length - 1; i++) order[k++] = i;
        for (int i : order) {
            int row = (int) Math.round((1 - ticks[i] / axisMax) * (rows - 1));
            if (used[row] || (row > 0 && used[row - 1]) || (row < rows - 1 && used[row + 1])) {
                if (i != ticks.length - 1 && i != 0) continue;
            }
            used[row] = true;
            String l = labels.get(i);
            buf.setString(x + width - l.length(), top + row, l, t.muted);
        }
    }

    /** Start and end of the window under the plot, and the middle when there's room. */
    private static void timeLabels(long start, long end, int left, int cols, int y, Buffer buf, TuiTheme t) {
        String a = time(start), b = time(end), m = time((start + end) / 2);
        if (cols < a.length() + b.length() + 2) {
            buf.setString(left + cols - b.length(), y, b, t.muted);
            return;
        }
        buf.setString(left, y, a, t.muted);
        buf.setString(left + cols - b.length(), y, b, t.muted);
        if (cols >= a.length() + m.length() + b.length() + 8) {
            buf.setString(left + (cols - m.length()) / 2, y, m, t.muted);
        }
    }

    private static String time(long millis) {
        return TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()));
    }

    /** Each line's latest value beside its end; skipped where it would sit on another's row. */
    private static void lastValues(List<Series> series, double axisMax, int x, int top, int rows,
                                   Buffer buf, TuiTheme t) {
        boolean[] used = new boolean[rows];
        for (Series s : series) {
            int row = (int) Math.round((1 - Math.max(0, s.last()) / axisMax) * (rows - 1));
            if (used[row]) continue;
            used[row] = true;
            buf.setString(x, top + row, NumberFormats.formatCompact(s.last()), t.text);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PIE

    static boolean pie(List<QueryRow> rows, Rect inner, Buffer buf, TuiTheme t) {
        double total = 0;
        for (QueryRow r : rows) total += Math.max(0, r.value());

        // Legend lines: "■ name  12.3%"
        List<String> shares = new ArrayList<>();
        int nameW = 0;
        for (QueryRow r : rows) {
            shares.add(total > 0 ? String.format(java.util.Locale.ROOT, "%.1f%%", Math.max(0, r.value()) / total * 100) : "–");
            nameW = Math.max(nameW, r.key() == null ? 0 : r.key().length());
        }
        int legendW = Math.min(nameW, 24) + 2 + 1 + 6;

        int rowsAvail = inner.height();
        int diameter = Math.min(rowsAvail * 2, inner.width() - 2 - legendW - 2);   // in pixels (= columns)
        if (diameter < 6 || rowsAvail < 3) return false;
        diameter -= diameter % 2;

        int cols = diameter, canvasRows = (diameter + 1) / 2;
        int left = inner.x() + 1, top = inner.y() + (rowsAvail - canvasRows) / 2;
        PixelCanvas canvas = new PixelCanvas(cols, canvasRows);
        double cx = (cols - 1) / 2.0, cy = (canvas.height - 1) / 2.0;
        double outer = diameter / 2.0, hole = outer * 0.58;

        // Slice boundaries as fractions of the circle, clockwise from the top
        double[] ends = new double[rows.size()];
        double acc = 0;
        for (int i = 0; i < rows.size(); i++) {
            acc += total > 0 ? Math.max(0, rows.get(i).value()) / total : 0;
            ends[i] = acc;
        }
        if (total > 0) {
            for (int y = 0; y < canvas.height; y++) {
                for (int x = 0; x < cols; x++) {
                    double dx = x - cx, dy = y - cy;
                    double d = Math.sqrt(dx * dx + dy * dy);
                    if (d > outer - 0.25 || d < hole) continue;
                    double turn = (Math.atan2(dx, -dy) / (2 * Math.PI) + 1) % 1;   // 0 at 12 o'clock, clockwise
                    int slice = 0;
                    while (slice < ends.length - 1 && turn >= ends[slice]) slice++;
                    canvas.set(x, y, t.series(rows.get(slice).color(), slice));
                }
            }
        }
        canvas.render(left, top, buf);

        // The total in the hole, when it fits
        String sum = NumberFormats.formatValue(total);
        if (hole * 2 >= sum.length() + 1 && canvasRows >= 3) {
            buf.setString(left + (cols - sum.length()) / 2, top + (canvasRows - 1) / 2, sum, t.title);
        }

        // Legend to the right, vertically centred
        int lx = left + cols + 2, lw = inner.right() - 1 - lx;
        int shown = Math.min(rows.size(), rowsAvail);
        boolean more = shown < rows.size();
        if (more) shown = rowsAvail - 1;
        int ly = inner.y() + (rowsAvail - shown - (more ? 1 : 0)) / 2;
        for (int i = 0; i < shown; i++, ly++) {
            QueryRow r = rows.get(i);
            String share = shares.get(i);
            buf.setString(lx, ly, "■ ", Style.EMPTY.fg(t.series(r.color(), i)));
            int nameRoom = Math.max(0, lw - 2 - share.length() - 1);
            buf.setString(lx + 2, ly, Panels.fit(r.key() == null ? "" : r.key(), nameRoom), t.text);
            buf.setString(lx + lw - share.length(), ly, share, t.muted);
        }
        if (more) buf.setString(lx, ly, "+" + (rows.size() - shown) + " more", t.muted);
        return true;
    }
}
