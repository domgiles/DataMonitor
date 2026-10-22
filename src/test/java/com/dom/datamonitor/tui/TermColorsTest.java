package com.dom.datamonitor.tui;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TermColorsTest {

    @Test
    void nearest256UsesTheColourCubeOrTheGreyRamp() {
        assertEquals(16,  TermColors.nearest256(0, 0, 0));
        assertEquals(231, TermColors.nearest256(255, 255, 255));
        assertEquals(196, TermColors.nearest256(255, 0, 0));
        assertEquals(46,  TermColors.nearest256(0, 255, 0));
        assertEquals(244, TermColors.nearest256(128, 128, 128));   // grey ramp, not the cube
    }

    @Test
    void trueColourIsDetectedFromTheEnvironment() {
        assertTrue(TermColors.supportsTrueColor(Map.of("COLORTERM", "truecolor")));
        assertTrue(TermColors.supportsTrueColor(Map.of("COLORTERM", "24bit")));
        assertTrue(TermColors.supportsTrueColor(Map.of("WT_SESSION", "x")));
        assertTrue(TermColors.supportsTrueColor(Map.of("TERM_PROGRAM", "iTerm.app")));
        assertFalse(TermColors.supportsTrueColor(Map.of("TERM_PROGRAM", "Apple_Terminal")));
        assertFalse(TermColors.supportsTrueColor(Map.of()));
    }
}
