package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;

import javax.swing.Timer;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Big-number KPI tile display, laid out like the web front end's TEXT tile.
 *
 * <ul>
 *   <li>The <em>label</em> (SQL key) is shown small at the top, unless it repeats the
 *       module title already shown in the title strip.</li>
 *   <li>The <em>value</em> fills the space in the middle: it is sized from the height
 *       available, so it grows with the module (e.g. one spanning several rows), and
 *       shrinks so it never overflows a small module or a long number.</li>
 *   <li>A footer with a sparkline of the value over the chart window and the change since
 *       the previous reading (▲ / ▼ with colour, so the direction never relies on colour).</li>
 * </ul>
 *
 * <p>When the value changes and {@code flashOnChange} is on, a brief colour flash is painted
 * around the value: green for an increase, red for a decrease.
 *
 * <p>Expects a single-row duplet or triplet from the SQL.  If multiple rows are
 * returned only the first is used.
 */
public class TextDisplay extends BaseChart {

    /** Value font size as a fraction of the height left for it. */
    private static final float VALUE_HEIGHT_RATIO = 0.62f;
    private static final float MAX_VALUE_SIZE     = 260f;
    private static final float MIN_VALUE_SIZE     = 10f;

    // Tile geometry (pixels), matching the web tile
    private static final int PAD_X = 16, PAD_TOP = 14, PAD_BOTTOM = 10;
    private static final int FOOT_H = 34, ROW_GAP = 6;

    private static final Font DELTA_FONT = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private String label      = "";
    private String valueText  = "–";
    private double currentVal = Double.NaN;
    private String deltaText  = "";
    private int    deltaSign;

    /** Recent readings as {epoch ms, value}, oldest first, trimmed to the chart window. */
    private final Deque<double[]> history = new ArrayDeque<>();
    private final long            windowMillis;

    /**
     * Flash opacity: starts at 1.0 after a value change and decays to 0.
     * Drives the glow ring around the value.
     */
    private final boolean flashEnabled;

    /** The module title, already shown in the title strip; a label repeating it is not drawn. */
    private final String title;

    private float flashAlpha = 0f;
    private Color flashColor  = Color.GRAY;

    private Timer flashDecayTimer;

    /**
     * @param windowSeconds how much history the sparkline shows
     */
    public TextDisplay(Theme theme, boolean flashEnabled, String title, int windowSeconds) {
        super(theme);
        this.flashEnabled = flashEnabled;
        this.title        = title == null ? "" : title.trim();
        this.windowMillis = Math.max(1, windowSeconds) * 1000L;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onNewData(QueryResult result) {
        if (result.isEmpty()) return;

        QueryRow row = result.rows().get(0);
        // The title strip already names the module; only show the label when it adds something
        String key  = row.key() == null ? "" : row.key();
        label       = key.trim().equalsIgnoreCase(title) ? "" : key;
        double val  = row.value();

        valueText = formatValue(val);

        // Change vs the previous reading: signed and arrowed, never colour alone
        if (!Double.isNaN(currentVal)) {
            double d  = val - currentVal;
            deltaSign = (int) Math.signum(d);
            deltaText = (d == 0) ? "± 0" : (d > 0 ? "▲ +" : "▼ ") + formatValue(d);
            if (flashEnabled && d != 0) triggerFlash(d > 0 ? theme.flashUp : theme.flashDown);
        }
        currentVal = val;

        long now = System.currentTimeMillis();
        history.addLast(new double[] { now, val });
        while (history.size() > 2 && history.peekFirst()[0] < now - windowMillis) history.removeFirst();
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void paintChart(Graphics2D g2, int w, int h) {
        int top    = PAD_TOP;
        int footY  = h - PAD_BOTTOM - FOOT_H;

        // Label (small, top-centre)
        if (!label.isEmpty()) {
            g2.setFont(theme.titleFont);
            g2.setColor(theme.axisForeground);
            FontMetrics fm = g2.getFontMetrics();
            String text = fit(label, fm, w - 2 * PAD_X);
            g2.drawString(text, (w - fm.stringWidth(text)) / 2f, top + fm.getAscent());
            top += fm.getHeight() + ROW_GAP;
        }

        // Value: as big as the space between label and footer allows
        int areaW = w - 2 * PAD_X;
        int areaH = footY - ROW_GAP - top;
        if (areaW > 0 && areaH > 0) {
            g2.setFont(valueFont(g2, valueText, areaW, areaH));
            Rectangle2D ink = g2.getFont()
                .createGlyphVector(g2.getFontRenderContext(), valueText).getVisualBounds();
            float x = (float) ((w - ink.getWidth()) / 2 - ink.getX());
            float y = (float) (top + (areaH - ink.getHeight()) / 2 - ink.getY());
            if (flashAlpha > 0.01f) {
                paintFlash(g2, new Rectangle2D.Double(x + ink.getX(), y + ink.getY(), ink.getWidth(), ink.getHeight()));
            }
            g2.setColor(theme.foreground);
            g2.drawString(valueText, x, y);
        }

        if (footY > top) paintFooter(g2, w, footY);
    }

    /** The value font: sized from the area's height, then reduced until the text fits its width. */
    private Font valueFont(Graphics2D g2, String text, int areaW, int areaH) {
        Font  font = theme.bigValueFont;
        float size = Math.max(MIN_VALUE_SIZE, Math.min(MAX_VALUE_SIZE, areaH * VALUE_HEIGHT_RATIO));
        double width = g2.getFontMetrics(font.deriveFont(size)).stringWidth(text);
        if (width > areaW * 0.9) size = (float) Math.max(MIN_VALUE_SIZE, size * areaW * 0.9 / width);
        return font.deriveFont(size);
    }

    /** Sparkline on the left, change since the previous reading on the right. */
    private void paintFooter(Graphics2D g2, int w, int footY) {
        int right = w - PAD_X;
        if (!deltaText.isEmpty()) {
            g2.setFont(DELTA_FONT);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(deltaSign > 0 ? theme.flashUp : deltaSign < 0 ? theme.flashDown : theme.axisForeground);
            int dw = fm.stringWidth(deltaText);
            g2.drawString(deltaText, right - dw, footY + (FOOT_H + fm.getAscent() - fm.getDescent()) / 2f);
            right -= dw + 12;
        }
        // Too narrow for a readable trend: show just the change
        if (history.size() < 2 || right - PAD_X < 60) return;

        double tMin = history.peekFirst()[0], tMax = history.peekLast()[0];
        double vMin = Double.MAX_VALUE, vMax = -Double.MAX_VALUE;
        for (double[] p : history) { vMin = Math.min(vMin, p[1]); vMax = Math.max(vMax, p[1]); }
        double x0 = PAD_X + 2, x1 = right - 4;                  // room for the end dot
        double y0 = footY + FOOT_H - 3.0, y1 = footY + 3.0;
        double tSpan = Math.max(1, tMax - tMin);
        double vSpan = vMax - vMin;

        Path2D.Double line = new Path2D.Double();
        double lastX = 0, lastY = 0;
        for (double[] p : history) {
            lastX = x0 + (p[0] - tMin) / tSpan * (x1 - x0);
            lastY = (vSpan == 0) ? (y0 + y1) / 2 : y0 - (p[1] - vMin) / vSpan * (y0 - y1);
            if (line.getCurrentPoint() == null) line.moveTo(lastX, lastY); else line.lineTo(lastX, lastY);
        }
        Path2D.Double area = new Path2D.Double(line);
        area.lineTo(lastX, y0);
        area.lineTo(x0, y0);
        area.closePath();

        Color muted = theme.axisForeground;
        g2.setColor(new Color(muted.getRed(), muted.getGreen(), muted.getBlue(), 20));
        g2.fill(area);
        g2.setColor(muted);
        g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(line);

        // Latest reading: accent dot with a ring in the tile colour
        g2.setColor(theme.chartBackground);
        g2.fill(new Ellipse2D.Double(lastX - 5.5, lastY - 5.5, 11, 11));
        g2.setColor(theme.accent);
        g2.fill(new Ellipse2D.Double(lastX - 3.5, lastY - 3.5, 7, 7));
    }

    private void paintFlash(Graphics2D g2, Rectangle2D ink) {
        float ringX = (float) ink.getX() - 18, ringY = (float) ink.getY() - 14;
        float ringW = (float) ink.getWidth() + 36, ringH = (float) ink.getHeight() + 28;
        // Outer glow: several progressively lighter strokes
        for (int t = 8; t >= 1; t--) {
            g2.setColor(new Color(flashColor.getRed(), flashColor.getGreen(),
                flashColor.getBlue(), Math.max(0, (int) (flashAlpha * 60 * t / 8f))));
            g2.setStroke(new BasicStroke(t));
            g2.draw(new RoundRectangle2D.Float(ringX - t, ringY - t, ringW + t * 2, ringH + t * 2, 16, 16));
        }
    }

    /** Shortens {@code text} with an ellipsis until it fits {@code max} pixels. */
    private static String fit(String text, FontMetrics fm, int max) {
        while (text.length() > 1 && fm.stringWidth(text) > max) {
            text = text.substring(0, text.length() - 2) + "…";
        }
        return text;
    }

    /** The label drawn above the value ({@code ""} when it would repeat the title). */
    String label() {
        return label;
    }

    /** The change since the previous reading as shown, e.g. {@code "▲ +20.5"}, or {@code ""}. */
    String deltaText() {
        return deltaText;
    }

    /** Starts the flash animation and schedules its decay. */
    private void triggerFlash(Color color) {
        if (flashDecayTimer != null) flashDecayTimer.stop();
        flashAlpha = 1.0f;
        flashColor = color;
        // Decay over ~600 ms using a Swing Timer (fires on EDT)
        flashDecayTimer = new Timer(30, e -> {
            flashAlpha = Math.max(0f, flashAlpha - 0.07f);
            repaint();
            if (flashAlpha <= 0f) ((Timer) e.getSource()).stop();
        });
        flashDecayTimer.start();
    }
}
