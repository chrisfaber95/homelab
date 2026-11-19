package dev.homelab.inbox.frontdoor;

import dev.homelab.inbox.read.Settings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The SSH tab's API ({@code ssh_state} in {@code overview.py} and {@code POST /api/ssh} in {@code server.py}): the
 * account, the confirm window and the last 15 key requests from the changes log, and a request for a new key that goes
 * to homelab-admin (which asks for confirmation on the phone). The key itself is never read or logged here.
 */
@RestController
@FrontDoorEnabled
public class SshController {

    private static final String JSON = "application/json; charset=utf-8";

    private final Settings settings;
    private final JsonMapper json;
    private final AdminSocket admin;

    @Autowired
    public SshController(Settings settings, JsonMapper json) {
        this(settings, json, AdminSocket.at(java.nio.file.Path.of(settings.conf("SOCKET", "/run/homelab-admin.sock")), json));
    }

    SshController(Settings settings, JsonMapper json, AdminSocket admin) {
        this.settings = settings;
        this.json = json;
        this.admin = admin;
    }

    private ResponseEntity<String> reply(int status, Object body) {
        return ResponseEntity.status(status).header("Content-Type", JSON).header("Cache-Control", "no-store")
                .body(json.writeValueAsString(body));
    }

    @GetMapping("/api/ssh")
    public ResponseEntity<String> state() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("user", settings.conf("SSH_KEY_USER", "chris"));
        out.put("confirm_minutes", settings.confInt("CONFIRM_SECONDS", 300) / 60);
        out.put("history", history());
        return reply(200, out);
    }

    /**
     * The "sshkey" lines of the log, newest first, at most 15. One line that is not JSON makes the whole log unreadable,
     * like in Python (the page shows an empty history then); only a line mentioning "sshkey" is parsed at all.
     */
    private List<Map<String, Object>> history() {
        List<JsonNode> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(settings.changesLog())) {
                if (!line.contains("\"sshkey\"")) {
                    continue;
                }
                JsonNode c = json.readTree(line);
                if (c.isObject() && "sshkey".equals(c.path("kind").asString(null))) {
                    entries.add(c);
                }
            }
        } catch (IOException | RuntimeException e) {
            entries.clear();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < 15; i--) {
            JsonNode c = entries.get(i);
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("status", status(c));
            h.put("summary", c.path("summary").asString(""));
            h.put("result", c.path("result").asString(""));
            h.put("at", c.get("at").asString());
            h.put("by", c.path("user").asString(""));
            out.add(h);
        }
        return out;
    }

    private static String status(JsonNode c) {
        if (c.path("result").asString("").startsWith("aangevraagd")) {
            return "waiting";
        }
        JsonNode ok = c.get("ok");
        boolean truthy = ok != null && !ok.isNull() && (ok.isBoolean() ? ok.booleanValue()
                : ok.isNumber() ? ok.doubleValue() != 0 : !ok.asString("").isEmpty());
        return truthy ? "ok" : "fail";
    }

    @PostMapping("/api/ssh")
    public ResponseEntity<String> request(HttpServletRequest request) {
        String name = strip(request.getParameter("name"));
        String key = strip(request.getParameter("key"));
        Object who = request.getAttribute(FrontDoorFilter.WHO);
        Map<String, Object> ask = new LinkedHashMap<>();
        ask.put("cmd", "sshkey");
        ask.put("label", name);
        ask.put("key", key);
        ask.put("by", who == null ? "?" : who.toString());
        JsonNode r = admin.ask(ask);
        if (!r.path("ok").asBoolean(false)) {
            return reply(400, Map.of("error", r.hasNonNull("text") && !r.get("text").asString("").isEmpty()
                    ? r.get("text").asString() : "mislukt"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fingerprint", r.get("fingerprint"));
        out.put("deadline", r.get("deadline"));
        out.put("id", r.get("id"));
        return reply(200, out);
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
