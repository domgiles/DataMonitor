package com.dom.datamonitor.model;

import com.dom.datamonitor.config.ConfigException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GridOccupancyTest {

    private static GridLocation loc(String spec) throws ConfigException {
        return GridLocation.parse(spec);
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
    void lastColumnHandlesSpanAllBeyondGrid() throws ConfigException {
        assertEquals(3, GridOccupancy.lastColumn(loc("R1,C1*"), 3));
        assertEquals(4, GridOccupancy.lastColumn(loc("R1,C4*"), 3));
        assertEquals(2, GridOccupancy.lastColumn(loc("R1,C1-2"), 3));
    }

    @Test
    void toSpecRoundTrips() throws ConfigException {
        for (String spec : List.of("R1,C1", "R2,C1-3", "R3,C2*")) {
            assertEquals(spec, loc(spec).toSpec());
        }
    }
}
