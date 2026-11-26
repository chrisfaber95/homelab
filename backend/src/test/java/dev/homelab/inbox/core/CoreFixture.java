package dev.homelab.inbox.core;

import dev.homelab.inbox.db.ThrowawayPostgres;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;

/** A throwaway PostgreSQL with the Flyway schema, plus the fakes the core tests share. One cluster per JVM. */
final class CoreFixture {

    private static ThrowawayPostgres pg;
    private static String url;
    private static String user;

    private CoreFixture() {}

    static synchronized ConnectionSource db() throws Exception {
        if (pg == null) {
            pg = ThrowawayPostgres.start();
            user = pg.user();
            try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), user, null); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE core");
            }
            url = pg.jdbcUrl("core");
            Flyway.configure().dataSource(url, user, null).load().migrate();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    pg.close();
                } catch (Exception ignored) {
                    // the temp directory is removed with the machine anyway
                }
            }));
        }
        String u = url;
        String us = user;
        return () -> {
            try {
                return DriverManager.getConnection(u, us, null);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /** The JDBC url and user of the database {@link #db()} started, for a Spring test that needs the same one. */
    static synchronized String url() throws Exception {
        db();
        return url;
    }

    static synchronized String user() throws Exception {
        db();
        return user;
    }

    static void clear(ConnectionSource db) throws Exception {
        try (Connection c = db.open(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE tasks RESTART IDENTITY");
        }
    }

    static void clearBuilds(ConnectionSource db) throws Exception {
        try (Connection c = db.open(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM tasks WHERE repo='brief'");
        }
    }

    /** Insert a task with explicit id and fields (status etc.), like the follow-up tests do with SQL. */
    static void insert(ConnectionSource db, long id, String repo, String status, String... columnValues) throws Exception {
        StringBuilder cols = new StringBuilder("id,repo,text,status,created,session");
        StringBuilder marks = new StringBuilder("?,?,?,?,?,?");
        List<String> values = new ArrayList<>();
        for (int i = 0; i < columnValues.length; i += 2) {
            cols.append(',').append(columnValues[i]);
            marks.append(",?");
            values.add(columnValues[i + 1]);
        }
        try (Connection c = db.open(); var st = c.prepareStatement("INSERT INTO tasks(" + cols + ") VALUES(" + marks + ")")) {
            st.setLong(1, id);
            st.setString(2, repo);
            st.setString(3, "text " + id);
            st.setString(4, status);
            st.setString(5, "2026-10-31T19:00:00+00:00");
            st.setString(6, "s" + id);
            for (int i = 0; i < values.size(); i++) {
                String col = columnValues[i * 2];
                String v = values.get(i);
                boolean number = List.of("rounds", "restarts", "parent", "after_task", "pr_number", "reprepare", "turns",
                        "accepted", "no_auto_revive", "run_id", "unattended", "reviews", "review_ok", "conflicts").contains(col);
                if (v == null) {
                    st.setObject(7 + i, null);
                } else if (number) {
                    st.setInt(7 + i, Integer.parseInt(v));
                } else {
                    st.setString(7 + i, v);
                }
            }
            st.executeUpdate();
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(String iso) {
            now = Instant.parse(iso);
        }

        void advance(java.time.Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** The effects with scripted answers; everything is recorded in order. */
    static class Fakes extends ShadowEffects {
        boolean workingCopy = true;
        boolean pushed = false;

        @Override
        public boolean has(long taskId) {
            super.has(taskId);
            return workingCopy;
        }

        @Override
        public boolean pushed(Task task) {
            super.pushed(task);
            return pushed;
        }
    }
}
