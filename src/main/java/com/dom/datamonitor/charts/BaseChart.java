package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * Abstract base for all chart/display components.
 *
 * <p>Implements double-buffered rendering via an off-screen {@link BufferedImage}
 * that is painted into a {@link BufferedImage} in {@link #paintChart(Graphics2D, int, int)}
 * and then blitted to the screen in {@link #paintComponent(Graphics)}.
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

    protected BaseChart(Theme theme) {
        this.theme = theme;
        setBackground(theme.chartBackground);
        setOpaque(true);
    }

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

        // Off-screen buffer for flicker-free rendering
        BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D    g2  = buf.createGraphics();

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
        g.drawImage(buf, 0, 0, null);
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

    /**
     * Formats a double value for display: uses integer notation when the value
     * has no meaningful decimal part, otherwise two decimal places.
     */
    protected static String formatValue(double value) {
        if (value == 0) return "0";
        if (value >= 1_000_000) return String.format("%.1fM", value / 1_000_000);
        if (value >= 1_000)     return String.format("%.1fK", value / 1_000);
        if (value == (long) value) return String.valueOf((long) value);
        return String.format("%.2f", value);
    }

    /** Inner padding for plot area (pixels). */
    protected int padLeft()   { return 52; }
    protected int padRight()  { return 12; }
    protected int padTop()    { return 10; }
    protected int padBottom() { return 28; }
}
