package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.MemorySteps;
import dev.homelab.inbox.core.Effects.Notifier;
import dev.homelab.inbox.core.Effects.Repos;
import dev.homelab.inbox.core.Effects.Result;
import dev.homelab.inbox.core.Effects.Sessions;
import dev.homelab.inbox.core.Effects.TaskCommands;
import dev.homelab.inbox.core.Effects.TaskLog;
import dev.homelab.inbox.core.Effects.WorkingCopies;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One round of a task from the queue's side ({@code queue.run_round}, H-026 K2): make or refresh the working copy,
 * merge main in for a task that continues without a PR, write the prompt, run Claude, and hand the result to
 * {@link RoundFinisher}. The router and the brief planner (K3, K4) are behind {@link Sessions}. With the shadow effects
 * nothing runs; the scheduler that calls this is off by default.
 */
public class RoundFlow {

    private static final Logger log = LoggerFactory.getLogger(RoundFlow.class);

    private final TaskStore store;
    private final Repos repos;
    private final MemorySteps memory;
    private final TaskCommands commands;
    private final WorkingCopies copies;
    private final Sessions sessions;
    private final CatchUp catchUp;
    private final RoundFinisher finisher;
    private final TaskLog taskLog;
    private final Notifier notifier;
    private final String pageBase;

    public RoundFlow(TaskStore store, Repos repos, MemorySteps memory, TaskCommands commands, WorkingCopies copies,
                     Sessions sessions, CatchUp catchUp, RoundFinisher finisher, TaskLog taskLog, Notifier notifier,
                     String pageBase) {
        this.store = store;
        this.repos = repos;
        this.memory = memory;
        this.commands = commands;
        this.copies = copies;
        this.sessions = sessions;
        this.catchUp = catchUp;
        this.finisher = finisher;
        this.taskLog = taskLog;
        this.notifier = notifier;
        this.pageBase = pageBase;
    }

    public void run(long taskId) {
        Task t = store.get(taskId).orElseThrow();
        Map<String, String> list = repos.list();
        if (CoreSettings.AUTO.equals(t.repo())) {
            sessions.route(taskId);
            return;
        }
        if (CoreSettings.BRIEF.equals(t.repo())) {
            sessions.plan(taskId);
            return;
        }
        if (!list.containsKey(t.repo())) {
            store.update(taskId, TaskStore.fields("status", "failed", "ended", store.now(),
                    "note", "repo staat niet (meer) in de lijst"));
            return;
        }
        String cache;
        Sessions.Outcome outcome;
        try {
            cache = repos.refresh(t.repo(), list.get(t.repo()));
            if (!t.repo().equals("homelab")) {
                repos.refresh("homelab", list.get("homelab")); // the skills come from main of homelab
            }
            memory.load(); // what the user just deleted or changed applies to this round already
            boolean first = t.num("rounds") == 0;
            if (first || t.num("reprepare") != 0) {
                Result r = commands.prepare(taskId, t.repo());
                if (r.code() != 0) {
                    String out = r.out();
                    throw new IllegalStateException("werkkopie maken mislukt: "
                            + out.substring(Math.max(0, out.length() - 300)));
                }
            }
            String prompt;
            if (first) {
                prompt = "Taak voor de repo " + t.repo() + ":\n\n" + t.str("text");
            } else {
                prompt = t.str("pending_input");
                String extra = catchUp.catchUp(t);
                if (!extra.isEmpty()) {
                    prompt = (prompt == null ? "" : prompt) + extra;
                }
            }
            sessions.writePrompt(t, prompt);
            store.update(taskId, TaskStore.fields("status", "running", "started",
                    t.has("started") ? t.str("started") : store.now(), "rounds", t.num("rounds") + 1,
                    "pending_input", null, "reprepare", 0));
            taskLog.line("t" + taskId, "info", "ronde " + (t.num("rounds") + 1) + " gestart"
                    + (first ? "" : ": " + RoundFinisher.firstLine(prompt)));
            outcome = sessions.stream(taskId, first ? "new" : "resume");
        } catch (RuntimeException e) {
            log.warn("task {}: {}", taskId, e.toString());
            String why = String.valueOf(e.getMessage());
            store.update(taskId, TaskStore.fields("status", "failed", "ended", store.now(),
                    "note", why.length() > 500 ? why.substring(0, 500) : why));
            if (!t.has("pr_number")) {
                copies.cleanup(taskId);
            }
            notifier.notify(TaskActions.label(t) + " mislukt", why, pageBase + "/task/" + taskId);
            memory.afterRound(taskId, List.of());
            return;
        }
        store.update(taskId, TaskStore.fields("restarts", 0));
        MemorySteps.MemorySplit split = memory.split(outcome.result() == null ? "" : outcome.result());
        try {
            finisher.finish(taskId, cache, split.text(), outcome.permissionDenials(), outcome.capped());
        } finally {
            memory.afterRound(taskId, outcome.capped() == null ? split.blocks() : List.of());
        }
    }
}
