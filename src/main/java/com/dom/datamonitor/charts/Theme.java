package com.dom.datamonitor.charts;

import java.awt.*;

/**
 * Immutable colour and font palette for a dashboard theme.
 *
 * <p>Two built-in themes are provided:
 * <ul>
 *   <li>{@link #DARK}  – black background, bright-green accent (default)</li>
 *   <li>{@link #LIGHT} – off-white background, dark-slate text</li>
 * </ul>
 */
public final class Theme {

    // ── Colours ──────────────────────────────────────────────────────────────

    /** Main panel/window background. */
    public final Color background;
    /** Chart plot area background. */
    public final Color chartBackground;
    /** Subtle grid-line colour. */
    public final Color gridLine;
    /** Primary text / label colour. */
    public final Color foreground;
    /** Dimmer text for axis labels. */
    public final Color axisForeground;
    /** Accent colour for titles and divider lines. */
    public final Color accent;
    /** Border colour around QModule panels. */
    public final Color border;
    /** Positive-change flash colour (TextDisplay). */
    public final Color flashUp;
    /** Negative-change flash colour (TextDisplay). */
    public final Color flashDown;

    // ── Fonts ────────────────────────────────────────────────────────────────

    public final Font titleFont;
    public final Font axisFont;
    public final Font legendFont;
    public final Font bigValueFont;

    // ── Constructor ──────────────────────────────────────────────────────────

    private Theme(Color background, Color chartBackground, Color gridLine,
                  Color foreground, Color axisForeground, Color accent,
                  Color border, Color flashUp, Color flashDown,
                  Font titleFont, Font axisFont, Font legendFont, Font bigValueFont) {
        this.background      = background;
        this.chartBackground = chartBackground;
        this.gridLine        = gridLine;
        this.foreground      = foreground;
        this.axisForeground  = axisForeground;
        this.accent          = accent;
        this.border          = border;
        this.flashUp         = flashUp;
        this.flashDown       = flashDown;
        this.titleFont       = titleFont;
        this.axisFont        = axisFont;
        this.legendFont      = legendFont;
        this.bigValueFont    = bigValueFont;
    }

    // ── Built-in themes ──────────────────────────────────────────────────────

    /** Dark theme – black / bright-green. */
    public static final Theme DARK = new Theme(
        new Color(13,  17,  23),   // background
        new Color(20,  26,  36),   // chartBackground
        new Color(35,  48,  64),   // gridLine
        new Color(45, 247,  14),   // foreground  (bright green)
        new Color(100,140,100),    // axisForeground
        new Color(45, 247,  14),   // accent
        new Color(35,  48,  64),   // border
        new Color(80, 220,  80),   // flashUp
        new Color(220, 60,  60),   // flashDown
        new Font("SansSerif", Font.BOLD,  13),
        new Font("SansSerif", Font.PLAIN, 10),
        new Font("SansSerif", Font.PLAIN, 11),
        new Font("SansSerif", Font.BOLD,  48)
    );

    /** Light theme – off-white / dark slate. */
    public static final Theme LIGHT = new Theme(
        new Color(245, 247, 250),  // background
        new Color(255, 255, 255),  // chartBackground
        new Color(210, 218, 228),  // gridLine
        new Color( 33,  37,  41),  // foreground
        new Color( 90, 100, 115),  // axisForeground
        new Color( 33,  37,  41),  // accent
        new Color(180, 190, 205),  // border
        new Color( 30, 160,  30),  // flashUp
        new Color(190,  40,  40),  // flashDown
        new Font("SansSerif", Font.BOLD,  13),
        new Font("SansSerif", Font.PLAIN, 10),
        new Font("SansSerif", Font.PLAIN, 11),
        new Font("SansSerif", Font.BOLD,  48)
    );

    /** Returns the appropriate theme for the given name (case-insensitive). */
    public static Theme forName(String name) {
        return "light".equalsIgnoreCase(name) ? LIGHT : DARK;
    }
}
