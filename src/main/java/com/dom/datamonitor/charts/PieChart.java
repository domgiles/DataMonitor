package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;

import java.awt.*;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Donut-style pie chart with a gradient per slice and an inline legend.
 *
 * <p>The centre of the donut is left empty (or may optionally show the total).
 * Slices are rendered with a radial gradient from bright (outer) to dim (inner)
 * to give a modern "backlit" appearance.
 */
public class PieChart extends BaseChart {

    private final List<String> keys   = new ArrayList<>();
    private final List<Double> values = new ArrayList<>();
    private final List<Color>  colors = new ArrayList<>();

    /** Fraction of the pie radius used for the inner hole (0 = solid pie, 1 = no pie). */
    private static final double HOLE_RATIO = 0.45;

    public PieChart(Theme theme) {
        super(theme);
    }

    @Override
    protected void onNewData(QueryResult result) {
        keys.clear();
        values.clear();
        colors.clear();
        for (int i = 0; i < result.size(); i++) {
            QueryRow row = result.rows().get(i);
            if (row.value() < 0) continue;
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

        double total = values.stream().mapToDouble(Double::doubleValue).sum();
        if (total <= 0) return;

        // Legend on the right
        int legendW = computeLegendWidth(g2) + 16;
        int pieW    = Math.min(w - legendW - 8, h - 16);
        int pieH    = pieW;
        int pieX    = (w - legendW - 8 - pieW) / 2;
        int pieY    = (h - pieH) / 2;

        // Draw slices
        double startAngle = 90.0; // start at 12 o'clock
        for (int i = 0; i < keys.size(); i++) {
            double sweep = values.get(i) / total * 360.0;
            Color  base  = colors.get(i);

            // Outer arc shape
            Arc2D arc = new Arc2D.Double(pieX, pieY, pieW, pieH,
                startAngle, sweep, Arc2D.PIE);

            // Radial gradient: bright at centre-ish → dim at edge
            int cx = pieX + pieW / 2;
            int cy = pieY + pieH / 2;
            float r = pieW / 2f;
            RadialGradientPaint rg = new RadialGradientPaint(
                new Point(cx, cy), r,
                new float[]{0.35f, 1f},
                new Color[]{base, base.darker()}
            );
            g2.setPaint(rg);
            g2.fill(arc);

            // Thin separating border
            g2.setColor(theme.chartBackground);
            g2.setStroke(new BasicStroke(1.5f));
            g2.draw(arc);

            startAngle += sweep;
        }

        // Hollow centre
        int holeSize = (int) (pieW * HOLE_RATIO);
        int holeX    = pieX + (pieW - holeSize) / 2;
        int holeY    = pieY + (pieH - holeSize) / 2;
        g2.setColor(theme.chartBackground);
        g2.fill(new Ellipse2D.Double(holeX, holeY, holeSize, holeSize));

        // Centre total text
        g2.setFont(theme.legendFont.deriveFont(Font.BOLD, 12f));
        g2.setColor(theme.axisForeground);
        String totalLabel = formatValue(total);
        FontMetrics fm = g2.getFontMetrics();
        int cx = pieX + pieW / 2, cy = pieY + pieH / 2;
        g2.drawString(totalLabel, cx - fm.stringWidth(totalLabel) / 2,
            cy + fm.getAscent() / 2 - 1);

        // Legend
        int lx = pieX + pieW + 16;
        int ly = h / 2 - (keys.size() * 18) / 2;
        g2.setFont(theme.legendFont);
        for (int i = 0; i < keys.size(); i++) {
            double pct = values.get(i) / total * 100;
            String entry = String.format("%s  %.1f%%", keys.get(i), pct);
            drawLegendEntry(g2, lx, ly + i * 18, colors.get(i), entry);
        }
    }

    private int computeLegendWidth(Graphics2D g2) {
        FontMetrics fm = g2.getFontMetrics(theme.legendFont);
        return keys.stream().mapToInt(k -> fm.stringWidth(k + "  100.0%")).max().orElse(80) + 20;
    }
}
