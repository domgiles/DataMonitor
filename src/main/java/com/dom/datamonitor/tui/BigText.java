package com.dom.datamonitor.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a number in large block characters, for the TEXT tile.
 *
 * <p>Each character is a small bitmap, 5 pixels high (3 wide for digits). It is drawn at
 * the largest size that fits the area: a pixel becomes a block {@code k} rows high and
 * {@code 2k} columns wide (terminal cells are about twice as tall as they are wide, so the
 * pixels look square), then 1 x 1, then half-height using ▀ ▄ (3 rows in all). When even
 * that doesn't fit, or the text has a character with no bitmap, it is drawn as plain text.
 */
final class BigText {

    /** Pixel rows per glyph. */
    static final int GLYPH_H = 5;

    private static final Map<Character, String[]> GLYPHS = new HashMap<>();

    static {
        glyph('0', "###", "#.#", "#.#", "#.#", "###");
        glyph('1', ".#.", "##.", ".#.", ".#.", "###");
        glyph('2', "###", "..#", "###", "#..", "###");
        glyph('3', "###", "..#", "###", "..#", "###");
        glyph('4', "#.#", "#.#", "###", "..#", "..#");
        glyph('5', "###", "#..", "###", "..#", "###");
        glyph('6', "###", "#..", "###", "#.#", "###");
        glyph('7', "###", "..#", "..#", "..#", "..#");
        glyph('8', "###", "#.#", "###", "#.#", "###");
        glyph('9', "###", "#.#", "###", "..#", "###");
        glyph('.', ".", ".", ".", ".", "#");
        glyph(',', ".", ".", ".", "#", "#");
        glyph('-', "...", "...", "###", "...", "...");
        glyph('–', "...", "...", "###", "...", "...");
        glyph('+', "...", ".#.", "###", ".#.", "...");
        glyph('%', "#.#", "..#", ".#.", "#..", "#.#");
        glyph(' ', "..", "..", "..", "..", "..");
        glyph('K', "#.#", "#.#", "##.", "#.#", "#.#");
        glyph('M', "#...#", "##.##", "#.#.#", "#...#", "#...#");
        glyph('B', "##.", "#.#", "##.", "#.#", "##.");
        glyph('T', "###", ".#.", ".#.", ".#.", ".#.");
    }

    private static void glyph(char c, String... rows) {
        GLYPHS.put(c, rows);
    }

    private BigText() {}

    /** Size of one pixel in cells; {@code half} draws two pixel rows per cell row. */
    record Scale(int rowsPerPixel, int colsPerPixel, boolean half) {
        int height()          { return half ? (GLYPH_H + 1) / 2 : GLYPH_H * rowsPerPixel; }
        int width(int pixels) { return pixels * colsPerPixel; }
    }

    /** Largest first. */
    private static final Scale[] SCALES = {
        new Scale(4, 8, false), new Scale(3, 6, false), new Scale(2, 4, false),
        new Scale(1, 2, false), new Scale(1, 1, false), new Scale(1, 1, true)
    };

    /** True when every character of {@code text} has a bitmap. */
    static boolean drawable(String text) {
        if (text.isEmpty()) return false;
        for (char c : text.toCharArray()) if (!GLYPHS.containsKey(c)) return false;
        return true;
    }

    /** Width of {@code text} in pixels, including a 1-pixel gap between characters. */
    static int pixelWidth(String text) {
        int w = 0;
        for (char c : text.toCharArray()) w += GLYPHS.get(c)[0].length();
        return w + Math.max(0, text.length() - 1);
    }

    /** The largest scale at which {@code text} fits in {@code width} x {@code height} cells, or null. */
    static Scale fit(String text, int width, int height) {
        if (!drawable(text)) return null;
        int px = pixelWidth(text);
        for (Scale s : SCALES) {
            if (s.width(px) <= width && s.height() <= height) return s;
        }
        return null;
    }

    /**
     * Draws {@code text} centred in {@code area}, as big as fits. Returns the rows used,
     * so callers can lay things out around it.
     */
    static int render(String text, Rect area, Buffer buf, Style style) {
        Scale s = fit(text, area.width(), area.height());
        if (s == null) {
            String shown = text.length() > area.width() ? text.substring(0, Math.max(0, area.width())) : text;
            int x = area.x() + (area.width() - shown.length()) / 2;
            buf.setString(x, area.y() + area.height() / 2, shown, style.bold());
            return 1;
        }
        int width = s.width(pixelWidth(text));
        int x0 = area.x() + (area.width() - width) / 2;
        int y0 = area.y() + (area.height() - s.height()) / 2;

        int px = 0;   // pixel column of the current glyph
        for (char c : text.toCharArray()) {
            String[] g = GLYPHS.get(c);
            for (int gy = 0; gy < GLYPH_H; gy++) {
                for (int gx = 0; gx < g[gy].length(); gx++) {
                    if (g[gy].charAt(gx) != '#') continue;
                    int cx = x0 + (px + gx) * s.colsPerPixel();
                    if (s.half()) {
                        int cy = y0 + gy / 2;
                        boolean top = gy % 2 == 0;
                        String existing = buf.get(cx, cy).symbol();
                        String sym = top ? (existing.equals("▄") ? "█" : "▀")
                                         : (existing.equals("▀") ? "█" : "▄");
                        buf.setString(cx, cy, sym, style);
                    } else {
                        String block = "█".repeat(s.colsPerPixel());
                        for (int r = 0; r < s.rowsPerPixel(); r++) {
                            buf.setString(cx, y0 + gy * s.rowsPerPixel() + r, block, style);
                        }
                    }
                }
            }
            px += g[0].length() + 1;
        }
        return s.height();
    }
}
