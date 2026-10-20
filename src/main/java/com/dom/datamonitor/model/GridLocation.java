package com.dom.datamonitor.model;

import com.dom.datamonitor.config.ConfigException;

/**
 * The grid cell(s) occupied by a QModule within the dashboard.
 *
 * <p>Specification format: {@code R<row>,C<col>}  where row can be:
 * <ul>
 *   <li>{@code R1}   – single row</li>
 *   <li>{@code R1-2} – row range (inclusive): the module is that many rows tall</li>
 * </ul>
 * and col can be:
 * <ul>
 *   <li>{@code C1}   – single column</li>
 *   <li>{@code C1-2} – column range (inclusive)</li>
 *   <li>{@code C1*}  – from column 1 to the last column (span all)</li>
 * </ul>
 *
 * <p>Row and column indices start at 1. {@code rowEnd} comes last so that the three-argument
 * single-row constructor keeps its meaning.
 */
public record GridLocation(int row, int colStart, int colEnd, int rowEnd) {

    /** Sentinel value for {@code colEnd} meaning "span to the last column". */
    public static final int SPAN_ALL = -1;

    /** A single-row location. */
    public GridLocation(int row, int colStart, int colEnd) {
        this(row, colStart, colEnd, row);
    }

    public static GridLocation parse(String spec) throws ConfigException {
        if (spec == null || spec.isBlank()) {
            throw new ConfigException(
                "grid is required. Example values: R1,C1  R1,C1-2  R1,C1*  R1-2,C1");
        }
        String[] parts = spec.trim().split(",", 2);
        if (parts.length != 2) {
            throw new ConfigException(
                "Invalid grid '" + spec + "'. Expected R<row|range>,C<col|range|*>  e.g. R2,C1-2 or R1-2,C3");
        }

        // --- Row ---
        String rowPart = parts[0].trim();
        if (!rowPart.toUpperCase().startsWith("R")) {
            throw new ConfigException("Grid row part must start with 'R', got: " + rowPart);
        }
        rowPart = rowPart.substring(1);
        int row, rowEnd;
        try {
            if (rowPart.contains("-")) {
                String[] range = rowPart.split("-", 2);
                row    = Integer.parseInt(range[0].trim());
                rowEnd = Integer.parseInt(range[1].trim());
                if (rowEnd < row) {
                    throw new ConfigException(
                        "Row end must be >= start in grid spec '" + spec + "'");
                }
            } else {
                row    = Integer.parseInt(rowPart.trim());
                rowEnd = row;
            }
            if (row < 1) throw new ConfigException("Grid row must be >= 1, got: " + row);
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid row number in grid spec '" + spec + "'");
        }

        // --- Column ---
        String colPart = parts[1].trim();
        if (!colPart.toUpperCase().startsWith("C")) {
            throw new ConfigException("Grid column part must start with 'C', got: " + colPart);
        }
        colPart = colPart.substring(1);

        int colStart, colEnd;
        try {
            if (colPart.endsWith("*")) {
                String prefix = colPart.substring(0, colPart.length() - 1);
                colStart = prefix.isEmpty() ? 1 : Integer.parseInt(prefix);
                colEnd   = SPAN_ALL;
            } else if (colPart.contains("-")) {
                String[] range = colPart.split("-", 2);
                colStart = Integer.parseInt(range[0].trim());
                colEnd   = Integer.parseInt(range[1].trim());
                if (colEnd < colStart) {
                    throw new ConfigException(
                        "Column end must be >= start in grid spec '" + spec + "'");
                }
            } else {
                colStart = Integer.parseInt(colPart.trim());
                colEnd   = colStart;
            }
            if (colStart < 1) throw new ConfigException("Column must be >= 1 in grid spec '" + spec + "'");
        } catch (NumberFormatException e) {
            throw new ConfigException("Invalid column number in grid spec '" + spec + "'");
        }

        return new GridLocation(row, colStart, colEnd, rowEnd);
    }

    /**
     * Returns the number of columns this location spans.
     *
     * @param totalColumns total columns configured for the dashboard
     */
    public int columnSpan(int totalColumns) {
        return (colEnd == SPAN_ALL) ? (totalColumns - colStart + 1) : (colEnd - colStart + 1);
    }

    /** Returns the number of rows this location spans. */
    public int rowSpan() {
        return rowEnd - row + 1;
    }

    /**
     * Formats this location in the config syntax accepted by {@link #parse}, e.g. {@code R2,C1-3}
     * or {@code R1-2,C3}. Single-row locations never get a row range.
     */
    public String toSpec() {
        String rows = (rowEnd == row) ? "R" + row : "R" + row + "-" + rowEnd;
        if (colEnd == SPAN_ALL)   return rows + ",C" + colStart + "*";
        if (colEnd == colStart)   return rows + ",C" + colStart;
        return rows + ",C" + colStart + "-" + colEnd;
    }

    /** 0-based x index suitable for {@code GridBagConstraints.gridx}. */
    public int gridBagX() { return colStart - 1; }

    /** 0-based y index suitable for {@code GridBagConstraints.gridy}. */
    public int gridBagY() { return row - 1; }
}
