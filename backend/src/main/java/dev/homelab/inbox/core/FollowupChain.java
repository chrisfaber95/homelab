package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.Followups;
import dev.homelab.inbox.core.Effects.WorkingCopies;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The follow-ups of a task that ended badly ({@code router.blocked_followups} and {@code revive_followups}), as far as
 * GitHub polling needs them (K5): after the user merged the PR of a refused, failed, capped or cancelled task, the
 * follow-ups that {@code settle_routed} cancelled go back in the queue, in their order. K3 ports the rest of the router
 * and wires this class; until then it is only used by tests and by the poller when it is given one.
 */
public class FollowupChain implements Followups {

    /** Live data: the note on a follow-up that settle_routed cancelled. */
    public static final String FOLLOWUP_NOTE = "niet gestart: de voorgaande taak";
    static final String RESUME_HINT = "The task before this one stopped early and the user continued it, so this task is "
            + "back in the queue. Check `git status` and `git log` first, then continue where you left off and end with "
            + "KLAAR: (or VRAAG:/GEWEIGERD:).";

    private final TaskStore store;
    private final WorkingCopies copies;

    public FollowupChain(TaskStore store, WorkingCopies copies) {
        this.store = store;
        this.copies = copies;
    }

    private Optional<Task> routedParent(Task t) {
        Long parent = t.longOrNull("parent");
        return parent == null ? Optional.empty() : store.get(parent).filter(p -> "routed".equals(p.status()));
    }

    private static boolean isFollowup(Task k, boolean automatic) {
        String note = k.str("note");
        return "cancelled".equals(k.status()) && note != null && note.startsWith(FOLLOWUP_NOTE)
                && !(automatic && k.num("no_auto_revive") != 0);
    }

    @Override
    public List<Task> blocked(Task t, boolean automatic) {
        Optional<Task> parent = routedParent(t);
        if (parent.isEmpty()) {
            return List.of();
        }
        List<Task> out = new ArrayList<>();
        for (Task k : store.children(parent.get().id())) {
            if (k.longOrNull("after_task") != null && k.longOrNull("after_task") == t.id() && isFollowup(k, automatic)) {
                out.add(k);
            }
        }
        return out;
    }

    @Override
    public void revive(Task t) {
        revive(t, false);
    }

    @Override
    public int reviveAfterMerge(Task t) {
        return revive(t, true);
    }

    private int revive(Task t, boolean automatic) {
        Optional<Task> parent = routedParent(t);
        if (parent.isEmpty()) {
            return 0;
        }
        Set<Long> revived = new HashSet<>(Set.of(t.id()));
        List<Task> kids = store.children(parent.get().id());
        while (true) {
            List<Task> more = new ArrayList<>();
            for (Task k : kids) {
                Long after = k.longOrNull("after_task");
                // a task with rounds counts too: the user retried it and settle_routed cancelled it again
                if (!revived.contains(k.id()) && after != null && revived.contains(after) && isFollowup(k, automatic)) {
                    more.add(k);
                }
            }
            if (more.isEmpty()) {
                break;
            }
            for (Task k : more) {
                if (k.num("rounds") != 0) { // it ran before: resume its session, in a working copy rebuilt if cleaned up
                    store.update(k.id(), TaskStore.fields("reprepare",
                            k.num("reprepare") != 0 || !copies.has(k.id()) ? 1 : 0,
                            "pending_input", k.has("pending_input") && !k.str("pending_input").isEmpty()
                                    ? k.str("pending_input") : RESUME_HINT));
                }
                store.update(k.id(), TaskStore.fields("status", "queued", "ended", null, "note", null));
                revived.add(k.id());
            }
        }
        store.update(parent.get().id(), TaskStore.fields("ended", null)); // the build or message isn't finished any more
        return revived.size() - 1;
    }
}
