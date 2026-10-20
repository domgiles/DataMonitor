package com.dom.datamonitor.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tracks which dashboard grid cells are taken, and by whom.
 *
 * <p>Shared by {@link com.dom.datamonitor.config.ConfigValidator} (overlap / bounds checks)
 * and the QModule editor's location picker (grey = taken, green = free).
 */
public final class GridOccupancy {

    /** A single 1-based grid cell. */
    public record Cell(int row, int col) {}

    /** A placed module: its location (explicit columns, never span-all) and owner label. */
    public record Placement(GridLocation location, String owner) {}

    private final int               totalColumns;
    private final Map<Cell, String> owners     = new HashMap<>();
    private final List<Placement>   placements = new ArrayList<>();
    private       int               maxRow;

    public GridOccupancy(int totalColumns) {
        this.totalColumns = totalColumns;
    }

    /**
     * Returns the last column {@code loc} reaches on a dashboard with {@code totalColumns}.
     * For span-all specs this is {@code totalColumns}, except when the start column is
     * itself beyond the grid (e.g. {@code C4*} on 3 columns), where the start is returned.
     */
    public static int lastColumn(GridLocation loc, int totalColumns) {
        int end = (loc.colEnd() == GridLocation.SPAN_ALL) ? totalColumns : loc.colEnd();
        return Math.max(loc.colStart(), end);
    }

    /**
     * Marks every cell of {@code loc} as owned by {@code owner}.
     *
     * @return the cells that were already taken (empty if there was no overlap);
     *         those cells keep their original owner
     */
    public List<Cell> occupy(GridLocation loc, String owner) {
        List<Cell> conflicts = new ArrayList<>();
        for (int r = loc.row(); r <= loc.rowEnd(); r++) {
            for (int c = loc.colStart(); c <= lastColumn(loc, totalColumns); c++) {
                Cell cell = new Cell(r, c);
                if (owners.putIfAbsent(cell, owner) != null) conflicts.add(cell);
            }
        }
        maxRow = Math.max(maxRow, loc.rowEnd());
        placements.add(new Placement(
            new GridLocation(loc.row(), loc.colStart(), lastColumn(loc, totalColumns), loc.rowEnd()), owner));
        return conflicts;
    }

    public boolean isFree(int row, int col) {
        return !owners.containsKey(new Cell(row, col));
    }

    /** Owner label of the cell, or {@code null} if it is free. */
    public String ownerAt(int row, int col) {
        return owners.get(new Cell(row, col));
    }

    /** True if every cell of {@code loc} is inside the grid and free. */
    public boolean fits(GridLocation loc) {
        int last = lastColumn(loc, totalColumns);
        if (last > totalColumns) return false;
        for (int r = loc.row(); r <= loc.rowEnd(); r++) {
            for (int c = loc.colStart(); c <= last; c++) {
                if (!isFree(r, c)) return false;
            }
        }
        return true;
    }

    /**
     * The largest free rectangle with {@code anchor} as one corner that stays inside the box
     * from {@code anchor} to {@code target} (the one ending closest to {@code target} on a tie),
     * or {@code null} if the anchor itself is taken. Used when dragging out a selection: the
     * selection stops at taken cells instead of overlapping them.
     */
    public GridLocation largestFreeRectangle(Cell anchor, Cell target) {
        int dr = (target.row() >= anchor.row()) ? 1 : -1;
        int dc = (target.col() >= anchor.col()) ? 1 : -1;
        GridLocation best = null;
        int bestArea = 0, bestDist = Integer.MAX_VALUE;
        for (int r = anchor.row(); dr > 0 ? r <= target.row() : r >= target.row(); r += dr) {
            for (int c = anchor.col(); dc > 0 ? c <= target.col() : c >= target.col(); c += dc) {
                GridLocation loc = new GridLocation(Math.min(anchor.row(), r),
                    Math.min(anchor.col(), c), Math.max(anchor.col(), c), Math.max(anchor.row(), r));
                if (!fits(loc)) continue;
                int area = loc.rowSpan() * (loc.colEnd() - loc.colStart() + 1);
                int dist = Math.abs(target.row() - r) + Math.abs(target.col() - c);
                if (area > bestArea || (area == bestArea && dist < bestDist)) {
                    best = loc; bestArea = area; bestDist = dist;
                }
            }
        }
        return best;
    }

    /** Every module placed so far, in placement order. */
    public List<Placement> placements() { return List.copyOf(placements); }

    /** Highest occupied row, or 0 when nothing is placed. */
    public int maxRow()       { return maxRow; }
    public int totalColumns() { return totalColumns; }
}
