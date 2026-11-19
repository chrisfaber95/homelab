package dev.homelab.inbox.core;

import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.Jobs.Job;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * The data behind the job pages (K6), a port of {@code jobs_data}, {@code job_detail}, {@code draft_detail},
 * {@code run_detail}, {@code research_data} and {@code job_facts} of {@code overview.py}: the same keys, the same
 * Dutch texts, as maps for JSON. Only reads; no route serves it yet (that is the front door, K8 and H-027).
 */
public class JobPages {

    static final List<String> DAYS_NL = List.of("ma", "di", "wo", "do", "vr", "za", "zo");
    static final Map<String, String> RUN_NL = Map.of("queued", "in de rij", "running", "bezig", "ok", "ok", "failed",
            "mislukt", "held", "wacht op limiet", "question", "vraag", "pr", "PR", "capped", "gestopt bij limiet",
            "cancelled", "geannuleerd");

    private final JobEngine engine;
    private final JobRuns runs;
    private final JobSettings settings;
    private final Supplier<Map<String, Job>> jobs;
    private final AgentCatalogue catalogue;
    private final Jobs parser;
    private final Clock clock;

    public JobPages(JobEngine engine, JobRuns runs, JobSettings settings, Supplier<Map<String, Job>> jobs,
                    AgentCatalogue catalogue, Jobs parser, Clock clock) {
        this.engine = engine;
        this.runs = runs;
        this.settings = settings;
        this.jobs = jobs;
        this.catalogue = catalogue;
        this.parser = parser;
        this.clock = clock;
    }

    private ZoneId tz() {
        return settings.tz();
    }

    static String firstLine(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return "taak";
        }
        String line = t.lines().findFirst().orElse("");
        return line.length() > 70 ? line.substring(0, 70) : line;
    }

    /** {@code local_time}: "dd-MM HH:mm" in the inbox time zone, "-" when empty, the text as is when it is no date. */
    String localTime(String iso) {
        if (iso == null || iso.isEmpty()) {
            return "-";
        }
        try {
            return JobText.parseInstant(iso).atZone(tz()).format(DateTimeFormatter.ofPattern("dd-MM HH:mm"));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    /** {@code day_time}: "vandaag 07:00", "morgen 07:00", "gisteren 22:10", "wo 07:00"; with a date from a week away. */
    String dayTime(ZonedDateTime when) {
        ZonedDateTime d = when.withZoneSameInstant(tz());
        long delta = d.toLocalDate().toEpochDay() - clock.instant().atZone(tz()).toLocalDate().toEpochDay();
        String word = delta == 0 ? "vandaag" : delta == 1 ? "morgen" : delta == -1 ? "gisteren" : null;
        if (word == null) {
            word = DAYS_NL.get(d.getDayOfWeek().getValue() - 1)
                    + (Math.abs(delta) < 7 ? "" : " " + d.format(DateTimeFormatter.ofPattern("dd-MM")));
        }
        return word + " " + d.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** {@code when}: "wo 18-11 07:00". */
    String when(ZonedDateTime t) {
        ZonedDateTime d = t.withZoneSameInstant(tz());
        return DAYS_NL.get(d.getDayOfWeek().getValue() - 1) + " " + d.format(DateTimeFormatter.ofPattern("dd-MM HH:mm"));
    }

    private static String model(String m) {
        return "local".equals(m) ? "lokaal" : "Claude";
    }

    private String jobLink(String name) {
        return JobText.RESEARCH_JOB.equals(name) && !jobs.get().containsKey(name) ? "/research" : "/job/" + name;
    }

    /** One run as the lists show it ({@code run_summary}). */
    public Map<String, Object> runSummary(Map<String, Object> r) {
        JobEngine.RunView view = engine.runView(r);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("job", r.get("job"));
        m.put("job_url", jobLink((String) r.get("job")));
        m.put("due", localTime((String) r.get("due")));
        m.put("status", view.status());
        m.put("status_label", RUN_NL.getOrDefault(view.status(), view.status()));
        m.put("url", view.url());
        m.put("model", model((String) r.get("model")));
        m.put("manual", r.get("manual") instanceof Number n && n.intValue() != 0);
        m.put("pr", view.prUrl());
        m.put("summary", view.summary() == null || view.summary().isEmpty() ? "" : firstLine(view.summary()));
        return m;
    }

    /** The facts of a parsed job ({@code job_facts}). */
    public Map<String, Object> jobFacts(Job job) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("when", job.when() == null ? "" : job.when());
        f.put("schedule", job.schedule());
        f.put("timezone", tz().getId());
        f.put("paused", job.paused());
        List<String> next = new ArrayList<>();
        Instant at = clock.instant();
        for (int i = 0; i < 3; i++) {
            ZonedDateTime t = job.cron().nextAfter(at, tz());
            next.add(when(t));
            at = t.toInstant();
        }
        f.put("next_times", next);
        f.put("kind", Jobs.KINDS.get(job.kind()));
        f.put("model", job.model());
        f.put("mode", job.mode() == null || job.mode().isEmpty() ? null : job.mode());
        f.put("repo", job.repo() == null || job.repo().isEmpty() ? null : job.repo());
        f.put("script", job.script() == null || job.script().isEmpty() ? null
                : String.join(" ", concat(job.script(), job.args())));
        f.put("sources", job.sources());
        f.put("deliver", job.deliver());
        f.put("nothing", "page".equals(job.deliver()) ? null : JobText.nothingStatement(job));
        f.put("skills", job.skills());
        f.put("minutes", job.minutes());
        f.put("instruction", job.instruction());
        return f;
    }

    private static List<String> concat(String first, List<String> rest) {
        List<String> all = new ArrayList<>();
        all.add(first);
        all.addAll(rest);
        return all;
    }

    private boolean refresh(List<Map<String, Object>> list, Set<String> active) {
        return list.stream().anyMatch(r -> active.contains(engine.runView(r).status()));
    }

    /** What the Jobs page renders ({@code jobs_data}). */
    public Map<String, Object> jobsData() {
        List<Map<String, Object>> jobList = new ArrayList<>();
        for (Job job : new TreeMap<>(jobs.get()).values()) {
            if (!job.ok()) {
                jobList.add(new LinkedHashMap<>(Map.of("name", job.name(), "error", job.error())));
                continue;
            }
            List<Map<String, Object>> last = runs.runs(job.name(), 1);
            String status = last.isEmpty() ? null : engine.runView(last.get(0)).status();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", job.name());
            m.put("error", null);
            m.put("kind", Jobs.KINDS.get(job.kind()));
            m.put("model", model(job.model()));
            m.put("when", job.when().isEmpty() ? job.schedule() : job.when());
            m.put("next", job.paused() ? "gepauzeerd" : dayTime(job.cron().nextAfter(clock.instant(), tz())));
            m.put("paused", job.paused());
            m.put("last", status == null ? null : RUN_NL.getOrDefault(status, ""));
            jobList.add(m);
        }
        List<Map<String, Object>> cat = new ArrayList<>();
        for (AgentCatalogue.Agent a : catalogue.load(jobs.get().keySet())) {
            if (a.error() != null) {
                cat.add(new LinkedHashMap<>(Map.of("name", a.name(), "error", a.error())));
                continue;
            }
            Job j = a.job();
            String what = j.instruction().split("\n\n", -1)[0];
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", a.name());
            m.put("error", null);
            m.put("on", a.on());
            m.put("what", what.length() > 300 ? what.substring(0, 300) : what);
            m.put("when", j.when().isEmpty() ? j.schedule() : j.when());
            m.put("model", model(j.model()));
            m.put("deliver", j.deliver());
            m.put("fill", a.fill());
            cat.add(m);
        }
        List<Map<String, Object>> drafts = new ArrayList<>();
        for (String status : List.of("drafting", "ready", "failed")) {
            drafts.addAll(runs.drafts(status));
        }
        drafts.sort((x, y) -> Long.compare(((Number) y.get("id")).longValue(), ((Number) x.get("id")).longValue()));
        if (drafts.size() > 10) {
            drafts = new ArrayList<>(drafts.subList(0, 10));
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("claude_runs_today", engine.claudeRunsToday());
        d.put("claude_cap", settings.dailyCap());
        d.put("timezone", tz().getId());
        d.put("refresh", drafts.stream().anyMatch(x -> "drafting".equals(x.get("status"))));
        List<Map<String, Object>> draftRows = new ArrayList<>();
        for (Map<String, Object> x : drafts) {
            draftRows.add(new LinkedHashMap<>(Map.of("id", x.get("id"), "status", x.get("status"), "request",
                    firstLine((String) x.get("request")))));
        }
        d.put("drafts", draftRows);
        d.put("catalogue", cat);
        d.put("jobs", jobList);
        d.put("runs", runs.runs(null, 20).stream().map(this::runSummary).toList());
        return d;
    }

    /** One job with its history and its file ({@code job_detail}); empty for a name that is not a job. */
    public Optional<Map<String, Object>> jobDetail(String name, String fileText) {
        Job job = jobs.get().get(name);
        if (job == null) {
            return Optional.empty();
        }
        List<Map<String, Object>> history = runs.runs(name, 30);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", name);
        d.put("error", job.error());
        d.put("paused", job.paused());
        d.put("text", fileText == null ? "" : fileText);
        d.put("facts", job.ok() ? jobFacts(job) : null);
        d.put("runs", history.stream().map(this::runSummary).toList());
        d.put("refresh", refresh(history, Set.of("queued", "running")));
        return Optional.of(d);
    }

    /** A draft; a {@code ready} one comes with the facts of its file, or why the file is not valid ({@code draft_detail}). */
    public Optional<Map<String, Object>> draftDetail(long id) {
        Optional<Map<String, Object>> found = runs.draft(id);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> d = found.get();
        String text = d.get("text") == null ? "" : (String) d.get("text");
        String name = d.get("base") != null ? (String) d.get("base") : AgentCatalogue.draftName(text);
        Object facts = null;
        String textError = null;
        if ("ready".equals(d.get("status"))) {
            try {
                facts = jobFacts(parser.parse(name, text, parser.repoNames(), parser.skillNames()));
            } catch (IllegalArgumentException e) {
                textError = e.getMessage();
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.get("id"));
        m.put("base", d.get("base"));
        m.put("request", d.get("request"));
        m.put("status", d.get("status"));
        m.put("error", d.get("error") == null || d.get("error").toString().isEmpty() ? null : d.get("error"));
        m.put("text", text);
        m.put("facts", facts);
        m.put("text_error", textError);
        m.put("refresh", "drafting".equals(d.get("status")));
        return Optional.of(m);
    }

    /** One run of a job; {@code log_key} is set for a local run ({@code run_detail}). */
    public Optional<Map<String, Object>> runDetail(long id) {
        return runs.run(id).map(r -> {
            Map<String, Object> m = new LinkedHashMap<>(runSummary(r));
            m.put("text", r.get("summary") == null ? "" : r.get("summary"));
            m.put("note", r.get("note") == null || r.get("note").toString().isEmpty() ? null : r.get("note"));
            m.put("cancellable", "running".equals(r.get("status")));
            m.put("log_key", "local".equals(r.get("model")) ? "r" + id : null);
            m.put("refresh", "queued".equals(engine.runView(r).status()));
            return m;
        });
    }

    /** What the Research page shows: the cap counter, the modes and the 30 latest questions ({@code research_data}). */
    public Map<String, Object> researchData() {
        List<Map<String, Object>> history = runs.runs(JobText.RESEARCH_JOB, 30);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("claude_runs_today", engine.claudeRunsToday());
        m.put("claude_cap", settings.dailyCap());
        m.put("modes", JobText.RESEARCH_MODES);
        m.put("runs", history.stream().map(this::runSummary).toList());
        m.put("refresh", refresh(history, Set.of("queued", "running", "claude")));
        return m;
    }
}
