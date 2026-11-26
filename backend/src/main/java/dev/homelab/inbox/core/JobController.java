package dev.homelab.inbox.core;

import dev.homelab.inbox.jobs.JobsWriter;
import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.Jobs.Job;
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
 * The job routes of {@code server.py} (H-028 W3a): {@code GET /api/jobs-page}, {@code GET /api/job/{name}},
 * {@code GET /jobs}, {@code GET /job/{name}}, {@code POST /jobs/draft} and
 * {@code POST /job/{name}/(run|pause|resume|delete|save|change)}. Same paths, status codes, bodies and Dutch texts as
 * Python. Like {@link TaskController}, the owner, Origin and CSRF checks are the front door's, not this class's. The
 * draft pages and {@code POST /draft/{id}/(confirm|cancel)} are {@link DraftRunController}; the research page and
 * {@code /agents/{name}/on} are the next slice. A new draft starts Claude through {@link Drafts}, so in shadow nothing runs.
 */
@RestController
public class JobController {

    private static final int DRAFT_REQUEST_MAX = 4000;

    private final TaskController tasks;
    private final JobPages pages;
    private final JobEngine engine;
    private final JobsWriter writer;
    private final Jobs jobs;
    private final Drafts drafts;

    public JobController(TaskController tasks, JobPages pages, JobEngine engine, JobsWriter writer, Jobs jobs, Drafts drafts) {
        this.tasks = tasks;
        this.pages = pages;
        this.engine = engine;
        this.writer = writer;
        this.jobs = jobs;
        this.drafts = drafts;
    }

    // ---- reads

    @GetMapping("/api/jobs-page")
    public ResponseEntity<String> jobsPage() {
        return tasks.api(200, pages.jobsData());
    }

    @GetMapping("/api/job/{name:[a-z0-9-]+}")
    public ResponseEntity<String> detail(@PathVariable String name) {
        Optional<Map<String, Object>> d = jobs.load().containsKey(name)
                ? pages.jobDetail(name, jobs.fileText(name).orElse("")) : Optional.empty();
        return d.isPresent() ? tasks.api(200, d.get()) : tasks.api(404, Map.of("error", "Not found."));
    }

    /** The Jobs page is TypeScript: {@code frontend/dist/index.html}, or 503 while the build is missing. */
    @GetMapping("/jobs")
    public ResponseEntity<String> jobsIndex() {
        return tasks.index();
    }

    /**
     * The job page: only for a job that exists. {@code /job/research} is the research page unless a real job has that
     * name ({@code job_route}).
     */
    @GetMapping("/job/{name:[a-z0-9-]+}")
    public ResponseEntity<String> jobPage(@PathVariable String name) {
        boolean known = jobs.load().containsKey(name) || name.equals(JobText.RESEARCH_JOB);
        return known ? tasks.index() : TaskController.notFoundPage();
    }

    // ---- writes

    /** A new job from a description: a draft that Claude writes; 303 to the draft. Without a text it is the 404 page. */
    @PostMapping("/jobs/{rest}")
    public ResponseEntity<String> create(@PathVariable String rest, HttpServletRequest request) {
        String text = text(request);
        if (!rest.equals("draft") || request.getQueryString() != null || text.isEmpty()) {
            return TaskController.notFoundPage();
        }
        return TaskController.redirect("/draft/" + drafts.start(requestText(text), null));
    }

    /**
     * Run now, pause, resume, delete, save or change (a draft of a changed job). Always 303 to the job (to /jobs after
     * a delete) when the job exists; a refused file or a failed store is a 400 with {@code {"error": text}}.
     */
    @PostMapping("/job/{name:[a-z0-9-]+}/{action:run|pause|resume|delete|save|change}")
    public ResponseEntity<String> act(@PathVariable String name, @PathVariable String action, HttpServletRequest request) {
        Job job = request.getQueryString() == null ? jobs.load().get(name) : null;
        if (job == null) {
            return TaskController.notFoundPage();
        }
        String text = text(request);
        String who = "bevestigd op de pagina vanaf " + TaskController.who(request);
        try {
            if (action.equals("change") && !text.isEmpty()) {
                return TaskController.redirect("/draft/" + drafts.start(requestText(text), name));
            }
            if (action.equals("run") && job.ok()) {
                engine.dispatch(job, engine.now(), true);
            } else if ((action.equals("pause") || action.equals("resume")) && job.ok()) {
                boolean pause = action.equals("pause");
                writer.pause(name, pause, "jobs: " + name + " " + (pause ? "gepauzeerd" : "hervat") + " (" + who + ")");
            } else if (action.equals("save") && !text.isEmpty()) {
                writer.save(name, text, "jobs: " + name + " bewerkt (" + who + ")");
            } else if (action.equals("delete")) {
                writer.save(name, null, "jobs: " + name + " verwijderd (" + who + ")");
                return TaskController.redirect("/jobs");
            }
        } catch (RuntimeException e) {
            return tasks.api(400, Map.of("error", String.valueOf(e.getMessage())));
        }
        return TaskController.redirect("/job/" + name);
    }

    /** {@code form.get("text", "").replace("\r\n", "\n").strip()} */
    private static String text(HttpServletRequest request) {
        return TaskController.param(request, "text").replace("\r\n", "\n").strip();
    }

    /** {@code text[:4000]}: the first 4000 characters (code points). */
    private static String requestText(String text) {
        return text.codePointCount(0, text.length()) <= DRAFT_REQUEST_MAX ? text
                : text.substring(0, text.offsetByCodePoints(0, DRAFT_REQUEST_MAX));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> failed(Exception e, HttpServletRequest request) {
        return tasks.failed(e, request);
    }
}
