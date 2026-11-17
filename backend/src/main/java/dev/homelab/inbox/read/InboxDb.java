package dev.homelab.inbox.read;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteConfig;

/**
 * Read-only access to the Python inbox's {@code inbox.db} (H-024 M1). The Python inbox stays the only writer of every
 * table in M1, so the routes read the live file instead of the PostgreSQL copy (which is a one-off import and would
 * be stale at once). The connection is opened read-only with a busy timeout, one per query, and nothing here
 * can write. A missing file or a missing table reads as "no rows", as an empty inbox would.
 */
@Component
public class InboxDb {

    private final Settings settings;

    public InboxDb(Settings settings) {
        this.settings = settings;
    }

    public List<Map<String, Object>> query(String sql, Object... args) {
        Path file = settings.inboxDb();
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(10_000);
        try (Connection c = config.createConnection("jdbc:sqlite:" + file);
                PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Map<String, Object>> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        } catch (SQLException e) {
            if (String.valueOf(e.getMessage()).contains("no such table")) {
                return List.of();
            }
            throw new IllegalStateException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    public Map<String, Object> one(String sql, Object... args) {
        List<Map<String, Object>> rows = query(sql, args);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Text of a column; null stays null. */
    public static String str(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v == null ? null : v.toString();
    }

    /** Python truthiness of a text column: null and "" are false. */
    public static boolean has(Map<String, Object> row, String key) {
        String v = str(row, key);
        return v != null && !v.isEmpty();
    }

    public static Long num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.longValue() : null;
    }
}
