package dev.homelab.inbox.core;

import dev.homelab.inbox.conversation.ConversationStore;
import dev.homelab.inbox.core.JobEngine.Asked;
import dev.homelab.inbox.read.Jobs;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The research, agent and Discord-switch routes of {@code server.py} (H-028 W3b): {@code GET /research},
 * {@code GET /api/research}, {@code POST /research/ask}, {@code POST /agents/{name}/on} and
 * {@code POST /discord/(discord|discord_approvals|kitt)/(on|off)}. Same paths, status codes, bodies and Dutch texts as
 * Python. Like {@link TaskController}, the owner, Origin and CSRF checks are the front door's, not this class's. A
 * research question starts Claude through {@link JobEngine}, so in shadow nothing runs.
 */
@RestController
public class ResearchController {

    private static final Pattern AGENT = Pattern.compile("/agents/([a-z0-9-]+)/on");

    private final TaskController tasks;
    private final JobPages pages;
    private final JobEngine engine;
    private final AgentCatalogue catalogue;
    private final Jobs jobs;
    private final ConversationStore conversation;

    public ResearchController(TaskController tasks, JobPages pages, JobEngine engine, AgentCatalogue catalogue, Jobs jobs,
                              ConversationStore conversation) {
        this.tasks = tasks;
        this.pages = pages;
        this.engine = engine;
        this.catalogue = catalogue;
        this.jobs = jobs;
        this.conversation = conversation;
    }

    // ---- reads

    @GetMapping("/api/research")
    public ResponseEntity<String> data() {
        return tasks.api(200, pages.researchData());
    }

    /** The Research page is TypeScript; a real job called {@code research} takes the name (then it is no page here). */
    @GetMapping("/research")
    public ResponseEntity<String> page() {
        return jobs.load().containsKey(JobText.RESEARCH_JOB) ? TaskController.notFoundPage() : tasks.index();
    }

    // ---- writes

    /** One Claude run for a question: 303 to the page, or the JSON error of a refused question (400, or 429 at the cap). */
    @PostMapping("/research/ask")
    public ResponseEntity<String> ask(HttpServletRequest request) {
        if (request.getQueryString() != null) {
            return TaskController.notFoundPage();
        }
        String mode = request.getParameter("mode");
        Asked a = engine.askResearch(TaskController.param(request, "question"), mode == null || mode.isEmpty() ? "quick" : mode, null);
        if (a.code() != 200) {
            return tasks.api(a.code(), Map.of("error", a.body().get("error")));
        }
        return TaskController.redirect("/research");
    }

    /**
     * Switching an agent on only makes a ready draft (303 to it); the job exists after Confirm on the draft page. An
     * unknown or broken agent, and any other path under {@code /agents/}, is a 404 with "Onbekende of kapotte agent.".
     */
    @PostMapping("/agents/**")
    public ResponseEntity<String> agent(HttpServletRequest request) {
        Matcher m = AGENT.matcher(request.getQueryString() == null ? request.getRequestURI() : "");
        Optional<Long> draft = m.matches() ? catalogue.switchOn(m.group(1), jobs.load().keySet()) : Optional.empty();
        if (draft.isEmpty()) {
            return TaskController.html(404, dev.homelab.inbox.frontdoor.ErrorPage.html("niet gevonden",
                    "<p>Onbekende of kapotte agent.</p>"));
        }
        return TaskController.redirect("/draft/" + draft.get());
    }

    /** A switch of the conversation store; 303 back to the page's Discord or kitt card. */
    @PostMapping("/discord/{name:discord|discord_approvals|kitt}/{state:on|off}")
    public ResponseEntity<String> discord(@PathVariable String name, @PathVariable String state, HttpServletRequest request) {
        if (request.getQueryString() != null) {
            return TaskController.notFoundPage();
        }
        conversation.setSwitch(name, state.equals("on"));
        org.apache.commons.logging.LogFactory.getLog(ResearchController.class)
                .info("switch " + name + " " + state + " by " + TaskController.who(request));
        return TaskController.redirect(name.equals("kitt") ? "/#kitt" : "/#discord");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> failed(Exception e, HttpServletRequest request) {
        return tasks.failed(e, request);
    }
}
