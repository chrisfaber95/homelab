package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.Followups;
import dev.homelab.inbox.core.Effects.Notifier;
import dev.homelab.inbox.core.Effects.PrLookup;
import dev.homelab.inbox.core.Effects.Repos;
import dev.homelab.inbox.core.Effects.TaskHistory;
import dev.homelab.inbox.core.Effects.TaskLog;
import dev.homelab.inbox.core.Effects.WorkingCopies;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The follow-up chain of a routed message or a build (H-026 K3): a task planned after another one is cancelled when its
 * predecessor ended badly, and comes back in the queue when the predecessor is retried, merged or marked as fine.
 * Ports {@code pre_fine}, {@code blocked_by}, {@code blocked_followups}, {@code revive_followups}, {@code accept_task} and
 * {@code settle_routed} of {@code router.py}. {@link #settleRouted()} is the scheduler's pass and does nothing while the
 * scheduler is off; the others are what a user action or the GitHub poller calls.
 */
public class FollowupChain implements Followups {

    /** The note on a follow-up that {@link #settleRouted()} cancelled (live data: the page and the revive match it). */
    static final String FOLLOWUP_NOTE = "niet gestart: de voorgaande taak";
    static final String CONTINUE_NOTE = "The task before this one stopped early and the user continued it, so this task "
            + "is back in the queue. Check `git status` and `git log` first, then continue where you left off and end "
            + "with KLAAR: (or VRAAG:/GEWEIGERD:).";
    private static final Set<String> PRE_OK = Set.of("queued", "running", "waiting", "answered", "pr_open", "merged");
    private static final Set<String> ACCEPTABLE = Set.of("failed", "cancelled", "capped", "refused", "closed");
    private static final Logger log = LoggerFactory.getLogger(FollowupChain.class);

    private final TaskStore store;
    private final CoreSettings settings;
    private final WorkingCopies copies;
    private final TaskLog taskLog;
    private final Notifier notifier;
    private final TaskHistory history;
    private final String pageBase;

    public FollowupChain(TaskStore store, CoreSettings settings, WorkingCopies copies, TaskLog taskLog,
                         Notifier notifier, TaskHistory history, String pageBase) {
        this.store = store;
        this.settings = settings;
        this.copies = copies;
        this.taskLog = taskLog;
        this.notifier = notifier;
        this.history = history;
        this.pageBase = pageBase;
    }

    /** A follow-up may run after a predecessor with one of these statuses, or one the user marked as fine. */
    static boolean preFine(Task pre) {
        return PRE_OK.contains(pre.status()) || pre.num("accepted") != 0;
    }

    /** The predecessor that stops this follow-up from running (settleRouted cancels it), or empty. */
    public Optional<Task> blockedBy(Task t) {
        if (!t.has("after_task")) {
            return Optional.empty();
        }
        return store.get(t.num("after_task")).filter(pre -> !preFine(pre));
    }

    private Optional<Task> routedParent(Task t) {
        return t.has("parent") ? store.get(t.num("parent")).filter(p -> p.status().equals("routed")) : Optional.empty();
    }

    private static boolean cancelledFollowup(Task k, boolean automatic) {
        return k.status().equals("cancelled") && k.has("note") && k.str("note").startsWith(FOLLOWUP_NOTE)
                && !(automatic && k.num("no_auto_revive") != 0);
    }

    /** The follow-ups cancelled because this task didn't end well. {@code automatic}: only those a merge may restart. */
    public List<Task> blockedFollowups(Task t, boolean automatic) {
        Optional<Task> parent = routedParent(t);
        if (parent.isEmpty()) {
            return List.of();
        }
        return store.children(parent.get().id()).stream()
                .filter(k -> k.has("after_task") && k.num("after_task") == t.id() && cancelledFollowup(k, automatic))
                .toList();
    }

    @Override
    public void revive(Task t) {
        reviveFollowups(t, false);
    }

    /** Back in the queue, in their order: the follow-ups cancelled because of this task. Returns how many. */
    public int reviveFollowups(Task t, boolean automatic) {
        Optional<Task> parent = routedParent(t);
        if (parent.isEmpty()) {
            return 0;
        }
        Set<Long> revived = new HashSet<>(Set.of(t.id()));
        List<Task> kids = store.children(parent.get().id());
        while (true) {
            // also a task with rounds: the user retried it, and settleRouted cancelled it again before it could start
            List<Task> more = new ArrayList<>();
            for (Task k : kids) {
                if (!revived.contains(k.id()) && k.has("after_task") && revived.contains((long) k.num("after_task"))
                        && cancelledFollowup(k, automatic)) {
                    more.add(k);
                }
            }
            if (more.isEmpty()) {
                break;
            }
            for (Task k : more) {
                if (k.num("rounds") != 0) { // it ran before: resume its session, in a working copy rebuilt if cleaned up
                    store.update(k.id(), TaskStore.fields("reprepare",
                            k.num("reprepare") != 0 || !copies.has(k.id()) ? 1 : 0, "pending_input",
                            k.has("pending_input") && !k.str("pending_input").isEmpty() ? k.str("pending_input")
                                    : CONTINUE_NOTE));
                }
                store.update(k.id(), TaskStore.fields("status", "queued", "ended", null, "note", null));
                revived.add(k.id());
            }
        }
        store.update(parent.get().id(), TaskStore.fields("ended", null)); // the build or message isn't finished any more
        return revived.size() - 1;
    }

    /** Statuses whose merged PR still counts ({@code MERGE_WATCHED} of {@code github.py}). */
    static final Set<String> MERGE_WATCHED = Set.of("refused", "failed", "capped", "cancelled");

    /**
     * Ports {@code check_blocking_pr}: a task that ended badly but whose PR the user merged anyway is fine, so its
     * follow-ups go back in the queue (not those from before the feature). Does nothing for a PR that is open or closed
     * unmerged. The poll loop that calls it for every watched task is K5.
     */
    public void checkBlockingPr(Task t, Repos repos, PrLookup prs) {
        String slug = repos.list().get(t.repo());
        if (slug == null || !t.has("pr_number")) {
            return;
        }
        Optional<Effects.PrState> pr = prs.get(slug, t.num("pr_number"));
        if (pr.isEmpty() || !pr.get().state().equals("closed") || !pr.get().merged()) {
            return;
        }
        store.update(t.id(), TaskStore.fields("status", "merged", "ended", store.now()));
        copies.cleanup(t.id());
        try {
            history.remember(t.id());
        } catch (RuntimeException e) {
            log.warn("taak {}: geschiedenis: {}", t.id(), e.toString());
        }
        int n = reviveFollowups(store.get(t.id()).orElseThrow(), true);
        taskLog.line("t" + t.id(), "info", "PR #" + t.num("pr_number") + " merged: " + n + " follow-up(s) back in the queue");
        notifier.notify(TaskActions.label(t) + ": PR #" + t.num("pr_number") + " merged",
                n + " follow-up(s) back in the queue", pageBase + "/task/" + t.id());
    }

    /** The user says this task is fine: it isn't run again, and its follow-ups go back in the queue. */
    public int acceptTask(Task t) {
        if (!ACCEPTABLE.contains(t.status()) || t.num("accepted") != 0 || blockedFollowups(t, false).isEmpty()) {
            return 0;
        }
        store.update(t.id(), TaskStore.fields("accepted", 1));
        taskLog.line("t" + t.id(), "info", "marked as fine on the page: follow-ups back in the queue");
        return reviveFollowups(store.get(t.id()).orElseThrow(), false);
    }

    private static boolean busy(Task k) {
        return k.status().equals("queued") || k.status().equals("running") || k.status().equals("waiting")
                // H-001: a brief task whose PR the reviewer hasn't checked yet still counts as busy
                || (k.has("brief") && k.status().equals("pr_open") && (!k.has("review") || k.str("review").isEmpty()));
    }

    /** Cancel the follow-ups whose predecessor didn't end well; close a routed message when all its children are done. */
    public void settleRouted() {
        if (!settings.schedulerEnabled()) {
            return;
        }
        for (Task p : store.withStatus("routed")) {
            if (p.has("ended")) {
                continue;
            }
            List<Task> kids = store.children(p.id());
            for (Task k : kids) {
                Optional<Task> pre = k.has("after_task")
                        ? kids.stream().filter(o -> o.id() == k.num("after_task")).findFirst() : Optional.empty();
                if (k.status().equals("queued") && pre.isPresent() && !preFine(pre.get())) {
                    store.update(k.id(), TaskStore.fields("status", "cancelled", "ended", store.now(), "note",
                            FOLLOWUP_NOTE + " #" + pre.get().id() + " was "
                                    + TaskActions.STATUS_NL.getOrDefault(pre.get().status(), pre.get().status())));
                }
            }
            kids = store.children(p.id());
            if (kids.stream().anyMatch(FollowupChain::busy)) {
                continue;
            }
            List<String> lines = new ArrayList<>();
            for (Task k : kids) {
                String what = RoundFinisher.firstLine(firstNonEmpty(k.str("answer"), k.str("summary"), k.str("note")));
                lines.add("- " + k.repo() + " (#" + k.id() + "): "
                        + TaskActions.STATUS_NL.getOrDefault(k.status(), k.status())
                        + (k.has("pr_url") && !k.str("pr_url").isEmpty()
                                ? ", PR #" + k.num("pr_number") + " " + k.str("pr_url") : "")
                        + (!what.isEmpty() ? " - " + what : ""));
            }
            String summary = String.join("\n", lines);
            store.update(p.id(), TaskStore.fields("ended", store.now(), "summary", summary));
            notifier.notify(TaskActions.label(p) + ": klaar", summary, pageBase + "/task/" + p.id());
            try {
                history.remember(p.id());
            } catch (RuntimeException e) {
                log.warn("taak {}: geschiedenis: {}", p.id(), e.toString());
            }
        }
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
