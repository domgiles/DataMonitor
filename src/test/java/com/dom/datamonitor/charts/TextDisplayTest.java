package com.dom.datamonitor.charts;

import com.dom.datamonitor.model.QueryResult;
import com.dom.datamonitor.model.QueryRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TextDisplayTest {

    private static String labelFor(String title, String key) {
        TextDisplay display = new TextDisplay(Theme.DARK, false, title);
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
}
