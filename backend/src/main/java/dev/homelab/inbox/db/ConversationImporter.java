package dev.homelab.inbox.db;

import dev.homelab.inbox.conversation.ConversationStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.sqlite.SQLiteConfig;

/**
 * The fresh import of the conversation store (H-024 M4): SQLite {@code inbox.db} -> PostgreSQL, and back for the
 * rollback. Moves exactly the data this store owns: {@code conversation}, {@code conversation_refusals} and the
 * {@code switch:*} keys of {@code kv}; the other tables are left alone (the other stores are still Python's).
 *
 * <p>Import: one PostgreSQL transaction (tables locked, old rows replaced, counts compared, identity sequences reset,
 * and the marker row {@link ConversationStore#MARKER} written last); any failure rolls back. Once the marker is there
 * Java has owned the store and may hold newer data than SQLite, so a plain import refuses ({@code replace} is only for
 * redoing the import before the switch). Export: one SQLite transaction that replaces the same three sets; run it with
 * the Python inbox stopped.
 */
public final class ConversationImporter {

    /** Nothing was written: the store is already Java's (marker present) and no replace was asked for. */
    public static final class AlreadyImported extends RuntimeException {
        AlreadyImported(String message) {
            super(message);
        }
    }

    public record Counts(long conversation, long refusals, long switches) {
    }

    private static final String SWITCH_LIKE = "switch:%";

    private ConversationImporter() {
    }

    /** SQLite -> PostgreSQL. */
    public static Counts importFrom(String sqlitePath, String pgUrl, String pgUser, boolean replace) throws SQLException {
        Flyway.configure().dataSource(pgUrl, pgUser, null).load().migrate();
        Properties props = new Properties();
        props.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        try (Connection lite = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath, props);
                Connection pg = DriverManager.getConnection(pgUrl, pgUser, null)) {
            lite.setAutoCommit(false);
            pg.setAutoCommit(false);
            try {
                Counts counts = load(lite, pg, replace);
                pg.commit();
                return counts;
            } catch (SQLException | RuntimeException e) {
                pg.rollback();
                throw e;
            } finally {
                lite.rollback();
            }
        }
    }

    private static Counts load(Connection lite, Connection pg, boolean replace) throws SQLException {
        try (Statement s = pg.createStatement()) {
            s.execute("LOCK TABLE conversation, conversation_refusals, kv IN ACCESS EXCLUSIVE MODE");
        }
        if (scalar(pg, "SELECT count(*) FROM kv WHERE key = '" + ConversationStore.MARKER + "'") > 0) {
            if (!replace) {
                throw new AlreadyImported("the conversation store is already Java's (marker " + ConversationStore.MARKER
                        + "); nothing was written");
            }
        }
        if (!replace && scalar(pg, "SELECT (SELECT count(*) FROM conversation) + (SELECT count(*) FROM conversation_refusals)"
                + " + (SELECT count(*) FROM kv WHERE key LIKE '" + SWITCH_LIKE + "')") > 0) {
            throw new IllegalStateException("the target holds conversation rows but no marker; use --replace to overwrite them");
        }
        try (Statement s = pg.createStatement()) {
            s.execute("DELETE FROM conversation");
            s.execute("DELETE FROM conversation_refusals");
            s.execute("DELETE FROM kv WHERE key LIKE '" + SWITCH_LIKE + "' OR key = '" + ConversationStore.MARKER + "'");
        }
        long conversation = copy(lite, pg, "conversation", List.of("id", "time", "author", "client", "kind", "text", "link", "ref"),
                "id", true, null);
        long refusals = copy(lite, pg, "conversation_refusals", List.of("id", "time", "client", "action", "why"), "id", true, null);
        long switches = copy(lite, pg, "kv", List.of("key", "value"), "key", false, "key LIKE '" + SWITCH_LIKE + "'");
        for (var e : List.of(java.util.Map.entry("conversation", conversation),
                java.util.Map.entry("conversation_refusals", refusals))) {
            long inPg = scalar(pg, "SELECT count(*) FROM " + e.getKey());
            if (inPg != e.getValue()) {
                throw new IllegalStateException("MISMATCH " + e.getKey() + ": sqlite=" + e.getValue() + " postgres=" + inPg);
            }
        }
        long switchesPg = scalar(pg, "SELECT count(*) FROM kv WHERE key LIKE '" + SWITCH_LIKE + "'");
        if (switchesPg != switches) {
            throw new IllegalStateException("MISMATCH kv switches: sqlite=" + switches + " postgres=" + switchesPg);
        }
        for (String t : List.of("conversation", "conversation_refusals")) {
            try (Statement s = pg.createStatement()) {
                s.execute("SELECT setval(pg_get_serial_sequence('" + t + "', 'id'), coalesce(max(id), 0) + 1, false) FROM " + t);
            }
        }
        try (PreparedStatement ps = pg.prepareStatement("INSERT INTO kv (key, value) VALUES (?, 'java')")) {
            ps.setString(1, ConversationStore.MARKER);
            ps.executeUpdate();
        }
        return new Counts(conversation, refusals, switches);
    }

    /** Copies a table (or the rows matching {@code where}); a table SQLite does not have yet counts as empty. */
    private static long copy(Connection lite, Connection pg, String table, List<String> cols, String order, boolean identity,
            String where) throws SQLException {
        if (scalar(lite, "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'") == 0) {
            return 0;
        }
        String names = String.join(", ", cols);
        String marks = String.join(", ", cols.stream().map(c -> "?").toList());
        String insert = "INSERT INTO " + table + " (" + names + ")" + (identity ? " OVERRIDING SYSTEM VALUE" : "")
                + " VALUES (" + marks + ")";
        long rows = 0;
        try (Statement read = lite.createStatement();
                ResultSet rs = read.executeQuery("SELECT " + names + " FROM " + table + (where == null ? "" : " WHERE " + where)
                        + " ORDER BY " + order);
                PreparedStatement ps = pg.prepareStatement(insert)) {
            while (rs.next()) {
                for (int i = 0; i < cols.size(); i++) {
                    Object v = rs.getObject(i + 1);
                    if (v == null) {
                        ps.setNull(i + 1, java.sql.Types.VARCHAR);
                    } else if (cols.get(i).equals("id")) {
                        ps.setInt(i + 1, ((Number) v).intValue());
                    } else if (v instanceof byte[]) {
                        throw new IllegalStateException("blob in text column " + table + "." + cols.get(i));
                    } else {
                        ps.setString(i + 1, v.toString());
                    }
                }
                ps.addBatch();
                rows++;
                if (rows % 500 == 0) {
                    ps.executeBatch();
                }
            }
            ps.executeBatch();
        }
        return rows;
    }

    /** PostgreSQL -> SQLite, for the rollback: replaces the same three sets in one SQLite transaction. */
    public static Counts exportTo(String sqlitePath, String pgUrl, String pgUser) throws SQLException {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(10_000);
        try (Connection lite = config.createConnection("jdbc:sqlite:" + sqlitePath);
                Connection pg = DriverManager.getConnection(pgUrl, pgUser, null)) {
            pg.setAutoCommit(false);
            lite.setAutoCommit(false);
            try {
                if (scalar(pg, "SELECT count(*) FROM kv WHERE key = '" + ConversationStore.MARKER + "'") == 0) {
                    throw new IllegalStateException("PostgreSQL has no conversation store (no marker): nothing to export");
                }
                // The first DELETE below takes SQLite's write lock: with the Python inbox running it fails busy (stop it first).
                try (Statement s = lite.createStatement()) {
                    s.execute("CREATE TABLE IF NOT EXISTS conversation (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL,"
                            + " author TEXT NOT NULL, client TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, link TEXT, ref TEXT)");
                    s.execute("CREATE TABLE IF NOT EXISTS conversation_refusals (id INTEGER PRIMARY KEY AUTOINCREMENT,"
                            + " time TEXT NOT NULL, client TEXT NOT NULL, action TEXT NOT NULL, why TEXT NOT NULL)");
                    s.execute("CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT)");
                    s.execute("DELETE FROM conversation");
                    s.execute("DELETE FROM conversation_refusals");
                    s.execute("DELETE FROM kv WHERE key LIKE '" + SWITCH_LIKE + "'");
                }
                long conversation = back(pg, lite, "conversation", List.of("id", "time", "author", "client", "kind", "text", "link", "ref"),
                        "id", null);
                long refusals = back(pg, lite, "conversation_refusals", List.of("id", "time", "client", "action", "why"), "id", null);
                long switches = back(pg, lite, "kv", List.of("key", "value"), "key", "key LIKE '" + SWITCH_LIKE + "'");
                for (var e : List.of(java.util.Map.entry("conversation", conversation),
                        java.util.Map.entry("conversation_refusals", refusals))) {
                    long inLite = scalar(lite, "SELECT count(*) FROM " + e.getKey());
                    if (inLite != e.getValue()) {
                        throw new IllegalStateException("MISMATCH " + e.getKey() + ": postgres=" + e.getValue() + " sqlite=" + inLite);
                    }
                }
                lite.commit();
                return new Counts(conversation, refusals, switches);
            } catch (SQLException | RuntimeException e) {
                lite.rollback();
                throw e;
            } finally {
                pg.rollback();
            }
        }
    }

    private static long back(Connection pg, Connection lite, String table, List<String> cols, String order, String where)
            throws SQLException {
        String names = String.join(", ", cols);
        String insert = "INSERT INTO " + table + " (" + names + ") VALUES (" + String.join(", ", cols.stream().map(c -> "?").toList()) + ")";
        long rows = 0;
        try (Statement read = pg.createStatement();
                ResultSet rs = read.executeQuery("SELECT " + names + " FROM " + table + (where == null ? "" : " WHERE " + where)
                        + " ORDER BY " + order);
                PreparedStatement ps = lite.prepareStatement(insert)) {
            while (rs.next()) {
                for (int i = 0; i < cols.size(); i++) {
                    Object v = rs.getObject(i + 1);
                    if (v == null) {
                        ps.setNull(i + 1, java.sql.Types.VARCHAR);
                    } else if (cols.get(i).equals("id")) {
                        ps.setInt(i + 1, ((Number) v).intValue());
                    } else {
                        ps.setString(i + 1, v.toString());
                    }
                }
                ps.addBatch();
                rows++;
            }
            ps.executeBatch();
        }
        return rows;
    }

    private static long scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
