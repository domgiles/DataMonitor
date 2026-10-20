package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextDisplayTest {

    private static String labelFor(String title, String key) {
        TextDisplay display = new TextDisplay(Theme.DARK, false, title, 120);
        display.updateData(QueryResult.success(List.of(new QueryRow(key, 42, null))));
        return display.label();
    }

    @Test
    void labelRepeatingTheTitleIsHidden() {
        assertEquals("", labelFor("Tx/Sec", "Tx/Sec"));
        assertEquals("", labelFor(" Active Sessions ", "active sessions"));
    }

    @Test
    void labelThatAddsInformationIsShown() {
        assertEquals("Checkpoints", labelFor("Checkpoints Since Start", "Checkpoints"));
        assertEquals("Transactions", labelFor(null, "Transactions"));
    }

    @Test
    void deltaShowsTheChangeWithDirection() {
        TextDisplay display = new TextDisplay(Theme.DARK, false, "Tx/Sec", 120);
        display.updateData(QueryResult.success(List.of(new QueryRow("Tx/Sec", 10, null))));
        assertEquals("", display.deltaText(), "no change to show after the first reading");

        display.updateData(QueryResult.success(List.of(new QueryRow("Tx/Sec", 30.5, null))));
        assertEquals("▲ +20.50", display.deltaText());
        display.updateData(QueryResult.success(List.of(new QueryRow("Tx/Sec", 29.5, null))));
        assertEquals("▼ -1", display.deltaText());
        display.updateData(QueryResult.success(List.of(new QueryRow("Tx/Sec", 29.5, null))));
        assertEquals("± 0", display.deltaText());
    }
}
