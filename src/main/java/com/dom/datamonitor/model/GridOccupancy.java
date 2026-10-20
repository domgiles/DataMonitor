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

    private final int               totalColumns;
    private final Map<Cell, String> owners = new HashMap<>();
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
        for (int c = loc.colStart(); c <= lastColumn(loc, totalColumns); c++) {
            Cell cell = new Cell(loc.row(), c);
            if (owners.putIfAbsent(cell, owner) != null) conflicts.add(cell);
        }
        maxRow = Math.max(maxRow, loc.row());
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
        for (int c = loc.colStart(); c <= last; c++) {
            if (!isFree(loc.row(), c)) return false;
        }
        return true;
    }

    /** Highest occupied row, or 0 when nothing is placed. */
    public int maxRow()       { return maxRow; }
    public int totalColumns() { return totalColumns; }
}
