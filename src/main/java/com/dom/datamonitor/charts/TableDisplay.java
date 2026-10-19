package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Custom-painted table showing all result rows as key-value pairs.
 *
 * <p>Rows alternate between two subtle background tones for readability.
 * The colour from the SQL triplet, if present, is used as a leading indicator
 * dot on the left side of each row.
 */
public class TableDisplay extends BaseChart {

    private final List<QueryRow> rows = new ArrayList<>();

    public TableDisplay(Theme theme) {
        super(theme);
    }

    @Override
    protected void onNewData(QueryResult result) {
        rows.clear();
        rows.addAll(result.rows());
    }

    @Override
    protected void paintChart(Graphics2D g2, int w, int h) {
        if (rows.isEmpty()) return;

        FontMetrics fm = g2.getFontMetrics(theme.axisFont);
        int rowH    = Math.max(fm.getHeight() + 8, 22);
        int n       = rows.size();
        int totalH  = n * rowH;
        int startY  = Math.max(4, (h - totalH) / 2);

        // Column layout: dot | key | value
        int dotSize   = 8;
        int dotMargin = 10;
        int keyCol    = dotMargin + dotSize + 6;
        int valCol    = w - 80;

        // Header row background
        g2.setColor(new Color(theme.gridLine.getRed(), theme.gridLine.getGreen(),
            theme.gridLine.getBlue(), 180));
        g2.fillRect(0, startY - rowH, w, rowH);
        g2.setFont(theme.axisFont.deriveFont(Font.BOLD));
        g2.setColor(theme.foreground);
        g2.drawString("Metric", keyCol, startY - rowH + fm.getAscent() + 4);
        g2.drawString("Value",  valCol, startY - rowH + fm.getAscent() + 4);

        g2.setFont(theme.axisFont);
        Color evenBg = new Color(theme.chartBackground.getRed(),
            theme.chartBackground.getGreen(),
            theme.chartBackground.getBlue(), 255);
        Color oddBg  = new Color(
            Math.min(255, theme.chartBackground.getRed()   + 8),
            Math.min(255, theme.chartBackground.getGreen() + 10),
            Math.min(255, theme.chartBackground.getBlue()  + 14), 255);

        for (int i = 0; i < n; i++) {
            QueryRow row = rows.get(i);
            int y = startY + i * rowH;

            // Alternating row background
            g2.setColor(i % 2 == 0 ? evenBg : oddBg);
            g2.fillRect(0, y, w, rowH);

            // Colour indicator dot (from SQL triplet colour if available)
            Color dot = row.hasColor()
                ? ColorPalette.resolve(row.color(), ColorPalette.defaultColor(i, theme))
                : ColorPalette.defaultColor(i, theme);
            g2.setColor(dot);
            g2.fillOval(dotMargin, y + (rowH - dotSize) / 2, dotSize, dotSize);

            // Key
            g2.setColor(theme.foreground);
            g2.drawString(truncate(row.key(), valCol - keyCol - 8, fm),
                keyCol, y + fm.getAscent() + (rowH - rowH) / 2 + 4);

            // Value (right-aligned)
            String valStr = formatValue(row.value());
            g2.setColor(theme.axisForeground);
            g2.drawString(valStr, valCol, y + fm.getAscent() + 4);

            // Row separator
            g2.setColor(theme.gridLine);
            g2.drawLine(0, y + rowH - 1, w, y + rowH - 1);
        }
    }

    private String truncate(String text, int maxWidth, FontMetrics fm) {
        if (text == null) return "";
        if (fm.stringWidth(text) <= maxWidth) return text;
        while (text.length() > 1 && fm.stringWidth(text + "…") > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text + "…";
    }

    @Override protected int padLeft()   { return 0; }
    @Override protected int padRight()  { return 0; }
    @Override protected int padTop()    { return 0; }
    @Override protected int padBottom() { return 0; }
}
