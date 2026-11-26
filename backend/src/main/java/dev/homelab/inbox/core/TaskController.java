package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.Effects.TaskLog;
import dev.homelab.inbox.frontdoor.ErrorPage;
import dev.homelab.inbox.frontdoor.FrontDoorFilter;
import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.Settings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The task routes of {@code server.py} (H-028 W2a): {@code GET /api/tasks}, {@code GET /api/tasks/{id}},
 * {@code GET /taken}, {@code GET /task/{id}}, {@code POST /tasks} and {@code POST /task/{id}/(accept|answer|cancel|
 * attended|retry)}. Same paths, status codes, bodies and Dutch texts as Python. Every mutating route is behind the
 * front door ({@code frontdoor.FrontDoorFilter}: tailnet owner, Origin and CSRF token) or, while Python still faces the
 * tailnet, behind the same checks of its facade; this class does neither itself. What a route changes goes through
 * {@link TaskActions}, {@link FollowupChain} and the {@link Effects}, so in shadow nothing is started.
 */
@RestController
public class TaskController {

    private static final String JSON_UTF8 = "application/json; charset=utf-8";
    private static final String HTML_UTF8 = "text/html; charset=utf-8";
    private static final Set<String> RETRYABLE = Set.of("failed", "cancelled", "capped", "refused");
    private static final Set<String> ACTIONS = Set.of("accept", "answer", "cancel", "attended", "retry");

    private final TaskStore store;
    private final TaskPages pages;
    private final TaskActions actions;
    private final FollowupChain chain;
    private final Jobs jobs;
    private final ConversationNotes notes;
    private final TaskLog taskLog;
    private final Settings conf;
    private final JsonMapper json;

    public TaskController(TaskStore store, TaskPages pages, TaskActions actions, FollowupChain chain, Jobs jobs,
                          ConversationNotes notes, TaskLog taskLog, Settings conf, JsonMapper json) {
        this.store = store;
        this.pages = pages;
        this.actions = actions;
        this.chain = chain;
        this.jobs = jobs;
        this.notes = notes;
        this.taskLog = taskLog;
        this.conf = conf;
        this.json = json;
    }

    // ---- answers

    private ResponseEntity<String> api(int status, Object body) {
        return ResponseEntity.status(status).header("Content-Type", JSON_UTF8).header("Cache-Control", "no-store")
                .body(json.writeValueAsString(body));
    }

    private static ResponseEntity<String> html(int status, String body) {
        return ResponseEntity.status(status).header("Content-Type", HTML_UTF8).header("Cache-Control", "no-store").body(body);
    }

    private static ResponseEntity<String> notFoundPage() {
        return html(404, ErrorPage.html("niet gevonden", "<p>Niet gevonden.</p>"));
    }

    private static ResponseEntity<String> redirect(String location) {
        return ResponseEntity.status(303).header("Content-Type", HTML_UTF8).header("Cache-Control", "no-store")
                .header("Location", location).body("");
    }

    /** {@code get(int(id))}: an id too big for a task is just an unknown task. */
    private Optional<Task> task(String id) {
        try {
            return store.get(Long.parseLong(id));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private String pageUrl(long id) {
        return "http://" + conf.conf("TAILNET_NAME", "homelab") + ":" + conf.conf("INBOX_PORT", "8105") + "/task/" + id;
    }

    // ---- reads

    @GetMapping("/api/tasks")
    public ResponseEntity<String> list() {
        return api(200, pages.list(new ArrayList<>(jobs.repoNames())));
    }

    @GetMapping("/api/tasks/{id:\\d+}")
    public ResponseEntity<String> detail(@PathVariable String id) {
        Optional<Task> t = task(id);
        return t.isPresent() ? api(200, pages.detail(t.get().id()).orElseThrow()) : api(404, Map.of("error", "Not found."));
    }

    /** The Tasks page is TypeScript: {@code frontend/dist/index.html}, or 503 while the build is missing. */
    @GetMapping("/taken")
    public ResponseEntity<String> tasksPage() {
        return index();
    }

    /** The task page: only for a task that exists, and only without a query string (Python matches {@code self.path}). */
    @GetMapping("/task/{id:\\d+}")
    public ResponseEntity<String> taskPage(@PathVariable String id, HttpServletRequest request) {
        return request.getQueryString() == null && task(id).isPresent() ? index() : notFoundPage();
    }

    private ResponseEntity<String> index() {
        Path index = conf.frontendDist().resolve("index.html");
        try {
            if (Files.isRegularFile(index)) {
                return html(200, Files.readString(index));
            }
        } catch (IOException e) {
            // unreadable counts as missing
        }
        return html(503, ErrorPage.html("fout", "<p>De frontend is niet gebouwd (frontend/dist ontbreekt).</p>"));
    }

    // ---- writes

    /** A new task from the page form; 303 to its page, or the 400 page for an unknown repo or an empty text. */
    @PostMapping("/tasks")
    public ResponseEntity<String> create(HttpServletRequest request) {
        String repo = param(request, "repo");
        String text = param(request, "text").strip();
        if ((!jobs.repoNames().contains(repo) && !repo.equals(CoreSettings.AUTO)) || text.isEmpty()) {
            return html(400, ErrorPage.html("fout", "<p>Kies een repo uit de lijst en beschrijf de taak.</p>"));
        }
        long id = store.newTask(repo, text, null);
        notes.note("chris", "page", "message", "[" + repo + "] " + text, pageUrl(id), "task:" + id);
        return redirect("/task/" + id);
    }

    /**
     * accept, answer, cancel, attended and retry: always 303 to the task page when the task exists, also when the action
     * did nothing because it does not apply now. Unknown task or action: the 404 page.
     */
    @PostMapping("/task/{id:\\d+}/{action}")
    public ResponseEntity<String> act(@PathVariable String id, @PathVariable String action, HttpServletRequest request) {
        Optional<Task> found = request.getQueryString() == null && ACTIONS.contains(action) ? task(id) : Optional.empty();
        if (found.isEmpty()) {
            return notFoundPage();
        }
        Task t = found.get();
        switch (action) {
            case "answer" -> actions.answer(t, param(request, "answer"), null);
            case "cancel" -> cancel(t);
            case "retry" -> {
                if (RETRYABLE.contains(t.status()) && !t.repo().equals(CoreSettings.AUTO) && !t.repo().equals(CoreSettings.BRIEF)
                        && chain.blockedBy(t).isEmpty()) {
                    actions.retry(t, param(request, "note"));
                }
            }
            case "accept" -> chain.acceptTask(t);
            default -> { // attended
                if (t.repo().equals(CoreSettings.BRIEF) && t.num("unattended") != 0) {
                    store.update(t.id(), TaskStore.fields("unattended", 0));
                    taskLog.line("t" + t.id(), "info", "build without me stopped by " + who(request) + ": you merge the rest");
                }
            }
        }
        return redirect("/task/" + t.id());
    }

    private void cancel(Task t) {
        boolean live = Scheduler.ACTIVE.contains(t.status()) || t.status().equals("waiting");
        if (live || (t.status().equals("routed") && !t.has("ended"))) {
            if (t.status().equals("routed")) { // the whole message: every child that is not done yet
                for (Task k : store.children(t.id())) {
                    if (Scheduler.ACTIVE.contains(k.status()) || k.status().equals("waiting")) {
                        actions.cancel(k);
                    }
                }
                store.update(t.id(), TaskStore.fields("status", "cancelled", "ended", store.now()));
            } else {
                actions.cancel(t);
            }
        }
    }

    /** The first value of a form field; a blank or missing one is the empty string ({@code parse_qs}). */
    private static String param(HttpServletRequest request, String name) {
        String v = request.getParameter(name);
        return v == null ? "" : v;
    }

    private static String who(HttpServletRequest request) {
        Object who = request.getAttribute(FrontDoorFilter.WHO);
        if (who != null) {
            return who.toString();
        }
        String header = request.getHeader("X-Inbox-Who");
        return header == null ? "?" : header;
    }

    /** server.guarded: an unexpected exception on /api/* is a 500 with a JSON error; a store that is down is a 503. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> failed(Exception e, HttpServletRequest request) {
        org.apache.commons.logging.LogFactory.getLog(TaskController.class)
                .warn(request.getMethod() + " " + request.getRequestURI() + ": " + e);
        boolean down = e instanceof dev.homelab.inbox.conversation.ConversationDb.Unavailable;
        int status = down ? 503 : 500;
        String error = down ? "the task store is not available; see the inbox journal"
                : "the request failed; see the inbox journal";
        return request.getRequestURI().startsWith("/api/") ? api(status, Map.of("error", error))
                : html(status, ErrorPage.html("fout", "<p>" + ErrorPage.escape(error) + "</p>"));
    }
}
