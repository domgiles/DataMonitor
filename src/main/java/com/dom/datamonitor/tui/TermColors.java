package com.dom.datamonitor.tui;

import dev.tamboui.style.Color;

import java.util.Map;

/**
 * Converts the dashboard's {@link java.awt.Color}s to terminal colours.
 *
 * <p>Terminals that announce 24-bit colour (or are known to support it) get the exact
 * colour; the rest get the nearest of the standard 256, which every modern terminal has.
 */
final class TermColors {

    private final boolean trueColor;

    TermColors(boolean trueColor) {
        this.trueColor = trueColor;
    }

    /** Decides from the environment, as most terminal programs do. */
    static TermColors detect() {
        return new TermColors(supportsTrueColor(System.getenv()));
    }

    static boolean supportsTrueColor(Map<String, String> env) {
        String colorTerm = env.getOrDefault("COLORTERM", "").toLowerCase();
        if (colorTerm.contains("truecolor") || colorTerm.contains("24bit")) return true;
        if (env.containsKey("WT_SESSION")) return true;                    // Windows Terminal
        String program = env.getOrDefault("TERM_PROGRAM", "");
        return program.equals("iTerm.app") || program.equals("WezTerm")
            || program.equals("vscode") || program.equals("ghostty");
    }

    Color of(java.awt.Color c) {
        return trueColor ? Color.rgb(c.getRed(), c.getGreen(), c.getBlue())
                         : Color.indexed(nearest256(c.getRed(), c.getGreen(), c.getBlue()));
    }

    /** The closest xterm-256 colour: from the 6x6x6 cube or the 24-step grey ramp. */
    static int nearest256(int r, int g, int b) {
        int ri = cubeIndex(r), gi = cubeIndex(g), bi = cubeIndex(b);
        int cube = 16 + 36 * ri + 6 * gi + bi;
        int cubeDist = dist(r, g, b, CUBE[ri], CUBE[gi], CUBE[bi]);

        int grey      = Math.max(0, Math.min(23, Math.round(((r + g + b) / 3f - 8) / 10f)));
        int greyLevel = 8 + 10 * grey;
        int greyDist  = dist(r, g, b, greyLevel, greyLevel, greyLevel);
        return greyDist < cubeDist ? 232 + grey : cube;
    }

    private static final int[] CUBE = { 0, 95, 135, 175, 215, 255 };

    private static int cubeIndex(int v) {
        if (v < 48)  return 0;
        if (v < 115) return 1;
        return Math.min(5, (v - 35) / 40);
    }

    private static int dist(int r1, int g1, int b1, int r2, int g2, int b2) {
        int dr = r1 - r2, dg = g1 - g2, db = b1 - b2;
        return dr * dr + dg * dg + db * db;
    }
}
