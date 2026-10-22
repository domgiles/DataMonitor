package com.dom.datamonitor.tui;

import com.dom.datamonitor.engine.MonitorEngine.ModuleView;
import com.dom.datamonitor.engine.MonitorEngine.Sample;
import com.dom.datamonitor.model.ChartType;
import com.dom.datamonitor.model.ProcessingMode;
import com.dom.datamonitor.model.QueryRow;
import com.dom.datamonitor.util.NumberFormats;
import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Borders;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.sparkline.Sparkline;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Draws one module in the terminal: a bordered panel titled like the web tiles, with the
 * processing mode and refresh rate on the right of the border, and the module's content
 * inside.
 *
 * <ul>
 *   <li>TEXT: the value in big digits, the label above it when it isn't the title, and a
 *       sparkline with the change since the last reading underneath.</li>
 *   <li>BAR: one horizontal bar per row, scaled to a round axis maximum like the web bars.</li>
 *   <li>TABLE: label / value rows with the series colour as a marker.</li>
 *   <li>LINE, AREA and PIE: see {@link Charts}. A panel too small for its chart lists the
 *       latest values instead (PIE with each row's share of the total).</li>
 * </ul>
 */
final class Panels {

    /** How long a TEXT value is shown in the up / down colour after it changes. */
    static final long FLASH_MILLIS = 1200;

    /** Narrowest bar area worth drawing; labels are shortened before bars get narrower. */
    private static final int MIN_BAR = 8;

    private static final String[] EIGHTHS = { "", "▏", "▎", "▍", "▌", "▋", "▊", "▉" };

    private Panels() {}

    /** What the panels need besides the module: the colours and whether TEXT values flash. */
    record Context(TuiTheme theme, boolean flashOnChange, long now) {}

    static void render(ModuleView m, Rect area, Buffer buf, Context ctx) {
        if (area.width() < 4 || area.height() < 2) return;
        TuiTheme t = ctx.theme();

        String title = " " + m.config().getTitle().toUpperCase(Locale.ROOT) + " ";
        Block block = Block.builder()
            .borders(Borders.ALL)
            .borderType(BorderType.ROUNDED)
            .borderStyle(t.border)
            .title(Title.from(Line.from(Span.styled(fit(title, area.width() - 4), t.title))))
            .build();
        block.render(area, buf);
        drawBadges(m, area, buf, t, title.length());

        Rect inner = block.inner(area);
        if (inner.width() < 1 || inner.height() < 1) return;

        List<Sample> history = m.history();
        Sample last = history.isEmpty() ? null : history.get(history.size() - 1);
        if (last == null) {
            centred(inner, buf, "Connecting…", t.muted);
        } else if (last.error() != null) {
            wrapped(inner, buf, last.error(), t.error);
        } else if (last.rows().isEmpty()) {
            centred(inner, buf, "No rows", t.muted);
        } else {
            ChartType type = ChartType.valueOf(m.config().getChartType().trim().toUpperCase(Locale.ROOT));
            switch (type) {
                case TEXT  -> text(m, history, inner, buf, ctx);
                case BAR   -> bars(last.rows(), inner, buf, t);
                case TABLE -> table(last.rows(), inner, buf, t, false);
                // Too small for a chart: list the values instead
                case LINE  -> { if (!Charts.timeSeries(history, m.windowSize(), false, inner, buf, t)) table(last.rows(), inner, buf, t, false); }
                case AREA  -> { if (!Charts.timeSeries(history, m.windowSize(), true,  inner, buf, t)) table(last.rows(), inner, buf, t, false); }
                case PIE   -> { if (!Charts.pie(last.rows(), inner, buf, t)) table(last.rows(), inner, buf, t, true); }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Border badges: " Δ/s  2s " on the top border, right-aligned, as on the web tiles

    private static void drawBadges(ModuleView m, Rect area, Buffer buf, TuiTheme t, int titleWidth) {
        String mode  = modeBadge(m.config().getProcessingMode());
        String badge = (mode.isEmpty() ? "" : " " + mode + " ") + " " + m.refreshRate() + "s ";
        int x = area.right() - 1 - badge.length();
        if (x > area.x() + 1 + titleWidth) buf.setString(x, area.y(), badge, t.muted);
    }

    static String modeBadge(String processingMode) {
        if (processingMode == null || processingMode.isBlank()) return "";
        try {
            return switch (ProcessingMode.valueOf(processingMode.trim().toUpperCase(Locale.ROOT))) {
                case DIFFERENCE             -> "Δ/s";
                case CHANGE_PER_MIN         -> "Δ/min";
                case CHANGE_PER_HOUR        -> "Δ/h";
                case CUMULATIVE_SINCE_START -> "Σ";
                case NONE                   -> "";
            };
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TEXT

    private static void text(ModuleView m, List<Sample> history, Rect inner, Buffer buf, Context ctx) {
        TuiTheme t = ctx.theme();
        QueryRow row = history.get(history.size() - 1).rows().get(0);

        // Readings for the trend and the change, oldest first
        List<Double> values = new ArrayList<>();
        for (Sample s : history) if (s.error() == null && !s.rows().isEmpty()) values.add(s.rows().get(0).value());
        double value = row.value();
        Double previous = values.size() >= 2 ? values.get(values.size() - 2) : null;

        int top = inner.y(), bottom = inner.bottom();      // bottom is exclusive
        String label = row.key() == null ? "" : row.key().trim();
        if (!label.isEmpty() && !label.equalsIgnoreCase(m.config().getTitle().trim()) && inner.height() >= 4) {
            buf.setString(inner.x() + 1, top, fit(label, inner.width() - 2), t.muted);
            top++;
        }

        // Footer: sparkline on the left, the change on the right
        if (inner.height() >= 3) {
            bottom--;
            String delta = previous == null ? "" : deltaText(value - previous);
            Style deltaStyle = previous == null || value == previous ? t.muted : value > previous ? t.up : t.down;
            int deltaX = inner.right() - 1 - delta.length();
            if (!delta.isEmpty()) buf.setString(deltaX, bottom, delta, deltaStyle);
            int sparkW = (delta.isEmpty() ? inner.right() - 1 : deltaX - 2) - (inner.x() + 1);
            if (sparkW >= 4 && values.size() >= 2) {
                Sparkline.builder().data(sparkData(values, sparkW)).max(101).style(t.muted).build()
                    .render(new Rect(inner.x() + 1, bottom, sparkW, 1), buf);
            }
        }

        Style style = t.accent;
        if (ctx.flashOnChange() && previous != null && value != previous
                && ctx.now() - history.get(history.size() - 1).time() < FLASH_MILLIS) {
            style = value > previous ? t.up : t.down;
        }
        Rect valueArea = new Rect(inner.x() + 1, top, Math.max(0, inner.width() - 2), Math.max(0, bottom - top));
        if (valueArea.height() > 0 && valueArea.width() > 0) {
            BigText.render(NumberFormats.formatValue(value), valueArea, buf, style.bold());
        }
    }

    static String deltaText(double d) {
        if (d == 0) return "± 0";
        return (d > 0 ? "▲ +" : "▼ ") + NumberFormats.formatValue(d);
    }

    /** The last {@code width} readings scaled to 1..101 between their minimum and maximum, as the web sparkline is. */
    static long[] sparkData(List<Double> values, int width) {
        List<Double> tail = values.subList(Math.max(0, values.size() - width), values.size());
        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        for (double v : tail) { min = Math.min(min, v); max = Math.max(max, v); }
        long[] data = new long[tail.size()];
        for (int i = 0; i < data.length; i++) {
            data[i] = max == min ? 50 : 1 + Math.round((tail.get(i) - min) / (max - min) * 100);
        }
        return data;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // BAR

    private static void bars(List<QueryRow> rows, Rect inner, Buffer buf, TuiTheme t) {
        int x = inner.x() + 1, width = inner.width() - 2;
        if (width < 6) return;

        double max = 0;
        int labelW = 0, valueW = 0;
        List<String> valueTexts = new ArrayList<>();
        for (QueryRow r : rows) {
            max = Math.max(max, r.value());
            labelW = Math.max(labelW, label(r).length());
            String v = NumberFormats.formatValue(r.value());
            valueTexts.add(v);
            valueW = Math.max(valueW, v.length());
        }
        double[] ticks = NumberFormats.niceTicks(max);
        double axisMax = ticks[ticks.length - 1];

        // Labels get what they need, as long as bars keep at least MIN_BAR cells
        labelW = Math.max(0, Math.min(labelW, width - 1 - (valueW + 1) - MIN_BAR));
        int barW = Math.max(1, width - labelW - 1 - (valueW + 1));

        // One line per bar, with a blank line between bars when there is room
        int shown = Math.min(rows.size(), inner.height());
        boolean more = shown < rows.size();
        if (more) shown = Math.max(0, inner.height() - 1);
        int step  = !more && 2 * shown - 1 <= inner.height() ? 2 : 1;
        int used  = step == 2 ? 2 * shown - 1 : shown + (more ? 1 : 0);
        int y     = inner.y() + (inner.height() - used) / 2;

        for (int i = 0; i < shown; i++, y += step) {
            QueryRow r = rows.get(i);
            buf.setString(x, y, pad(fit(label(r), labelW), labelW), t.muted);
            int bx = x + labelW + 1;
            String bar = bar(Math.max(0, r.value()) / axisMax, barW);
            buf.setString(bx, y, bar, Style.EMPTY.fg(t.series(r.color(), i)));
            buf.setString(bx + bar.length() + (bar.isEmpty() ? 0 : 1), y, valueTexts.get(i), t.text);
        }
        if (more) buf.setString(x, y, "+" + (rows.size() - shown) + " more", t.muted);
    }

    /** A bar {@code fraction} of {@code width} cells long, to the nearest eighth of a cell. */
    static String bar(double fraction, int width) {
        int eighths = (int) Math.round(Math.max(0, Math.min(1, fraction)) * width * 8);
        return "█".repeat(eighths / 8) + EIGHTHS[eighths % 8];
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TABLE (and the values list for charts too small to draw)

    private static void table(List<QueryRow> rows, Rect inner, Buffer buf, TuiTheme t, boolean percentages) {
        int x = inner.x() + 1, width = inner.width() - 2;
        if (width < 4) return;
        int y = inner.y(), end = inner.bottom();

        buf.setString(x, y, "METRIC", t.muted.bold());
        String head = percentages ? "VALUE   SHARE" : "VALUE";
        if (width > head.length() + 8) buf.setString(x + width - head.length(), y, head, t.muted.bold());
        y++;

        double total = 0;
        for (QueryRow r : rows) total += Math.max(0, r.value());

        for (int i = 0; i < rows.size() && y < end; i++, y++) {
            QueryRow r = rows.get(i);
            if (y == end - 1 && i < rows.size() - 1) {
                buf.setString(x, y, "+" + (rows.size() - i) + " more", t.muted);
                break;
            }
            String value = NumberFormats.formatValue(r.value());
            if (percentages) {
                String share = total > 0 ? String.format(Locale.ROOT, "%.1f%%", Math.max(0, r.value()) / total * 100) : "–";
                value = value + " " + pad(share, 6, true);
            }
            buf.setString(x, y, "▬ ", Style.EMPTY.fg(t.series(r.color(), i)));
            int labelW = Math.max(0, width - 2 - value.length() - 1);
            buf.setString(x + 2, y, fit(label(r), labelW), t.text);
            buf.setString(x + width - value.length(), y, value, t.text);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Text helpers

    private static String label(QueryRow r) {
        return r.key() == null ? "" : r.key();
    }

    /** {@code s} shortened with an ellipsis to at most {@code width} characters. */
    static String fit(String s, int width) {
        if (width <= 0) return "";
        if (s.length() <= width) return s;
        return width == 1 ? "…" : s.substring(0, width - 1) + "…";
    }

    private static String pad(String s, int width) {
        return pad(s, width, false);
    }

    private static String pad(String s, int width, boolean left) {
        if (s.length() >= width) return s;
        String spaces = " ".repeat(width - s.length());
        return left ? spaces + s : s + spaces;
    }

    private static void centred(Rect area, Buffer buf, String text, Style style) {
        String s = fit(text, area.width());
        buf.setString(area.x() + (area.width() - s.length()) / 2, area.y() + (area.height() - 1) / 2, s, style);
    }

    /** {@code text} word-wrapped into {@code area}, for error messages. */
    private static void wrapped(Rect area, Buffer buf, String text, Style style) {
        int width = Math.max(1, area.width() - 2);
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.replaceAll("\\s+", " ").trim().split(" ")) {
            while (word.length() > width) {                         // a very long word: break it
                if (line.length() > 0) { lines.add(line.toString()); line.setLength(0); }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (line.length() > 0 && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add(line.toString());

        int n = Math.min(lines.size(), area.height());
        for (int i = 0; i < n; i++) {
            String s = (i == n - 1 && n < lines.size()) ? fit(lines.get(i) + " …", width) : lines.get(i);
            buf.setString(area.x() + 1, area.y() + i, s, style);
        }
    }
}
