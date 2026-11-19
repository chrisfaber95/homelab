package dev.homelab.inbox.door;

import dev.homelab.inbox.conversation.Clients;
import dev.homelab.inbox.conversation.ConversationStore;
import dev.homelab.inbox.core.CoreSettings;
import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.Effects.Notifier;
import dev.homelab.inbox.core.Task;
import dev.homelab.inbox.core.TaskActions;
import dev.homelab.inbox.core.TaskStore;
import dev.homelab.inbox.door.Ports.Brief;
import dev.homelab.inbox.door.Ports.Briefs;
import dev.homelab.inbox.door.Ports.JobInfo;
import dev.homelab.inbox.door.Ports.JobsPort;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import dev.homelab.inbox.core.ConnectionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * What a chat client may do besides reading and writing the conversation (H-016), ported from {@code chat.py} (H-026
 * K7): start a task, answer a question from Claude, list what runs, list and run jobs, research, and approve a draft
 * brief in two steps. Every action is checked against the client's permissions ({@link ConversationStore#allowed}); a
 * client names the action explicitly, nothing here is decided by free text. Each method returns a {@link Result}.
 *
 * <p>Shadow: the queue, briefs, jobs and notifications are the interfaces of {@link dev.homelab.inbox.core.Effects} and
 * {@link Ports}; with the shadow implementations nothing is built, run, merged or sent. Nothing calls this class from
 * a route: Python's {@code chat.py} stays the live one until H-027.
 */
public class ChatActions {

    static final Pattern BRIEF_ID = Pattern.compile("[A-Z]-[0-9]{3,4}");
    /** The two buttons: Approve and build (false = attended), Approve and build without me (true). */
    static final Map<String, Boolean> MODES = Map.of("build", false, "without-me", true);

    private static final Logger LOG = LoggerFactory.getLogger(ChatActions.class);
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TaskStore tasks;
    private final TaskActions taskActions;
    private final ConversationStore conversation;
    private final Clients clients;
    private final ConversationNotes notes;
    private final Notifier notifier;
    private final Briefs briefs;
    private final JobsPort jobs;
    private final Supplier<Set<String>> repos;
    private final ConnectionSource db;
    private final Clock clock;
    private final DoorSettings settings;
    private final Object startLock = new Object();

    public ChatActions(TaskStore tasks, TaskActions taskActions, ConversationStore conversation, Clients clients,
                       ConversationNotes notes, Notifier notifier, Briefs briefs, JobsPort jobs,
                       Supplier<Set<String>> repos, ConnectionSource db, Clock clock, DoorSettings settings) {
        this.tasks = tasks;
        this.taskActions = taskActions;
        this.conversation = conversation;
        this.clients = clients;
        this.notes = notes;
        this.notifier = notifier;
        this.briefs = briefs;
        this.jobs = jobs;
        this.repos = repos;
        this.db = db;
        this.clock = clock;
        this.settings = settings;
    }

    String pageUrl(Long taskId) {
        return taskId == null ? settings.pageBase() : settings.pageBase() + "/task/" + taskId;
    }

    private Result refused(String client, String action) {
        String why = conversation.switchedOff(client, action);
        return Result.error(403, "refused: " + (why != null ? why : client + " may not " + action.replace('_', ' ')));
    }

    int ttl() {
        return settings.confirmTtlSeconds();
    }

    /** Tasks a client may start per day: {@code tasks_per_day} on the client in clients.json, else CLIENT_TASKS_PER_DAY. */
    int dailyCap(String client) {
        JsonNode own = clients.setting(client, "tasks_per_day");
        if (own == null) {
            return Math.max(0, settings.defaultTasksPerDay());
        }
        try {
            if (own.isNumber()) {
                return Math.max(0, own.intValue());
            }
            if (own.isBoolean()) {
                return own.booleanValue() ? 1 : 0;
            }
            return Math.max(0, Integer.parseInt(own.asString("").strip()));
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    /** Tasks (and builds) this client started since local midnight. */
    int startedToday(String client) {
        ZonedDateTime midnight = ZonedDateTime.now(clock.withZone(settings.tz())).truncatedTo(ChronoUnit.DAYS);
        return tasks.countFromSince(client, midnight.withZoneSameInstant(ZoneOffset.UTC).format(ISO_SECONDS));
    }

    String capReason(String client) {
        int cap = dailyCap(client);
        if (startedToday(client) >= cap) {
            return "daily limit reached: " + cap + " task(s) per day may start from " + Texts.capitalize(client)
                    + "; nothing was started";
        }
        return null;
    }

    /** A task from a client: the same row, queue, limits and PR as a task from the page, marked with where it came from. */
    public Result startTask(String client, String repo, String text) {
        if (!conversation.allowed(client, "start_task")) {
            return refused(client, "start_task");
        }
        repo = Texts.strip(repo);
        text = Texts.strip(text);
        if ((!repos.get().contains(repo) && !repo.equals(CoreSettings.AUTO)) || text.isEmpty()) {
            return Result.error(400, "give a repo from the list (or 'auto') and a task text");
        }
        long tid;
        synchronized (startLock) { // check and insert together, so two commands cannot both slip under the cap
            String why = capReason(client);
            if (why != null) {
                notes.note("system", "inbox", "notification", why, null, null);
                return Result.error(429, why);
            }
            tid = tasks.newTask(repo, text, client);
        }
        notes.note("chris", client, "message", "[" + repo + "] " + text, pageUrl(tid), "task:" + tid);
        return Result.of(200, "task", tid, "url", pageUrl(tid));
    }

    /** A reply to a question from Claude (or a reaction to its answer): continues the task as the page does. */
    public Result answer(String client, String taskId, String text) {
        if (!conversation.allowed(client, "answer")) {
            return refused(client, "answer");
        }
        Task t = null;
        if (Texts.digits(taskId) && taskId.length() < 18) {
            t = tasks.get(Long.parseLong(taskId)).orElse(null);
        }
        if (t == null) {
            return Result.error(404, "no such task");
        }
        if (!taskActions.answer(t, text, client)) {
            return Result.error(409, "task " + t.id() + " is not waiting for an answer");
        }
        return Result.of(200, "task", t.id(), "url", pageUrl(t.id()));
    }

    /** What is running, waiting for Chris and queued, oldest first. */
    public Result tasksState(String client) {
        if (!conversation.allowed(client, "read")) {
            return refused(client, "read");
        }
        List<Object> ordered = new ArrayList<>();
        for (Task t : tasks.withStatus("queued", "running", "waiting")) {
            ordered.add(Result.of(0, "id", t.id(), "status", t.status(), "repo", t.repo(), "what",
                    Texts.firstLine(t.str("text")), "source", t.str("source"), "url", pageUrl(t.id())).body());
        }
        return Result.of(200, "tasks", ordered);
    }

    public Result jobsList(String client) {
        if (!conversation.allowed(client, "read")) {
            return refused(client, "read");
        }
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, JobInfo> e : new TreeMap<>(jobs.jobs()).entrySet()) {
            JobInfo j = e.getValue();
            String when = j.schedule() != null && !j.schedule().isEmpty() ? j.schedule() : j.when();
            out.add(Result.of(0, "name", e.getKey(), "kind", j.kind(), "when", when, "paused", j.paused(), "error",
                    j.error()).body());
        }
        return Result.of(200, "jobs", out);
    }

    public Result runJob(String client, String name) {
        if (!conversation.allowed(client, "run_job")) {
            return refused(client, "run_job");
        }
        JobInfo job = name == null ? null : jobs.jobs().get(name);
        if (job == null || !job.usable()) {
            return Result.error(404, "no runnable job '" + name + "'");
        }
        long run = jobs.dispatch(job.name());
        notes.note("chris", client, "message", "Ran job " + job.name() + " now", pageUrl(null), "job:" + job.name());
        return Result.of(200, "run", run, "job", job.name());
    }

    /** A research question from a client (H-017): one read-only Claude run; the report comes back in the conversation. */
    public Result research(String client, String question, String mode) {
        if (!conversation.allowed(client, "research")) {
            return refused(client, "research");
        }
        Result r = jobs.research(question, mode, client);
        if (r.status() == 200) {
            notes.note("chris", client, "message", "[research:" + mode + "] " + Texts.strip(question),
                    String.valueOf(r.get("url")), "job:research:" + r.get("run"));
        } else if (r.status() == 429) {
            notes.note("system", "inbox", "notification", r.error(), null, null);
        }
        return r;
    }

    /** Draft briefs, for a command or button to approve. */
    public Result drafts(String client) {
        if (!conversation.allowed(client, "read")) {
            return refused(client, "read");
        }
        List<Object> out = new ArrayList<>();
        for (Brief b : briefs.all()) {
            if (b.status().equals("draft")) {
                out.add(Result.of(0, "id", b.id(), "title", b.title()).body());
            }
        }
        return Result.of(200, "briefs", out);
    }

    private double nowSeconds() {
        return clock.millis() / 1000.0;
    }

    /** Step one: a pending approval with a confirm token that expires. Nothing is approved or started yet. */
    public Result approvalRequest(String client, String bid, String mode) {
        if (!conversation.allowed(client, "approve")) {
            return refused(client, "approve");
        }
        if (mode == null || !MODES.containsKey(mode) || bid == null || !BRIEF_ID.matcher(bid).matches()) {
            return Result.error(400, "give a brief id and a mode: build or without-me");
        }
        Brief b = briefs.byId(bid).orElse(null);
        if (b == null || !b.status().equals("draft")) {
            return Result.error(409, bid + " is not a draft brief");
        }
        if (briefs.liveBuild(bid)) {
            return Result.error(409, bid + " is already being built");
        }
        String why = capReason(client);
        if (why != null) {
            return Result.error(429, why);
        }
        byte[] raw = new byte[18];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(
                "INSERT INTO approvals (token, client, brief, mode, created, expires, status, task_id) "
                        + "VALUES (?,?,?,?,?,?,?,NULL)")) {
            st.setString(1, token);
            st.setString(2, client);
            st.setString(3, bid);
            st.setString(4, mode);
            st.setString(5, tasks.now());
            st.setDouble(6, nowSeconds() + ttl());
            st.setString(7, "pending");
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot store the approval: " + e.getMessage(), e);
        }
        String what = MODES.get(mode) ? "Approve & build without me" : "Approve & build";
        // The token is not in the conversation: only the client that asked gets it, in this response.
        notes.note("system", client, "approval_request", what + " for " + bid + " (" + b.title() + ") is waiting for "
                + "confirmation; it expires in " + ttl() / 60 + " min", pageUrl(null), "brief:" + bid);
        return Result.of(200, "confirm", token, "brief", bid, "mode", mode, "title", b.title(), "expires_in", ttl());
    }

    private record Row(String token, String client, String brief, String mode, String status) {}

    /**
     * Step two: only a valid, unexpired, unused token from the same client approves. Switches and permission are
     * checked again now, so turning approvals off between the steps stops it.
     */
    public Result approvalConfirm(String client, String token) {
        if (!conversation.allowed(client, "approve")) {
            return refused(client, "approve");
        }
        String given = token == null ? "None" : token;
        Row row = null;
        boolean taken = false;
        try (Connection c = db.open()) {
            try (PreparedStatement st = c.prepareStatement(
                    "SELECT token, client, brief, mode, status FROM approvals WHERE token=?")) {
                st.setString(1, given);
                try (ResultSet rs = st.executeQuery()) {
                    if (rs.next()) {
                        row = new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5));
                    }
                }
            }
            if (row != null && row.client().equals(client)) {
                // One use: the first confirm flips pending to used; a second, or an expired token, matches no row.
                try (PreparedStatement st = c.prepareStatement(
                        "UPDATE approvals SET status='used' WHERE token=? AND status='pending' AND expires>?")) {
                    st.setString(1, row.token());
                    st.setDouble(2, nowSeconds());
                    taken = st.executeUpdate() == 1;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read the approval: " + e.getMessage(), e);
        }
        if (row == null || !row.client().equals(client)) {
            return Result.error(404, "unknown confirm token");
        }
        if (!taken) {
            String why = row.status().equals("pending") ? "expired" : "already " + row.status();
            return Result.error(410, "this confirmation is " + why + "; nothing was approved");
        }
        String bid = row.brief();
        boolean unattended = MODES.get(row.mode());
        long tid;
        try {
            String why = capReason(client);
            if (why != null) {
                throw new IllegalStateException(why);
            }
            Brief b = briefs.byId(bid).orElse(null);
            if (b == null || !b.status().equals("draft")) {
                throw new IllegalStateException(bid + " is no longer a draft brief");
            }
            tid = briefs.startBuild(bid, unattended, "Chris", client);
        } catch (RuntimeException e) {
            setApproval(row.token(), "UPDATE approvals SET status='failed' WHERE token=?", null);
            LOG.warn("chat: approval of {} by {} failed: {}", bid, client, e.getMessage());
            return Result.error(409, String.valueOf(e.getMessage()));
        }
        setApproval(row.token(), "UPDATE approvals SET task_id=? WHERE token=?", tid);
        String what = "Approved " + bid + " from " + Texts.capitalize(client) + " and started the build"
                + (unattended ? " without me" : "");
        notes.note("chris", client, "approval", what, pageUrl(tid), "brief:" + bid);
        notifier.notify(what, "Build #" + tid, pageUrl(tid)); // an approval is never silent
        return Result.of(200, "task", tid, "url", pageUrl(tid), "brief", bid, "mode", row.mode());
    }

    private void setApproval(String token, String sql, Long taskId) {
        try (Connection c = db.open(); PreparedStatement st = c.prepareStatement(sql)) {
            if (taskId == null) {
                st.setString(1, token);
            } else {
                st.setLong(1, taskId);
                st.setString(2, token);
            }
            st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("cannot update the approval: " + e.getMessage(), e);
        }
    }
}
