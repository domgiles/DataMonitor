package com.dom.datamonitor.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * Coloured pixels two to a cell, one above the other, drawn with the half-block ▀: its
 * foreground colours the top pixel and its background the bottom one. Terminal cells are
 * about twice as tall as wide, so the pixels are roughly square, which suits filled areas
 * and the pie chart's circle. Pixels left unset show the terminal's own background.
 */
final class PixelCanvas {

    final int width, height;          // in pixels: height is twice the rows
    private final Color[] pixels;

    PixelCanvas(int cols, int rows) {
        this.width  = cols;
        this.height = rows * 2;
        this.pixels = new Color[width * height];
    }

    void set(int x, int y, Color colour) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        pixels[y * width + x] = colour;
    }

    Color get(int x, int y) {
        return pixels[y * width + x];
    }

    void render(int left, int top, Buffer buf) {
        for (int row = 0; row < height / 2; row++) {
            for (int x = 0; x < width; x++) {
                Color upper = pixels[2 * row * width + x];
                Color lower = pixels[(2 * row + 1) * width + x];
                if (upper == null && lower == null) continue;
                String symbol;
                Style style;
                if (lower == null) {
                    symbol = "▀"; style = Style.EMPTY.fg(upper);
                } else if (upper == null) {
                    symbol = "▄"; style = Style.EMPTY.fg(lower);
                } else if (upper.equals(lower)) {
                    symbol = "█"; style = Style.EMPTY.fg(upper);
                } else {
                    symbol = "▀"; style = Style.EMPTY.fg(upper).bg(lower);
                }
                buf.setString(left + x, top + row, symbol, style);
            }
        }
    }
}
