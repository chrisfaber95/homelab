package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.TaskLog;
import dev.homelab.inbox.jobs.JobsWriter;
import dev.homelab.inbox.read.Jobs;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The draft and run routes of {@code server.py} (H-028 W2b): {@code GET /draft/{id}}, {@code GET /run/{id}},
 * {@code GET /api/draft/{id}}, {@code GET /api/run/{id}}, {@code POST /draft/{id}/(confirm|cancel)} and
 * {@code POST /run/{id}/cancel}. {@code /api/log/{t|r}{id}} is {@code read.ReadController} (H-024 M1). Same paths, status
 * codes, bodies and Dutch texts as Python. Like {@link TaskController}, the owner, Origin and CSRF checks are the front
 * door's ({@code frontdoor.FrontDoorFilter}), not this class's. Starting a draft ({@code POST /jobs/draft}) and the other
 * job routes are W3.
 */
@RestController
public class DraftRunController {

    private final TaskController tasks;
    private final JobRuns runs;
    private final JobPages pages;
    private final JobEngine engine;
    private final JobsWriter writer;
    private final Jobs jobs;
    private final TaskLog taskLog;

    public DraftRunController(TaskController tasks, JobRuns runs, JobPages pages, JobEngine engine, JobsWriter writer,
                              Jobs jobs, TaskLog taskLog) {
        this.tasks = tasks;
        this.runs = runs;
        this.pages = pages;
        this.engine = engine;
        this.writer = writer;
        this.jobs = jobs;
        this.taskLog = taskLog;
    }

    /** An id too big for a row is just an unknown one. */
    private static Optional<Long> id(String id) {
        try {
            return Optional.of(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private ResponseEntity<String> notFoundJson() {
        return tasks.api(404, Map.of("error", "Not found."));
    }

    // ---- reads

    @GetMapping("/api/draft/{id:\\d+}")
    public ResponseEntity<String> draft(@PathVariable String id) {
        Optional<Map<String, Object>> d = id(id).flatMap(pages::draftDetail);
        return d.isPresent() ? tasks.api(200, d.get()) : notFoundJson();
    }

    @GetMapping("/api/run/{id:\\d+}")
    public ResponseEntity<String> run(@PathVariable String id) {
        Optional<Map<String, Object>> r = id(id).flatMap(pages::runDetail);
        return r.isPresent() ? tasks.api(200, r.get()) : notFoundJson();
    }

    /** The draft page is TypeScript: only for a draft that exists (a query string is allowed, Python matches the path). */
    @GetMapping("/draft/{id:\\d+}")
    public ResponseEntity<String> draftPage(@PathVariable String id) {
        return id(id).flatMap(runs::draft).isPresent() ? tasks.index() : TaskController.notFoundPage();
    }

    @GetMapping("/run/{id:\\d+}")
    public ResponseEntity<String> runPage(@PathVariable String id) {
        return id(id).flatMap(runs::run).isPresent() ? tasks.index() : TaskController.notFoundPage();
    }

    // ---- writes

    /**
     * Confirm writes the job file and redirects to the job; cancel drops the draft and redirects to /jobs. Only for a
     * draft that is {@code ready} (else the 404 page); a refused file is a 400 with {@code {"error": text}}.
     */
    @PostMapping("/draft/{id:\\d+}/{action:confirm|cancel}")
    public ResponseEntity<String> decide(@PathVariable String id, @PathVariable String action, HttpServletRequest request) {
        Optional<Map<String, Object>> found = request.getQueryString() == null ? id(id).flatMap(runs::draft) : Optional.empty();
        if (found.isEmpty() || !"ready".equals(found.get().get("status"))) {
            return TaskController.notFoundPage();
        }
        Map<String, Object> d = found.get();
        long draftId = ((Number) d.get("id")).longValue();
        if (action.equals("cancel")) {
            runs.updateDraft(draftId, TaskStore.fields("status", "cancelled"));
            return TaskController.redirect("/jobs");
        }
        String text = TaskController.param(request, "text").replace("\r\n", "\n").strip();
        String base = d.get("base") == null || d.get("base").toString().isEmpty() ? null : d.get("base").toString();
        String name = base != null ? base : AgentCatalogue.draftName(text);
        String who = "bevestigd op de pagina vanaf " + TaskController.who(request);
        try {
            if (base == null && jobs.load().containsKey(name)) {
                throw new IllegalArgumentException("er is al een job " + name + "; kies een andere name");
            }
            if (base != null && !AgentCatalogue.draftName(text).isEmpty() && !AgentCatalogue.draftName(text).equals(base)) {
                throw new IllegalArgumentException("de naam blijft " + base);
            }
            jobs.parse(name, text, jobs.repoNames(), jobs.skillNames());
            writer.save(name, text, "jobs: " + name + " " + (base != null ? "gewijzigd" : "nieuw") + " (" + who + ")");
        } catch (RuntimeException e) {
            return tasks.api(400, Map.of("error", String.valueOf(e.getMessage())));
        }
        runs.updateDraft(draftId, TaskStore.fields("status", "confirmed", "text", text));
        return TaskController.redirect("/job/" + name);
    }

    /**
     * A local run is stopped from the page; a Claude job is cancelled through its task (303 there). Always 303 to the run
     * when it exists, also when it was not running.
     */
    @PostMapping("/run/{id:\\d+}/cancel")
    public ResponseEntity<String> cancelRun(@PathVariable String id, HttpServletRequest request) {
        Optional<Map<String, Object>> found = request.getQueryString() == null ? id(id).flatMap(runs::run) : Optional.empty();
        if (found.isEmpty()) {
            return TaskController.notFoundPage();
        }
        Map<String, Object> r = found.get();
        long runId = ((Number) r.get("id")).longValue();
        if (r.get("task_id") != null) {
            return TaskController.redirect("/task/" + ((Number) r.get("task_id")).longValue());
        }
        if ("running".equals(r.get("status"))) {
            engine.cancelLocalRun(runId, "script".equals(r.get("kind")));
            runs.updateRun(runId, TaskStore.fields("status", "cancelled", "ended", pagesNow(), "note", "geannuleerd op de pagina"));
            taskLog.line("r" + runId, "info", "geannuleerd op de pagina");
        }
        return TaskController.redirect("/run/" + runId);
    }

    private String pagesNow() {
        return engine.now();
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> failed(Exception e, HttpServletRequest request) {
        return tasks.failed(e, request);
    }
}
