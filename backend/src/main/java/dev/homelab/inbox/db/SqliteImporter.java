package dev.homelab.inbox.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import org.flywaydb.core.Flyway;

/**
 * One-off load of the old SQLite {@code inbox.db} into the PostgreSQL database {@code inbox} (H-023 B5).
 *
 * <p>SQLite is opened read-only (SQLITE_OPEN_READONLY) and read in one read transaction, so the copy is one consistent
 * snapshot and the live Python inbox is never blocked or changed. PostgreSQL gets everything in one transaction: the
 * tables are locked, must all be empty (else nothing is written), are filled, compared by row count with the SQLite
 * snapshot, and only then the identity sequences are reset and the transaction is committed. Any failure or count
 * mismatch rolls back.
 */
public final class SqliteImporter {

    /** The nine tables of {@code V1__baseline.sql}, in load order. */
    static final List<String> TABLES = List.of("tasks", "job_runs", "job_state", "drafts", "skill_prs", "kv",
            "conversation", "conversation_refusals", "approvals");

    private static final int BATCH = 500;

    /** Target already holds data: nothing was written. */
    public static final class TargetNotEmptyException extends RuntimeException {
        TargetNotEmptyException(String message) {
            super(message);
        }
    }

    /** Row counts differ (or the import failed): everything was rolled back. */
    public static final class ImportFailedException extends RuntimeException {
        ImportFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Rows per table, SQLite and PostgreSQL. */
    public record Counts(Map<String, Long> sqlite, Map<String, Long> postgres) {
    }

    private final String sqlitePath;
    private final String pgUrl;
    private final String pgUser;
    private final String pgPassword;
    private final Consumer<Connection> beforeCompare;

    public SqliteImporter(String sqlitePath, String pgUrl, String pgUser, String pgPassword) {
        this(sqlitePath, pgUrl, pgUser, pgPassword, c -> { });
    }

    /** {@code beforeCompare} runs in the import transaction after the load; the tests use it to force a mismatch. */
    SqliteImporter(String sqlitePath, String pgUrl, String pgUser, String pgPassword,
            Consumer<Connection> beforeCompare) {
        this.sqlitePath = sqlitePath;
        this.pgUrl = pgUrl;
        this.pgUser = pgUser;
        this.pgPassword = pgPassword;
        this.beforeCompare = beforeCompare;
    }

    public Counts run() throws SQLException {
        // The schema is the Flyway baseline: apply it when the database is new (a no-op when it is already there).
        Flyway.configure().dataSource(pgUrl, pgUser, pgPassword).load().migrate();

        Properties sqliteProps = new Properties();
        sqliteProps.setProperty("open_mode", "1"); // SQLITE_OPEN_READONLY
        try (Connection lite = DriverManager.getConnection("jdbc:sqlite:" + sqlitePath, sqliteProps);
                Connection pg = DriverManager.getConnection(pgUrl, pgUser, pgPassword)) {
            lite.setAutoCommit(false); // one read transaction: counts and rows come from the same snapshot
            pg.setAutoCommit(false);
            try {
                Counts counts = load(lite, pg);
                pg.commit();
                return counts;
            } catch (TargetNotEmptyException e) {
                pg.rollback();
                throw e;
            } catch (ImportFailedException e) {
                pg.rollback();
                throw e;
            } catch (SQLException | RuntimeException e) {
                pg.rollback();
                throw new ImportFailedException("import failed, rolled back: " + e.getMessage(), e);
            } finally {
                lite.rollback(); // ends the read transaction; there is nothing to write
            }
        }
    }

    private Counts load(Connection lite, Connection pg) throws SQLException {
        Map<String, List<Col>> targetColumns = new LinkedHashMap<>();
        for (String table : TABLES) {
            targetColumns.put(table, pgColumns(pg, table));
        }
        checkSqliteTables(lite, targetColumns);

        try (Statement s = pg.createStatement()) {
            s.execute("LOCK TABLE " + String.join(", ", TABLES) + " IN ACCESS EXCLUSIVE MODE");
        }
        for (String table : TABLES) {
            long n = count(pg, table);
            if (n != 0) {
                throw new TargetNotEmptyException("target is not empty: " + table + " has " + n
                        + " rows; refusing to import (nothing was written)");
            }
        }

        Map<String, Long> sqliteCounts = new LinkedHashMap<>();
        for (String table : TABLES) {
            sqliteCounts.put(table, count(lite, table));
            copy(lite, pg, table, targetColumns.get(table));
        }
        beforeCompare.accept(pg);

        Map<String, Long> pgCounts = new LinkedHashMap<>();
        StringBuilder mismatches = new StringBuilder();
        for (String table : TABLES) {
            long inPg = count(pg, table);
            pgCounts.put(table, inPg);
            if (inPg != sqliteCounts.get(table)) {
                mismatches.append("MISMATCH ").append(table).append(": sqlite=").append(sqliteCounts.get(table))
                        .append(" postgres=").append(inPg).append('\n');
            }
        }
        if (mismatches.length() > 0) {
            throw new ImportFailedException(mismatches + "rolled back, nothing was imported", null);
        }
        resetIdentities(pg, targetColumns);
        return new Counts(sqliteCounts, pgCounts);
    }

    private record Col(String name, String type, boolean identity) {
    }

    private static List<Col> pgColumns(Connection pg, String table) throws SQLException {
        List<Col> cols = new ArrayList<>();
        try (PreparedStatement ps = pg.prepareStatement("SELECT column_name, data_type, is_identity "
                + "FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? "
                + "ORDER BY ordinal_position")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(new Col(rs.getString(1), rs.getString(2), "YES".equals(rs.getString(3))));
                }
            }
        }
        if (cols.isEmpty()) {
            throw new IllegalStateException("table " + table + " is missing in PostgreSQL (Flyway baseline not applied?)");
        }
        return cols;
    }

    /** Every SQLite table must be one of the nine and have no column PostgreSQL lacks: else data would be dropped. */
    private static void checkSqliteTables(Connection lite, Map<String, List<Col>> target) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement s = lite.createStatement();
                ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' "
                        + "AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        for (String t : tables) {
            if (!target.containsKey(t)) {
                throw new IllegalStateException("SQLite has a table that is not imported: " + t);
            }
        }
        for (String t : TABLES) {
            if (!tables.contains(t)) {
                throw new IllegalStateException("SQLite has no table " + t);
            }
            List<String> known = target.get(t).stream().map(Col::name).toList();
            for (String c : sqliteColumns(lite, t)) {
                if (!known.contains(c)) {
                    throw new IllegalStateException("SQLite column " + t + "." + c + " does not exist in PostgreSQL");
                }
            }
        }
    }

    private static List<String> sqliteColumns(Connection lite, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Statement s = lite.createStatement(); ResultSet rs = s.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void copy(Connection lite, Connection pg, String table, List<Col> targetCols) throws SQLException {
        // Columns SQLite lacks (an older schema) keep their PostgreSQL default.
        List<String> inSqlite = sqliteColumns(lite, table);
        List<Col> cols = targetCols.stream().filter(c -> inSqlite.contains(c.name())).toList();
        String names = String.join(", ", cols.stream().map(Col::name).toList());
        String marks = String.join(", ", cols.stream().map(c -> "?").toList());
        // The old ids are kept: identity columns are BY DEFAULT, but say so explicitly.
        String overriding = cols.stream().anyMatch(Col::identity) ? " OVERRIDING SYSTEM VALUE" : "";
        String insert = "INSERT INTO " + table + " (" + names + ")" + overriding + " VALUES (" + marks + ")";
        try (Statement read = lite.createStatement();
                ResultSet rs = read.executeQuery("SELECT " + names + " FROM " + table);
                PreparedStatement ps = pg.prepareStatement(insert)) {
            int pending = 0;
            long row = 0;
            while (rs.next()) {
                row++;
                for (int i = 0; i < cols.size(); i++) {
                    bind(ps, i + 1, cols.get(i), rs.getObject(i + 1), table, row);
                }
                ps.addBatch();
                if (++pending == BATCH) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
        }
    }

    /** SQLite is dynamically typed: a value that does not fit the PostgreSQL column fails loudly, never converts. */
    private static void bind(PreparedStatement ps, int i, Col col, Object v, String table, long row)
            throws SQLException {
        String where = table + "." + col.name() + " row " + row;
        switch (col.type()) {
            case "text" -> {
                if (v == null) {
                    ps.setNull(i, java.sql.Types.VARCHAR);
                } else if (v instanceof byte[]) {
                    throw new IllegalStateException("blob in text column " + where);
                } else {
                    ps.setString(i, v.toString());
                }
            }
            case "integer" -> {
                if (v == null) {
                    ps.setNull(i, java.sql.Types.INTEGER);
                } else if (v instanceof Number n && !(v instanceof Double) && !(v instanceof Float)
                        && n.longValue() >= Integer.MIN_VALUE && n.longValue() <= Integer.MAX_VALUE) {
                    ps.setInt(i, n.intValue());
                } else {
                    throw new IllegalStateException("value " + v + " does not fit integer column " + where);
                }
            }
            case "double precision" -> {
                if (v == null) {
                    ps.setNull(i, java.sql.Types.DOUBLE);
                } else if (v instanceof Number n) {
                    ps.setDouble(i, n.doubleValue());
                } else {
                    throw new IllegalStateException("value " + v + " is not a number in column " + where);
                }
            }
            default -> throw new IllegalStateException("unsupported column type " + col.type() + " in " + where);
        }
    }

    /** The next generated id must come after the imported ones (setval is not transactional: done last). */
    private static void resetIdentities(Connection pg, Map<String, List<Col>> columns) throws SQLException {
        for (var e : columns.entrySet()) {
            for (Col c : e.getValue()) {
                if (c.identity()) {
                    String sql = "SELECT setval(pg_get_serial_sequence('" + e.getKey() + "', '" + c.name() + "'), "
                            + "coalesce(max(" + c.name() + "), 0) + 1, false) FROM " + e.getKey();
                    try (Statement s = pg.createStatement()) {
                        s.execute(sql);
                    }
                }
            }
        }
    }
}
