package com.dom.datamonitor.model;

import com.dom.datamonitor.config.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class GridLocationTest {

    @Test
    void parsesSingleColumn() throws ConfigException {
        GridLocation loc = GridLocation.parse("R2,C3");
        assertEquals(new GridLocation(2, 3, 3), loc);
        assertEquals(1, loc.columnSpan(3));
        assertEquals(2, loc.gridBagX());
        assertEquals(1, loc.gridBagY());
    }

    @Test
    void parsesColumnRange() throws ConfigException {
        GridLocation loc = GridLocation.parse("R1,C1-2");
        assertEquals(new GridLocation(1, 1, 2), loc);
        assertEquals(2, loc.columnSpan(3));
    }

    @Test
    void parsesSpanAll() throws ConfigException {
        GridLocation loc = GridLocation.parse("R3,C2*");
        assertEquals(new GridLocation(3, 2, GridLocation.SPAN_ALL), loc);
        assertEquals(2, loc.columnSpan(3));
    }

    @Test
    void bareStarSpansFromFirstColumn() throws ConfigException {
        assertEquals(new GridLocation(1, 1, GridLocation.SPAN_ALL), GridLocation.parse("R1,C*"));
    }

    @Test
    void isCaseInsensitiveAndTrimsWhitespace() throws ConfigException {
        assertEquals(new GridLocation(4, 1, 3), GridLocation.parse("  r4 , c1-3 "));
    }

    @Test
    void singleRowSpecsSpanOneRow() throws ConfigException {
        GridLocation loc = GridLocation.parse("R2,C1-2");
        assertEquals(2, loc.rowEnd());
        assertEquals(1, loc.rowSpan());
        assertEquals(new GridLocation(2, 1, 2, 2), loc);
    }

    @Test
    void parsesRowRange() throws ConfigException {
        GridLocation loc = GridLocation.parse("R2-4,C3");
        assertEquals(new GridLocation(2, 3, 3, 4), loc);
        assertEquals(3, loc.rowSpan());
        assertEquals(1, loc.gridBagY());
    }

    @Test
    void parsesRowRangeWithColumnRangeAndSpanAll() throws ConfigException {
        assertEquals(new GridLocation(1, 2, 3, 2), GridLocation.parse("R1-2,C2-3"));
        assertEquals(new GridLocation(3, 1, GridLocation.SPAN_ALL, 5), GridLocation.parse(" r3 - 5 , c* "));
    }

    @Test
    void singleRowRangeIsAllowed() throws ConfigException {
        assertEquals(new GridLocation(2, 1, 1), GridLocation.parse("R2-2,C1"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"R1", "X1,C1", "R1,X1", "R0,C1", "Rx,C1", "R1,C0", "R1,C3-1", "R1,Cx", "R1,C1-x",
                            "R2-1,C1", "R0-2,C1", "R1-x,C1", "R1-,C1", "R-2,C1", "R*,C1"})
    void rejectsInvalidSpecs(String spec) {
        assertThrows(ConfigException.class, () -> GridLocation.parse(spec));
    }
}
