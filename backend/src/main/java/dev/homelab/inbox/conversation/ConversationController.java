package dev.homelab.inbox.conversation;

import dev.homelab.inbox.proxy.Upstream;
import dev.homelab.inbox.proxy.Upstream.Answer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The conversation store routes (H-024 M4), moved from {@code conversation.py} and the chat door of {@code server.py}.
 *
 * <p>Two sets, both behind the facade secret and both reached only from the Python inbox:
 * <ul>
 *   <li>{@code GET /api/chat/state}, {@code GET|POST /api/chat/entries}: the chat door's routes with the same paths,
 *   bodies and statuses as before. The Python door still checks that the call is from loopback and verifies the bearer
 *   token (until K7); it then tells this side which client it was in {@code X-Inbox-Client}. The permissions of the
 *   client (config/clients.json), the switches, scrubbing and the refusal log are done here.</li>
 *   <li>{@code /internal/conversation/*}: what the rest of the Python inbox (queue, kitt door, notifications, overview)
 *   needs from the store now that it no longer writes it itself.</li>
 * </ul>
 * {@code /api/chat/action} is ported in shadow (the {@code door} package, K7) and still served by Python until H-027.
 */
@RestController
public class ConversationController {

    static final String CLIENT_HEADER = "X-Inbox-Client";
    private static final int MAX_BODY = 64 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(ConversationController.class);

    private final ConversationStore store;
    private final JsonMapper json;
    private final boolean frontDoor;

    public ConversationController(ConversationStore store, JsonMapper json,
                                  @org.springframework.beans.factory.annotation.Value("${inbox.front-door.enabled:false}") boolean frontDoor) {
        this.store = store;
        this.json = json;
        this.frontDoor = frontDoor;
    }

    /**
     * With the front door on (no Python facade) the client name in X-Inbox-Client is trusted only on a request that passed
     * ChatEntriesFilter (loopback, bearer token). Whatever other spelling of the path reached this route (percent-encoding,
     * dot segments), it is refused here, so nobody can name a client of their own.
     */
    private boolean notThroughTheDoor(HttpServletRequest in) {
        return frontDoor && in.getAttribute(dev.homelab.inbox.frontdoor.FrontDoorFilter.DOOR_PASSED) == null;
    }

    // --- the chat door -----------------------------------------------------------------------------------------------

    @RequestMapping("/api/chat/state")
    public void state(@RequestHeader(value = CLIENT_HEADER, required = false) String client,
                      HttpServletRequest in, HttpServletResponse out) throws IOException {
        Upstream.write(out, guarded(() -> {
            if (notThroughTheDoor(in)) {
                return Upstream.plainError(403, "loopback only");
            }
            if (client == null || client.isBlank()) {
                return Upstream.plainError(401, "unknown client or token");
            }
            if (!"GET".equals(in.getMethod())) {
                return Upstream.plainError(405, "GET only");
            }
            return Upstream.respond(200, json.valueToTree(store.state()));
        }));
    }

    @GetMapping("/api/chat/entries")
    public void entries(@RequestHeader(value = CLIENT_HEADER, required = false) String client,
                        HttpServletRequest in, HttpServletResponse out) throws IOException {
        Upstream.write(out, guarded(() -> {
            if (notThroughTheDoor(in)) {
                return Upstream.plainError(403, "loopback only");
            }
            if (client == null || client.isBlank()) {
                return Upstream.plainError(401, "unknown client or token");
            }
            Answer off = switchedOff(client);
            if (off != null) {
                return off;
            }
            long after;
            long limit;
            try {
                after = number(in.getParameter("after"), 0);
                limit = number(in.getParameter("limit"), 200);
            } catch (NumberFormatException e) {
                return Upstream.plainError(400, "after and limit are numbers");
            }
            if (!store.allowed(client, "read")) {
                return Upstream.plainError(403, "refused: no permission 'read'");
            }
            List<Map<String, Object>> entries = store.read(after, limit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("entries", entries);
            body.put("next", entries.isEmpty() ? after : entries.get(entries.size() - 1).get("id"));
            return Upstream.respond(200, json.valueToTree(body));
        }));
    }

    @PostMapping("/api/chat/entries")
    public void append(@RequestHeader(value = CLIENT_HEADER, required = false) String client,
                       HttpServletRequest in, HttpServletResponse out) throws IOException {
        byte[] raw = in.getInputStream().readNBytes(MAX_BODY);
        Upstream.write(out, guarded(() -> {
            if (notThroughTheDoor(in)) {
                return Upstream.plainError(403, "loopback only");
            }
            if (client == null || client.isBlank()) {
                return Upstream.plainError(401, "unknown client or token");
            }
            Answer off = switchedOff(client);
            if (off != null) {
                return off;
            }
            String kind = "message";
            try {
                JsonNode body = json.readTree(raw);
                if (!body.isObject()) {
                    throw new IllegalArgumentException("not an object");
                }
                if (!body.has("text")) {
                    throw new IllegalArgumentException("'text'");
                }
                kind = body.has("kind") ? pyStr(body.get("kind")) : "message";
                String text = pyStr(body.get("text"));
                JsonNode refNode = body.get("ref");
                String ref = refNode != null && Upstream.truthy(refNode) ? cut(pyStr(refNode), 80) : null;
                String permission = ConversationStore.CLIENT_KINDS.get(kind);
                if (permission == null) {
                    store.allowed(client, cut("append:" + kind, 40)); // logs the refusal: clients cannot write notifications
                    return Upstream.plainError(403, "refused: " + client + " may not write '" + kind + "'");
                }
                if (!store.allowed(client, permission)) {
                    return Upstream.plainError(403, "refused: " + client + " may not write '" + kind + "'");
                }
                Map<String, Object> entry = store.append("chris", client, kind, text, null, ref);
                return Upstream.respond(200, json.valueToTree(Map.of("entry", entry)));
            } catch (ConversationStore.Invalid | IllegalArgumentException e) {
                return Upstream.plainError(400, "bad request: " + e.getMessage());
            } catch (tools.jackson.core.JacksonException e) {
                return Upstream.plainError(400, "bad request: not valid JSON");
            }
        }));
    }

    private Answer switchedOff(String client) {
        String why = store.switchedOff(client, null); // not logged as a refusal: a stopped client polls
        if (why == null) {
            return null;
        }
        ObjectNode body = json.createObjectNode();
        body.put("error", "refused: " + why);
        body.setAll((ObjectNode) json.valueToTree(store.state()));
        return Upstream.respond(403, body);
    }

    // --- for the rest of the Python inbox ----------------------------------------------------------------------------

    @PostMapping("/internal/conversation/append")
    public void internalAppend(HttpServletRequest in, HttpServletResponse out) throws IOException {
        byte[] raw = in.getInputStream().readNBytes(MAX_BODY);
        Upstream.write(out, guarded(() -> {
            JsonNode b = parse(raw);
            if (b == null) {
                return Upstream.plainError(400, "bad request: not a JSON object");
            }
            try {
                Map<String, Object> entry = store.append(b.path("author").asString(""), b.path("client").asString(""),
                        b.path("kind").asString(""), b.path("text").asString(""), optional(b, "link"), optional(b, "ref"));
                return Upstream.respond(200, json.valueToTree(Map.of("entry", entry)));
            } catch (ConversationStore.Invalid e) {
                return Upstream.plainError(400, e.getMessage());
            }
        }));
    }

    @GetMapping("/internal/conversation/entries")
    public void internalEntries(HttpServletRequest in, HttpServletResponse out) throws IOException {
        Upstream.write(out, guarded(() -> {
            try {
                var entries = store.read(number(in.getParameter("after"), 0), number(in.getParameter("limit"), 200));
                return Upstream.respond(200, json.valueToTree(Map.of("entries", entries)));
            } catch (NumberFormatException e) {
                return Upstream.plainError(400, "after and limit are numbers");
            }
        }));
    }

    @GetMapping("/internal/conversation/refusals")
    public void internalRefusals(HttpServletRequest in, HttpServletResponse out) throws IOException {
        Upstream.write(out, guarded(() -> {
            try {
                return Upstream.respond(200, json.valueToTree(Map.of("refusals", store.refusals(number(in.getParameter("limit"), 100)))));
            } catch (NumberFormatException e) {
                return Upstream.plainError(400, "limit is a number");
            }
        }));
    }

    @PostMapping("/internal/conversation/allowed")
    public void internalAllowed(HttpServletRequest in, HttpServletResponse out) throws IOException {
        byte[] raw = in.getInputStream().readNBytes(MAX_BODY);
        Upstream.write(out, guarded(() -> {
            JsonNode b = parse(raw);
            if (b == null) {
                return Upstream.plainError(400, "bad request: not a JSON object");
            }
            boolean ok = store.allowed(b.path("client").asString(""), b.path("action").asString(""));
            return Upstream.respond(200, json.valueToTree(Map.of("allowed", ok)));
        }));
    }

    @GetMapping("/internal/conversation/switch/{name}")
    public void internalSwitch(@PathVariable String name, HttpServletResponse out) throws IOException {
        Upstream.write(out, guarded(() -> ConversationStore.SWITCHES.contains(name)
                ? Upstream.respond(200, json.valueToTree(Map.of("on", store.switchOn(name))))
                : Upstream.plainError(400, "unknown switch: " + name)));
    }

    @PostMapping("/internal/conversation/switch/{name}")
    public void internalSetSwitch(@PathVariable String name, HttpServletRequest in, HttpServletResponse out) throws IOException {
        byte[] raw = in.getInputStream().readNBytes(MAX_BODY);
        Upstream.write(out, guarded(() -> {
            JsonNode b = parse(raw);
            if (b == null || !b.path("on").isBoolean()) {
                return Upstream.plainError(400, "bad request: {\"on\": true|false}");
            }
            try {
                store.setSwitch(name, b.get("on").booleanValue());
            } catch (ConversationStore.Invalid e) {
                return Upstream.plainError(400, e.getMessage());
            }
            return Upstream.respond(200, json.valueToTree(Map.of("on", b.get("on").booleanValue())));
        }));
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    private interface Handler {
        Answer run();
    }

    /** A store that is not imported or whose database is down answers 503 (the Python side then fails closed). */
    private Answer guarded(Handler handler) {
        try {
            return handler.run();
        } catch (ConversationDb.Unavailable e) {
            LOG.warn("conversation store unavailable: {}", e.getMessage());
            return Upstream.plainError(503, e.getMessage());
        }
    }

    private JsonNode parse(byte[] raw) {
        try {
            JsonNode b = json.readTree(raw);
            return b.isObject() ? b : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String optional(JsonNode b, String key) {
        JsonNode v = b.get(key);
        return v == null || v.isNull() ? null : v.asString("");
    }

    /** Python's {@code int()} of a query value; absent or blank means the default (parse_qs drops blank values). */
    private static long number(String value, long fallback) {
        return value == null || value.isEmpty() ? fallback : Long.parseLong(value.strip().replaceFirst("^\\+", ""));
    }

    private static String pyStr(JsonNode v) {
        return v == null || v.isNull() ? "None" : v.isString() ? v.stringValue() : v.toString();
    }

    private static String cut(String s, int max) {
        return s.codePointCount(0, s.length()) <= max ? s : s.substring(0, s.offsetByCodePoints(0, max));
    }
}
