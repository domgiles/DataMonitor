package com.dom.datamonitor.charts;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;

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
}
