package com.dom.datamonitor.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * A grid of braille characters used as dots for line charts: each cell is 2 dots wide and
 * 4 high, so a 40 x 6 cell area plots at 80 x 24.
 *
 * <p>A cell has one colour, so where lines cross, the cell takes the colour of the series
 * drawn last.
 */
final class BrailleCanvas {

    /** Bit for the dot at (x, y) within a cell: the Unicode braille dot numbering. */
    private static final int[][] BITS = {
        { 0x01, 0x02, 0x04, 0x40 },   // left column, top to bottom
        { 0x08, 0x10, 0x20, 0x80 }    // right column
    };

    final int cols, rows;
    private final int[]   dots;
    private final Color[] colours;

    BrailleCanvas(int cols, int rows) {
        this.cols    = cols;
        this.rows    = rows;
        this.dots    = new int[cols * rows];
        this.colours = new Color[cols * rows];
    }

    int width()  { return cols * 2; }
    int height() { return rows * 4; }

    void set(int x, int y, Color colour) {
        if (x < 0 || y < 0 || x >= width() || y >= height()) return;
        int cell = (y / 4) * cols + x / 2;
        dots[cell] |= BITS[x % 2][y % 4];
        colours[cell] = colour;
    }

    /** A straight line between two dots (Bresenham), clipped to the canvas. */
    void line(int x0, int y0, int x1, int y1, Color colour) {
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            set(x0, y0, colour);
            if (x0 == x1 && y0 == y1) return;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    void render(int left, int top, Buffer buf) {
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                int cell = r * cols + c;
                if (dots[cell] == 0) continue;
                buf.setString(left + c, top + r, String.valueOf((char) (0x2800 + dots[cell])),
                              Style.EMPTY.fg(colours[cell]));
            }
        }
    }
}
