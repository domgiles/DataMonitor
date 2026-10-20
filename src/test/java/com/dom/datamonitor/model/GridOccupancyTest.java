package com.dom.datamonitor.model;

import com.dom.datamonitor.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GridOccupancyTest {

    private static GridLocation loc(String spec) throws ConfigException {
        return GridLocation.parse(spec);
    }

    private static GridOccupancy.Cell cell(int row, int col) {
        return new GridOccupancy.Cell(row, col);
    }

    @Test
    void tracksOwnersAndFreeCells() throws ConfigException {
        GridOccupancy g = new GridOccupancy(3);
        assertTrue(g.occupy(loc("R1,C1-2"), "A").isEmpty());

        assertEquals("A", g.ownerAt(1, 1));
        assertEquals("A", g.ownerAt(1, 2));
        assertTrue(g.isFree(1, 3));
        assertNull(g.ownerAt(1, 3));
        assertEquals(1, g.maxRow());
    }

    @Test
    void spanAllCoversRemainingColumns() throws ConfigException {
        GridOccupancy g = new GridOccupancy(3);
        g.occupy(loc("R2,C2*"), "A");
        assertTrue(g.isFree(2, 1));
        assertFalse(g.isFree(2, 2));
        assertFalse(g.isFree(2, 3));
    }

    @Test
    void reportsConflictsAndKeepsFirstOwner() throws ConfigException {
        GridOccupancy g = new GridOccupancy(3);
        g.occupy(loc("R1,C2"), "A");
        List<GridOccupancy.Cell> conflicts = g.occupy(loc("R1,C1*"), "B");

        assertEquals(List.of(new GridOccupancy.Cell(1, 2)), conflicts);
        assertEquals("A", g.ownerAt(1, 2));
        assertEquals("B", g.ownerAt(1, 3));
    }

    @Test
    void fitsRequiresFreeCellsInsideTheGrid() throws ConfigException {
        GridOccupancy g = new GridOccupancy(3);
        g.occupy(loc("R1,C1"), "A");

        assertTrue(g.fits(loc("R1,C2-3")));
        assertTrue(g.fits(loc("R5,C1*")));
        assertFalse(g.fits(loc("R1,C1-2")));
        assertFalse(g.fits(loc("R2,C3-4")));
        assertFalse(g.fits(loc("R2,C4*")));
    }

    @Test
    void multiRowLocationsOccupyEveryRow() throws ConfigException {
        GridOccupancy g = new GridOccupancy(3);
        assertTrue(g.occupy(loc("R1-3,C2-3"), "A").isEmpty());

        for (int r = 1; r <= 3; r++) {
            assertTrue(g.isFree(r, 1));
            assertEquals("A", g.ownerAt(r, 2));
            assertEquals("A", g.ownerAt(r, 3));
        }
        assertTrue(g.isFree(4, 2));
        assertEquals(3, g.maxRow());

        assertTrue(g.fits(loc("R1-3,C1")));
        assertFalse(g.fits(loc("R3-4,C2")));
        assertEquals(List.of(new GridOccupancy.Cell(3, 3)), g.occupy(loc("R3-4,C3"), "B"));
    }

    @Test
    void placementsKeepEachModuleAsOneBlock() throws ConfigException {
        GridOccupancy g = new GridOccupancy(3);
        g.occupy(loc("R1-2,C2*"), "Same title");
        g.occupy(loc("R3,C2-3"), "Same title");

        assertEquals(List.of(
            new GridOccupancy.Placement(new GridLocation(1, 2, 3, 2), "Same title"),
            new GridOccupancy.Placement(new GridLocation(3, 2, 3, 3), "Same title")), g.placements());
    }

    @Test
    void largestFreeRectangleStopsAtTakenCells() throws ConfigException {
        GridOccupancy g = new GridOccupancy(3);
        g.occupy(loc("R1,C1"), "A");
        g.occupy(loc("R3,C3"), "B");

        assertEquals(loc("R1-2,C2-3"), g.largestFreeRectangle(cell(1, 2), cell(2, 3)));
        // R2,C1 to R3,C3 would include B; the largest free part is the 2 x 2 block on the left
        assertEquals(loc("R2-3,C1-2"), g.largestFreeRectangle(cell(2, 1), cell(3, 3)));
        // Upwards and leftwards
        assertEquals(loc("R1-2,C2-3"), g.largestFreeRectangle(cell(2, 3), cell(1, 1)));
        // Single cell, and a taken anchor
        assertEquals(loc("R4,C2"), g.largestFreeRectangle(cell(4, 2), cell(4, 2)));
        assertNull(g.largestFreeRectangle(cell(1, 1), cell(2, 2)));
    }

    @Test
    void lastColumnHandlesSpanAllBeyondGrid() throws ConfigException {
        assertEquals(3, GridOccupancy.lastColumn(loc("R1,C1*"), 3));
        assertEquals(4, GridOccupancy.lastColumn(loc("R1,C4*"), 3));
        assertEquals(2, GridOccupancy.lastColumn(loc("R1,C1-2"), 3));
    }

    @Test
    void toSpecRoundTrips() throws ConfigException {
        for (String spec : List.of("R1,C1", "R2,C1-3", "R3,C2*", "R1-2,C1", "R2-4,C2-3", "R1-3,C1*")) {
            assertEquals(spec, loc(spec).toSpec());
        }
    }
}
