package dev.homelab.inbox.proxy;

import dev.homelab.inbox.proxy.Upstream.Answer;
import dev.homelab.inbox.proxy.Upstream.Reply;
import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The forge controls and forge's log (H-015), moved from bin/homelab_inbox/forge.py (H-024, M3): {@code GET /api/forge},
 * {@code POST /api/forge/start|stop} and {@code GET /api/log/f1}. The inbox calls forge's own endpoints on the tailnet
 * address; same paths, same bodies, same errors as the Python version. CSRF and the access check stay in front, in the
 * Python server (the facade).
 */
@RestController
public class ForgeController {

    static final String UNREACHABLE = "Forge is not reachable (is homelab-app-forge running?).";
    private static final int LOG_LIMIT = 500;
    private static final Pattern ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T[\\d:+.-]+");
    private static final Pattern TOOL = Pattern.compile("[A-Z][A-Za-z_]*:.*", Pattern.DOTALL);
    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
    private static final Logger LOG = LoggerFactory.getLogger(ForgeController.class);

    private final String forgeUrl;

    public ForgeController(@Value("${forge.url}") String forgeUrl) {
        this.forgeUrl = forgeUrl.strip().replaceAll("/+$", "");
    }

    @GetMapping("/api/forge")
    public void state(HttpServletResponse out) throws IOException {
        Upstream.write(out, state(5));
    }

    private Answer state(int timeout) {
        Reply reply = Upstream.call(forgeUrl + "/api/state", null, timeout);
        if (reply.status() != 200 || !reply.body().isObject()) {
            return Upstream.respond(502, Upstream.error(UNREACHABLE));
        }
        JsonNode data = reply.body();
        ObjectNode view = Upstream.JSON.createObjectNode();
        view.put("running", running(data));
        view.set("step", orNull(data, "step"));
        view.set("project", orNull(data, "project"));
        view.set("activity", orNull(data, "activity"));
        view.set("started", orNull(data, "started_at"));
        view.put("paused", Upstream.truthy(data.get("paused")));
        view.set("pause_reason", orNull(data, "pause_reason"));
        view.set("paused_until", orNull(data, "paused_until"));
        return Upstream.respond(200, view);
    }

    private static JsonNode orNull(JsonNode data, String key) {
        JsonNode v = data.get(key);
        return v == null ? Upstream.JSON.nullNode() : v;
    }

    /** Forge says whether the worker runs as a boolean or as a status word. */
    static boolean running(JsonNode data) {
        if (data.path("running").isBoolean()) {
            return data.get("running").booleanValue();
        }
        JsonNode word = Upstream.truthy(data.get("status")) ? data.get("status") : data.get("state");
        String text = word != null && Upstream.truthy(word) ? Upstream.text(word) : "";
        return text.toLowerCase(Locale.ROOT).equals("running");
    }

    @PostMapping("/api/forge/{action}")
    public void control(@PathVariable String action, @RequestHeader(value = "X-Inbox-Who", defaultValue = "?") String who,
                        HttpServletResponse out) throws IOException {
        Upstream.write(out, control(action, who));
    }

    private Answer control(String action, String who) {
        if (!action.equals("start") && !action.equals("stop")) {
            return Upstream.plainError(404, "Not found.");  // the Python route regex only matches start and stop
        }
        Reply reply = Upstream.call(forgeUrl + "/api/" + action, Upstream.JSON.createObjectNode(), 10);
        if (reply.status() >= 400) {
            JsonNode error = reply.body().path("error");
            String message = Upstream.truthy(error.isObject() ? error.get("message") : error)
                    ? Upstream.text(error.isObject() ? error.get("message") : error) : "no reason given";
            return Upstream.respond(502, Upstream.error("Forge refused " + action + " (HTTP " + reply.status() + "): " + message));
        }
        LOG.info("forge {} by {}", action, who);
        return reply.status() == 200 ? state(5) : Upstream.respond(reply.status(), reply.body());
    }

    @GetMapping("/api/log/f1")
    public void log(@RequestParam(name = "from", required = false) String from, HttpServletResponse out) throws IOException {
        Upstream.write(out, logAnswer(from));
    }

    private Answer logAnswer(String from) {
        BigInteger start;
        try {
            start = from == null || from.isBlank() ? BigInteger.ZERO : new BigInteger(from.strip().replaceFirst("^\\+", ""));
        } catch (NumberFormatException e) {
            return Upstream.plainError(400, "from must be a number");
        }
        start = start.max(BigInteger.ZERO);
        Reply reply = Upstream.call(forgeUrl + "/api/log?from=" + start, null, 5);
        JsonNode data = reply.body();
        if (reply.status() != 200 || !data.isObject() || !data.path("lines").isArray()) {
            return Upstream.respond(502, Upstream.error(UNREACHABLE));
        }
        ArrayNode lines = Upstream.JSON.createArrayNode();
        for (JsonNode line : data.get("lines")) {
            if (lines.size() == LOG_LIMIT) {
                break;
            }
            lines.add(logLine(Upstream.text(line)));
        }
        ObjectNode out = Upstream.JSON.createObjectNode();
        out.set("lines", lines);
        JsonNode next = data.get("next");
        if (next != null && next.isIntegralNumber() && !next.isBoolean()) {
            out.set("next", next);
        } else {
            out.put("next", start.add(BigInteger.valueOf(lines.size())).longValue());
        }
        out.put("done", !Upstream.truthy(data.get("running")));
        return Upstream.respond(200, out);
    }

    /** One of forge's log lines ("&lt;iso&gt; &lt;text&gt;") as the page's log panel wants it. */
    static ObjectNode logLine(String line) {
        int space = line.indexOf(' ');
        String at = space < 0 ? line : line.substring(0, space);
        String text = space < 0 ? "" : line.substring(space + 1);
        if (text.isEmpty() || !ISO.matcher(at).matches()) {
            at = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS).format(NOW);
            text = line;
        }
        ObjectNode node = Upstream.JSON.createObjectNode();
        node.put("at", at);
        node.put("kind", kindOf(text));
        node.put("text", text.codePointCount(0, text.length()) > 600 ? text.substring(0, text.offsetByCodePoints(0, 600)) : text);
        return node;
    }

    /** The colour of a log line: forge's own words, a tool it used, or something that went wrong. */
    static String kindOf(String text) {
        String low = text.toLowerCase(Locale.ROOT);
        if (low.startsWith("refused") || low.startsWith("paused") || low.contains("failed") || low.contains("not built")) {
            return "error";
        }
        if (low.startsWith("claude ") || low.startsWith("out of tokens")) {
            return "claude";
        }
        return TOOL.matcher(text).matches() ? "tool" : "info";
    }
}
