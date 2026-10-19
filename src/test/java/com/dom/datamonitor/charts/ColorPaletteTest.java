package com.dom.datamonitor.charts;

import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.*;

class ColorPaletteTest {

    private static final Color FALLBACK = new Color(1, 2, 3);

    @Test
    void resolvesNamedColoursCaseInsensitively() {
        Color red = ColorPalette.resolve("red", FALLBACK);
        assertNotEquals(FALLBACK, red);
        assertEquals(red, ColorPalette.resolve("  RED ", FALLBACK));
        assertEquals(ColorPalette.resolve("gray", FALLBACK), ColorPalette.resolve("Grey", FALLBACK));
    }

    @Test
    void resolvesHexWithAndWithoutHash() {
        assertEquals(new Color(0xFF, 0x57, 0x33), ColorPalette.resolve("#FF5733", FALLBACK));
        assertEquals(new Color(0xFF, 0x57, 0x33), ColorPalette.resolve("ff5733", FALLBACK));
    }

    @Test
    void resolvesRgbNotation() {
        assertEquals(new Color(255, 87, 51), ColorPalette.resolve("rgb(255, 87, 51)", FALLBACK));
        assertEquals(new Color(10, 20, 30),  ColorPalette.resolve("RGB(10,20,30)", FALLBACK));
    }

    @Test
    void resolvesAwtColorFieldNames() {
        assertEquals(Color.MAGENTA, ColorPalette.resolve("magenta", FALLBACK));
    }

    @Test
    void fallsBackForBlankOrUnrecognised() {
        assertSame(FALLBACK, ColorPalette.resolve(null, FALLBACK));
        assertSame(FALLBACK, ColorPalette.resolve("  ", FALLBACK));
        assertSame(FALLBACK, ColorPalette.resolve("not a colour", FALLBACK));
        assertSame(FALLBACK, ColorPalette.resolve("#GGGGGG", FALLBACK));
        assertSame(FALLBACK, ColorPalette.resolve("rgb(1,2)", FALLBACK));
        assertSame(FALLBACK, ColorPalette.resolve("rgb(999,0,0)", FALLBACK));
    }

    @Test
    void defaultColourWrapsAround() {
        int n = ColorPalette.defaultPalette(Theme.DARK).length;
        assertEquals(ColorPalette.defaultColor(0, Theme.DARK), ColorPalette.defaultColor(n, Theme.DARK));
        assertNotEquals(ColorPalette.defaultColor(0, Theme.DARK), ColorPalette.defaultColor(0, Theme.LIGHT));
    }
}
