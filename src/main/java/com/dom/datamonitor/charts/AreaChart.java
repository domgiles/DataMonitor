package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;

import java.awt.*;
import java.awt.geom.Path2D;
import java.util.*;
import java.util.List;

/**
 * Stacked area chart for time-series data.
 *
 * <p>Series values are <em>stacked</em>: the area for series N sits on top of the
 * combined area for series 0…N-1.  This is ideal for showing composition of a total
 * (e.g. wait-class breakdown).  Each area is painted with a semi-transparent gradient
 * fill and a solid outline for the modern gradient look requested by the spec.
 */
public class AreaChart extends BaseChart {

    /** Left edge of the legend, in line with the y-axis labels. */
    private static final int LEGEND_X = 8;

    private final int maxPoints;
    private final int windowSeconds;

    private final List<String>               seriesKeys   = new ArrayList<>();
    private final List<Color>                seriesColors = new ArrayList<>();
    private final Map<String, ArrayDeque<Double>> data    = new LinkedHashMap<>();

    /** When each point was read (epoch ms), aligned with {@link #data}; 0 for the padding. */
    private final ArrayDeque<Long> times = new ArrayDeque<>();

    private double   maxStackedY = 1.0;
    private double[] ticks       = niceTicks(1.0);

    // ─────────────────────────────────────────────────────────────────────────

    public AreaChart(Theme theme, int windowSeconds, int refreshSeconds) {
        super(theme);
        this.windowSeconds = windowSeconds;
        this.maxPoints = Math.max(2, windowSeconds / Math.max(1, refreshSeconds));
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onNewData(QueryResult result) {
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
            for (int j = 0; j < maxPoints; j++) times.add(0L);
        }
        if (times.size() >= maxPoints) times.pollFirst();
        times.addLast(System.currentTimeMillis());

        // Push values; compute max stacked
        maxStackedY = 1.0;
        for (int t = 0; t < maxPoints; t++) {
            double stackedTotal = 0;
            for (String key : seriesKeys) {
                ArrayDeque<Double> d = data.get(key);
                if (d != null && !d.isEmpty()) {
                    stackedTotal += d.toArray(new Double[0])[Math.min(t, d.size() - 1)];
                }
            }
            maxStackedY = Math.max(maxStackedY, stackedTotal);
        }

        for (int i = 0; i < Math.min(result.size(), seriesKeys.size()); i++) {
            String key   = seriesKeys.get(i);
            double value = result.rows().get(i).value();
            ArrayDeque<Double> deque = data.get(key);
            if (deque != null) {
                if (deque.size() >= maxPoints) deque.pollFirst();
                deque.addLast(value);
            }
        }

        // Recompute maxStackedY after adding new data
        maxStackedY = 1.0;
        int n = data.isEmpty() ? 0 : data.values().iterator().next().size();
        Double[][] matrix = buildMatrix(n);
        for (int t = 0; t < n; t++) {
            double sum = 0;
            for (int s = 0; s < seriesKeys.size(); s++) sum += matrix[s][t];
            maxStackedY = Math.max(maxStackedY, sum);
        }
        ticks       = niceTicks(maxStackedY);
        maxStackedY = ticks[ticks.length - 1];
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void paintChart(Graphics2D g2, int w, int h) {
        int pl = padLeft(), pr = padRight(), pt = padTop(), pb = padBottom();
        // The legend gets its own band above the plot, so data never draws over it
        int legendH = layoutLegend(g2, seriesKeys, seriesColors, LEGEND_X, 0, w - pr, false);
        int plotX = pl, plotY = pt + (legendH > 0 ? legendH + 6 : 0);
        int plotW = w - pl - pr;
        int plotH = h - plotY - pb;
        if (plotW < 4 || plotH < 4) return;

        int n = data.isEmpty() ? 0 : data.values().iterator().next().size();
        if (n < 2) return;

        Double[][] matrix = buildMatrix(n);
        float stepX = (float) plotW / (n - 1);

        // Paint series from back to front (series 0 at bottom of stack)
        // Build cumulative stacks
        double[] cumulative = new double[n];

        // Draw grid first
        drawGrid(g2, plotX, plotY, plotW, plotH);

        for (int s = 0; s < seriesKeys.size(); s++) {
            double[] seriesVals = new double[n];
            for (int t = 0; t < n; t++) seriesVals[t] = matrix[s][t];

            // Build filled area path: top of this series → bottom of this series (= top of prev)
            Path2D fillPath = new Path2D.Float();
            // Top edge (cumulative + this series)
            for (int t = 0; t < n; t++) {
                float x = plotX + t * stepX;
                float y = toY(cumulative[t] + seriesVals[t], plotY, plotH);
                if (t == 0) fillPath.moveTo(x, y);
                else        fillPath.lineTo(x, y);
            }
            // Bottom edge (cumulative baseline) – traverse right to left
            for (int t = n - 1; t >= 0; t--) {
                float x = plotX + t * stepX;
                float y = toY(cumulative[t], plotY, plotH);
                fillPath.lineTo(x, y);
            }
            fillPath.closePath();

            // Compute the actual vertical span of this series band so the gradient
            // is scoped to the band, not the full plot height. Without this, a series
            // stacked near the bottom gets only the dim tail of the gradient and its
            // colour blends into the dark background (e.g. yellow looks brown).
            float bandTop = plotY + plotH;   // smallest screen-Y in this band
            float bandBot = (float) plotY;   // largest screen-Y in this band
            for (int t = 0; t < n; t++) {
                bandTop = Math.min(bandTop, toY(cumulative[t] + seriesVals[t], plotY, plotH));
                bandBot = Math.max(bandBot, toY(cumulative[t],                 plotY, plotH));
            }
            if (bandBot <= bandTop + 1f) { bandTop = plotY; bandBot = plotY + plotH; }

            // Use high alpha so colours read correctly against the dark background.
            Color base = seriesColors.get(s);
            Color top  = new Color(base.getRed(), base.getGreen(), base.getBlue(), 220);
            Color bot  = new Color(base.getRed(), base.getGreen(), base.getBlue(), 150);
            LinearGradientPaint grad = new LinearGradientPaint(
                0, bandTop, 0, bandBot,
                new float[]{0f, 1f}, new Color[]{top, bot}
            );
            g2.setPaint(grad);
            g2.fill(fillPath);

            // Top-edge line
            Path2D linePath = new Path2D.Float();
            for (int t = 0; t < n; t++) {
                float x = plotX + t * stepX;
                float y = toY(cumulative[t] + seriesVals[t], plotY, plotH);
                if (t == 0) linePath.moveTo(x, y);
                else        linePath.lineTo(x, y);
            }
            g2.setColor(base);
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(linePath);

            // Advance cumulative baseline
            for (int t = 0; t < n; t++) cumulative[t] += seriesVals[t];
        }

        drawYAxis(g2, plotX, plotY, plotH);
        drawXAxisLine(g2, plotX, plotY, plotW, plotH, h);
        layoutLegend(g2, seriesKeys, seriesColors, LEGEND_X, pt + 10, w - pr, true);
        drawHover(g2, w, matrix, n, plotX, plotY, plotW, plotH);
    }

    @Override protected boolean showsHover() { return true; }

    /**
     * Guide line and readout at the reading nearest the pointer (none over the empty padding).
     * Dots sit on the top edge of each band; the readout lists each series' own value and the total.
     */
    private void drawHover(Graphics2D g2, int w, Double[][] matrix, int n,
                           int px, int py, int pw, int ph) {
        int hx = hoverX();
        if (hx < px || hx > px + pw || n < 2 || times.size() != n) return;
        float stepX = (float) pw / (n - 1);
        int i = Math.round((hx - px) / stepX);
        long time = times.stream().skip(i).findFirst().orElse(0L);
        if (time == 0) return;

        List<Double> values = new ArrayList<>();
        List<Float>  dots   = new ArrayList<>();
        double stack = 0;
        for (int s = 0; s < seriesKeys.size(); s++) {
            values.add(matrix[s][i]);
            stack += matrix[s][i];
            dots.add(toY(stack, py, ph));
        }
        drawHoverReadout(g2, w, py, ph, px + i * stepX, time, seriesKeys, seriesColors, values, dots, true);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private Double[][] buildMatrix(int n) {
        Double[][] m = new Double[seriesKeys.size()][n];
        for (int s = 0; s < seriesKeys.size(); s++) {
            Double[] arr = data.get(seriesKeys.get(s)).toArray(new Double[0]);
            for (int t = 0; t < n; t++) m[s][t] = arr[Math.min(t, arr.length - 1)];
        }
        return m;
    }

    private float toY(double value, int plotY, int plotH) {
        float y = (float) (plotY + plotH - (value / maxStackedY) * plotH);
        return Math.max(plotY, Math.min(plotY + plotH, y));
    }

    private void drawGrid(Graphics2D g2, int px, int py, int pw, int ph) {
        Stroke dashed = new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
            1f, new float[]{4f, 4f}, 0f);
        g2.setColor(theme.gridLine);
        g2.setStroke(dashed);
        for (double tick : ticks) {
            int y = py + ph - (int) (tick / maxStackedY * ph);
            g2.drawLine(px, y, px + pw, y);
        }
        g2.setStroke(new BasicStroke(1f));
    }

    private void drawYAxis(Graphics2D g2, int px, int py, int ph) {
        g2.setColor(theme.axisForeground);
        g2.setFont(theme.axisFont);
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(px, py, px, py + ph);
        FontMetrics fm = g2.getFontMetrics();
        for (double tick : ticks) {
            int y = py + ph - (int) (tick / maxStackedY * ph);
            String label = formatCompact(tick);
            g2.drawString(label, px - fm.stringWidth(label) - 4, y + fm.getAscent() / 2);
        }
    }

    private void drawXAxisLine(Graphics2D g2, int px, int py, int pw, int ph, int h) {
        g2.setColor(theme.gridLine);
        g2.setStroke(new BasicStroke(1f));
        g2.drawLine(px, py + ph, px + pw, py + ph);
        g2.setFont(theme.axisFont);
        g2.setColor(theme.axisForeground);
        String left  = "← " + (windowSeconds >= 60 ? (windowSeconds / 60) + "m" : windowSeconds + "s");
        String right = "now →";
        g2.drawString(left, px, h - 4);
        g2.drawString(right, px + pw - g2.getFontMetrics().stringWidth(right), h - 4);
    }
}
