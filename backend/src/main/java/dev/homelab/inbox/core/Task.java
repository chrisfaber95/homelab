package dev.homelab.inbox.core;

import java.util.Map;

/** One row of the {@code tasks} table, column name to value (what {@code sqlite3.Row} is to the Python inbox). */
public record Task(Map<String, Object> row) {

    public Task {
        row = Map.copyOf(row);
    }

    public long id() {
        return ((Number) row.get("id")).longValue();
    }

    public String str(String column) {
        Object v = row.get(column);
        return v == null ? null : v.toString();
    }

    /** An integer column; NULL reads as 0, the way Python's {@code t["x"] or 0} is used. */
    public int num(String column) {
        return row.get(column) instanceof Number n ? n.intValue() : 0;
    }

    public Integer numOrNull(String column) {
        return row.get(column) instanceof Number n ? n.intValue() : null;
    }

    public Long longOrNull(String column) {
        return row.get(column) instanceof Number n ? n.longValue() : null;
    }

    public String status() {
        return str("status");
    }

    public String repo() {
        return str("repo");
    }

    public boolean has(String column) {
        return row.get(column) != null;
    }
}
