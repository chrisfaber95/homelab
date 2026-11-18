package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.RoundRunner;
import dev.homelab.inbox.core.Effects.TaskLog;
import java.util.List;

/**
 * Recovery after a restart ({@code queue.recover}): a task that was {@code running} when the inbox stopped has its
 * Claude stopped and goes back in the queue, where its own session continues, with a note for Claude. A task that was cut
 * off {@code restartRounds} rounds in a row (it probably crashes the inbox) fails instead. The router and planner start
 * over without a note (a pending input would read as an answer). A brief task whose PR review was cut off is reviewed
 * again. With the scheduler switched off this does nothing: it would stop real sessions.
 */
public class Recovery {

    static final String CONTINUE_HINT = "Your working copy is as you left it: check `git status` and `git log` first, "
            + "then continue where you left off and end with KLAAR: (or VRAAG:/GEWEIGERD:).";
    static final String RESTART_NOTE = "The inbox restarted while you were working on this task, which cut off your "
            + "last turn. " + CONTINUE_HINT;

    private final TaskStore store;
    private final CoreSettings settings;
    private final RoundRunner runner;
    private final TaskLog taskLog;

    public Recovery(TaskStore store, CoreSettings settings, RoundRunner runner, TaskLog taskLog) {
        this.store = store;
        this.settings = settings;
        this.runner = runner;
        this.taskLog = taskLog;
    }

    public void recover() {
        if (!settings.schedulerEnabled()) {
            return;
        }
        List<Task> tasks = store.tasks(200);
        for (Task t : tasks) {
            if (!"running".equals(t.status())) {
                continue;
            }
            runner.stop(t.id());
            int restarts = t.num("restarts");
            if (restarts >= settings.restartRounds()) {
                store.update(t.id(), TaskStore.fields("status", "failed", "ended", store.now(),
                        "note", "onderbroken door een herstart"));
                continue;
            }
            boolean startsOver = CoreSettings.AUTO.equals(t.repo()) || CoreSettings.BRIEF.equals(t.repo());
            store.update(t.id(), TaskStore.fields("status", "queued", "restarts", restarts + 1,
                    "pending_input", startsOver ? null : RESTART_NOTE));
            taskLog.line("t" + t.id(), "info", "interrupted by a restart of the inbox: back in the queue");
        }
        for (Task t : store.tasks(200)) {
            if ("pr_open".equals(t.status()) && t.has("brief") && !t.has("review")) {
                runner.reviewAgain(t.id());
            }
        }
    }
}
