package dev.homelab.inbox.proxy;

import dev.homelab.inbox.proxy.Upstream.Answer;
import dev.homelab.inbox.read.Forge;
import dev.homelab.inbox.read.Settings;
import dev.homelab.inbox.proxy.Upstream.Reply;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The forge controls and forge's log (H-015), moved from bin/homelab_inbox/forge.py (H-024, M3): {@code GET /api/forge},
 * {@code POST /api/forge/start|stop}. The inbox calls forge's own endpoints on the tailnet
 * address; same paths, same bodies, same errors as the Python version. CSRF and the access check stay in front, in the
 * Python server (the facade).
 */
@RestController
public class ForgeController {

    static final String UNREACHABLE = "Forge is not reachable (is homelab-app-forge running?).";
    private static final Logger LOG = LoggerFactory.getLogger(ForgeController.class);

    private final Settings settings;
    private final Forge forge;

    public ForgeController(Settings settings, Forge forge) {
        this.settings = settings;
        this.forge = forge;
    }

    @GetMapping("/api/forge")
    public void state(HttpServletResponse out) throws IOException {
        Upstream.write(out, state(5));
    }

    private Answer state(int timeout) {
        Reply reply = Upstream.call(settings.forgeUrl() + "/api/state", null, timeout);
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
        Reply reply = Upstream.call(settings.forgeUrl() + "/api/" + action, Upstream.JSON.createObjectNode(), 10);
        if (reply.status() >= 400) {
            JsonNode error = reply.body().path("error");
            String message = Upstream.truthy(error.isObject() ? error.get("message") : error)
                    ? Upstream.text(error.isObject() ? error.get("message") : error) : "no reason given";
            return Upstream.respond(502, Upstream.error("Forge refused " + action + " (HTTP " + reply.status() + "): " + message));
        }
        LOG.info("forge {} by {}", action, who);
        forge.forget();
        return reply.status() == 200 ? state(5) : Upstream.respond(reply.status(), reply.body());
    }
}
