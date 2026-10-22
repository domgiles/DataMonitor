package com.dom.datamonitor.tui;

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BigTextTest {

    @Test
    void picksTheLargestScaleThatFits() {
        // "42": 3 + 1 + 3 = 7 pixels wide, 5 high
        assertEquals(7, BigText.pixelWidth("42"));
        assertEquals(new BigText.Scale(2, 4, false), BigText.fit("42", 28, 10));
        assertEquals(new BigText.Scale(1, 2, false), BigText.fit("42", 27, 10));
        assertEquals(new BigText.Scale(1, 1, true),  BigText.fit("42", 7, 3));
        assertNull(BigText.fit("42", 6, 3));
        assertNull(BigText.fit("3.4 Mio.", 100, 100), "no bitmap for 'i' or 'o'");
    }

    @Test
    void drawsHalfHeightDigitsWithHalfBlocks() {
        Buffer buf = Buffer.empty(Rect.of(7, 3));
        assertEquals(3, BigText.render("42", Rect.of(7, 3), buf, Style.EMPTY));
        assertEquals(List.of("█ █ ▀▀█",
                             "▀▀█ █▀▀",
                             "  ▀ ▀▀▀"), TuiTestSupport.lines(buf));
    }

    @Test
    void drawsFullSizeDigitsCentred() {
        Buffer buf = Buffer.empty(Rect.of(10, 7));
        BigText.render("1", Rect.of(10, 7), buf, Style.EMPTY);
        List<String> lines = TuiTestSupport.lines(buf);
        assertEquals("          ", lines.get(0));
        assertEquals("    ██    ", lines.get(1));
        assertEquals("  ████    ", lines.get(2));
        assertEquals("  ██████  ", lines.get(5));
    }

    @Test
    void fallsBackToPlainText() {
        Buffer buf = Buffer.empty(Rect.of(10, 3));
        assertEquals(1, BigText.render("12,345,678", Rect.of(10, 3), buf, Style.EMPTY));
        assertEquals("12,345,678", TuiTestSupport.lines(buf).get(1));
    }
}
