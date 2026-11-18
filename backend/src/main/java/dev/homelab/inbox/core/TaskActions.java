package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.Effects.Followups;
import dev.homelab.inbox.core.Effects.RoundRunner;
import dev.homelab.inbox.core.Effects.TaskLog;
import dev.homelab.inbox.core.Effects.WorkingCopies;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * What the user (or a chat client) does to a task: answer, cancel, retry or continue; plus the sweep of old working
 * copies. Ports {@code answer_task}, {@code cancel_task}, {@code retry_task} and {@code sweep_capped} of
 * {@code queue.py}. The side effects go through {@link Effects}.
 */
public class TaskActions {

    static final String LEARN_HINT = "Is iets hiervan een correctie die ook voor latere taken geldt, bewaar het dan "
            + "als voorkeur (GEHEUGEN: voorkeur ...).";
    static final Map<String, String> STATUS_NL = Map.ofEntries(Map.entry("queued", "in de rij"),
            Map.entry("running", "bezig"), Map.entry("waiting", "wacht op jou"), Map.entry("pr_open", "PR open"),
            Map.entry("answered", "beantwoord"), Map.entry("capped", "gestopt bij limiet"),
            Map.entry("cancelled", "geannuleerd"), Map.entry("failed", "mislukt"), Map.entry("refused", "geweigerd"),
            Map.entry("merged", "gemerged"), Map.entry("closed", "PR gesloten"), Map.entry("routed", "doorgestuurd"));
    private static final int TEXT_LIMIT = 8000;

    private final TaskStore store;
    private final CoreSettings settings;
    private final Cancellations cancellations;
    private final RoundRunner runner;
    private final WorkingCopies copies;
    private final TaskLog taskLog;
    private final ConversationNotes notes;
    private final Followups followups;
    private final Executor background;
    private final Clock clock;
    private final String pageBase;
    private final Set<Long> swept = new HashSet<>();

    public TaskActions(TaskStore store, CoreSettings settings, Cancellations cancellations, RoundRunner runner,
                       WorkingCopies copies, TaskLog taskLog, ConversationNotes notes, Followups followups,
                       Executor background, Clock clock, String pageBase) {
        this.store = store;
        this.settings = settings;
        this.cancellations = cancellations;
        this.runner = runner;
        this.copies = copies;
        this.taskLog = taskLog;
        this.notes = notes;
        this.followups = followups;
        this.background = background;
        this.clock = clock;
        this.pageBase = pageBase;
    }

    static String label(Task t) {
        return t.has("job") ? "Job " + t.str("job") : "Inbox " + t.id();
    }

    private String pageUrl(long id) {
        return pageBase + "/task/" + id;
    }

    /** Continue a task with the user's reply: an answer to a question (waiting) or a reaction to an answer (answered). */
    public boolean answer(Task t, String text, String source) {
        text = text == null ? "" : text.strip();
        if (text.isEmpty() || !(t.status().equals("waiting") || t.status().equals("answered"))
                || (t.status().equals("answered") && t.has("job"))) {
            return false;
        }
        String reply = text.length() > TEXT_LIMIT ? text.substring(0, TEXT_LIMIT) : text;
        notes.note("chris", source == null ? "page" : source, "message", text, pageUrl(t.id()), "task:" + t.id());
        if (t.status().equals("waiting")) {
            store.update(t.id(), TaskStore.fields("status", "queued", "answer", null, "pending_input",
                    "Antwoord van de gebruiker op je vraag:\n\n" + reply + "\n\nGa verder met de taak. Stel geen nieuwe "
                            + "vraag; neem waar nodig een redelijke aanname en noem die. " + LEARN_HINT,
                    "mem_source", "jouw antwoord in " + label(t).toLowerCase(), "mem_link", pageUrl(t.id())));
        } else {
            // A follow-up on an answer: same session, new working copy.
            store.update(t.id(), TaskStore.fields("status", "queued", "question", t.str("answer"), "answer", null,
                    "ended", null, "reprepare", 1, "pending_input",
                    "Reactie van de gebruiker op je antwoord:\n\n" + reply + "\n\nGa verder met de taak. Neem waar "
                            + "nodig een redelijke aanname en noem die. " + LEARN_HINT,
                    "mem_source", "jouw reactie in " + label(t).toLowerCase(), "mem_link", pageUrl(t.id())));
        }
        return true;
    }

    /** A running task is stopped (its round ends as "geannuleerd"); any other task is cancelled right away. */
    public synchronized void cancel(Task t) {
        if (t.status().equals("running")) {
            cancellations.request(t.id());
            background.execute(() -> runner.stop(t.id()));
        } else {
            store.update(t.id(), TaskStore.fields("status", "cancelled", "ended", store.now()));
            if (t.status().equals("waiting") && !t.has("pr_number")) {
                background.execute(() -> copies.cleanup(t.id()));
            }
        }
    }

    /** Retry on the page ("Continue" for a capped task): the task goes back in the queue. */
    public void retry(Task t, String note) {
        note = note == null ? "" : note.strip();
        note = note.length() > TEXT_LIMIT ? note.substring(0, TEXT_LIMIT) : note;
        String extra = note.isEmpty() ? "" : "\n\nNote from the user with this retry:\n\n" + note;
        if (t.status().equals("refused")) {
            extra = "\n\nYou refused it earlier with this reason:\n\n" + (t.has("summary") ? t.str("summary") : "(none given)")
                    + "\n\nDon't refuse again for the same reason unless the user's note or the task gives you a new one."
                    + extra;
        }
        if (t.num("rounds") > 0) {
            boolean fresh = !copies.has(t.id());
            String why = t.has("note") ? t.str("note") : STATUS_NL.get(t.status());
            String hint;
            if (!fresh) {
                hint = Recovery.CONTINUE_HINT;
            } else if (copies.pushed(t)) {
                hint = "Your working copy was cleaned up and rebuilt from your commits on inbox/" + t.id()
                        + ": uncommitted work is gone. " + Recovery.CONTINUE_HINT;
            } else {
                hint = "Your working copy was cleaned up: this is a fresh copy of main on inbox/" + t.id() + ", so work "
                        + "from earlier rounds that isn't on main is gone. Do the task again and end with KLAAR: (or "
                        + "VRAAG:/GEWEIGERD:).";
            }
            store.update(t.id(), TaskStore.fields("status", "queued", "ended", null, "note", null, "restarts", 0,
                    "accepted", 0, "reprepare", fresh ? 1 : 0,
                    "summary", t.status().equals("refused") ? null : t.str("summary"),
                    "pending_input", "This task stopped before it was finished (" + why + ") and the user asked to "
                            + (t.status().equals("capped") ? "continue" : "retry") + " it. " + hint + extra));
        } else {
            // never started: its first round reads the task text, not pending_input
            store.update(t.id(), TaskStore.fields("status", "queued", "ended", null, "note", null, "accepted", 0,
                    "text", t.str("text") + extra));
        }
        taskLog.line("t" + t.id(), "info", "retried on the page: back in the queue");
        followups.revive(t);
    }

    /**
     * Remove the working copy of a task that stopped at the limit and wasn't continued for {@code cappedKeepDays}. Its
     * commits are on inbox/id, so a later Continue rebuilds the copy from those; only uncommitted work is gone. Once per
     * task per process. With the scheduler off nothing is touched.
     */
    public synchronized void sweepCapped() {
        if (!settings.schedulerEnabled()) {
            return;
        }
        Instant limit = clock.instant().minus(Duration.ofDays(settings.cappedKeepDays()));
        for (Task t : store.tasks(200)) {
            if (!t.status().equals("capped") || t.has("pr_number") || swept.contains(t.id()) || !t.has("ended")
                    || OffsetDateTime.parse(t.str("ended")).toInstant().isAfter(limit)) {
                continue;
            }
            swept.add(t.id());
            if (!copies.has(t.id())) {
                continue;
            }
            copies.cleanup(t.id());
            taskLog.line("t" + t.id(), "info", "working copy removed: not continued since it stopped at the limit");
            if (store.get(t.id()).map(Task::status).orElse("").equals("queued")) { // continued in the same moment
                store.update(t.id(), TaskStore.fields("reprepare", 1));
            }
        }
    }
}
