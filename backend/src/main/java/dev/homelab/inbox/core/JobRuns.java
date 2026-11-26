package dev.homelab.inbox.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tables of jobs ({@code job_runs}, {@code job_state}, {@code kv}) and the one insert into {@code tasks} a Claude
 * job makes, over the PostgreSQL {@code inbox} database (H-026 K6). The SQL is that of {@code jobs.py}; the SQLite
 * {@code INSERT OR REPLACE} became an upsert.
 */
public class JobRuns {

    static final Set<String> RUN_COLUMNS = Set.of("status", "started", "ended", "summary", "note", "task_id", "dispatched",
            "mode", "deliver", "source");

    private final ConnectionSource db;

    public JobRuns(ConnectionSource db) {
        this.db = db;
    }

    public long insertRun(String job, String kind, String model, String due, String created, String status,
                          boolean manual, Long minutes, String mode, String deliver, String source) {
        return insert("INSERT INTO job_runs (job, kind, model, due, created, status, manual, minutes, mode, deliver, source) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,?)", job, kind, model, due, created, status, manual ? 1 : 0, minutes, mode,
                deliver, source);
    }

    public void updateRun(long runId, Map<String, Object> fields) {
        for (String column : fields.keySet()) {
            if (!RUN_COLUMNS.contains(column)) {
                throw new IllegalArgumentException("no such job_runs column: " + column);
            }
        }
        List<Object> args = new ArrayList<>(fields.values());
        args.add(runId);
        exec("UPDATE job_runs SET " + String.join(", ", fields.keySet().stream().map(k -> k + "=?").toList())
                + " WHERE id=?", args.toArray());
    }

    public Optional<Map<String, Object>> run(long runId) {
        return rows("SELECT * FROM job_runs WHERE id=?", runId).stream().findFirst();
    }

    /** {@code job_runs}: newest first, of one job or of all. */
    public List<Map<String, Object>> runs(String job, int limit) {
        return job == null ? rows("SELECT * FROM job_runs ORDER BY id DESC LIMIT ?", limit)
                : rows("SELECT * FROM job_runs WHERE job=? ORDER BY id DESC LIMIT ?", job, limit);
    }

    public void deleteRun(long runId) {
        exec("DELETE FROM job_runs WHERE id=?", runId);
    }

    /** Claude runs started since {@code dayStart} (runs with a task): what the daily cap counts. */
    public int claudeRunsSince(String dayStart) {
        return ((Number) rows("SELECT COUNT(*) AS n FROM job_runs WHERE task_id IS NOT NULL AND dispatched >= ?", dayStart)
                .get(0).get("n")).intValue();
    }

    public boolean otherHeldRun(String job, long runId) {
        return !rows("SELECT id FROM job_runs WHERE job=? AND status='held' AND id<>?", job, runId).isEmpty();
    }

    public List<Map<String, Object>> heldBefore(String dayStart) {
        return rows("SELECT * FROM job_runs WHERE status='held' AND created < ? ORDER BY id", dayStart);
    }

    /** When the last successful run of this job before {@code runId} was created. */
    public Optional<String> previousRunTime(String job, long runId) {
        return rows("SELECT created FROM job_runs WHERE job=? AND status='ok' AND id<? ORDER BY id DESC LIMIT 1", job, runId)
                .stream().map(r -> (String) r.get("created")).findFirst();
    }

    public Optional<String> lastDue(String job) {
        return rows("SELECT last_due FROM job_state WHERE name=?", job).stream().map(r -> (String) r.get("last_due"))
                .findFirst();
    }

    public void setLastDue(String job, String lastDue) {
        exec("INSERT INTO job_state (name, last_due) VALUES (?,?) ON CONFLICT (name) DO UPDATE SET last_due=EXCLUDED.last_due",
                job, lastDue);
    }

    public Optional<String> kvGet(String key) {
        return rows("SELECT value FROM kv WHERE key=?", key).stream().map(r -> (String) r.get("value")).findFirst();
    }

    public void kvSet(String key, String value) {
        exec("INSERT INTO kv (key, value) VALUES (?,?) ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value", key, value);
    }

    /** The task of a Claude job: the same row an inbox task has, with the job and the run it belongs to. */
    public long insertJobTask(String repo, String text, String created, String session, String job, long runId) {
        return insert("INSERT INTO tasks (repo, text, status, created, session, job, run_id) VALUES (?,?,?,?,?,?,?)", repo,
                text, "queued", created, session, job, runId);
    }

    public List<Map<String, Object>> drafts(String status) {
        return rows("SELECT * FROM drafts WHERE status=? ORDER BY id DESC", status);
    }

    public Optional<Map<String, Object>> draft(long id) {
        return rows("SELECT * FROM drafts WHERE id=?", id).stream().findFirst();
    }

    static final Set<String> DRAFT_COLUMNS = Set.of("status", "text", "base", "error", "request");

    public void updateDraft(long id, Map<String, Object> fields) {
        for (String column : fields.keySet()) {
            if (!DRAFT_COLUMNS.contains(column)) {
                throw new IllegalArgumentException("no such drafts column: " + column);
            }
        }
        List<Object> args = new ArrayList<>(fields.values());
        args.add(id);
        exec("UPDATE drafts SET " + String.join(", ", fields.keySet().stream().map(k -> k + "=?").toList())
                + " WHERE id=?", args.toArray());
    }

    /** {@code start_draft}: a draft that Claude is still writing. */
    public long insertDrafting(String created, String request, String base) {
        return insert("INSERT INTO drafts (created, request, base, status) VALUES (?,?,?,?)", created, request, base,
                "drafting");
    }

    public long insertReadyDraft(String created, String request, String text) {
        return insert("INSERT INTO drafts (created, request, base, status, text) VALUES (?,?,?,?,?)", created, request, null,
                "ready", text);
    }

    private long insert(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(st, args);
            st.executeUpdate();
            try (ResultSet keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getLong("id");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("job store: " + e.getMessage(), e);
        }
    }

    private void exec(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(sql)) {
            bind(st, args);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("job store: " + e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(sql)) {
            bind(st, args);
            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Map<String, Object>> out = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        row.put(meta.getColumnLabel(i), rs.getObject(i));
                    }
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("job store: " + e.getMessage(), e);
        }
    }

    private static void bind(PreparedStatement st, Object[] args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            st.setObject(i + 1, args[i]);
        }
    }
}
