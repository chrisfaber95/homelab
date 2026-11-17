package dev.homelab.inbox.conversation;

import dev.homelab.inbox.read.Settings;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The permissions of the chat clients, read from {@code config/clients.json} on every call (as
 * {@code conversation.py clients()} does): an unknown permission is dropped, which is also why the FORBIDDEN actions in
 * the file give nothing. Authentication (the bearer token) stays in the Python door until K7.
 */
@Component
public class Clients {

    public static final List<String> PERMISSIONS =
            List.of("read", "write", "ask", "start_task", "answer", "approve", "run_job", "research");

    /** Actions no chat client can ever do, whatever the file says. */
    public static final List<String> FORBIDDEN = List.of("ssh_key", "memory_edit", "merge_homelab");

    private static final Logger LOG = LoggerFactory.getLogger(Clients.class);

    private final Settings settings;
    private final JsonMapper json;

    public Clients(Settings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    /** name -> permissions; empty (nobody may do anything) when the file cannot be read. */
    public Map<String, Set<String>> all() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        String text = settings.clientsJson();
        if (text == null) {
            LOG.warn("conversation: clients.json cannot be read; no client has permissions");
            return out;
        }
        try {
            JsonNode clients = json.readTree(text).path("clients");
            for (var e : clients.properties()) {
                if (!e.getValue().isObject()) {
                    continue;
                }
                Set<String> perms = new LinkedHashSet<>();
                for (JsonNode p : e.getValue().path("permissions")) {
                    if (p.isString() && PERMISSIONS.contains(p.stringValue())) {
                        perms.add(p.stringValue());
                    }
                }
                out.put(e.getKey(), perms);
            }
        } catch (RuntimeException e) {
            LOG.warn("conversation: clients.json is not valid JSON ({}); no client has permissions", e.getMessage());
            out.clear();
        }
        return out;
    }
}
