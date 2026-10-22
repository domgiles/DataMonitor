package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.util.NumberFormats;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Abstract base for all chart/display components.
 *
 * <p>Draws straight onto the component's {@link Graphics} in {@link #paintComponent(Graphics)}.
 * Swing's own back buffer is already flicker-free and matches the screen's pixel density, so
 * text and lines stay sharp on HiDPI (Retina) displays; an extra off-screen image at logical
 * size would be stretched and look blurred.
 *
 * <p>Sub-classes must implement:
 * <ul>
 *   <li>{@link #updateData(QueryResult)} – called on the EDT with new data</li>
 *   <li>{@link #paintChart(Graphics2D, int, int)} – draw onto the provided g2</li>
 * </ul>
 */
public abstract class BaseChart extends JPanel {

    protected Theme theme;

    /** Latest error message to display when data is unavailable. */
    private volatile String errorMessage = null;
    /** True while waiting for the first successful result. */
    private volatile boolean connecting = true;

    /** Pointer x while it is over the chart, or -1; drives the hover readout on time series. */
    private int hoverX = -1;

    private static final DateTimeFormatter HOVER_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    protected BaseChart(Theme theme) {
        this.theme = theme;
        setBackground(theme.chartBackground);
        setOpaque(true);

        MouseAdapter hover = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e)   { setHoverX(e.getX()); }
            @Override public void mouseDragged(MouseEvent e) { setHoverX(e.getX()); }
            @Override public void mouseExited(MouseEvent e)  { setHoverX(-1); }
        };
        addMouseListener(hover);
        addMouseMotionListener(hover);
    }

    private void setHoverX(int x) {
        if (x == hoverX) return;
        hoverX = x;
        if (showsHover()) repaint();
    }

    /** Whether this chart draws a hover readout; only repaints on pointer moves when it does. */
    protected boolean showsHover() { return false; }

    /** Pointer x while it is over the chart, or -1. */
    protected int hoverX() { return hoverX; }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Delivers a new query result to the chart.
     * Must be called on the Event Dispatch Thread.
     */
    public final void updateData(QueryResult result) {
        if (result.isError()) {
            this.errorMessage = result.errorMessage();
            repaint();
            return;
        }
        this.errorMessage = null;
        this.connecting   = false;
        onNewData(result);
        repaint();
    }

    /** Changes the active theme and triggers a repaint. */
    public void setTheme(Theme theme) {
        this.theme = theme;
        setBackground(theme.chartBackground);
        repaint();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Subclass contract
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Called on the EDT when new (non-error) data arrives.
     * Update internal data structures here; {@link #repaint()} is called automatically.
     */
    protected abstract void onNewData(QueryResult result);

    /**
     * Draws the chart content onto {@code g2}.
     * The clip is already set to the full component bounds.
     * Anti-aliasing and rendering hints are pre-configured.
     *
     * @param g2 Graphics2D context
     * @param w  component width
     * @param h  component height
     */
    protected abstract void paintChart(Graphics2D g2, int w, int h);

    // ─────────────────────────────────────────────────────────────────────────
    // Rendering
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected final void paintComponent(Graphics g) {
        super.paintComponent(g);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        Graphics2D g2 = (Graphics2D) g.create();

        applyRenderingHints(g2);
        g2.setColor(theme.chartBackground);
        g2.fillRect(0, 0, w, h);

        if (connecting) {
            drawStatusMessage(g2, w, h, "Connecting…", theme.axisForeground);
        } else if (errorMessage != null) {
            drawStatusMessage(g2, w, h, errorMessage, theme.flashDown);
        } else {
            paintChart(g2, w, h);
        }

        g2.dispose();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Shared drawing utilities
    // ─────────────────────────────────────────────────────────────────────────

    public static void applyRenderingHints(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,        RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,   RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,           RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,      RenderingHints.VALUE_STROKE_PURE);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,   RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    }

    protected void drawStatusMessage(Graphics2D g2, int w, int h, String msg, Color color) {
        g2.setFont(theme.axisFont);
        g2.setColor(color);
        FontMetrics fm = g2.getFontMetrics();
        int x = (w - fm.stringWidth(msg)) / 2;
        int y = h / 2 + fm.getAscent() / 2;
        g2.drawString(msg, x, y);
    }

    private static final int LEGEND_ROW_H = 16, LEGEND_GAP = 14, LEGEND_SWATCH = 14;

    /**
     * Lays out a legend that wraps onto as many rows as it needs between {@code x0} and
     * {@code maxX}, and draws it with its first row's baseline at {@code y} when {@code draw}
     * is true.
     *
     * <p>Like the web charts, a single series gets no legend: the module title names it.
     *
     * @return the height the legend takes (0 when there is none), so charts can place the
     *         plot below it instead of drawing data over the labels
     */
    protected int layoutLegend(Graphics2D g2, List<String> names, List<Color> colors,
                               int x0, int y, int maxX, boolean draw) {
        if (names.size() < 2) return 0;
        FontMetrics fm = g2.getFontMetrics(theme.legendFont);
        int x = x0, rows = 1;
        for (int i = 0; i < names.size(); i++) {
            int entryW = LEGEND_SWATCH + fm.stringWidth(names.get(i));
            if (x > x0 && x + entryW > maxX) {
                x = x0;
                rows++;
            }
            if (draw) drawLegendEntry(g2, x, y + (rows - 1) * LEGEND_ROW_H, colors.get(i), names.get(i));
            x += entryW + LEGEND_GAP;
        }
        return rows * LEGEND_ROW_H;
    }

    /**
     * Draws a rounded-rectangle legend entry: coloured swatch + label.
     */
    protected void drawLegendEntry(Graphics2D g2, int x, int y, Color color, String label) {
        int swatchSize = 10;
        g2.setColor(color);
        g2.fillRoundRect(x, y - swatchSize + 2, swatchSize, swatchSize, 3, 3);
        g2.setFont(theme.legendFont);
        g2.setColor(theme.axisForeground);
        g2.drawString(label, x + swatchSize + 4, y);
    }

    /** See {@link NumberFormats#formatValue(double)}. */
    protected static String formatValue(double value) {
        return NumberFormats.formatValue(value);
    }

    static String formatValue(double value, Locale locale) {
        return NumberFormats.formatValue(value, locale);
    }

    /** See {@link NumberFormats#formatCompact(double)}. */
    protected static String formatCompact(double value) {
        return NumberFormats.formatCompact(value);
    }

    static String formatCompact(double value, Locale locale) {
        return NumberFormats.formatCompact(value, locale);
    }

    /** See {@link NumberFormats#niceTicks(double)}. */
    static double[] niceTicks(double max) {
        return NumberFormats.niceTicks(max);
    }

    /**
     * Draws the hover readout for a time series: a vertical guide at {@code x}, a dot on each
     * series and a box with the time and each series' value (plus a total when stacked), laid
     * out like the web tooltip. The box sits beside the guide, flipping sides near the edge.
     */
    protected void drawHoverReadout(Graphics2D g2, int w, int plotY, int plotH, float x, long time,
                                    List<String> names, List<Color> colors, List<Double> values,
                                    List<Float> dotYs, boolean stacked) {
        Color guide = theme.axisForeground;
        g2.setColor(new Color(guide.getRed(), guide.getGreen(), guide.getBlue(), 160));
        g2.setStroke(new BasicStroke(1f));
        g2.draw(new Line2D.Float(x, plotY, x, plotY + plotH));
        for (int i = 0; i < dotYs.size(); i++) {
            g2.setColor(theme.chartBackground);
            g2.fill(new Ellipse2D.Float(x - 5, dotYs.get(i) - 5, 10, 10));
            g2.setColor(colors.get(i));
            g2.fill(new Ellipse2D.Float(x - 3.5f, dotYs.get(i) - 3.5f, 7, 7));
        }

        // Box contents
        Font  labelFont = theme.legendFont;
        Font  valueFont = new Font(Font.MONOSPACED, Font.BOLD, labelFont.getSize());
        FontMetrics lfm = g2.getFontMetrics(labelFont), vfm = g2.getFontMetrics(valueFont);
        String header = HOVER_TIME.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()));
        List<String> shown = new ArrayList<>();
        double total = 0;
        int valueW = 0, nameW = lfm.stringWidth(header) - 20;
        for (int i = 0; i < names.size(); i++) {
            String v = formatValue(values.get(i));
            shown.add(v);
            total += values.get(i);
            valueW = Math.max(valueW, vfm.stringWidth(v));
            nameW  = Math.max(nameW, lfm.stringWidth(names.get(i)));
        }
        boolean showTotal = stacked && names.size() > 1;
        String totalText = formatValue(total);
        if (showTotal) valueW = Math.max(valueW, vfm.stringWidth(totalText));

        int line = Math.max(lfm.getHeight(), vfm.getHeight()) + 2;
        int pad = 8, swatch = 12;
        int boxW = pad + swatch + 8 + valueW + 10 + nameW + pad;
        int boxH = pad + line * (1 + names.size() + (showTotal ? 1 : 0)) + (showTotal ? 4 : 0) + pad - 2;
        float bx = (x + 14 + boxW <= w - 4) ? x + 14 : x - 14 - boxW;
        bx = Math.max(4, bx);
        float by = Math.max(4, Math.min(plotY + 4, getHeight() - boxH - 4));

        Color bg = theme.background, edge = theme.gridLine;
        g2.setColor(new Color(bg.getRed(), bg.getGreen(), bg.getBlue(), 235));
        g2.fill(new RoundRectangle2D.Float(bx, by, boxW, boxH, 10, 10));
        g2.setColor(edge);
        g2.draw(new RoundRectangle2D.Float(bx, by, boxW, boxH, 10, 10));

        float ty = by + pad + lfm.getAscent();
        g2.setFont(labelFont);
        g2.setColor(theme.axisForeground);
        g2.drawString(header, bx + pad, ty);
        for (int i = 0; i < names.size(); i++) {
            ty += line;
            g2.setColor(colors.get(i));
            g2.fill(new RoundRectangle2D.Float(bx + pad, ty - lfm.getAscent() / 2f - 1, swatch, 3, 2, 2));
            g2.setFont(valueFont);
            g2.setColor(theme.foreground);
            g2.drawString(shown.get(i), bx + pad + swatch + 8, ty);
            g2.setFont(labelFont);
            g2.setColor(theme.axisForeground);
            g2.drawString(names.get(i), bx + pad + swatch + 8 + valueW + 10, ty);
        }
        if (showTotal) {
            ty += 4;
            g2.setColor(edge);
            g2.draw(new Line2D.Float(bx + pad, ty + lfm.getDescent(), bx + boxW - pad, ty + lfm.getDescent()));
            ty += line;
            g2.setFont(valueFont);
            g2.setColor(theme.foreground);
            g2.drawString(totalText, bx + pad + swatch + 8, ty);
            g2.setFont(labelFont);
            g2.setColor(theme.axisForeground);
            g2.drawString("Total", bx + pad + swatch + 8 + valueW + 10, ty);
        }
    }

    /** Inner padding for plot area (pixels). */
    protected int padLeft()   { return 52; }
    protected int padRight()  { return 12; }
    protected int padTop()    { return 10; }
    protected int padBottom() { return 28; }
}
