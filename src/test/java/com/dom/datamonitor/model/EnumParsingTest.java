package com.dom.datamonitor.model;

import com.dom.datamonitor.config.ConfigException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EnumParsingTest {

    @Test
    void chartTypeIsCaseInsensitive() throws ConfigException {
        assertEquals(ChartType.LINE,  ChartType.fromString("line"));
        assertEquals(ChartType.TABLE, ChartType.fromString(" Table "));
    }

    @Test
    void chartTypeRejectsBlankAndUnknown() {
        assertThrows(ConfigException.class, () -> ChartType.fromString(null));
        assertThrows(ConfigException.class, () -> ChartType.fromString(" "));
        assertThrows(ConfigException.class, () -> ChartType.fromString("SCATTER"));
    }

    @Test
    void onlyLineAndAreaAreTimeSeries() {
        for (ChartType t : ChartType.values()) {
            assertEquals(t == ChartType.LINE || t == ChartType.AREA, t.isTimeSeries(), t.name());
        }
    }

    @Test
    void processingModeDefaultsToNone() throws ConfigException {
        assertEquals(ProcessingMode.NONE, ProcessingMode.fromString(null));
        assertEquals(ProcessingMode.NONE, ProcessingMode.fromString(""));
    }

    @Test
    void processingModeParsesAllValues() throws ConfigException {
        for (ProcessingMode m : ProcessingMode.values()) {
            assertEquals(m, ProcessingMode.fromString(m.name().toLowerCase()));
        }
    }

    @Test
    void processingModeRejectsUnknown() {
        assertThrows(ConfigException.class, () -> ProcessingMode.fromString("AVERAGE"));
    }
}
