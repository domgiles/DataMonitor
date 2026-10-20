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
 * <p>An extra empty row is always shown below the last used row, so a module can be
 * placed on a new row at the bottom. Click a free cell to select it; drag along the
 * row to span several adjacent free cells.
 */
public class GridPicker extends JComponent {

    private static final Color TAKEN      = new Color(128, 128, 128);
    private static final Color TAKEN_TEXT = new Color(245, 245, 245);
    private static final Color FREE       = new Color( 46, 160,  67);
    private static final Color FREE_FILL  = new Color( 46, 160,  67, 40);

    private static final int GUTTER   = 28;   // row labels on the left
    private static final int HEADER   = 18;   // column labels on top
    private static final int CELL_H   = 30;
    private static final int GAP      = 4;

    private final GridOccupancy occupancy;
    private final int           columns;
    private final int           rows;

    /** Selected cells in one row (inclusive columns), or {@code null}. */
    private GridLocation selection;
    private int          anchorCol;
    private Consumer<GridLocation> onChange = loc -> {};

    // ─────────────────────────────────────────────────────────────────────────

    /**
     * @param occupancy cells taken by the <em>other</em> modules
     * @param initial   starting selection, or {@code null}; ignored if it does not fit
     */
    public GridPicker(GridOccupancy occupancy, GridLocation initial) {
        this.occupancy = occupancy;
        this.columns   = occupancy.totalColumns();

        GridLocation start = (initial != null && occupancy.fits(initial)) ? explicit(initial) : null;
        int lastRow = Math.max(occupancy.maxRow(), start != null ? start.row() : 0);
        this.rows      = lastRow + 1;   // always one empty row at the bottom
        this.selection = start;

        setPreferredSize(new Dimension(GUTTER + columns * 96, HEADER + rows * (CELL_H + GAP)));
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
        return new GridLocation(loc.row(), loc.colStart(), GridOccupancy.lastColumn(loc, columns));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mouse
    // ─────────────────────────────────────────────────────────────────────────

    private void press(Point p) {
        GridOccupancy.Cell cell = cellAt(p);
        if (cell == null || !occupancy.isFree(cell.row(), cell.col())) return;
        anchorCol = cell.col();
        select(new GridLocation(cell.row(), cell.col(), cell.col()));
    }

    /** Extends the selection along the anchor's row, stopping at the first taken cell. */
    private void drag(Point p) {
        if (selection == null) return;
        int row    = selection.row();
        int target = Math.max(1, Math.min(columns, colAt(p.x)));
        int step   = (target >= anchorCol) ? 1 : -1;

        int reach = anchorCol;
        for (int c = anchorCol + step; step > 0 ? c <= target : c >= target; c += step) {
            if (!occupancy.isFree(row, c)) break;
            reach = c;
        }
        select(new GridLocation(row, Math.min(anchorCol, reach), Math.max(anchorCol, reach)));
    }

    private void select(GridLocation loc) {
        if (loc.equals(selection)) return;
        selection = loc;
        repaint();
        onChange.accept(loc);
    }

    @Override
    public String getToolTipText(MouseEvent e) {
        GridOccupancy.Cell cell = cellAt(e.getPoint());
        if (cell == null) return null;
        String owner = occupancy.ownerAt(cell.row(), cell.col());
        return (owner != null) ? owner : "Free: click to place here, drag along the row to span columns";
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

    private GridOccupancy.Cell cellAt(Point p) {
        int col = colAt(p.x);
        int row = (p.y - HEADER) / (CELL_H + GAP) + 1;
        if (p.x < GUTTER || p.y < HEADER || col < 1 || col > columns || row > rows) return null;
        return new GridOccupancy.Cell(row, col);
    }

    private RoundRectangle2D cellRect(int row, int colStart, int colEnd) {
        double cw = cellWidth();
        double x  = GUTTER + (colStart - 1) * cw + GAP / 2.0;
        double y  = HEADER + (row - 1) * (CELL_H + GAP);
        double w  = (colEnd - colStart + 1) * cw - GAP;
        return new RoundRectangle2D.Double(x, y, w, CELL_H, 8, 8);
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
            RoundRectangle2D r = cellRect(1, c, c);
            String text = "C" + c;
            g2.drawString(text, (float) (r.getCenterX() - fm.stringWidth(text) / 2.0), HEADER - 5);
        }
        for (int r = 1; r <= rows; r++) {
            RoundRectangle2D rect = cellRect(r, 1, 1);
            g2.drawString("R" + r, 2, (float) (rect.getCenterY() + fm.getAscent() / 2.0 - 1));
        }

        for (int r = 1; r <= rows; r++) {
            int c = 1;
            while (c <= columns) {
                String owner = occupancy.ownerAt(r, c);
                if (owner == null) {
                    if (!isSelected(r, c)) paintFree(g2, r, c);
                    c++;
                } else {
                    // Merge adjacent cells of the same module into one grey block
                    int end = c;
                    while (end + 1 <= columns && owner.equals(occupancy.ownerAt(r, end + 1))) end++;
                    paintTaken(g2, fm, r, c, end, owner);
                    c = end + 1;
                }
            }
        }

        if (selection != null) {
            g2.setColor(FREE);
            g2.fill(cellRect(selection.row(), selection.colStart(), selection.colEnd()));
        }
        g2.dispose();
    }

    private boolean isSelected(int row, int col) {
        return selection != null && selection.row() == row
            && col >= selection.colStart() && col <= selection.colEnd();
    }

    private void paintFree(Graphics2D g2, int row, int col) {
        RoundRectangle2D r = cellRect(row, col, col);
        g2.setColor(FREE_FILL);
        g2.fill(r);
        g2.setColor(FREE);
        g2.setStroke(new BasicStroke(1.5f));
        g2.draw(r);
    }

    private void paintTaken(Graphics2D g2, FontMetrics fm, int row, int c1, int c2, String owner) {
        RoundRectangle2D r = cellRect(row, c1, c2);
        g2.setColor(TAKEN);
        g2.fill(r);

        String text = owner;
        int max = (int) r.getWidth() - 8;
        while (text.length() > 1 && fm.stringWidth(text) > max) {
            text = text.substring(0, text.length() - 2) + "…";
        }
        g2.setColor(TAKEN_TEXT);
        g2.drawString(text,
            (float) (r.getCenterX() - fm.stringWidth(text) / 2.0),
            (float) (r.getCenterY() + fm.getAscent() / 2.0 - 2));
    }
}
