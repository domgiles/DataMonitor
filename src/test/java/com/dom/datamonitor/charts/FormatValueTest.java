package com.dom.datamonitor.charts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Swing number formatting follows the web front end's formatValue (charts.js); both use the locale's conventions (en-GB writes 3.4m). */
class FormatValueTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "0           | 0",
        "512.5       | 513",       // a DIFFERENCE rate over 2 s: rounded like the browser, not 512.50
        "1284        | 1,284",
        "999999.4    | 999,999",
        "12.5        | 12.5",
        "12.04       | 12",
        "3.14159     | 3.14",
        "2.0         | 2",
        "0.004123    | 0.00412",
        "0.125       | 0.125",
        "0.1         | 0.1",
        "3400000     | 3.4M",
        "125234567   | 125.2M",
        "-1          | -1",
        "-512.5      | -513",
    })
    void matchesTheWebFormatting(double value, String expected) {
        assertEquals(expected, BaseChart.formatValue(value, Locale.US));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "0       | 0",
        "0.002   | 0.002",
        "2.5     | 2.5",
        "67.47   | 67",
        "300     | 300",
        "12000   | 12K",
        "3400000 | 3.4M",
    })
    void axisLabelsMatchTheWebFormatCompact(double value, String expected) {
        assertEquals(expected, BaseChart.formatCompact(value, Locale.US));
    }

    @Test
    void ticksUseRoundSteps() {
        // The cases from the web screenshots: 0-400 by 100, 0-40 by 10
        assertArrayEquals(new double[] {0, 100, 200, 300, 400}, BaseChart.niceTicks(370), 1e-9);
        assertArrayEquals(new double[] {0, 10, 20, 30, 40}, BaseChart.niceTicks(38), 1e-9);
        assertArrayEquals(new double[] {0, 0.5, 1, 1.5}, BaseChart.niceTicks(1.43), 1e-9);
        assertArrayEquals(new double[] {0, 0.25, 0.5, 0.75, 1}, BaseChart.niceTicks(1), 1e-9);
        assertArrayEquals(new double[] {0, 200, 400, 600}, BaseChart.niceTicks(512), 1e-9);
        // Nothing to scale yet
        assertArrayEquals(new double[] {0, 0.25, 0.5, 0.75, 1}, BaseChart.niceTicks(0), 1e-9);
    }
}
