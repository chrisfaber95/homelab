package dev.homelab.inbox.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Runs the Flyway migrations on a throwaway cluster (Q5) and checks the nine tables of today's inbox.db. */
class BaselineMigrationTest {

    // Table -> column names in the order of the SQLite database (bin/homelab_inbox/{storage,conversation,chat}.py).
    private static final Map<String, List<String>> EXPECTED = new LinkedHashMap<>();

    static {
        EXPECTED.put("tasks", List.of("id", "repo", "text", "status", "created", "started", "ended", "session",
                "rounds", "pending_input", "question", "answer", "summary", "pr_number", "pr_url", "last_comment_at",
                "turns", "note", "job", "run_id", "mem_source", "mem_link", "remembered", "model", "reprepare",
                "parent", "after_task", "reason", "brief", "criteria", "reviews", "review", "handoff", "unattended",
                "review_ok", "conflicts", "conflict_sha", "restarts", "accepted", "no_auto_revive", "source"));
        EXPECTED.put("job_runs", List.of("id", "job", "kind", "model", "due", "created", "started", "ended",
                "dispatched", "status", "summary", "note", "task_id", "manual", "minutes", "mode", "deliver",
                "source"));
        EXPECTED.put("job_state", List.of("name", "last_due"));
        EXPECTED.put("drafts", List.of("id", "created", "request", "base", "status", "text", "error"));
        EXPECTED.put("skill_prs", List.of("id", "name", "task_id", "pr_url", "created"));
        EXPECTED.put("kv", List.of("key", "value"));
        EXPECTED.put("conversation", List.of("id", "time", "author", "client", "kind", "text", "link", "ref"));
        EXPECTED.put("conversation_refusals", List.of("id", "time", "client", "action", "why"));
        EXPECTED.put("approvals", List.of("token", "client", "brief", "mode", "created", "expires", "status",
                "task_id"));
    }

    private static ThrowawayPostgres pg;

    @BeforeAll
    static void startClusterAndMigrate() throws Exception {
        pg = ThrowawayPostgres.start();
        Flyway.configure().dataSource(pg.jdbcUrl(), pg.user(), null).load().migrate();
    }

    @AfterAll
    static void stopCluster() throws Exception {
        if (pg != null) {
            pg.close();
        }
    }

    private static Connection connect() throws Exception {
        return DriverManager.getConnection(pg.jdbcUrl(), pg.user(), null);
    }

    private static List<String> column(Connection c, String sql, String... args) throws Exception {
        try (var ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            List<String> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }
    }

    @Test
    void createsExactlyTheNineTables() throws Exception {
        try (Connection c = connect()) {
            List<String> tables = column(c, "SELECT table_name FROM information_schema.tables "
                    + "WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history' ORDER BY 1");
            assertThat(tables).containsExactlyInAnyOrderElementsOf(EXPECTED.keySet());
            assertThat(tables).hasSize(9);
        }
    }

    @Test
    void everyTableHasItsColumnsInOrder() throws Exception {
        try (Connection c = connect()) {
            for (var e : EXPECTED.entrySet()) {
                List<String> columns = column(c, "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ? ORDER BY ordinal_position", e.getKey());
                assertThat(columns).as("columns of %s", e.getKey()).containsExactlyElementsOf(e.getValue());
            }
        }
    }

    @Test
    void keysAndTypesMatchTheSqliteSchema() throws Exception {
        try (Connection c = connect()) {
            Map<String, String> primaryKeys = Map.of("tasks", "id", "job_runs", "id", "job_state", "name",
                    "drafts", "id", "skill_prs", "id", "kv", "key", "conversation", "id",
                    "conversation_refusals", "id", "approvals", "token");
            for (var e : primaryKeys.entrySet()) {
                assertThat(column(c, "SELECT kcu.column_name FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu USING (constraint_schema, constraint_name) "
                        + "WHERE tc.table_schema = 'public' AND tc.constraint_type = 'PRIMARY KEY' "
                        + "AND tc.table_name = ?", e.getKey())).as("primary key of %s", e.getKey())
                        .containsExactly(e.getValue());
            }
            assertThat(column(c, "SELECT data_type FROM information_schema.columns WHERE table_name = 'approvals' "
                    + "AND column_name = 'expires'")).containsExactly("double precision");
            // NOT NULL columns of the SQLite schema.
            assertThat(column(c, "SELECT column_name FROM information_schema.columns WHERE table_name = 'tasks' "
                    + "AND is_nullable = 'NO' ORDER BY ordinal_position"))
                    .containsExactly("id", "repo", "text", "status", "created", "session");
        }
    }

    @Test
    void identityIdsAssignThemselvesAndAcceptImportedValues() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO drafts (created, request, status) VALUES ('t', 'r', 'new')");
            s.executeUpdate("INSERT INTO drafts (id, created, request, status) VALUES (500, 't', 'r', 'new')");
            try (ResultSet rs = s.executeQuery("SELECT count(*), max(id) FROM drafts")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(2);
                assertThat(rs.getInt(2)).isEqualTo(500);
            }
        }
    }

    @Test
    void migrationRunsOnceAndIsRecorded() throws Exception {
        var info = Flyway.configure().dataSource(pg.jdbcUrl(), pg.user(), null).load().info();
        assertThat(info.applied()).hasSize(1);
        assertThat(info.applied()[0].getVersion().getVersion()).isEqualTo("1");
        assertThat(info.pending()).isEmpty();
    }
}
