package dev.homelab.inbox.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The one-off import (B5) against a small SQLite fixture and a throwaway cluster (Q5). */
class SqliteImporterTest {

    private static ThrowawayPostgres pg;
    private static final AtomicInteger DBS = new AtomicInteger();

    @TempDir
    Path tmp;

    @BeforeAll
    static void start() throws Exception {
        pg = ThrowawayPostgres.start();
    }

    @AfterAll
    static void stop() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    /** A new empty database (no tables yet: the importer applies the Flyway baseline itself). */
    private static String newDatabase() throws Exception {
        String name = "imp" + DBS.incrementAndGet();
        try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.user(), null); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        return pg.jdbcUrl(name);
    }

    /** The nine tables as SQLite has them (a few columns only: the importer leaves the others at their default). */
    private Path fixture() throws Exception {
        Path file = tmp.resolve("inbox.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE tasks (id INTEGER PRIMARY KEY, repo TEXT NOT NULL, text TEXT NOT NULL, "
                    + "status TEXT NOT NULL, created TEXT NOT NULL, session TEXT NOT NULL, rounds INTEGER DEFAULT 0, "
                    + "note TEXT, review_ok INTEGER)");
            s.execute("INSERT INTO tasks (id, repo, text, status, created, session, note, review_ok) VALUES "
                    + "(7, 'homelab', 'één', 'done', '2026-11-17T10:00:00+00:00', 'abc', 'ünïcode ✓', 1), "
                    + "(42, 'homelab', 'twee', 'queued', '2026-11-17T11:00:00+00:00', 'def', NULL, NULL)");
            s.execute("CREATE TABLE job_runs (id INTEGER PRIMARY KEY, job TEXT NOT NULL, created TEXT NOT NULL, "
                    + "status TEXT NOT NULL, task_id INTEGER)");
            s.execute("INSERT INTO job_runs VALUES (3, 'nightly', 't', 'ok', 7)");
            s.execute("CREATE TABLE job_state (name TEXT PRIMARY KEY, last_due TEXT NOT NULL)");
            s.execute("INSERT INTO job_state VALUES ('nightly', 't1'), ('weekly', 't2')");
            s.execute("CREATE TABLE drafts (id INTEGER PRIMARY KEY, created TEXT NOT NULL, request TEXT NOT NULL, "
                    + "status TEXT NOT NULL)");
            s.execute("CREATE TABLE skill_prs (id INTEGER PRIMARY KEY, name TEXT NOT NULL, created TEXT NOT NULL)");
            s.execute("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT)");
            s.execute("INSERT INTO kv VALUES ('a', 'b'), ('n', 12)"); // a number in a TEXT column is kept as text
            s.execute("CREATE TABLE conversation (id INTEGER PRIMARY KEY, time TEXT NOT NULL, author TEXT NOT NULL, "
                    + "client TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL)");
            s.execute("INSERT INTO conversation VALUES (1, 't', 'chris', 'web', 'msg', 'hoi')");
            s.execute("CREATE TABLE conversation_refusals (id INTEGER PRIMARY KEY, time TEXT NOT NULL, "
                    + "client TEXT NOT NULL, action TEXT NOT NULL, why TEXT NOT NULL)");
            s.execute("CREATE TABLE approvals (token TEXT PRIMARY KEY, client TEXT NOT NULL, brief TEXT NOT NULL, "
                    + "mode TEXT NOT NULL, created TEXT NOT NULL, expires REAL NOT NULL, status TEXT NOT NULL)");
            s.execute("INSERT INTO approvals VALUES ('tok', 'discord', 'b', 'build', 't', 1789000000.5, 'open')");
        }
        return file;
    }

    private static long count(String url, String table) throws Exception {
        try (Connection c = DriverManager.getConnection(url, pg.user(), null);
                Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long total(String url) throws Exception {
        long n = 0;
        for (String t : SqliteImporter.TABLES) {
            n += count(url, t);
        }
        return n;
    }

    @Test
    void loadsEveryTableKeepsIdsAndResetsSequences() throws Exception {
        Path db = fixture();
        String url = newDatabase();
        byte[] before = Files.readAllBytes(db);

        var counts = new SqliteImporter(db.toString(), url, pg.user(), null).run();

        assertThat(counts.postgres()).isEqualTo(counts.sqlite());
        assertThat(counts.sqlite()).containsEntry("tasks", 2L).containsEntry("job_state", 2L)
                .containsEntry("drafts", 0L).containsEntry("approvals", 1L);
        assertThat(Files.readAllBytes(db)).as("inbox.db is not changed").isEqualTo(before);
        try (Connection c = DriverManager.getConnection(url, pg.user(), null); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT id, note, turns, review_ok FROM tasks ORDER BY id")) {
                rs.next();
                assertThat(rs.getInt("id")).isEqualTo(7);
                assertThat(rs.getString("note")).isEqualTo("ünïcode ✓");
                assertThat(rs.getInt("turns")).as("column SQLite lacks keeps its default").isZero();
                assertThat(rs.getInt("review_ok")).isEqualTo(1);
                rs.next();
                assertThat(rs.getInt("id")).isEqualTo(42);
                assertThat(rs.getObject("review_ok")).isNull();
            }
            try (ResultSet rs = s.executeQuery("SELECT value FROM kv WHERE key = 'n'")) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("12");
            }
            try (ResultSet rs = s.executeQuery("SELECT expires FROM approvals")) {
                rs.next();
                assertThat(rs.getDouble(1)).isEqualTo(1789000000.5);
            }
            // The sequences continue after the highest imported id (also in an empty table: from 1).
            s.execute("INSERT INTO tasks (repo, text, status, created, session) VALUES ('r', 't', 'new', 'c', 's')");
            s.execute("INSERT INTO drafts (created, request, status) VALUES ('c', 'r', 'new')");
            try (ResultSet rs = s.executeQuery("SELECT (SELECT max(id) FROM tasks), (SELECT max(id) FROM drafts), "
                    + "(SELECT max(id) FROM job_runs)")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(43);
                assertThat(rs.getInt(2)).isEqualTo(1);
                assertThat(rs.getInt(3)).isEqualTo(3);
            }
        }
    }

    @Test
    void refusesANonEmptyTargetAndWritesNothing() throws Exception {
        Path db = fixture();
        String url = newDatabase();
        new SqliteImporter(db.toString(), url, pg.user(), null).run();
        long rows = total(url);

        assertThatThrownBy(() -> new SqliteImporter(db.toString(), url, pg.user(), null).run())
                .isInstanceOf(SqliteImporter.TargetNotEmptyException.class).hasMessageContaining("not empty");
        assertThat(total(url)).isEqualTo(rows);
    }

    @Test
    void mismatchRollsBackNamesTheTableAndTheDatabaseCanBeImportedAgain() throws Exception {
        Path db = fixture();
        String url = newDatabase();
        // Force the mismatch: a row vanishes from tasks between the load and the comparison.
        var tampered = new SqliteImporter(db.toString(), url, pg.user(), null, c -> {
            try (Statement s = c.createStatement()) {
                s.execute("DELETE FROM tasks WHERE id = 42");
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });

        assertThatThrownBy(tampered::run).isInstanceOf(SqliteImporter.ImportFailedException.class)
                .hasMessageContaining("MISMATCH tasks: sqlite=2 postgres=1")
                .hasMessageNotContaining("MISMATCH kv");
        assertThat(total(url)).as("rolled back, nothing left").isZero();

        // The target is still empty, so a clean run works.
        var counts = new SqliteImporter(db.toString(), url, pg.user(), null).run();
        assertThat(counts.postgres()).isEqualTo(counts.sqlite());
        assertThat(count(url, "tasks")).isEqualTo(2);
    }

    @Test
    void aValueThatDoesNotFitFailsAndRollsBack() throws Exception {
        Path db = fixture();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("UPDATE job_runs SET task_id = 'x'"); // SQLite lets text into an INTEGER column
        }
        String url = newDatabase();

        assertThatThrownBy(() -> new SqliteImporter(db.toString(), url, pg.user(), null).run())
                .isInstanceOf(SqliteImporter.ImportFailedException.class).hasMessageContaining("does not fit integer");
        assertThat(total(url)).isZero();
    }

    @Test
    void aSqliteTableOrColumnThatWouldBeDroppedFails() throws Exception {
        Path db = fixture();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db); Statement s = c.createStatement()) {
            s.execute("ALTER TABLE kv ADD COLUMN extra TEXT");
        }
        String url = newDatabase();
        assertThatThrownBy(() -> new SqliteImporter(db.toString(), url, pg.user(), null).run())
                .isInstanceOf(SqliteImporter.ImportFailedException.class).hasMessageContaining("kv.extra");
        assertThat(total(url)).isZero();
    }

    @Test
    void commandExitCodes() throws Exception {
        Path db = fixture();
        String url = newDatabase();
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        String[] args = {"--sqlite", db.toString(), "--url", url, "--user", pg.user()};

        assertThat(ImportSqliteCommand.run(args, new PrintStream(out), new PrintStream(err))).isZero();
        assertThat(out.toString()).contains("import ok").contains("tasks").contains("sqlite=2 postgres=2");

        assertThat(ImportSqliteCommand.run(args, new PrintStream(out), new PrintStream(err))).isEqualTo(2);
        assertThat(err.toString()).contains("REFUSED");

        assertThat(ImportSqliteCommand.run(new String[0], new PrintStream(out), new PrintStream(err))).isEqualTo(2);
        assertThat(ImportSqliteCommand.run(new String[] {"--sqlite", tmp.resolve("nope.db").toString()},
                new PrintStream(out), new PrintStream(err))).isEqualTo(2);
    }
}
