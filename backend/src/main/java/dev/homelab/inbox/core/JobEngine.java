package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.Effects.JobIo;
import dev.homelab.inbox.core.Effects.Notifier;
import dev.homelab.inbox.core.Effects.TaskLog;
import dev.homelab.inbox.core.Effects.WorkingCopies;
import dev.homelab.inbox.read.Jobs.Job;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * Jobs and research in Java (H-026 K6), a port of the run side of {@code jobs.py}: the scheduler tick (a job that was due
 * since its last run runs once, also after downtime), dispatch, the daily cap of Claude runs with its one held run per
 * job, local runs through the local model, research questions, delivery of a result and the end of a research task.
 *
 * <p>Shadow only: every contact with the outside goes through {@link Effects}, the scheduler tick does nothing while
 * {@code core.scheduler.enabled} is off, and nothing here is reachable from a route. A Claude job only becomes a
 * {@code queued} row in {@code tasks}; starting it is the scheduler's job (K1), which is off as well.
 */
public class JobEngine {

    private final JobRuns runs;
    private final TaskStore tasks;
    private final CoreSettings core;
    private final JobSettings settings;
    private final Supplier<Map<String, Job>> jobs;
    private final JobIo io;
    private final Notifier notifier;
    private final TaskLog log;
    private final ConversationNotes notes;
    private final WorkingCopies copies;
    private final Cancellations cancelledRuns;
    private final Executor background;
    private final Clock clock;
    private final String pageBase;
    private final Object lock = new Object();

    public JobEngine(JobRuns runs, TaskStore tasks, CoreSettings core, JobSettings settings,
                     Supplier<Map<String, Job>> jobs, JobIo io, Notifier notifier, TaskLog log, ConversationNotes notes,
                     WorkingCopies copies, Cancellations cancelledRuns, Executor background, Clock clock, String pageBase) {
        this.runs = runs;
        this.tasks = tasks;
        this.core = core;
        this.settings = settings;
        this.jobs = jobs;
        this.io = io;
        this.notifier = notifier;
        this.log = log;
        this.notes = notes;
        this.copies = copies;
        this.cancelledRuns = cancelledRuns;
        this.background = background;
        this.clock = clock;
        this.pageBase = pageBase;
    }

    private String pageUrl(Long taskId) {
        return taskId == null ? pageBase : pageBase + "/task/" + taskId;
    }

    /** {@code today_start}: midnight in the job time zone, as UTC. */
    String todayStart() {
        return JobText.iso(clock.instant().atZone(settings.tz()).toLocalDate().atStartOfDay(settings.tz())
                .withZoneSameInstant(ZoneOffset.UTC));
    }

    public int claudeRunsToday() {
        return runs.claudeRunsSince(todayStart());
    }

    // ---- the scheduler ----

    /** One pass of the job scheduler: every job that was due since its previous run runs once. Does nothing while the
     * scheduler is off. */
    public void tick() {
        if (!core.schedulerEnabled()) {
            return;
        }
        Instant now = clock.instant();
        for (Job job : List.copyOf(jobs.get().values())) {
            if (!job.ok()) {
                continue;
            }
            Optional<String> last = runs.lastDue(job.name());
            if (last.isEmpty() || job.paused()) {
                // New or paused: count from now, so there is no catch-up run for the time before.
                runs.setLastDue(job.name(), JobText.iso(now));
                continue;
            }
            ZonedDateTime due = null;
            ZonedDateTime next = job.cron().nextAfter(JobText.parseInstant(last.get()), settings.tz());
            for (int i = 0; i < 5000; i++) {
                if (next.toInstant().isAfter(now)) {
                    break;
                }
                due = next;
                next = job.cron().nextAfter(due.toInstant(), settings.tz());
            }
            if (due != null) {
                runs.setLastDue(job.name(), JobText.iso(due));
                dispatch(job, JobText.iso(due.withZoneSameInstant(ZoneOffset.UTC)), false);
            }
        }
        releaseHeld();
    }

    public long dispatch(Job job, String due, boolean manual) {
        long runId;
        synchronized (lock) {
            runId = runs.insertRun(job.name(), job.kind(), job.model(), due, tasks.now(), "queued", manual, job.minutes(),
                    job.mode(), null, null);
        }
        background.execute(() -> runJob(runId, job));
        return runId;
    }

    public void runJob(long runId, Job job) {
        try {
            if ("local".equals(job.model())) {
                runLocal(runId, job);
            } else {
                startClaude(runId, job);
            }
        } catch (RuntimeException e) {
            log.line("system", "error", "job " + job.name() + ": " + e.getMessage());
            runs.updateRun(runId, TaskStore.fields("status", "failed", "ended", tasks.now(), "note", cut(e.getMessage(), 500)));
            notifier.notify("Job " + job.name() + " mislukt", String.valueOf(e.getMessage()), jobUrl(job.name()));
        }
    }

    private String jobUrl(String name) {
        return pageBase + "/job/" + name;
    }

    private static String cut(String s, int n) {
        String v = String.valueOf(s);
        return v.length() > n ? v.substring(0, n) : v;
    }

    // ---- the daily cap and the Claude run ----

    /** A Claude job becomes an inbox task, under a daily cap shared by jobs and research. At the cap the run is held
     * (at most one held run per job: a second one is dropped) and the user is told once a day. */
    public void startClaude(long runId, Job job) {
        synchronized (lock) {
            int cap = settings.dailyCap();
            if (claudeRunsToday() >= cap) {
                if (runs.otherHeldRun(job.name(), runId)) {
                    runs.deleteRun(runId);
                } else {
                    runs.updateRun(runId, TaskStore.fields("status", "held", "note",
                            "daglimiet van " + cap + " Claude-runs bereikt; draait morgen"));
                }
                if (!runs.kvGet("cap_notified").equals(Optional.of(todayStart()))) {
                    runs.kvSet("cap_notified", todayStart());
                    notifier.notify("Jobs: daglimiet Claude bereikt", cap + " Claude-runs vandaag; verdere Claude-jobs "
                            + "wachten tot morgen. Lokale jobs en je eigen taken lopen gewoon.", pageBase + "/jobs");
                }
                return;
            }
            String text = JobText.jobPrompt(job, needsData(job) ? gather(job, runId) : "",
                    settings.researchLimits(job.mode()));
            String now = tasks.now();
            long taskId = runs.insertJobTask(job.repo() == null ? "homelab" : job.repo(), text, now,
                    UUID.randomUUID().toString(), job.name(), runId);
            runs.updateRun(runId, TaskStore.fields("status", "claude", "task_id", taskId, "dispatched", now, "note", null));
        }
    }

    private static boolean needsData(Job job) {
        return List.of("box-report", "digest", "repo-watch").contains(job.kind());
    }

    /** What was held yesterday or earlier goes first today. */
    public void releaseHeld() {
        if (!core.schedulerEnabled()) {
            return;
        }
        for (Map<String, Object> r : runs.heldBefore(todayStart())) {
            if (claudeRunsToday() >= settings.dailyCap()) {
                break;
            }
            long id = ((Number) r.get("id")).longValue();
            Job job = jobs.get().get((String) r.get("job"));
            if (job == null || !job.ok() || job.paused()) {
                runs.updateRun(id, TaskStore.fields("status", "cancelled", "ended", tasks.now(), "note",
                        "job weg, gepauzeerd of met een fout"));
                continue;
            }
            runJob(id, job);
        }
    }

    // ---- research ----

    public record Asked(int code, Map<String, Object> body) {}

    /** A research question from the page ({@code source} null) or a chat client: one Claude run, counted against the
     * daily cap. Above the cap it is refused and nothing is queued. */
    public Asked askResearch(String question, String mode, String source) {
        String q = question == null ? "" : question.strip();
        if (q.isEmpty() || !JobText.RESEARCH_MODES.contains(mode)) {
            return new Asked(400, Map.of("error", "give a question and a mode: "
                    + String.join(" or ", JobText.RESEARCH_MODES)));
        }
        int cap = settings.dailyCap();
        String deliver = source != null ? "conversation" : "page"; // to where the question came from
        Job job = new Job(JobText.RESEARCH_JOB, null, null, "", "", "research", "claude", false, null, List.of(), null,
                null, List.of(), List.of(), deliver, mode, q.length() > 4000 ? q.substring(0, 4000) : q);
        synchronized (lock) { // check and start together, so two questions cannot both slip under the cap
            if (claudeRunsToday() >= cap) {
                return new Asked(429, Map.of("error", "daily limit reached: " + cap + " Claude runs today; research and "
                        + "Claude jobs share it. Nothing was started; ask again tomorrow."));
            }
            String now = tasks.now();
            long runId = runs.insertRun(JobText.RESEARCH_JOB, "research", "claude", now, now, "queued", true, null, mode,
                    deliver, source);
            startClaude(runId, job);
            return new Asked(200, Map.of("run", runId, "mode", mode, "url", pageBase + "/research"));
        }
    }

    /** The job_runs row of a task when the task is a research run. */
    public Optional<Map<String, Object>> researchRun(Task t) {
        if (!t.has("job") || !t.has("run_id")) {
            return Optional.empty();
        }
        return runs.run(t.longOrNull("run_id")).filter(r -> "research".equals(r.get("kind")));
    }

    /** The job a task is the run of: the file's job, or for a research question from the page or a chat client the job
     * made up for it. */
    public Optional<Job> jobOfTask(Task t) {
        if (!t.has("job")) {
            return Optional.empty();
        }
        Job file = jobs.get().get(t.str("job"));
        if (file != null) {
            return Optional.of(file);
        }
        return researchRun(t).map(r -> new Job(t.str("job"), null, null, "", "", "research", "claude", false, null,
                List.of(), null, null, List.of(), List.of(), r.get("deliver") == null ? "page" : (String) r.get("deliver"),
                (String) r.get("mode"), t.str("text")));
    }

    // ---- delivery ----

    /** Send a successful result where the job file says, in full, with the link to the run. Nothing for the default
     * (page), for an empty result and for the job's own "nothing to report" marker. Never throws: the run stays ok. */
    public boolean deliverResult(Job job, Long runId, String text, String link) {
        if (job == null || "page".equals(job.deliver() == null ? "page" : job.deliver()) || text == null
                || text.isBlank()) {
            return false;
        }
        if (JobText.isNothing(text)) {
            log.line("r" + runId, "info", "niets te melden: niets bezorgd");
            return false;
        }
        try {
            String title = "Job " + job.name();
            if ("ntfy".equals(job.deliver())) {
                notifier.notify(title, text.strip(), link, JobText.NTFY_DELIVER_CHARS);
            } else {
                notes.note("box", "inbox", "notification", title + "\n" + text.strip(), link, "job:" + job.name() + ":" + runId);
            }
            return true;
        } catch (RuntimeException e) {
            log.line("system", "error", "job " + job.name() + ": bezorgen mislukt: " + e.getMessage());
            return false;
        }
    }

    // ---- local runs ----

    /** A local job: gather text and let the local model summarise it. No Claude. */
    public void runLocal(long runId, Job job) {
        runs.updateRun(runId, TaskStore.fields("status", "running", "started", tasks.now()));
        String key = "r" + runId;
        try {
            log.line(key, "info", JobText.KIND_NL.get(job.kind()) + ": tekst verzamelen");
            String text = gather(job, runId);
            boolean script = job.kind().equals("script");
            log.line(key, script ? "out" : "info", script ? tail(text, 4000) : text.length() + " tekens verzameld");
            if (cancelledRuns.requested(runId)) {
                throw new InterruptedException();
            }
            if (text.length() > settings.localMaxChars()) {
                text = text.substring(0, settings.localMaxChars()) + "\n[afgekapt]";
                log.line(key, "info", "afgekapt op " + settings.localMaxChars() + " tekens");
            }
            log.line(key, "tool", "lokaal model: samenvatten");
            long started = System.nanoTime();
            String summary = io.summarize(job.name(), job.instruction(), text);
            if (cancelledRuns.requested(runId)) {
                throw new InterruptedException();
            }
            log.line(key, "claude", summary);
            log.line(key, "info", "klaar in " + Math.round((System.nanoTime() - started) / 1e9) + " s");
            runs.updateRun(runId, TaskStore.fields("status", "ok", "ended", tasks.now(), "summary", summary));
            deliverResult(job, runId, summary, pageBase + "/run/" + runId);
        } catch (InterruptedException e) {
            cancelled(key, runId);
        } catch (Exception e) {
            if (cancelledRuns.requested(runId)) {
                cancelled(key, runId);
            } else {
                log.line(key, "error", String.valueOf(e.getMessage()));
                runs.updateRun(runId, TaskStore.fields("status", "failed", "ended", tasks.now(), "note",
                        cut(e.getMessage(), 500)));
                notifier.notify("Job " + job.name() + " mislukt", String.valueOf(e.getMessage()), jobUrl(job.name()));
            }
        } finally {
            cancelledRuns.clear(runId);
        }
    }

    private void cancelled(String key, long runId) {
        log.line(key, "info", "geannuleerd");
        runs.updateRun(runId, TaskStore.fields("status", "cancelled", "ended", tasks.now(), "note",
                "geannuleerd op de pagina"));
    }

    private static String tail(String s, int n) {
        return s.length() > n ? s.substring(s.length() - n) : s;
    }

    // ---- gathering ----

    /** The text a local job (or a Claude box report or digest) works with. */
    String gather(Job job, long runId) {
        try {
            switch (job.kind()) {
                case "box-report":
                    return io.boxOverview();
                case "digest": {
                    int per = settings.localMaxChars() / job.sources().size();
                    List<String> parts = job.sources().stream().map(s -> head(fetchSource(s), per)).toList();
                    if (parts.stream().allMatch(p -> p.contains("(ophalen mislukt"))) {
                        throw new IllegalStateException("geen enkele bron kon worden opgehaald");
                    }
                    return String.join("\n\n", parts);
                }
                case "repo-watch": {
                    String since = JobText.sinceDay(runs.previousRunTime(job.name(), runId).orElse(null), clock.instant(),
                            settings.tz(), 31);
                    List<String> parts = job.sources().stream()
                            .map(s -> SourceReader.watchRepo(io, s.substring("github:".length()), since)).toList();
                    if (parts.stream().allMatch(p -> p.contains("(ophalen mislukt"))) {
                        throw new IllegalStateException("geen enkele repo kon worden opgehaald");
                    }
                    return String.join("\n\n", parts);
                }
                case "script": {
                    Effects.ScriptResult r = io.runScript(job.repo(), job.script(), job.args(), runId);
                    if (r.code() == 65) { // repo or path invalid, the script did not run
                        throw new IllegalStateException(tail(r.output(), 300));
                    }
                    return "$ " + job.script() + " " + String.join(" ", job.args()) + "\n(exitcode " + r.code() + ")\n\n"
                            + r.output();
                }
                default:
                    throw new IllegalStateException("een " + job.kind() + " draait niet lokaal");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private String fetchSource(String src) {
        return SourceReader.fetchSource(io, src, clock.instant());
    }

    private static String head(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    // ---- the history ----

    public record RunView(String status, String summary, String url, String prUrl) {}

    /** Status, summary and link of a run; for a Claude run from its task. */
    public RunView runView(Map<String, Object> run) {
        Number taskId = (Number) run.get("task_id");
        Optional<Task> task = taskId == null ? Optional.empty() : tasks.get(taskId.longValue());
        if (task.isPresent()) {
            Task t = task.get();
            String summary = first(t.str("answer"), t.str("summary"), t.str("question"), t.str("note"));
            return new RunView(JobText.TASK_TO_RUN.get(t.status()), summary, "/task/" + t.id(), t.str("pr_url"));
        }
        return new RunView((String) run.get("status"), first((String) run.get("summary"), (String) run.get("note")),
                "/run/" + run.get("id"), null);
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }
}
