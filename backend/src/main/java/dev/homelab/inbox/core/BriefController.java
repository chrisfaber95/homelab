package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.frontdoor.ErrorPage;
import dev.homelab.inbox.frontdoor.FrontDoorFilter;
import dev.homelab.inbox.read.Settings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * The brief routes of {@code server.py} (H-028 W2c): {@code GET /api/briefs}, {@code GET /api/briefs/{id}},
 * {@code GET /briefs}, {@code GET /briefs/{id}} and {@code POST /briefs/{id}/(build|park)}. Same paths, status codes,
 * bodies and Dutch texts as Python. Like {@link TaskController}, this class does not check Origin or the CSRF token
 * itself: every POST is behind the front door ({@code frontdoor.FrontDoorFilter}). What a build or a park changes goes
 * through {@link Briefs}, so with the shadow effects nothing is committed to the product repo.
 */
@RestController
public class BriefController {

    private static final String JSON_UTF8 = "application/json; charset=utf-8";
    private static final String HTML_UTF8 = "text/html; charset=utf-8";
    private static final Pattern ID = Pattern.compile("[A-Z]-\\d{3,4}");

    private final Briefs briefs;
    private final BriefPages pages;
    private final ConversationNotes notes;
    private final Settings conf;
    private final JsonMapper json;

    public BriefController(Briefs briefs, BriefPages pages, ConversationNotes notes, Settings conf, JsonMapper json) {
        this.briefs = briefs;
        this.pages = pages;
        this.notes = notes;
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

    private static boolean isId(String id) {
        return ID.matcher(id).matches();
    }

    // ---- reads

    @GetMapping("/api/briefs")
    public ResponseEntity<String> list() {
        return api(200, pages.briefsData());
    }

    @GetMapping("/api/briefs/{id}")
    public ResponseEntity<String> detail(@PathVariable String id) {
        Optional<BriefParsing.Brief> b = isId(id) ? briefs.byId(id, java.time.Duration.ofSeconds(60)) : Optional.empty();
        return b.isPresent() ? api(200, pages.briefData(b.get())) : api(404, Map.of("error", "Not found."));
    }

    /**
     * The Briefs page is TypeScript ({@code frontend/dist/index.html}); with a query string Python serves the page when
     * the build exists and otherwise falls through to the 404 page, and without a build and without a query it is 503.
     */
    @GetMapping("/briefs")
    public ResponseEntity<String> listPage(HttpServletRequest request) {
        return page(request);
    }

    /** A brief's own page: only for a brief that exists, else the 404 page. */
    @GetMapping("/briefs/{id}")
    public ResponseEntity<String> briefPage(@PathVariable String id, HttpServletRequest request) {
        if (!isId(id) || briefs.byId(id, java.time.Duration.ofSeconds(60)).isEmpty()) {
            return notFoundPage();
        }
        return page(request);
    }

    private ResponseEntity<String> page(HttpServletRequest request) {
        Path index = conf.frontendDist().resolve("index.html");
        try {
            if (Files.isRegularFile(index)) {
                return html(200, Files.readString(index));
            }
        } catch (IOException e) {
            // unreadable counts as missing
        }
        if (request.getQueryString() != null) {
            return notFoundPage();
        }
        return html(503, ErrorPage.html("fout", "<p>De frontend is niet gebouwd (frontend/dist ontbreekt).</p>"));
    }

    // ---- writes

    /**
     * Build or park a brief. Park: 303 to {@code /briefs}. Build: 303 to the new build's page; {@code mode=without-me}
     * starts it unattended. Anything that goes wrong (not a draft, not approved, already being built, the product repo
     * not reachable) is a 400 with {@code {"error": ...}}, as in Python.
     */
    @PostMapping("/briefs/{id}/{action}")
    public ResponseEntity<String> act(@PathVariable String id, @PathVariable String action, HttpServletRequest request) {
        Matcher m = Pattern.compile("/briefs/(" + ID.pattern() + ")/(build|park)").matcher(request.getRequestURI());
        if (request.getQueryString() != null || !m.matches()) {
            return notFoundPage();
        }
        String who = who(request);
        try {
            if (action.equals("park")) {
                briefs.setStatus(id, "parked", "parked on the homelab page by " + who);
                return redirect("/briefs");
            }
            boolean withoutMe = "without-me".equals(request.getParameter("mode"));
            long tid = briefs.startBuild(id, withoutMe, who, null);
            notes.note("chris", "page", "approval", "Approved " + id + " and started the build"
                    + (withoutMe ? " without me" : ""), pageUrl(tid), "brief:" + id);
            return redirect("/task/" + tid);
        } catch (RuntimeException e) {
            return api(400, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private String pageUrl(long id) {
        return "http://" + conf.conf("TAILNET_NAME", "homelab") + ":" + conf.conf("INBOX_PORT", "8105") + "/task/" + id;
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
        org.apache.commons.logging.LogFactory.getLog(BriefController.class)
                .warn(request.getMethod() + " " + request.getRequestURI() + ": " + e);
        boolean down = e instanceof dev.homelab.inbox.conversation.ConversationDb.Unavailable;
        int status = down ? 503 : 500;
        String error = down ? "the task store is not available; see the inbox journal"
                : "the request failed; see the inbox journal";
        return request.getRequestURI().startsWith("/api/") ? api(status, Map.of("error", error))
                : html(status, ErrorPage.html("fout", "<p>" + ErrorPage.escape(error) + "</p>"));
    }
}
