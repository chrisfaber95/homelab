package dev.homelab.inbox.proxy;

import dev.homelab.inbox.proxy.Upstream.Answer;
import dev.homelab.inbox.proxy.Upstream.Reply;
import dev.homelab.inbox.read.Settings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Search tab's proxy (H-012), moved from bin/homelab_inbox/search.py (H-024, M3): {@code GET /api/search/filters},
 * {@code GET /api/search/results}, {@code POST /api/search/ask} and {@code POST /api/search/feedback}. The search API
 * and the router answer on loopback; same paths, same bodies, same errors as the Python version.
 */
@RestController
public class SearchController {

    private static final List<String> FILTER_FIELDS = List.of("project", "type", "source");
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{6,64}");
    private static final int ASK_SECONDS = 330;  // a hard question goes to Claude, whose tier timeout is up to 300 s

    private final Settings settings;

    public SearchController(Settings settings) {
        this.settings = settings;
    }

    @GetMapping("/api/search/filters")
    public void filters(HttpServletResponse out) throws IOException {
        Upstream.write(out, filtersAnswer());
    }

    private Answer filtersAnswer() {
        Reply reply = Upstream.call(settings.searchUrl() + "/v1/filters", null, 30);
        return Upstream.respond(reply.status(), reply.body());
    }

    @GetMapping("/api/search/results")
    public void results(@RequestParam MultiValueMap<String, String> params, HttpServletResponse out) throws IOException {
        Upstream.write(out, resultsAnswer(params));
    }

    private Answer resultsAnswer(MultiValueMap<String, String> params) {
        StringBuilder query = new StringBuilder("q=").append(encode(first(params, "q")));
        for (String field : FILTER_FIELDS) {
            String value = first(params, field);
            if (!value.isEmpty()) {
                query.append('&').append(field).append('=').append(encode(value));
            }
        }
        Reply reply = Upstream.call(settings.searchUrl() + "/v1/search?" + query, null, 30);
        return Upstream.respond(reply.status(), reply.body());
    }

    @PostMapping("/api/search/ask")
    public void ask(@RequestParam Map<String, String> form, HttpServletResponse out) throws IOException {
        Upstream.write(out, askAnswer(form));
    }

    private Answer askAnswer(Map<String, String> form) {
        String question = form.getOrDefault("question", "").strip();
        if (question.isEmpty()) {
            return Upstream.plainError(400, "Type a question first.");
        }
        ObjectNode body = Upstream.JSON.createObjectNode();
        body.put("question", cut(question, 2000));
        for (String field : FILTER_FIELDS) {
            if (!form.getOrDefault(field, "").isEmpty()) {
                body.put(field, form.get(field));
            }
        }
        if (List.of("1", "true", "on").contains(form.getOrDefault("hard", ""))) {
            body.put("hard", true);
        }
        Reply reply = Upstream.call(settings.searchUrl() + "/v1/ask", body, ASK_SECONDS);
        return Upstream.respond(reply.status(), reply.body());
    }

    /** Thumbs-down for the router request behind an answer; the router stores caller, tier and backend with it. */
    @PostMapping("/api/search/feedback")
    public void feedback(@RequestParam Map<String, String> form, HttpServletResponse out) throws IOException {
        Upstream.write(out, feedbackAnswer(form));
    }

    private Answer feedbackAnswer(Map<String, String> form) {
        String id = form.getOrDefault("request_id", "");
        if (!REQUEST_ID.matcher(id).matches()) {
            return Upstream.plainError(400, "That answer has no request id to mark.");
        }
        ObjectNode body = Upstream.JSON.createObjectNode();
        body.put("request_id", id);
        String note = cut(form.getOrDefault("note", ""), 1000);
        if (note.isEmpty()) {
            body.putNull("note");
        } else {
            body.put("note", note);
        }
        Reply reply = Upstream.call(settings.routerUrl() + "/v1/feedback", body, 30);
        return Upstream.respond(reply.status(), reply.body());
    }

    /** The first non-blank value of a parameter, like Python's {@code parse_qs} (blank values are dropped), or "". */
    private static String first(MultiValueMap<String, String> params, String key) {
        for (String v : params.getOrDefault(key, List.of())) {
            if (!v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    /** Python's {@code urllib.parse.urlencode} escaping (quote_plus): spaces as +, only letters, digits and _.-~ stay. */
    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("*", "%2A").replace("%7E", "~");
    }

    private static String cut(String s, int codePoints) {
        return s.codePointCount(0, s.length()) > codePoints ? s.substring(0, s.offsetByCodePoints(0, codePoints)) : s;
    }
}
