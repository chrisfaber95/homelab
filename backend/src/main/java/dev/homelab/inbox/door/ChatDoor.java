package dev.homelab.inbox.door;

import dev.homelab.inbox.conversation.Clients;
import dev.homelab.inbox.conversation.ConversationStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The narrow door for chat clients as far as K7 moves it ({@code server.py chat_door} and {@code chat_action}): only
 * {@code POST /api/chat/action}, loopback only, a client token per request, the client's switches, and then the action
 * the client names, each checked against its permissions in {@link ChatActions}. Free text never reaches an action.
 * {@code /api/chat/entries} and {@code /api/chat/state} are already Java's (the conversation controller, H-024 M4) and
 * are not handled here. Shadow: nothing binds this to a port.
 */
public class ChatDoor {

    static final String ACTION_PATH = "/api/chat/action";
    private static final int MAX_BODY = 64 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(ChatDoor.class);

    private final ChatActions chat;
    private final ConversationStore conversation;
    private final Clients clients;
    private final DoorSettings settings;
    private final JsonMapper json;

    public ChatDoor(ChatActions chat, ConversationStore conversation, Clients clients, DoorSettings settings,
                    JsonMapper json) {
        this.chat = chat;
        this.conversation = conversation;
        this.clients = clients;
        this.settings = settings;
        this.json = json;
    }

    /**
     * The client an {@code Authorization: Bearer <token>} header belongs to (the token is in
     * {@code chat-<name>-token} in the token folder), or null; an unknown token is logged as a refusal.
     */
    public String authenticate(String header) {
        String given = header == null ? "" : header.startsWith("Bearer ") ? header.substring(7).strip() : header.strip();
        for (String name : clients.all().keySet()) {
            String want;
            try {
                want = Files.readString(settings.tokenDir().resolve("chat-" + name + "-token")).strip();
            } catch (IOException e) {
                continue;
            }
            if (!want.isEmpty() && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                    want.getBytes(StandardCharsets.UTF_8))) {
                return name;
            }
        }
        conversation.allowed("unknown", "authenticate");
        return null;
    }

    private static boolean loopback(String ip) {
        String plain = ip.startsWith("::ffff:") ? ip.substring(7) : ip;
        return plain.equals("127.0.0.1") || plain.equals("::1");
    }

    /** Empty when the path is not one this door handles; otherwise the answer, a refusal included. */
    public Optional<Result> handle(Req req) {
        if (!req.path().equals(ACTION_PATH)) {
            return Optional.empty();
        }
        if (!loopback(req.remoteIp())) {
            return Optional.of(Result.error(403, "loopback only"));
        }
        String client = authenticate(req.header("Authorization"));
        if (client == null) {
            return Optional.of(Result.error(401, "unknown client or token"));
        }
        String why = conversation.switchedOff(client, null); // not logged as a refusal: a stopped client polls
        if (why != null) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("error", "refused: " + why);
            body.putAll(conversation.state());
            return Optional.of(new Result(403, body));
        }
        return Optional.of(action(client, req.method(), req.body()));
    }

    private static String text(JsonNode body, String key) {
        JsonNode v = body.get(key);
        return v == null || v.isNull() ? null : v.isString() ? v.stringValue() : v.toString();
    }

    /** {@code POST /api/chat/action {"action": ..., ...}}: the explicit actions of {@code chat.py}. */
    Result action(String client, String method, byte[] raw) {
        if (!method.equals("POST")) {
            return Result.error(405, "POST only");
        }
        JsonNode body;
        String action;
        try {
            byte[] bytes = raw == null ? new byte[0] : raw.length > MAX_BODY ? java.util.Arrays.copyOf(raw, MAX_BODY) : raw;
            body = json.readTree(bytes);
            if (!body.isObject() || !body.has("action")) {
                throw new IllegalArgumentException(body.isObject() ? "'action'" : "not an object");
            }
            action = text(body, "action");
        } catch (RuntimeException e) {
            return Result.error(400, "bad request: " + e.getMessage());
        }
        if (Clients.FORBIDDEN.contains(action)) { // SSH keys, memory edits, merges on homelab: no client, whatever the config
            conversation.allowed(client, action);
            return Result.error(403, "refused: " + action + " cannot be done from a chat client");
        }
        try {
            return switch (action) {
                case "task" -> chat.startTask(client, text(body, "repo"), text(body, "text"));
                case "answer" -> chat.answer(client, text(body, "task"), text(body, "text"));
                case "state" -> chat.tasksState(client);
                case "jobs" -> chat.jobsList(client);
                case "run_job" -> chat.runJob(client, text(body, "job"));
                case "research" -> chat.research(client, text(body, "question"),
                        body.path("mode").isString() && !body.path("mode").stringValue().isEmpty()
                                ? body.path("mode").stringValue() : "quick");
                case "drafts" -> chat.drafts(client);
                case "approve" -> chat.approvalRequest(client, text(body, "brief"), text(body, "mode"));
                case "confirm" -> chat.approvalConfirm(client, text(body, "confirm"));
                default -> Result.error(400, "unknown action '" + action + "'");
            };
        } catch (RuntimeException e) {
            LOG.warn("chat action {} by {}: {}", action, client, e.toString());
            return Result.error(500, "the action failed; see the inbox journal");
        }
    }
}
