package com.dom.datamonitor.tui;

import com.dom.datamonitor.config.ConfigException;
import com.dom.datamonitor.model.GridLocation;
import dev.tamboui.layout.Rect;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GridLayoutTest {

    private static List<GridLocation> grid(String... specs) throws ConfigException {
        List<GridLocation> out = new ArrayList<>();
        for (String s : specs) out.add(GridLocation.parse(s));
        return out;
    }

    @Test
    void splitsSpaceIntoNearEqualParts() {
        assertArrayEquals(new int[] { 0, 34, 67, 100 }, GridLayout.split(0, 100, 3));
        assertArrayEquals(new int[] { 5, 10 },          GridLayout.split(5, 5, 1));
    }

    @Test
    void placesSpansAcrossColumnsAndRows() throws ConfigException {
        GridLayout g = GridLayout.place(grid("R1-2,C1", "R1,C2-3", "R2,C2", "R2,C3", "R3,C1*"), 3,
                                        new Rect(0, 1, 90, 30), 0);
        assertEquals(3, g.totalRows);
        assertFalse(g.scrollable());
        assertEquals(new Rect(0, 1, 30, 20),  g.placements.get(0).rect());   // two rows tall
        assertEquals(new Rect(30, 1, 60, 10), g.placements.get(1).rect());   // two columns wide
        assertEquals(new Rect(60, 11, 30, 10), g.placements.get(3).rect());
        assertEquals(new Rect(0, 21, 90, 10), g.placements.get(4).rect());   // C1* spans every column
    }

    @Test
    void scrollsWhenRowsWouldBeTooShort() throws ConfigException {
        // 12 lines: room for two rows of MIN_ROW_HEIGHT (5)
        List<GridLocation> rows = grid("R1,C1", "R2-3,C1", "R4,C1");
        GridLayout top = GridLayout.place(rows, 1, new Rect(0, 0, 40, 12), 0);
        assertTrue(top.scrollable());
        assertEquals(2, top.visibleRows);
        assertEquals(2, top.maxScroll());
        assertEquals(2, top.placements.size(), "R4 is off screen");
        assertEquals(new Rect(0, 6, 40, 6), top.placements.get(1).rect(), "R2-3 cut to the visible row");

        GridLayout bottom = GridLayout.place(rows, 1, new Rect(0, 0, 40, 12), 99);
        assertEquals(2, bottom.firstRow, "scroll is clamped");
        assertEquals(List.of(1, 2), bottom.placements.stream().map(GridLayout.Placement::index).toList());
        assertEquals(new Rect(0, 0, 40, 6), bottom.placements.get(0).rect(), "R2-3 shows its second row");
    }

    @Test
    void columnsBeyondTheConfiguredCountAreClamped() throws ConfigException {
        GridLayout g = GridLayout.place(grid("R1,C3"), 2, new Rect(0, 0, 40, 10), 0);
        assertEquals(new Rect(20, 0, 20, 10), g.placements.get(0).rect());
    }
}
