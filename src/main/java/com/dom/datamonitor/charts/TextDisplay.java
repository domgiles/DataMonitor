package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;

import javax.swing.Timer;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;

/**
 * Big-number KPI tile display.
 *
 * <p>Renders a single key-value pair as a focal-point number:
 * <ul>
 *   <li>The <em>label</em> (SQL key) is shown small at the top-centre.</li>
 *   <li>The <em>value</em> is shown large and bold, dominating the panel.</li>
 * </ul>
 *
 * <p>When the value changes compared to the previous reading, a brief colour flash
 * is painted around the value: green for an increase, red for a decrease.
 *
 * <p>Expects a single-row duplet or triplet from the SQL.  If multiple rows are
 * returned only the first is used.
 */
public class TextDisplay extends BaseChart {

    private String label      = "";
    private String valueText  = "–";
    private double currentVal = Double.NaN;

    /**
     * Flash opacity: starts at 1.0 after a value change and decays to 0.
     * Drives the glow ring around the value.
     */
    private final boolean flashEnabled;

    private float flashAlpha = 0f;
    private Color flashColor  = Color.GRAY;

    private Timer flashDecayTimer;

    public TextDisplay(Theme theme, boolean flashEnabled) {
        super(theme);
        this.flashEnabled = flashEnabled;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onNewData(QueryResult result) {
        if (result.isEmpty()) return;

        QueryRow row = result.rows().get(0);
        label       = row.key() == null ? "" : row.key();
        double val  = row.value();

        valueText = formatValue(val);

        if (flashEnabled && !Double.isNaN(currentVal)) {
            if      (val > currentVal) triggerFlash(theme.flashUp);
            else if (val < currentVal) triggerFlash(theme.flashDown);
        }

        currentVal = val;
    }

    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void paintChart(Graphics2D g2, int w, int h) {
        int cx = w / 2;
        int cy = h / 2;

        // Flash glow ring
        if (flashAlpha > 0.01f) {
            Color glow = new Color(flashColor.getRed(), flashColor.getGreen(),
                flashColor.getBlue(), (int)(flashAlpha * 80));
            g2.setColor(glow);
            float ringW = w * 0.7f, ringH = h * 0.6f;
            float ringX = cx - ringW / 2f, ringY = cy - ringH / 2f;
            // Outer glow: several progressively lighter strokes
            for (int t = 8; t >= 1; t--) {
                Color c2 = new Color(flashColor.getRed(), flashColor.getGreen(),
                    flashColor.getBlue(), Math.max(0, (int)(flashAlpha * 60 * t / 8f)));
                g2.setColor(c2);
                g2.setStroke(new BasicStroke(t));
                g2.draw(new RoundRectangle2D.Float(ringX - t, ringY - t,
                    ringW + t * 2, ringH + t * 2, 16, 16));
            }
        }

        // Label (small, top-centre)
        g2.setFont(theme.titleFont);
        g2.setColor(theme.axisForeground);
        FontMetrics labelFm = g2.getFontMetrics();
        int labelY = cy - (int)(h * 0.20);
        g2.drawString(label, cx - labelFm.stringWidth(label) / 2, labelY);

        // Value (large, centred)
        Font valueFont = scaledValueFont(g2, valueText, w);
        g2.setFont(valueFont);
        g2.setColor(theme.foreground);
        FontMetrics valFm = g2.getFontMetrics();
        int valX = cx - valFm.stringWidth(valueText) / 2;
        int valY = cy + valFm.getAscent() / 2;
        g2.drawString(valueText, valX, valY);
    }

    // ─────────────────────────────────────────────────────────────────────────

    /** Returns a version of the big-value font scaled to fit within {@code maxWidth}. */
    private Font scaledValueFont(Graphics2D g2, String text, int maxWidth) {
        Font font = theme.bigValueFont;
        // Scale down until it fits
        float size = font.getSize2D();
        while (size > 12) {
            FontMetrics fm = g2.getFontMetrics(font.deriveFont(size));
            if (fm.stringWidth(text) <= maxWidth * 0.75) break;
            size -= 2f;
        }
        return font.deriveFont(size);
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
