package com.dom.datamonitor.charts;

import java.awt.*;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves colour names (from SQL results) to {@link Color} objects,
 * and provides a default rotating palette for un-named series.
 *
 * <p>Supports:
 * <ul>
 *   <li>Named colours (case-insensitive): "red", "Blue", "Light Green", etc.</li>
 *   <li>HTML hex codes: "#FF5733" or "FF5733"</li>
 *   <li>RGB function notation: "rgb(255,87,51)"</li>
 *   <li>{@link Color} field names: "CYAN", "ORANGE", etc.</li>
 * </ul>
 */
public final class ColorPalette {

    /** Bright, modern default series colours for dark themes. */
    private static final Color[] DEFAULT_SERIES_DARK = {
        new Color(  0, 200, 255),   // electric blue
        new Color(255, 140,   0),   // orange
        new Color( 80, 240,  80),   // lime green
        new Color(255,  80,  80),   // coral red
        new Color(200, 100, 255),   // violet
        new Color(255, 220,   0),   // yellow
        new Color(  0, 220, 180),   // teal
        new Color(255, 120, 180),   // pink
    };

    /** Slightly darker palette for light themes. */
    private static final Color[] DEFAULT_SERIES_LIGHT = {
        new Color(  0, 120, 200),
        new Color(200,  80,   0),
        new Color( 20, 160,  20),
        new Color(200,  30,  30),
        new Color(130,  40, 200),
        new Color(180, 160,   0),
        new Color(  0, 160, 130),
        new Color(200,  60, 130),
    };

    /** Named colour map (lowercase names → Color). */
    private static final Map<String, Color> NAMED = new HashMap<>();

    static {
        NAMED.put("red",             new Color(210,  50,  30));
        NAMED.put("dark red",        new Color(140,  20,  10));
        NAMED.put("green",           new Color(  0, 200,   0));
        NAMED.put("light green",     new Color(160, 240, 160));
        NAMED.put("blue",            new Color(  0, 100, 230));
        NAMED.put("light blue",      new Color(100, 180, 255));
        NAMED.put("cyan",            new Color(  0, 210, 240));
        NAMED.put("orange",          new Color(230, 110,   0));
        NAMED.put("yellow",          new Color(240, 210,   0));
        NAMED.put("pink",            new Color(240, 100, 160));
        NAMED.put("purple",          new Color(160,  40, 200));
        NAMED.put("violet",          new Color(200,  80, 255));
        NAMED.put("white",           Color.WHITE);
        NAMED.put("black",           Color.BLACK);
        NAMED.put("gray",            new Color(140, 140, 140));
        NAMED.put("grey",            new Color(140, 140, 140));
        NAMED.put("light gray",      new Color(196, 182, 156));
        NAMED.put("light grey",      new Color(196, 182, 156));
        NAMED.put("dark gray",       new Color( 80,  80,  80));
        NAMED.put("dark grey",       new Color( 80,  80,  80));
        NAMED.put("brown",           new Color( 91,  69,   9));
        NAMED.put("light brown",     new Color(115, 116,  85));
        NAMED.put("gray brown",      new Color(162, 147, 116));
        NAMED.put("light gray brown",new Color(196, 182, 156));
        NAMED.put("teal",            new Color(  0, 180, 160));
    }

    private ColorPalette() {}

    /**
     * Resolves a colour string to a {@link Color}, falling back to
     * the {@code fallback} colour if parsing fails.
     *
     * @param colorStr  string from the SQL result (may be null)
     * @param fallback  used when {@code colorStr} is null or unrecognised
     */
    public static Color resolve(String colorStr, Color fallback) {
        if (colorStr == null || colorStr.isBlank()) return fallback;

        // 1. Named map (lowercase)
        Color named = NAMED.get(colorStr.trim().toLowerCase());
        if (named != null) return named;

        // 2. HTML hex: #RRGGBB or RRGGBB
        String hex = colorStr.trim().startsWith("#") ? colorStr.trim().substring(1) : null;
        if (hex == null && colorStr.trim().matches("[0-9A-Fa-f]{6}")) hex = colorStr.trim();
        if (hex != null) {
            try { return Color.decode("#" + hex); } catch (NumberFormatException ignored) {}
        }

        // 3. rgb(r,g,b)
        String lower = colorStr.trim().toLowerCase();
        if (lower.startsWith("rgb(") && lower.endsWith(")")) {
            String[] parts = lower.substring(4, lower.length() - 1).split(",");
            try {
                return new Color(Integer.parseInt(parts[0].trim()),
                                 Integer.parseInt(parts[1].trim()),
                                 Integer.parseInt(parts[2].trim()));
            } catch (Exception ignored) {}
        }

        // 4. java.awt.Color field name (e.g. "CYAN")
        try {
            return (Color) Color.class.getField(colorStr.trim().toUpperCase()).get(null);
        } catch (Exception ignored) {}

        return fallback;
    }

    /** Returns the default series palette for the given theme. */
    public static Color[] defaultPalette(Theme theme) {
        return (theme == Theme.LIGHT) ? DEFAULT_SERIES_LIGHT : DEFAULT_SERIES_DARK;
    }

    /** Returns the {@code index}-th default series colour (wraps around). */
    public static Color defaultColor(int index, Theme theme) {
        Color[] palette = defaultPalette(theme);
        return palette[index % palette.length];
    }
}
