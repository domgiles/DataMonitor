package com.dom.datamonitor.ui;

import com.dom.datamonitor.charts.BaseChart;
import com.dom.datamonitor.model.GridLocation;
import com.dom.datamonitor.model.GridOccupancy;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.util.function.Consumer;

/**
 * Miniature of the dashboard grid used to choose where a QModule goes.
 *
 * <ul>
 *   <li>Grey   – taken by another module (its title is shown, and as a tooltip)</li>
 *   <li>Green outline – free</li>
 *   <li>Solid green   – the current selection</li>
 * </ul>
 *
 * <p>An extra empty row is always shown below the last used row (and below the selection),
 * so a module can be placed on, or extended onto, new rows at the bottom. Click a free cell
 * to select it; drag to span a rectangle of rows and columns. The selection stops at taken
 * cells rather than overlapping them.
 */
public class GridPicker extends JComponent {

    private static final Color TAKEN      = new Color(128, 128, 128);
    private static final Color TAKEN_TEXT = new Color(245, 245, 245);
    private static final Color FREE       = new Color( 46, 160,  67);
    private static final Color FREE_FILL  = new Color( 46, 160,  67, 40);
    private static final Color FREE_TEXT  = Color.WHITE;

    private static final int GUTTER   = 28;   // row labels on the left
    private static final int HEADER   = 18;   // column labels on top
    private static final int CELL_H   = 30;
    private static final int GAP      = 4;

    private final GridOccupancy occupancy;
    private final int           columns;
    private       int           rows;

    /** Selected rectangle (explicit columns), or {@code null}. */
    private GridLocation       selection;
    private GridOccupancy.Cell anchor;
    private Consumer<GridLocation> onChange = loc -> {};

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param occupancy cells taken by the <em>other</em> modules
     * @param initial   starting selection, or {@code null}; ignored if it does not fit
     */
    public GridPicker(GridOccupancy occupancy, GridLocation initial) {
        this.occupancy = occupancy;
        this.columns   = occupancy.totalColumns();
        this.selection = (initial != null && occupancy.fits(initial)) ? explicit(initial) : null;
        updateRows();
        setToolTipText("");   // enables getToolTipText(MouseEvent)

        MouseAdapter mouse = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { press(e.getPoint()); }
            @Override public void mouseDragged(MouseEvent e)  { drag(e.getPoint());  }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    /** First free cell, scanning row by row; always succeeds thanks to the empty bottom row. */
    public static GridLocation firstFreeCell(GridOccupancy occupancy) {
        for (int r = 1; ; r++) {
            for (int c = 1; c <= occupancy.totalColumns(); c++) {
                if (occupancy.isFree(r, c)) return new GridLocation(r, c, c);
            }
        }
    }

    /** The selected location with explicit columns (never span-all), or {@code null}. */
    public GridLocation getSelection() {
        return selection;
    }

    public void setOnChange(Consumer<GridLocation> onChange) {
        this.onChange = onChange;
    }

    /** Converts span-all ({@code C2*}) to an explicit range so it can be compared and drawn. */
    public GridLocation explicit(GridLocation loc) {
        return new GridLocation(loc.row(), loc.colStart(), GridOccupancy.lastColumn(loc, columns), loc.rowEnd());
    }

    /** One empty row below the last used row or the selection, whichever is lower. */
    private void updateRows() {
        int lastRow = Math.max(occupancy.maxRow(), selection != null ? selection.rowEnd() : 0);
        if (lastRow + 1 == rows) return;
        rows = lastRow + 1;
        setPreferredSize(new Dimension(GUTTER + columns * 96, HEADER + rows * (CELL_H + GAP)));
        revalidate();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mouse
    // ─────────────────────────────────────────────────────────────────────────

    private void press(Point p) {
        GridOccupancy.Cell cell = cellAt(p);
        if (cell == null || !occupancy.isFree(cell.row(), cell.col())) return;
        anchor = cell;
        select(new GridLocation(cell.row(), cell.col(), cell.col()));
    }

    /** Grows the selection from the anchor towards the pointer, stopping at taken cells. */
    private void drag(Point p) {
        if (anchor == null) return;
        // Beside the grid, clamp to the nearest column; below the last row, ignore the pointer
        // (otherwise every move would add another empty row)
        int col = Math.max(1, Math.min(columns, colAt(p.x)));
        int row = rowAt(p.y);
        if (row < 1) row = 1;
        if (row > rows) return;
        GridLocation next = occupancy.largestFreeRectangle(anchor, new GridOccupancy.Cell(row, col));
        if (next != null) select(next);
    }

    private void select(GridLocation loc) {
        if (loc.equals(selection)) return;
        selection = loc;
        updateRows();
        repaint();
        onChange.accept(loc);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        GridOccupancy.Cell cell = cellAt(e.getPoint());
        if (cell == null) return null;
        for (GridOccupancy.Placement placed : occupancy.placements()) {
            if (contains(placed.location(), cell.row(), cell.col())) {
                return placed.owner() + " (" + placed.location().toSpec() + ")";
            }
        }
        if (contains(selection, cell.row(), cell.col())) return selection.toSpec() + ": drag to resize";
        return "Free (R" + cell.row() + ",C" + cell.col() + "): click to place here, drag to span rows and columns";
    }

    private static boolean contains(GridLocation loc, int row, int col) {
        return loc != null && row >= loc.row() && row <= loc.rowEnd()
            && col >= loc.colStart() && col <= loc.colEnd();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Geometry
    // ─────────────────────────────────────────────────────────────────────────

    private double cellWidth() {
        return (getWidth() - GUTTER) / (double) columns;
    }

    private int colAt(int x) {
        return (int) Math.floor((x - GUTTER) / cellWidth()) + 1;
    }

    private int rowAt(int y) {
        return (int) Math.floor((y - HEADER) / (double) (CELL_H + GAP)) + 1;
    }

    private GridOccupancy.Cell cellAt(Point p) {
        int col = colAt(p.x);
        int row = rowAt(p.y);
        if (p.x < GUTTER || p.y < HEADER || col < 1 || col > columns || row > rows) return null;
        return new GridOccupancy.Cell(row, col);
    }

    private RoundRectangle2D cellRect(GridLocation loc) {
        double cw = cellWidth();
        double x  = GUTTER + (loc.colStart() - 1) * cw + GAP / 2.0;
        double y  = HEADER + (loc.row() - 1) * (CELL_H + GAP);
        double w  = (loc.colEnd() - loc.colStart() + 1) * cw - GAP;
        double h  = loc.rowSpan() * (CELL_H + GAP) - GAP;
        return new RoundRectangle2D.Double(x, y, w, h, 8, 8);
    }

    private RoundRectangle2D cellRect(int row, int col) {
        return cellRect(new GridLocation(row, col, col));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Painting
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        BaseChart.applyRenderingHints(g2);
        g2.setFont(getFont().deriveFont(11f));
        FontMetrics fm = g2.getFontMetrics();
        Color label = UIManager.getColor("Label.disabledForeground");
        if (label == null) label = Color.GRAY;

        // Column headers and row labels
        g2.setColor(label);
        for (int c = 1; c <= columns; c++) {
            RoundRectangle2D r = cellRect(1, c);
            String text = "C" + c;
            g2.drawString(text, (float) (r.getCenterX() - fm.stringWidth(text) / 2.0), HEADER - 5);
        }
        for (int r = 1; r <= rows; r++) {
            RoundRectangle2D rect = cellRect(r, 1);
            g2.drawString("R" + r, 2, (float) (rect.getCenterY() + fm.getAscent() / 2.0 - 1));
        }

        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= columns; c++) {
                if (occupancy.isFree(r, c) && !contains(selection, r, c)) paintFree(g2, r, c);
            }
        }
        // Each module is one block, however many rows and columns it covers
        for (GridOccupancy.Placement placed : occupancy.placements()) {
            GridLocation loc = placed.location();
            if (loc.colEnd() > columns) continue;   // invalid configs are rejected before this
            paintBlock(g2, fm, cellRect(loc), TAKEN, TAKEN_TEXT, placed.owner());
        }
        if (selection != null) {
            paintBlock(g2, fm, cellRect(selection), FREE, FREE_TEXT, selection.toSpec());
        }
        g2.dispose();
    }

    private void paintFree(Graphics2D g2, int row, int col) {
        RoundRectangle2D r = cellRect(row, col);
        g2.setColor(FREE_FILL);
        g2.fill(r);
        g2.setColor(FREE);
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(r);
    }

    private void paintBlock(Graphics2D g2, FontMetrics fm, RoundRectangle2D r,
                            Color fill, Color ink, String text) {
        g2.setColor(fill);
        g2.fill(r);

        int max = (int) r.getWidth() - 8;
        while (text.length() > 1 && fm.stringWidth(text) > max) {
            text = text.substring(0, text.length() - 2) + "…";
        }
        g2.setColor(ink);
        g2.drawString(text,
            (float) (r.getCenterX() - fm.stringWidth(text) / 2.0),
            (float) (r.getCenterY() + fm.getAscent() / 2.0 - 2));
    }
}
