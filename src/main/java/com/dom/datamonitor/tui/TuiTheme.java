package com.dom.datamonitor.tui;

import com.dom.datamonitor.charts.ColorPalette;
import com.dom.datamonitor.charts.Theme;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

/**
 * The dashboard theme in terminal colours. The terminal's own background is left alone, so
 * pick the theme that suits it: {@code dark} for a dark terminal, {@code light} for a light one.
 */
final class TuiTheme {

    final Theme  source;
    final Style  text, muted, accent, border, title, up, down, error, paused;
    private final TermColors colors;

    TuiTheme(Theme source, TermColors colors) {
        this.source = source;
        this.colors = colors;
        text   = Style.EMPTY;                                   // the terminal's own text colour, as titles are on the web
        muted  = Style.EMPTY.fg(colors.of(source.axisForeground));
        accent = Style.EMPTY.fg(colors.of(source.accent));
        // The themes' border is very faint on a terminal background; halfway to the axis colour reads better
        border = Style.EMPTY.fg(colors.of(mix(source.border, source.axisForeground)));
        title  = text.bold();
        up     = Style.EMPTY.fg(colors.of(source.flashUp));
        down   = Style.EMPTY.fg(colors.of(source.flashDown));
        error  = down;
        paused = Style.EMPTY.fg(colors.of(new java.awt.Color(0xE0, 0xA0, 0x30))).bold();
    }

    private static java.awt.Color mix(java.awt.Color a, java.awt.Color b) {
        return new java.awt.Color((a.getRed() + b.getRed()) / 2, (a.getGreen() + b.getGreen()) / 2,
                                  (a.getBlue() + b.getBlue()) / 2);
    }

    TuiTheme other() {
        return new TuiTheme(source == Theme.DARK ? Theme.LIGHT : Theme.DARK, colors);
    }

    /** A series colour: the SQL's colour if it named one, else the palette colour for its position. */
    Color series(String sqlColour, int index) {
        return colors.of(ColorPalette.resolve(sqlColour, ColorPalette.defaultColor(index, source)));
    }
}
