package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal bar chart, refreshed on each query execution.
 *
 * <p>Bars are drawn horizontally (left-to-right) with gradient fills and rounded
 * caps.  The longest bar fills the available plot width; all others are scaled
 * proportionally.  Series labels appear to the left; values to the right of each bar.
 */
public class BarChart extends BaseChart {

    private final List<String> keys   = new ArrayList<>();
    private final List<Double> values = new ArrayList<>();
    private final List<Color>  colors = new ArrayList<>();

    public BarChart(Theme theme) {
        super(theme);
    }

    @Override
    protected void onNewData(QueryResult result) {
        keys.clear();
        values.clear();
        colors.clear();
        for (int i = 0; i < result.size(); i++) {
            QueryRow row = result.rows().get(i);
            keys.add(row.key());
            values.add(row.value());
            Color c = row.hasColor()
                ? ColorPalette.resolve(row.color(), ColorPalette.defaultColor(i, theme))
                : ColorPalette.defaultColor(i, theme);
            colors.add(c);
        }
    }

    @Override
    protected void paintChart(Graphics2D g2, int w, int h) {
        if (keys.isEmpty()) return;

        int n = keys.size();
        double maxVal = values.stream().mapToDouble(Double::doubleValue).max().orElse(1.0);
        if (maxVal <= 0) maxVal = 1.0;

        // Calculate layout
        FontMetrics fm = g2.getFontMetrics(theme.axisFont);
        int labelWidth = keys.stream().mapToInt(fm::stringWidth).max().orElse(60) + 10;
        int valWidth   = 60;
        int plotX      = labelWidth;
        int plotW      = w - labelWidth - valWidth - 8;
        int totalH     = h - 16;
        int barH       = Math.max(6, Math.min(28, (totalH / n) - 8));
        int rowH       = totalH / n;
        int startY     = 8 + (rowH - barH) / 2;

        g2.setFont(theme.axisFont);

        for (int i = 0; i < n; i++) {
            int y   = startY + i * rowH;
            int barW = (int) (values.get(i) / maxVal * plotW);
            Color c  = colors.get(i);

            // Gradient fill
            LinearGradientPaint grad = new LinearGradientPaint(
                plotX, y, plotX + Math.max(1, barW), y + barH,
                new float[]{0f, 1f},
                new Color[]{c, new Color(c.getRed(), c.getGreen(), c.getBlue(), 120)}
            );
            g2.setPaint(grad);
            g2.fill(new RoundRectangle2D.Float(plotX, y, Math.max(1, barW), barH, 6, 6));

            // Bar border
            g2.setColor(c);
            g2.setStroke(new BasicStroke(1f));
            g2.draw(new RoundRectangle2D.Float(plotX, y, Math.max(1, barW), barH, 6, 6));

            // Key label (left)
            g2.setColor(theme.foreground);
            String label = keys.get(i);
            g2.drawString(label, plotX - fm.stringWidth(label) - 6,
                y + barH / 2 + fm.getAscent() / 2 - 1);

            // Value label (right of bar)
            String valLabel = formatValue(values.get(i));
            g2.setColor(theme.axisForeground);
            g2.drawString(valLabel, plotX + Math.max(1, barW) + 6,
                y + barH / 2 + fm.getAscent() / 2 - 1);
        }
    }

    @Override protected int padLeft()   { return 4; }
    @Override protected int padRight()  { return 4; }
    @Override protected int padTop()    { return 4; }
    @Override protected int padBottom() { return 4; }
}
