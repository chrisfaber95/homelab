package dev.homelab.inbox.core;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The task store over the existing {@code tasks} table (H-026 K1): the Java counterpart of {@code get}, {@code update},
 * {@code tasks} and {@code new_task} in {@code bin/homelab_inbox/storage.py}. Times stay ISO-8601 strings with a
 * {@code +00:00} offset and whole seconds, as in the SQLite database. Python is still the owner of the table (H-027
 * switches it); nothing in the running service calls this class unless the scheduler is switched on.
 */
public class TaskStore {

    /** Columns {@link #update} may set; a typo is an error here, not an SQL error. */
    static final Set<String> COLUMNS = Set.of("repo", "text", "status", "created", "started", "ended", "session", "rounds",
            "pending_input", "question", "answer", "summary", "pr_number", "pr_url", "last_comment_at", "turns", "note",
            "job", "run_id", "mem_source", "mem_link", "remembered", "model", "reprepare", "parent", "after_task",
            "reason", "brief", "criteria", "reviews", "review", "handoff", "unattended", "review_ok", "conflicts",
            "conflict_sha", "restarts", "accepted", "no_auto_revive", "source");

    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
    private static final int TEXT_LIMIT = 8000;

    private final ConnectionSource db;
    private final Clock clock;

    public TaskStore(ConnectionSource db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** {@code storage.now()}: UTC, whole seconds, {@code +00:00}. */
    public String now() {
        return OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).format(ISO_SECONDS);
    }

    public Optional<Task> get(long id) {
        List<Task> rows = query("SELECT * FROM tasks WHERE id=?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** A skill proposal's PR ({@code skill_prs}), shown on the skills page. */
    public void addSkillPr(String name, long taskId, String prUrl) {
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(
                "INSERT INTO skill_prs (name, task_id, pr_url, created) VALUES (?,?,?,?)")) {
            st.setString(1, name);
            st.setLong(2, taskId);
            st.setString(3, prUrl);
            st.setString(4, now());
            st.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    /** Newest first, like {@code storage.tasks}. */
    public List<Task> tasks(int limit) {
        return query("SELECT * FROM tasks ORDER BY id DESC LIMIT ?", limit);
    }

    /** The tasks in one of these statuses, oldest first ({@code SELECT ... WHERE status IN (...) ORDER BY id}). */
    public List<Task> withStatus(String... statuses) {
        String marks = String.join(",", java.util.Collections.nCopies(statuses.length, "?"));
        return query("SELECT * FROM tasks WHERE status IN (" + marks + ") ORDER BY id", (Object[]) statuses);
    }

    /** How many tasks this source created at or after {@code since} (an ISO-8601 UTC string, as stored). */
    public int countFromSince(String source, String since) {
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(
                "SELECT COUNT(*) FROM tasks WHERE source=? AND created>=?")) {
            st.setString(1, source);
            st.setString(2, since);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot count tasks: " + e.getMessage(), e);
        }
    }

    /** Queue a task: the one place a task is created. {@code source} is the client it came from (null = the page). */
    public synchronized long newTask(String repo, String text, String source) {
        String sql = "INSERT INTO tasks (repo, text, status, created, session, source) VALUES (?,?,?,?,?,?)";
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            st.setString(1, repo);
            st.setString(2, text.length() > TEXT_LIMIT ? text.substring(0, TEXT_LIMIT) : text);
            st.setString(3, "queued");
            st.setString(4, now());
            st.setString(5, UUID.randomUUID().toString());
            st.setString(6, source);
            st.executeUpdate();
            try (ResultSet keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getLong("id");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create a task: " + e.getMessage(), e);
        }
    }

    /** {@code router.children}: the tasks planned from a build, oldest first. */
    public List<Task> children(long parent) {
        return query("SELECT * FROM tasks WHERE parent=? ORDER BY id", parent);
    }

    /** {@code briefs.builds}: the build parents (pseudo-repo brief), newest first; of one brief when {@code briefId} is set. */
    public List<Task> builds(String briefId) {
        return briefId == null
                ? query("SELECT * FROM tasks WHERE repo=? ORDER BY id DESC", CoreSettings.BRIEF)
                : query("SELECT * FROM tasks WHERE repo=? AND brief=? ORDER BY id DESC", CoreSettings.BRIEF, briefId);
    }

    /** Builds that are planned and finished but have no handoff PR yet ({@code settle_briefs}). */
    public List<Task> buildsToHandOff() {
        return query("SELECT * FROM tasks WHERE repo=? AND status='routed' AND ended IS NOT NULL AND handoff IS NULL",
                CoreSettings.BRIEF);
    }

    /** The build task of {@code start_build}: queued, on the pseudo-repo {@code brief}. */
    public synchronized long newBuild(String briefId, String text, boolean unattended, String source) {
        return insert("INSERT INTO tasks (repo, text, status, created, session, brief, unattended, source) "
                + "VALUES (?,?,?,?,?,?,?,?)", CoreSettings.BRIEF, text, "queued", now(), UUID.randomUUID().toString(),
                briefId, unattended ? 1 : 0, source);
    }

    /** A task planned from a brief: queued, with its parent, predecessor and criteria (a JSON array). */
    public synchronized long newChild(String repo, String text, long parent, Long after, String reason, String briefId,
                                      String criteriaJson) {
        return insert("INSERT INTO tasks (repo, text, status, created, session, parent, after_task, reason, brief, "
                + "criteria) VALUES (?,?,?,?,?,?,?,?,?,?)", repo, text, "queued", now(), UUID.randomUUID().toString(),
                parent, after, reason, briefId, criteriaJson);
    }

    private long insert(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            st.executeUpdate();
            try (ResultSet keys = st.getGeneratedKeys()) {
                keys.next();
                return keys.getLong("id");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot create a task: " + e.getMessage(), e);
        }
    }

    /** {@code storage.update}: set the given columns of one task. */
    public synchronized void update(long id, Map<String, Object> fields) {
        if (fields.isEmpty()) {
            return;
        }
        for (String column : fields.keySet()) {
            if (!COLUMNS.contains(column)) {
                throw new IllegalArgumentException("no such task column: " + column);
            }
        }
        List<String> sets = new ArrayList<>();
        fields.keySet().forEach(k -> sets.add(k + "=?"));
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(
                "UPDATE tasks SET " + String.join(", ", sets) + " WHERE id=?")) {
            int i = 1;
            for (Object v : fields.values()) {
                st.setObject(i++, v);
            }
            st.setLong(i, id);
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot update task " + id + ": " + e.getMessage(), e);
        }
    }

    /** Insertion-ordered map for {@link #update}: {@code TaskStore.fields("status", "queued", "ended", null)}. */
    public static Map<String, Object> fields(Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return m;
    }

    private List<Task> query(String sql, Object... args) {
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                st.setObject(i + 1, args[i]);
            }
            try (ResultSet rs = st.executeQuery()) {
                ResultSetMetaData meta = rs.getMetaData();
                List<Task> rows = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        Object v = rs.getObject(i);
                        if (v != null) {
                            row.put(meta.getColumnLabel(i), v);
                        }
                    }
                    rows.add(new Task(row));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read tasks: " + e.getMessage(), e);
        }
    }
}
