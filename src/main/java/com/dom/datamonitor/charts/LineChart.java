package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;

import java.awt.*;
import java.awt.geom.Path2D;
import java.util.*;
import java.util.List;

/**
 * Time-series line chart that displays multiple series over a rolling time window.
 *
 * <p>Each series is rendered as a smooth anti-aliased line with a gradient glow.
 * The Y-axis auto-scales to the maximum value in the current window.
 * The X-axis shows elapsed time from (now − windowSize) to now.
 *
 * <p>Up to {@code maxPoints} data points are kept per series (windowSize / refreshRate).
 */
public class LineChart extends BaseChart {

    private final int maxPoints;
    private final int windowSeconds;

    /** Ordered series names (established on first result). */
    private final List<String> seriesKeys   = new ArrayList<>();
    private final List<Color>  seriesColors = new ArrayList<>();

    /** Circular data store: seriesKey → deque of values (oldest first). */
    private final Map<String, ArrayDeque<Double>> data = new LinkedHashMap<>();

    private double maxY = 1.0;

    // ─────────────────────────────────────────────────────────────────────────

    public LineChart(Theme theme, int windowSeconds, int refreshSeconds) {
        super(theme);
        this.windowSeconds = windowSeconds;
        this.maxPoints = Math.max(2, windowSeconds / Math.max(1, refreshSeconds));
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onNewData(QueryResult result) {
        // Initialise series on first result
        if (seriesKeys.isEmpty()) {
            for (int i = 0; i < result.size(); i++) {
                QueryRow row = result.rows().get(i);
                seriesKeys.add(row.key());
                Color c = row.hasColor()
                    ? ColorPalette.resolve(row.color(), ColorPalette.defaultColor(i, theme))
                    : ColorPalette.defaultColor(i, theme);
                seriesColors.add(c);
                ArrayDeque<Double> deque = new ArrayDeque<>(maxPoints);
                for (int j = 0; j < maxPoints; j++) deque.add(0.0);
                data.put(row.key(), deque);
            }
        }

        // Push new values
        maxY = 1.0;
        for (int i = 0; i < Math.min(result.size(), seriesKeys.size()); i++) {
            String key   = seriesKeys.get(i);
            double value = result.rows().get(i).value();
            ArrayDeque<Double> deque = data.get(key);
            if (deque != null) {
                if (deque.size() >= maxPoints) deque.pollFirst();
                deque.addLast(value);
                for (double v : deque) maxY = Math.max(maxY, v);
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void paintChart(Graphics2D g2, int w, int h) {
        int pl = padLeft(), pr = padRight(), pt = padTop(), pb = padBottom();
        int plotX = pl, plotY = pt;
        int plotW = w - pl - pr;
        int plotH = h - pt - pb;

        if (plotW < 4 || plotH < 4) return;

        // Background
        g2.setColor(theme.chartBackground);
        g2.fillRect(0, 0, w, h);

        // Grid lines
        drawGrid(g2, plotX, plotY, plotW, plotH);

        // Series lines
        for (int s = 0; s < seriesKeys.size(); s++) {
            String key = seriesKeys.get(s);
            ArrayDeque<Double> deque = data.get(key);
            if (deque == null || deque.isEmpty()) continue;

            Color seriesColor = seriesColors.get(s);
            drawSeriesLine(g2, deque, seriesColor, plotX, plotY, plotW, plotH);
        }

        // Axes & labels
        drawYAxis(g2, plotX, plotY, plotH);
        drawXAxisLabel(g2, plotX, plotY, plotW, plotH, h);
        drawLegend(g2, w, pt);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void drawGrid(Graphics2D g2, int px, int py, int pw, int ph) {
        g2.setColor(theme.gridLine);
        Stroke dashed = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
            1f, new float[]{4f, 4f}, 0f);
        g2.setStroke(dashed);
        int gridLines = 4;
        for (int i = 0; i <= gridLines; i++) {
            int y = py + ph - (int) ((double) i / gridLines * ph);
            g2.drawLine(px, y, px + pw, y);
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawSeriesLine(Graphics2D g2, ArrayDeque<Double> deque, Color color,
                                int px, int py, int pw, int ph) {
        Double[] values = deque.toArray(new Double[0]);
        int n = values.length;
        if (n < 2) return;

        float stepX = (float) pw / (n - 1);

        // Glow pass (wider, transparent)
        Color glow = new Color(color.getRed(), color.getGreen(), color.getBlue(), 50);
        g2.setColor(glow);
        g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(buildPath(values, n, stepX, px, py, ph));

        // Main line
        g2.setColor(color);
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(buildPath(values, n, stepX, px, py, ph));
    }

    private Path2D buildPath(Double[] values, int n, float stepX,
                             int px, int py, int ph) {
        Path2D path = new Path2D.Float();
        for (int i = 0; i < n; i++) {
            float x = px + i * stepX;
            float y = (float) (py + ph - (values[i] / maxY) * ph);
            y = Math.max(py, Math.min(py + ph, y));
            if (i == 0) path.moveTo(x, y);
            else        path.lineTo(x, y);
        }
        return path;
    }

    private void drawYAxis(Graphics2D g2, int px, int py, int ph) {
        g2.setFont(theme.axisFont);
        g2.setColor(theme.axisForeground);
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(px, py, px, py + ph);

        int ticks = 4;
        FontMetrics fm = g2.getFontMetrics();
        for (int i = 0; i <= ticks; i++) {
            double val = maxY * i / ticks;
            int y = py + ph - (int) ((double) i / ticks * ph);
            String label = formatValue(val);
            g2.drawString(label, px - fm.stringWidth(label) - 4, y + fm.getAscent() / 2);
        }
    }

    private void drawXAxisLabel(Graphics2D g2, int px, int py, int pw, int ph, int h) {
        g2.setColor(theme.gridLine);
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(px, py + ph, px + pw, py + ph);
        // "← window →" time label
        g2.setFont(theme.axisFont);
        g2.setColor(theme.axisForeground);
        String left  = "← " + formatWindowLabel();
        String right = "now →";
        g2.drawString(left, px, h - 4);
        FontMetrics fm = g2.getFontMetrics();
        g2.drawString(right, px + pw - fm.stringWidth(right), h - 4);
    }

    private String formatWindowLabel() {
        if (windowSeconds >= 60) return (windowSeconds / 60) + "m";
        return windowSeconds + "s";
    }

    private void drawLegend(Graphics2D g2, int w, int pt) {
        if (seriesKeys.isEmpty()) return;
        int x = padLeft() + 6;
        int y = pt + 14;
        for (int i = 0; i < seriesKeys.size(); i++) {
            drawLegendEntry(g2, x, y, seriesColors.get(i), seriesKeys.get(i));
            x += g2.getFontMetrics(theme.legendFont).stringWidth(seriesKeys.get(i)) + 28;
            if (x > w - 80) { x = padLeft() + 6; y += 16; }
        }
    }
}
