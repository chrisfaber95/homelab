package dev.homelab.inbox.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
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

/** import-conversation and export-conversation (H-024 M4): the switch and its rollback, on a throwaway cluster. */
class ConversationImporterTest {

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
        pg.close();
    }

    private static String newDatabase() throws Exception {
        String name = "convimp" + DBS.incrementAndGet();
        try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.user(), null); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE " + name);
        }
        return pg.jdbcUrl(name);
    }

    private Path fixture(String name) throws Exception {
        Path file = tmp.resolve(name);
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE conversation (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL, author TEXT NOT NULL,"
                    + " client TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, link TEXT, ref TEXT)");
            s.execute("CREATE TABLE conversation_refusals (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL,"
                    + " client TEXT NOT NULL, action TEXT NOT NULL, why TEXT NOT NULL)");
            s.execute("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT)");
            s.execute("INSERT INTO conversation VALUES (1, 't1', 'chris', 'page', 'message', 'één ✓', NULL, NULL),"
                    + " (4, 't2', 'claude', 'inbox', 'question', 'vraag', 'http://x', 'task:4')");
            s.execute("INSERT INTO conversation_refusals VALUES (2, 't3', 'discord', 'approve', 'no')");
            s.execute("INSERT INTO kv VALUES ('switch:discord', 'off'), ('other', 'x')");
        }
        return file;
    }

    private static long count(String url, String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(url, pg.user(), null); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private int command(String name, PrintStream out, PrintStream err, String url, Path sqlite, String... extra) {
        String[] args = new String[extra.length + 4];
        args[0] = "--sqlite";
        args[1] = sqlite.toString();
        args[2] = "--url";
        args[3] = url;
        System.arraycopy(extra, 0, args, 4, extra.length);
        String[] withUser = java.util.Arrays.copyOf(args, args.length + 2);
        withUser[args.length] = "--user";
        withUser[args.length + 1] = pg.user();
        return ConversationCommand.run(name, withUser, out, err);
    }

    @Test
    void importThenRefuseThenReplaceThenExportBack() throws Exception {
        String url = newDatabase();
        Path sqlite = fixture("inbox.db");
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        assertThat(command(ConversationCommand.IMPORT, new PrintStream(out), new PrintStream(err), url, sqlite)).isZero();
        assertThat(out.toString()).contains("conversation=2 conversation_refusals=1 switches=1");
        assertThat(count(url, "SELECT count(*) FROM kv WHERE key = 'store:conversation'")).isEqualTo(1);
        assertThat(count(url, "SELECT count(*) FROM kv WHERE key = 'other'")).isZero();  // other kv keys stay with Python

        // Java writes after the switch; a second import must not touch that
        try (Connection c = DriverManager.getConnection(url, pg.user(), null); Statement s = c.createStatement()) {
            s.execute("INSERT INTO conversation (time, author, client, kind, text) VALUES ('t9','box','ntfy','notification','nieuw')");
            s.execute("UPDATE kv SET value = 'on' WHERE key = 'switch:discord'");
        }
        assertThat(count(url, "SELECT max(id) FROM conversation")).isEqualTo(5);  // the sequence continued after id 4
        err.reset();
        assertThat(command(ConversationCommand.IMPORT, new PrintStream(out), new PrintStream(err), url, sqlite)).isEqualTo(2);
        assertThat(err.toString()).contains("REFUSED");
        out.reset();
        assertThat(command(ConversationCommand.IMPORT, new PrintStream(out), new PrintStream(err), url, sqlite, "--if-not-imported")).isZero();
        assertThat(out.toString()).contains("already imported");
        assertThat(count(url, "SELECT count(*) FROM conversation")).isEqualTo(3);

        // The rollback: export Java's data into a SQLite file that still has the old rows and an unrelated kv key
        assertThat(command(ConversationCommand.EXPORT, new PrintStream(out), new PrintStream(err), url, sqlite)).isZero();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT count(*), max(id) FROM conversation");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(3);
            assertThat(rs.getInt(2)).isEqualTo(5);
            rs = s.executeQuery("SELECT value FROM kv WHERE key = 'switch:discord'");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("on");
            rs = s.executeQuery("SELECT count(*) FROM kv WHERE key = 'other'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);  // the export leaves other keys alone
            rs = s.executeQuery("SELECT text FROM conversation WHERE id = 1");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("één ✓");
        }

        // --replace redoes the import (only before the switch): Java's newer row is gone, the marker is back
        assertThat(command(ConversationCommand.IMPORT, new PrintStream(out), new PrintStream(err), url, sqlite, "--replace")).isZero();
        assertThat(count(url, "SELECT count(*) FROM conversation")).isEqualTo(3);
        assertThat(count(url, "SELECT count(*) FROM kv WHERE key = 'store:conversation'")).isEqualTo(1);
    }

    @Test
    void anEmptyOrNewSqliteImportsAsEmptyAndExportNeedsTheMarker() throws Exception {
        String url = newDatabase();
        Path empty = tmp.resolve("empty.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + empty)) {
            c.createStatement().execute("CREATE TABLE tasks (id INTEGER PRIMARY KEY)");
        }
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        assertThat(command(ConversationCommand.EXPORT, new PrintStream(out), new PrintStream(err), url, empty)).isEqualTo(1);
        assertThat(err.toString()).contains("EXPORT FAILED");  // no database schema, so no marker either
        assertThat(command(ConversationCommand.IMPORT, new PrintStream(out), new PrintStream(err), url, empty)).isZero();
        assertThat(out.toString()).contains("conversation=0 conversation_refusals=0 switches=0");
        assertThat(count(url, "SELECT count(*) FROM kv WHERE key = 'store:conversation'")).isEqualTo(1);
    }

    @Test
    void aFailureRollsBackAndBadUsageWritesNothing() throws Exception {
        String url = newDatabase();
        Path bad = tmp.resolve("bad.db");
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + bad); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE conversation (id INTEGER PRIMARY KEY, time TEXT NOT NULL, author TEXT NOT NULL,"
                    + " client TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, link TEXT, ref TEXT)");
            s.execute("INSERT INTO conversation VALUES (1, 't', 'chris', 'page', 'message', X'00FF', NULL, NULL)");
        }
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        assertThat(command(ConversationCommand.IMPORT, new PrintStream(out), new PrintStream(err), url, bad)).isEqualTo(1);
        assertThat(count(url, "SELECT count(*) FROM kv WHERE key = 'store:conversation'")).isZero();
        assertThat(count(url, "SELECT count(*) FROM conversation")).isZero();
        assertThat(ConversationCommand.run(ConversationCommand.IMPORT, new String[] {"--nope"}, new PrintStream(out), new PrintStream(err))).isEqualTo(2);
        assertThat(ConversationCommand.run(ConversationCommand.EXPORT, new String[] {"--sqlite", "x", "--replace"}, new PrintStream(out),
                new PrintStream(err))).isEqualTo(2);
    }
}
