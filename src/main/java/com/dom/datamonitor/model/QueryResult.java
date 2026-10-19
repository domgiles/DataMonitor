package com.dom.datamonitor.model;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * Immutable result from one execution of a monitored SQL query.
 * May represent a successful data set or an error condition.
 */
public final class QueryResult {

    private final List<QueryRow> rows;
    private final Instant        timestamp;
    private final boolean        error;
    private final String         errorMessage;

    private QueryResult(List<QueryRow> rows, Instant timestamp, boolean error, String errorMessage) {
        this.rows         = List.copyOf(rows);
        this.timestamp    = timestamp;
        this.error        = error;
        this.errorMessage = errorMessage;
    }

    // --- Factory methods ---

    public static QueryResult success(List<QueryRow> rows) {
        return new QueryResult(rows, Instant.now(), false, null);
    }

    public static QueryResult error(String message) {
        return new QueryResult(Collections.emptyList(), Instant.now(), true, message);
    }

    // --- Accessors ---

    public List<QueryRow> rows()         { return rows;         }
    public Instant        timestamp()    { return timestamp;    }
    public boolean        isError()      { return error;        }
    public String         errorMessage() { return errorMessage; }
    public boolean        isEmpty()      { return rows.isEmpty(); }
    public int            size()         { return rows.size();  }
}
