package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.RoundRunner;
import dev.homelab.inbox.core.Effects.TaskLog;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One pass of the scheduler ({@code queue.scheduler} of the Python inbox): start queued tasks, oldest first, within
 * the limits. A queued task starts only when
 * <ul>
 *   <li>fewer than {@code maxRunning} tasks run (all repos together),</li>
 *   <li>fewer than {@code maxPerRepo} tasks of its repo run (the router and the brief planner are exempt: they are short
 *       and touch no repo),</li>
 *   <li>its predecessor ({@code after_task}) is not queued, running or waiting, and, for a brief task, not {@code pr_open}
 *       (a brief task starts only after the PR of its predecessor is merged). A predecessor that is not among the newest
 *       200 tasks counts as queued, as in Python.</li>
 * </ul>
 * A started task is marked {@code running} first and its round is handed to the executor. With the scheduler switched
 * off, {@link #tick()} does nothing at all.
 */
public class Scheduler {

    static final Set<String> ACTIVE = Set.of("queued", "running");
    private static final int WINDOW = 200;
    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    private final TaskStore store;
    private final CoreSettings settings;
    private final RoundRunner runner;
    private final TaskLog taskLog;
    private final Executor executor;

    public Scheduler(TaskStore store, CoreSettings settings, RoundRunner runner, TaskLog taskLog, Executor executor) {
        this.store = store;
        this.settings = settings;
        this.runner = runner;
        this.taskLog = taskLog;
        this.executor = executor;
    }

    /** Returns the ids it started, in the order it started them. */
    public synchronized List<Long> tick() {
        if (!settings.schedulerEnabled()) {
            return List.of();
        }
        List<Task> all = store.tasks(WINDOW);
        Map<Long, Task> byId = new HashMap<>();
        all.forEach(t -> byId.put(t.id(), t));
        int running = 0;
        Map<String, Integer> perRepo = new HashMap<>();
        for (Task t : all) {
            if ("running".equals(t.status())) {
                running++;
                perRepo.merge(t.repo(), 1, Integer::sum);
            }
        }
        List<Long> started = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0; i--) {
            Task t = all.get(i);
            if (!"queued".equals(t.status())) {
                continue;
            }
            if (running >= settings.maxRunning()) {
                break;
            }
            boolean routerOrPlanner = CoreSettings.AUTO.equals(t.repo()) || CoreSettings.BRIEF.equals(t.repo());
            if (perRepo.getOrDefault(t.repo(), 0) >= settings.maxPerRepo() && !routerOrPlanner) {
                continue;
            }
            if (waitsForPredecessor(t, byId)) {
                continue;
            }
            store.update(t.id(), TaskStore.fields("status", "running"));
            running++;
            perRepo.merge(t.repo(), 1, Integer::sum);
            started.add(t.id());
            long id = t.id();
            executor.execute(() -> {
                try {
                    runner.runRound(id);
                } catch (RuntimeException e) {
                    log.error("round of task {} failed: {}", id, e.toString());
                    taskLog.line("t" + id, "error", "round failed: " + e);
                }
            });
        }
        return started;
    }

    private static boolean waitsForPredecessor(Task t, Map<Long, Task> byId) {
        Long after = t.longOrNull("after_task");
        if (after == null || after == 0) {
            return false;
        }
        Task pre = byId.get(after);
        String status = pre == null ? "queued" : pre.status();
        return ACTIVE.contains(status) || "waiting".equals(status) || (t.has("brief") && "pr_open".equals(status));
    }
}
