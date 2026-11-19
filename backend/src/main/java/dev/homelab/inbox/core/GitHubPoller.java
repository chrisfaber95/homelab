package dev.homelab.inbox.core;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import dev.homelab.inbox.core.Effects.GitHubHttpException;
import dev.homelab.inbox.core.Effects.GitHubReads;
import dev.homelab.inbox.core.Effects.GitHubWrites;
import dev.homelab.inbox.core.Effects.Followups;
import dev.homelab.inbox.core.Effects.MemorySteps;
import dev.homelab.inbox.core.Effects.Notifier;
import dev.homelab.inbox.core.Effects.Pushes;
import dev.homelab.inbox.core.Effects.Repos;
import dev.homelab.inbox.core.Effects.Research;
import dev.homelab.inbox.core.Effects.Result;
import dev.homelab.inbox.core.Effects.TaskCommands;
import dev.homelab.inbox.core.Effects.TaskLog;
import dev.homelab.inbox.core.Effects.WorkingCopies;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GitHub polling and the inbox merging its own PRs ({@code bin/homelab_inbox/github.py}, H-026 K5): one pass of
 * {@link #poll()} looks at every open PR of a task (closed or merged, review comments of the owner, a conflict with
 * main, or, for a brief task of a build without me, the CI state and the merge) and at the PR of a task that ended badly
 * but may have been merged anyway.
 *
 * <p>GitHub is read through {@link GitHubReads} (no write method exists there) and written only through
 * {@link GitHubWrites#merge}. With the scheduler off {@link #poll()} does nothing: no read, no write, no notification.
 * The Dutch review text and the English reasons are those of Python: the page shows them.
 */
public class GitHubPoller {

    private static final Logger log = LoggerFactory.getLogger(GitHubPoller.class);
    /** A merged PR of these still counts: see {@link #checkBlockingPr}. */
    static final Set<String> MERGE_WATCHED = Set.of("refused", "failed", "capped", "cancelled");
    /** A merge there runs root scripts on the box: always the user. */
    static final Set<String> NEVER_SELF_MERGE = Set.of("homelab");
    static final Duration CI_WAIT = Duration.ofMinutes(30);
    private static final int WINDOW = 200;

    /** {@code OWNER_GITHUB} (or {@code OWNER_LOGIN}): only this account's comments count; {@code CONFLICT_ROUNDS}. */
    public record GitHubSettings(String owner, int conflictRounds) {}

    private final TaskStore store;
    private final CoreSettings settings;
    private final GitHubSettings github;
    private final GitHubReads reads;
    private final GitHubWrites writes;
    private final Notifier notifier;
    private final TaskLog taskLog;
    private final WorkingCopies copies;
    private final MemorySteps memory;
    private final Followups followups;
    private final Repos repos;
    private final TaskCommands commands;
    private final Pushes pushes;
    private final Research research;
    private final Clock clock;
    private final String pageBase;
    private final Map<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();

    public GitHubPoller(TaskStore store, CoreSettings settings, GitHubSettings github, GitHubReads reads,
                        GitHubWrites writes, Notifier notifier, TaskLog taskLog, WorkingCopies copies,
                        MemorySteps memory, Followups followups, Repos repos, TaskCommands commands, Pushes pushes,
                        Research research, Clock clock, String pageBase) {
        this.store = store;
        this.settings = settings;
        this.github = github;
        this.reads = reads;
        this.writes = writes;
        this.notifier = notifier;
        this.taskLog = taskLog;
        this.copies = copies;
        this.memory = memory;
        this.followups = followups;
        this.repos = repos;
        this.commands = commands;
        this.pushes = pushes;
        this.research = research;
        this.clock = clock;
        this.pageBase = pageBase;
    }

    /** One cycle of {@code poll_github}. Does nothing at all with the scheduler off. */
    public synchronized void poll() {
        if (!settings.schedulerEnabled()) {
            return;
        }
        Set<String> merged = new HashSet<>(); // one merge per repo per cycle, so GitHub rechecks the other PRs against main
        for (Task t : store.tasks(WINDOW)) {
            if ("pr_open".equals(t.status()) && t.has("pr_number")) {
                try {
                    checkPr(t, merged);
                } catch (RuntimeException e) {
                    log.error("PR of task {}: {}", t.id(), e.toString());
                }
            }
        }
        for (Task t : store.tasks(WINDOW)) {
            if (MERGE_WATCHED.contains(t.status()) && t.has("pr_number") && !followups.blocked(t, true).isEmpty()) {
                try {
                    checkBlockingPr(t);
                } catch (RuntimeException e) {
                    log.error("PR of task {}: {}", t.id(), e.toString());
                }
            }
        }
    }

    // ---- CI ----

    /** {@code ok} (green), {@code none} (the repo has no CI), {@code pending} or {@code failed} for a PR's head commit. */
    String ciState(String slug, String sha, String since) {
        if (sha == null || sha.isEmpty()) {
            return "none";
        }
        List<JsonNode> runs;
        try {
            runs = list(reads.get("/repos/" + slug + "/commits/" + sha + "/check-runs?per_page=100"), "check_runs");
        } catch (GitHubHttpException e) {
            if (e.code() != 403) {
                throw e;
            }
            // GitHub gives a fine-grained personal token no access to check runs, whatever its permissions. The Actions
            // runs of the commit carry the same status and conclusion (Actions: read); checks of other apps are not seen.
            runs = list(reads.get("/repos/" + slug + "/actions/runs?head_sha=" + sha + "&per_page=100"), "workflow_runs");
        }
        JsonNode status = reads.get("/repos/" + slug + "/commits/" + sha + "/status");
        boolean statuses = truthy(status, "total_count");
        if (runs.isEmpty() && truthy(reads.get("/repos/" + slug + "/actions/workflows"), "total_count")) {
            ObjectNode queued = JsonNodeFactory.instance.objectNode(); // CI that hasn't started for this commit yet
            queued.put("status", "queued");
            runs = List.of(queued);
        }
        boolean pending = runs.stream().anyMatch(r -> !"completed".equals(text(r, "status")))
                || (statuses && "pending".equals(text(status, "state")));
        if (pending) {
            boolean waited = Duration.between(OffsetDateTime.parse(since), OffsetDateTime.now(clock)).compareTo(CI_WAIT) > 0;
            return waited ? "failed" : "pending";
        }
        if (runs.stream().anyMatch(r -> !Set.of("success", "neutral", "skipped").contains(text(r, "conclusion")))
                || (statuses && !"success".equals(text(status, "state")))) {
            return "failed";
        }
        return !runs.isEmpty() || statuses ? "ok" : "none";
    }

    private static List<JsonNode> list(JsonNode answer, String field) {
        List<JsonNode> out = new ArrayList<>();
        if (answer != null && answer.path(field).isArray()) {
            answer.get(field).forEach(out::add);
        }
        return out;
    }

    private static boolean truthy(JsonNode node, String field) {
        return node != null && node.path(field).asLong(0) != 0;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node == null ? null : node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    // ---- who merges ----

    /**
     * Does the inbox merge this PR itself? Only a brief task of a build without me, on a product repo, whose last review
     * passed every criterion.
     */
    boolean mergesItself(Task t) {
        if (t == null || !t.has("brief") || !t.has("parent") || NEVER_SELF_MERGE.contains(t.repo())
                || !repos.list().containsKey(t.repo())) {
            return false;
        }
        Task parent = store.get(t.longOrNull("parent")).orElse(null);
        Integer ok = t.numOrNull("review_ok");
        return parent != null && parent.num("unattended") != 0 && ok != null && (ok == 1 || ok == 2);
    }

    // ---- closed PRs ----

    private void prClosed(Task t, boolean merged) {
        store.update(t.id(), TaskStore.fields("status", merged ? "merged" : "closed", "ended", store.now()));
        copies.cleanup(t.id());
        memory.remember(t.id());
    }

    /** A task that ended badly but whose PR the user merged anyway is fine: its follow-ups go back in the queue. */
    void checkBlockingPr(Task t) {
        String slug = repos.list().get(t.repo());
        JsonNode pr = slug == null ? null : reads.get("/repos/" + slug + "/pulls/" + t.num("pr_number"));
        if (pr == null || !pr.isObject() || !"closed".equals(text(pr, "state")) || !pr.path("merged").asBoolean(false)) {
            return;
        }
        prClosed(t, true);
        int n = followups.reviveAfterMerge(store.get(t.id()).orElseThrow());
        taskLog.line("t" + t.id(), "info", "PR #" + t.num("pr_number") + " merged: " + n + " follow-up(s) back in the queue");
        notifier.notify(label(t) + ": PR #" + t.num("pr_number") + " merged", n + " follow-up(s) back in the queue",
                pageBase + "/task/" + t.id());
    }

    private static String label(Task t) {
        return t.has("job") ? "Job " + t.str("job") : "Inbox " + t.id();
    }

    private String titleOf(Task t) {
        if (t.has("job")) {
            String instruction = research.jobInstruction(t.str("job"));
            return "job " + t.str("job") + ": " + RoundFinisher.firstLine(instruction == null ? "" : instruction);
        }
        return "inbox " + t.id() + ": " + RoundFinisher.firstLine(t.str("text"));
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    // ---- build without me ----

    /** Returns true when the PR was merged. */
    boolean autoMerge(Task t, String slug, JsonNode pr) {
        String sha = text(pr.path("head"), "sha");
        String state;
        String unreadable = null;
        try {
            state = ciState(slug, sha, t.has("ended") ? t.str("ended") : t.str("created"));
        } catch (GitHubHttpException e) { // e.g. a token without permission to read checks: say so, don't stay silent
            state = "unreadable";
            String[] parts = e.url().split("api.github.com", -1);
            unreadable = "GitHub refused to show the CI status (" + e.code() + " on " + cut(parts[parts.length - 1], 80) + ")";
        }
        if (state.equals("pending")) {
            return false;
        }
        String prUrl = t.str("pr_url");
        int number = t.num("pr_number");
        if (state.equals("unreadable")) { // can pass by itself (a token that gets the permission): try again, say so once
            if (!orEmpty(t.str("review")).contains(unreadable)) {
                store.update(t.id(), TaskStore.fields("review",
                        cut("Not merged yet: " + unreadable + ". " + orEmpty(t.str("review")), 2000))); // first: the cap keeps it
                taskLog.line("t" + t.id(), "info", "build without me: not merged yet: " + unreadable);
                notifier.notify(label(t) + ": PR #" + number + " waits for you",
                        "Build without me: " + unreadable + ". The inbox keeps trying; you can also merge it yourself.", prUrl);
            }
            return false;
        }
        Integer reviewOk = t.numOrNull("review_ok");
        String why = state.equals("failed") ? "CI failed or didn't finish in 30 minutes"
                : state.equals("none") && reviewOk != null && reviewOk == 2
                        ? "a criterion can only be confirmed by CI (🧪) and this repo has none"
                : pr.path("draft").asBoolean(false) ? "the PR is a draft" : null;
        if (why == null) {
            try {
                writes.merge(slug, number, sha, "Merge PR #" + number + ": " + titleOf(t) + " (build without me)");
            } catch (GitHubHttpException e) {
                why = "GitHub refused the merge (" + e.code() + ": " + cut(e.body(), 150) + ")";
            }
        }
        if (why != null) {
            store.update(t.id(), TaskStore.fields("review_ok", 0, "review", cut(orEmpty(t.str("review")) + ". Not merged: " + why, 2000)));
            taskLog.line("t" + t.id(), "info", "build without me: not merged: " + why);
            notifier.notify(label(t) + ": PR #" + number + " waits for you", "Build without me: " + why + ".", prUrl);
            return false;
        }
        taskLog.line("t" + t.id(), "info", "build without me: merged PR #" + number);
        prClosed(t, true);
        notifier.notify(label(t) + ": PR #" + number + " merged",
                "Build without me (" + t.str("brief") + "): every criterion met" + (state.equals("ok") ? ", CI green" : "")
                        + ". To undo: revert the PR.", prUrl);
        return true;
    }

    // ---- one PR ----

    private record Comment(String when, String body, String link) {}

    void checkPr(Task t, Set<String> merged) {
        String slug = repos.list().get(t.repo());
        if (slug == null) {
            return;
        }
        int number = t.num("pr_number");
        JsonNode pr = reads.get("/repos/" + slug + "/pulls/" + number);
        boolean isPr = pr != null && pr.isObject();
        if (isPr && "closed".equals(text(pr, "state"))) {
            prClosed(t, pr.path("merged").asBoolean(false));
            return;
        }
        String since = t.has("last_comment_at") ? t.str("last_comment_at") : t.has("ended") ? t.str("ended") : t.str("created");
        List<Comment> comments = new ArrayList<>();
        for (String path : List.of("/repos/" + slug + "/issues/" + number + "/comments",
                "/repos/" + slug + "/pulls/" + number + "/comments", "/repos/" + slug + "/pulls/" + number + "/reviews")) {
            JsonNode all = reads.get(path + "?per_page=100");
            if (all == null || !all.isArray()) {
                continue;
            }
            for (JsonNode c : all) {
                String when = orEmpty(text(c, "created_at") != null ? text(c, "created_at") : text(c, "submitted_at"))
                        .replace("Z", "+00:00");
                String body = orEmpty(text(c, "body")).strip();
                // Only your comments count; other accounts are ignored.
                if (github.owner().equals(text(c.path("user"), "login")) && when.compareTo(since) > 0 && !body.isEmpty()
                        && !body.contains("<!-- homelab-inbox")) {
                    String line = text(c, "line") != null ? text(c, "line") : text(c, "original_line");
                    String where = text(c, "path") != null ? " (bij " + text(c, "path") + ":" + line + ")" : "";
                    String link = text(c, "html_url") != null ? text(c, "html_url") : t.str("pr_url");
                    comments.add(new Comment(when, body + where, link));
                }
            }
        }
        if (!comments.isEmpty()) {
            comments.sort(Comparator.comparing(Comment::when).thenComparing(Comment::body).thenComparing(Comment::link));
            StringBuilder b = new StringBuilder("Review-commentaar op de PR:\n\n");
            for (int i = 0; i < comments.size(); i++) {
                b.append(i == 0 ? "" : "\n\n").append("- ").append(comments.get(i).body());
            }
            b.append("\n\nVerwerk dit op dezelfde branch en eindig weer met KLAAR: (of VRAAG:/GEWEIGERD:). ")
                    .append(TaskActions.LEARN_HINT);
            store.update(t.id(), TaskStore.fields("status", "queued", "pending_input", b.toString(),
                    "last_comment_at", comments.get(comments.size() - 1).when(), "ended", null,
                    "mem_source", "jouw PR-commentaar op PR #" + number + " (" + label(t).toLowerCase() + ")",
                    "mem_link", comments.get(0).link(), "review_ok", null));
            log.info("task {}: {} review comment(s), back in the queue", t.id(), comments.size());
        } else if (isPr && pr.path("mergeable").isBoolean() && !pr.get("mergeable").asBoolean()) {
            onConflict(t, slug, pr);
        } else if (isPr && mergesItself(t) && !merged.contains(t.repo())
                && pr.has("mergeable") && !pr.get("mergeable").isNull()) { // mergeable null: GitHub is still checking
            if (autoMerge(t, slug, pr)) {
                merged.add(t.repo());
            }
        }
    }

    /**
     * The PR conflicts with main: merge main into the task's branch. A clean merge is pushed right away; conflicts send
     * the task back to its own session with the conflicted files, at most {@code CONFLICT_ROUNDS} times.
     */
    void onConflict(Task t, String slug, JsonNode pr) {
        String sha = text(pr.path("head"), "sha");
        if (sha != null && sha.equals(t.str("conflict_sha"))) {
            return; // this head was already handled (or handed to you)
        }
        store.update(t.id(), TaskStore.fields("conflict_sha", sha));
        int limit = github.conflictRounds();
        int number = t.num("pr_number");
        long id = t.id();
        if (t.num("conflicts") >= limit) {
            taskLog.line("t" + id, "info", "PR #" + number + " still conflicts after " + limit + " conflict round(s)");
            notifier.notify(label(t) + ": PR #" + number + " conflicts with main",
                    "Still conflicting after " + limit + " automatic round(s): resolve it yourself.", t.str("pr_url"));
            return;
        }
        Result sync;
        try {
            String cache = repos.refresh(t.repo(), slug);
            ReentrantLock lock = repoLocks.computeIfAbsent(t.repo(), k -> new ReentrantLock());
            lock.lock();
            try {
                sync = commands.sync(id);
            } finally {
                lock.unlock();
            }
            if (sync.code() == 0) {
                pushes.push(id, cache);
                store.update(id, TaskStore.fields("ended", store.now())); // CI runs again on the new head: its wait starts now
                taskLog.line("t" + id, "info", "PR #" + number + ": merged main into inbox/" + id + ", no conflicts");
                return;
            }
            if (sync.code() != 4) {
                String out = sync.out().strip();
                throw new IllegalStateException(out.isEmpty() ? "exit " + sync.code()
                        : out.substring(Math.max(0, out.length() - 300)));
            }
        } catch (RuntimeException e) {
            log.warn("task {}: conflict: {}", id, e.toString());
            notifier.notify(label(t) + ": PR #" + number + " conflicts with main",
                    "Couldn't merge main into the branch (" + e.getMessage() + "): resolve it yourself.", t.str("pr_url"));
            return;
        }
        List<String> files = sync.out().lines().filter(f -> !f.isBlank()).toList();
        StringBuilder text = new StringBuilder("PR #" + number + " conflicts with main: other work was merged into main after "
                + "you made it. `git merge origin/main` is already in progress in your working copy, with conflicts in:\n\n");
        text.append(String.join("\n", files.stream().map(f -> "- " + f).toList()));
        text.append("\n\nResolve them so that both your change and the one on main keep working, check that it still builds "
                + "and the tests pass, commit the merge on the same branch and end with KLAAR: again (or "
                + "VRAAG:/GEWEIGERD:).");
        java.util.Map<String, Object> fields = TaskStore.fields("status", "queued", "ended", null,
                "conflicts", t.num("conflicts") + 1, "review_ok", null, "mem_source", null, "mem_link", null,
                "pending_input", text.toString());
        store.update(id, fields);
        taskLog.line("t" + id, "info", "PR #" + number + " conflicts with main in " + files.size()
                + " file(s): back in the queue");
    }
}
