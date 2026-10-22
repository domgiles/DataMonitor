package com.dom.datamonitor.tui;

import com.dom.datamonitor.model.GridLocation;
import dev.tamboui.layout.Rect;

import java.util.ArrayList;
import java.util.List;

/**
 * Places modules on the dashboard grid in terminal cells.
 *
 * <p>Columns share the width and rows share the height, as in the other front ends. When the
 * terminal is too short to give every row {@link #MIN_ROW_HEIGHT} lines, only as many rows
 * as fit are shown and the rest are reached by scrolling; a module spanning the edge of the
 * view is cut to the rows on screen.
 */
final class GridLayout {

    /** A border, a line of content and room for a footer: anything less is unreadable. */
    static final int MIN_ROW_HEIGHT = 5;

    /** Where one module goes; {@code index} is its position in the list given to {@link #place}. */
    record Placement(int index, Rect rect) {}

    final int totalRows;
    final int visibleRows;
    final int firstRow;                 // 0-based, after clamping the requested scroll
    final List<Placement> placements;

    private GridLayout(int totalRows, int visibleRows, int firstRow, List<Placement> placements) {
        this.totalRows   = totalRows;
        this.visibleRows = visibleRows;
        this.firstRow    = firstRow;
        this.placements  = placements;
    }

    /** True when some rows are off screen. */
    boolean scrollable() {
        return visibleRows < totalRows;
    }

    /** Largest useful scroll position. */
    int maxScroll() {
        return totalRows - visibleRows;
    }

    static GridLayout place(List<GridLocation> locations, int columns, Rect area, int scroll) {
        int totalRows = 1;
        for (GridLocation g : locations) totalRows = Math.max(totalRows, g.rowEnd());

        int visible  = Math.max(1, Math.min(totalRows, area.height() / MIN_ROW_HEIGHT));
        int first    = Math.max(0, Math.min(scroll, totalRows - visible));
        int[] rowY   = split(area.y(), area.height(), visible);
        int[] colX   = split(area.x(), area.width(), Math.max(1, columns));

        List<Placement> out = new ArrayList<>();
        for (int i = 0; i < locations.size(); i++) {
            GridLocation g = locations.get(i);
            int top    = Math.max(g.row() - 1, first);              // 0-based rows, clipped to the view
            int bottom = Math.min(g.rowEnd() - 1, first + visible - 1);
            if (top > bottom) continue;                             // entirely off screen

            int c0 = Math.min(g.colStart(), columns) - 1;
            int c1 = Math.min(c0 + g.columnSpan(columns), columns) - 1;
            if (c1 < c0) continue;

            int y = rowY[top - first], yEnd = rowY[bottom - first + 1];
            int x = colX[c0],          xEnd = colX[c1 + 1];
            out.add(new Placement(i, new Rect(x, y, xEnd - x, yEnd - y)));
        }
        return new GridLayout(totalRows, visible, first, out);
    }

    /**
     * Boundaries for {@code n} near-equal parts of {@code length} starting at {@code start}:
     * {@code n + 1} values, the earlier parts taking the remainder.
     */
    static int[] split(int start, int length, int n) {
        int[] edges = new int[n + 1];
        int base = length / n, extra = length % n;
        edges[0] = start;
        for (int i = 0; i < n; i++) edges[i + 1] = edges[i] + base + (i < extra ? 1 : 0);
        return edges;
    }
}
