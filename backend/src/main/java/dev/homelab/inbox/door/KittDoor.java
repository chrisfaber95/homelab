package dev.homelab.inbox.door;

import dev.homelab.inbox.conversation.Clients;
import dev.homelab.inbox.conversation.ConversationStore;
import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.Task;
import dev.homelab.inbox.core.TaskStore;
import dev.homelab.inbox.door.Ports.Brief;
import dev.homelab.inbox.door.Ports.Briefs;
import dev.homelab.inbox.door.Ports.Chunk;
import dev.homelab.inbox.door.Ports.Health;
import dev.homelab.inbox.door.Ports.JobInfo;
import dev.homelab.inbox.door.Ports.JobsPort;
import dev.homelab.inbox.door.Ports.Logs;
import dev.homelab.inbox.door.Ports.OwnerCheck;
import dev.homelab.inbox.door.Ports.Peers;
import dev.homelab.inbox.door.Ports.RunInfo;
import dev.homelab.inbox.door.Ports.Search;
import dev.homelab.inbox.door.Ports.Unavailable;
import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The door for kitt (H-018), ported from {@code kitt.py} and {@code server.py kitt_door} (H-026 K7). kitt's adapter
 * calls the inbox on loopback with the header {@code X-Homelab-Client: kitt}; this class decides whether that call is
 * really kitt acting for Chris and answers it with the same code the page and Discord use. What kitt may do is the
 * {@code kitt} entry in config/clients.json, read on every call, and the kitt switch on the homelab page.
 *
 * <p>The peer check: the client header, the user id that owns the connection (the kernel's socket table, so a local
 * process cannot claim it) against kitt's service account, and Chris's identity from the gate. A request that fails any
 * of them is refused with 403 and nothing is read from its body. Shadow: nothing binds this to a port.
 */
public class KittDoor {

    static final String CLIENT = "kitt";
    /** What kitt's screens call a capability, and the permissions in clients.json it takes. All of them must be there. */
    static final Map<String, List<String>> CAPABILITIES = capabilities();
    private static final Set<String> KINDS =
            Set.of("question", "task", "answer", "notification", "approval_request", "approval");
    private static final Pattern LOG_ID = Pattern.compile("[tr][0-9]+");
    private static final Pattern LOG_PATH = Pattern.compile("/api/logs/([^/]+)");
    private static final Pattern APPROVAL_PATH = Pattern.compile("/api/approvals/([^/]+)");
    private static final Pattern JOB_PATH = Pattern.compile("/api/jobs/([a-z0-9-]+)/(run|pause|resume)");
    private static final int MAX_BODY = 64 * 1024;
    /** How a Claude run looks in the job history, derived from its inbox task ({@code TASK_TO_RUN}). */
    private static final Map<String, String> TASK_TO_RUN = Map.ofEntries(Map.entry("queued", "queued"),
            Map.entry("running", "running"), Map.entry("waiting", "question"), Map.entry("pr_open", "pr"),
            Map.entry("merged", "pr"), Map.entry("closed", "pr"), Map.entry("answered", "ok"),
            Map.entry("capped", "capped"), Map.entry("cancelled", "cancelled"), Map.entry("failed", "failed"),
            Map.entry("refused", "failed"));
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");
    private static final Logger LOG = LoggerFactory.getLogger(KittDoor.class);

    private static Map<String, List<String>> capabilities() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        m.put("chat", List.of("read", "write", "ask"));
        m.put("jobs", List.of("read", "run_job"));
        m.put("approvals", List.of("approve"));
        m.put("usage", List.of("read"));
        m.put("logs", List.of("read"));
        return m;
    }

    private final ChatActions chat;
    private final ConversationStore conversation;
    private final Clients clients;
    private final ConversationNotes notes;
    private final TaskStore tasks;
    private final Briefs briefs;
    private final JobsPort jobs;
    private final Search search;
    private final Health health;
    private final Logs logs;
    private final OwnerCheck owner;
    private final Peers peers;
    private final Executor background;
    private final Clock clock;
    private final DoorSettings settings;
    private final JsonMapper json;

    public KittDoor(ChatActions chat, ConversationStore conversation, Clients clients, ConversationNotes notes,
                    TaskStore tasks, Briefs briefs, JobsPort jobs, Search search, Health health,
                    Logs logs, OwnerCheck owner, Peers peers, Executor background, Clock clock, DoorSettings settings,
                    JsonMapper json) {
        this.chat = chat;
        this.conversation = conversation;
        this.clients = clients;
        this.notes = notes;
        this.tasks = tasks;
        this.briefs = briefs;
        this.jobs = jobs;
        this.search = search;
        this.health = health;
        this.logs = logs;
        this.owner = owner;
        this.peers = peers;
        this.background = background;
        this.clock = clock;
        this.settings = settings;
        this.json = json;
    }

    // --- the peer check ---------------------------------------------------------------------------------------------

    /**
     * Why this is not kitt acting for Chris (a string), or null when it is. Checked: the client header, the account
     * that owns the connection, and that the person at the app is the owner of the tailnet (the gate puts that header
     * on; a request that did not pass the gate has none).
     */
    public String identify(Req req) {
        if (!CLIENT.equals(req.header("X-Homelab-Client"))) {
            return "not the kitt client";
        }
        OptionalLong want = peers.accountUid();
        if (want.isEmpty() || !peers.peerUid(req.remoteIp(), req.remotePort(), req.serverPort()).equals(want)) {
            return "not from kitt's service account";
        }
        String who = owner.owner();
        if (who == null || who.isEmpty() || !who.equals(req.header("Tailscale-User-Login"))) {
            return "not Chris at the app";
        }
        return null;
    }

    /**
     * The door as {@code server.py kitt_door}: empty when the request has no {@code X-Homelab-Client} header (it falls
     * through to the normal check, which refuses the box itself); otherwise the answer, a refusal included.
     */
    public Optional<Result> handle(Req req) {
        if (req.header("X-Homelab-Client") == null || req.header("X-Homelab-Client").isEmpty()) {
            return Optional.empty();
        }
        String why = identify(req);
        if (why != null) {
            LOG.info("kitt door: refused {} {} ({})", req.method(), req.path(), why);
            return Optional.of(Result.error(403, "refused: " + why));
        }
        JsonNode body;
        try {
            body = "POST".equals(req.method()) ? parseBody(req.body()) : json.createObjectNode();
        } catch (IllegalArgumentException e) {
            return Optional.of(Result.error(400, "bad request: " + e.getMessage()));
        }
        Result result;
        try {
            result = route(req.method(), req.path(), req.query(), body);
        } catch (RuntimeException e) {
            LOG.warn("kitt door {} {}: {}", req.method(), req.path(), e.toString());
            result = Result.error(500, "the action failed; see the inbox journal");
        }
        return Optional.of(result != null ? result : Result.error(404, "not a call kitt may make"));
    }

    private JsonNode parseBody(byte[] raw) {
        byte[] bytes = raw == null ? new byte[0] : raw.length > MAX_BODY ? java.util.Arrays.copyOf(raw, MAX_BODY) : raw;
        if (new String(bytes, java.nio.charset.StandardCharsets.UTF_8).isBlank()) {
            return json.createObjectNode();
        }
        JsonNode b;
        try {
            b = json.readTree(bytes);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not valid JSON");
        }
        if (!b.isObject()) {
            throw new IllegalArgumentException("not an object");
        }
        return b;
    }

    // --- capabilities -----------------------------------------------------------------------------------------------

    /** kitt's capabilities, from the permission list, and false for all of them while the switch is off. */
    public Map<String, Object> permissions() {
        Set<String> have = clients.all().getOrDefault(CLIENT, Set.of());
        boolean on = conversation.switchOn(CLIENT);
        Map<String, Object> out = new LinkedHashMap<>();
        CAPABILITIES.forEach((cap, need) -> out.put(cap, on && have.containsAll(need)));
        return out;
    }

    Result clientState() {
        return Result.of(200, "enabled", conversation.switchOn(CLIENT), "permissions", permissions());
    }

    /** Null when kitt may do this; otherwise the refusal to return (the refusal is logged by {@code allowed}). */
    private Result need(String action) {
        return conversation.allowed(CLIENT, action) ? null : refuseText(action);
    }

    private Result refuseText(String action) {
        String why = conversation.switchedOff(CLIENT, null);
        if (why == null) {
            why = action.equals("approve") ? "approvals from kitt are off" : "kitt may not " + action.replace('_', ' ');
        }
        return Result.error(403, why);
    }

    // --- conversation -----------------------------------------------------------------------------------------------

    /** A conversation entry as kitt shows it: Chris's messages are a question or a task, an unknown client is the box. */
    Map<String, Object> entry(Map<String, Object> e) {
        String kind = String.valueOf(e.get("kind"));
        if (kind.equals("message")) {
            String ref = e.get("ref") == null ? "" : e.get("ref").toString();
            kind = ref.startsWith("task:") || ref.startsWith("job:") ? "task" : "question";
        }
        if (!KINDS.contains(kind)) {
            kind = "notification";
        }
        String client = String.valueOf(e.get("client"));
        if (!List.of("discord", "kitt", "page").contains(client)) {
            client = "box";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", e.get("id"));
        out.put("at", e.get("time"));
        out.put("author", e.get("author"));
        out.put("client", client);
        out.put("kind", kind);
        out.put("text", e.get("text"));
        return out;
    }

    Result readConversation(long after) {
        Result r = need("read");
        if (r != null) {
            return r;
        }
        List<Map<String, Object>> entries = conversation.read(after, 200);
        List<Object> shown = new ArrayList<>();
        entries.forEach(e -> shown.add(entry(e)));
        Object cursor = entries.isEmpty() ? (Object) after : entries.get(entries.size() - 1).get("id");
        return Result.of(200, "entries", shown, "cursor", cursor);
    }

    /** The private search's answer to a question from kitt, written to the conversation as the box's answer. */
    public void answerQuestion(String question) {
        Result r = search.ask(question);
        String text;
        if (r.status() == 200 && Boolean.TRUE.equals(r.get("found"))) {
            StringBuilder cites = new StringBuilder();
            if (r.get("citations") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> c) {
                        if (cites.length() > 0) {
                            cites.append('\n');
                        }
                        cites.append('[').append(c.get("n")).append("] ").append(c.get("path")).append(" (")
                                .append(c.get("location")).append(')');
                        if (c.get("link") != null && !c.get("link").toString().isEmpty()) {
                            cites.append(" <").append(c.get("link")).append('>');
                        }
                    }
                }
            }
            text = cites.length() > 0 ? r.get("answer") + "\n\nSources\n" + cites : String.valueOf(r.get("answer"));
        } else if (r.status() == 200) {
            text = "Nothing relevant found in your private search for that question.";
        } else {
            Object why = r.get("error") != null && !r.error().isEmpty() ? r.get("error") : r.status();
            text = "The search could not answer: " + why + ".";
        }
        notes.note("box", CLIENT, "answer", text, null, null);
    }

    /**
     * A question or task typed in kitt. A question is stored and answered by the private search in the background; a
     * task is queued exactly as a task from Discord (same queue, cap and PR), with source kitt.
     */
    Result send(String kind, String text) {
        text = Texts.strip(text);
        if (kind == null || !(kind.equals("question") || kind.equals("task")) || text.isEmpty()) {
            return Result.error(400, "send a question or a task with a text");
        }
        if (kind.equals("task")) {
            Result r = chat.startTask(CLIENT, "auto", text);
            if (r.status() != 200) {
                return r;
            }
            List<Map<String, Object>> all = conversation.read(0, 1000);
            Map<String, Object> found = null;
            for (Map<String, Object> e : all.subList(Math.max(0, all.size() - 50), all.size())) {
                if (("task:" + r.get("task")).equals(e.get("ref"))) {
                    found = e;
                }
            }
            return found != null ? Result.of(200, "entry", entry(found))
                    : Result.error(502, "the task started but is not in the thread");
        }
        Result r = need("ask");
        if (r != null) {
            return r;
        }
        if (!conversation.allowed(CLIENT, "ask")) {
            return refuseText("ask");
        }
        Map<String, Object> e = conversation.append("chris", CLIENT, "question", text, null, null);
        String question = text;
        background.execute(() -> answerQuestion(question));
        return Result.of(200, "entry", entry(e));
    }

    // --- logs -------------------------------------------------------------------------------------------------------

    private static String taskState(String status) {
        return status.equals("running") ? "running" : status.equals("failed") ? "failed" : "finished";
    }

    private static String runState(String status) {
        return List.of("queued", "running").contains(status) ? "running" : status.equals("failed") ? "failed" : "finished";
    }

    Result logsList() {
        Result r = need("read");
        if (r != null) {
            return r;
        }
        List<Map<String, Object>> items = new ArrayList<>();
        for (Task t : tasks.tasks(30)) {
            if (t.status().equals("queued")) {
                continue;
            }
            String title = (t.has("job") ? "Job " + t.str("job") : "Task #" + t.id()) + ": "
                    + Texts.firstLine(t.str("text"));
            items.add(Result.of(0, "id", "t" + t.id(), "title", Texts.cut(title, 120), "kind", t.has("job") ? "job" : "task",
                    "state", taskState(t.status()), "startedAt", t.has("started") ? t.str("started") : t.str("created"))
                    .body());
        }
        for (RunInfo run : jobs.runs(null, 30)) {
            if (run.taskId() == null) {
                items.add(Result.of(0, "id", "r" + run.id(), "title", "Job " + run.job(), "kind", "job", "state",
                        runState(run.status()), "startedAt", run.started() != null ? run.started() : run.created()).body());
            }
        }
        items.sort(Comparator.comparing((Map<String, Object> i) -> i.get("startedAt").toString()).reversed());
        return Result.of(200, "logs", new ArrayList<Object>(items.subList(0, Math.min(40, items.size()))));
    }

    Result readLogChunk(String key, long start) {
        Result r = need("read");
        if (r != null) {
            return r;
        }
        if (!LOG_ID.matcher(key).matches()) {
            return Result.error(404, "no such log");
        }
        String state = null;
        if (key.length() < 18) {
            long id = Long.parseLong(key.substring(1));
            if (key.charAt(0) == 't') {
                state = tasks.get(id).map(t -> taskState(t.status())).orElse(null);
            } else {
                state = jobs.run(id).map(run -> runState(run.status())).orElse(null);
            }
        }
        if (state == null) {
            return Result.error(404, "no such log");
        }
        Chunk chunk = logs.read(key, (int) Math.max(0, Math.min(start, Integer.MAX_VALUE)));
        List<Object> lines = new ArrayList<>();
        for (Map<String, Object> e : chunk.lines()) {
            String at = String.valueOf(e.get("at"));
            lines.add(at.substring(Math.min(11, at.length()), Math.min(19, at.length())) + " [" + e.get("kind") + "] "
                    + e.get("text"));
        }
        return Result.of(200, "lines", lines, "next", chunk.next(), "state", state);
    }

    // --- approvals --------------------------------------------------------------------------------------------------

    /** The bell: draft briefs waiting for Chris, and tasks waiting on his answer. */
    Result approvalsList() {
        Result r = need("read");
        if (r != null) {
            return r;
        }
        List<Object> items = new ArrayList<>();
        for (Brief b : briefs.all()) {
            if (b.status().equals("draft")) {
                items.add(Result.of(0, "id", b.id(), "kind", "brief", "title", b.id() + ": " + b.title(), "at",
                        tasks.now()).body());
            }
        }
        for (Task t : tasks.withStatus("waiting")) {
            String text = t.has("question") && !t.str("question").isEmpty() ? t.str("question") : t.str("text");
            String at = t.has("ended") ? t.str("ended") : t.has("started") ? t.str("started") : t.str("created");
            items.add(Result.of(0, "id", "question:" + t.id(), "kind", "question", "title",
                    Texts.cut(Texts.firstLine(text), 200), "at", at).body());
        }
        return Result.of(200, "items", items);
    }

    /**
     * Approve and build ({@code approve}) or Approve and build without me ({@code approve_unattended}) for a brief. kitt
     * has asked for its own confirm already, so both steps of the approval run here, which keeps the same checks, the
     * "approved from kitt" record and the ntfy notification as Discord's approval.
     */
    Result decide(String item, String decision) {
        String mode = "approve".equals(decision) ? "build" : "approve_unattended".equals(decision) ? "without-me" : null;
        if (mode == null) {
            return Result.error(400, "kitt approves with approve or approve_unattended; denying is not offered");
        }
        if (!ChatActions.BRIEF_ID.matcher(item).matches()) {
            return Result.error(409, "only a brief is approved; answer a question from Claude in the thread");
        }
        if (!conversation.allowed(CLIENT, "approve")) {
            return refuseText("approve");
        }
        Result r = chat.approvalRequest(CLIENT, item, mode);
        return r.status() == 200 ? chat.approvalConfirm(CLIENT, String.valueOf(r.get("confirm"))) : r;
    }

    // --- jobs -------------------------------------------------------------------------------------------------------

    /** {@code run_view}: the state and summary of a run; for a Claude run from its inbox task. */
    private String[] runView(RunInfo run) {
        if (run.taskId() != null) {
            Optional<Task> t = tasks.get(run.taskId());
            if (t.isPresent()) {
                Task task = t.get();
                String summary = first(task.str("answer"), task.str("summary"), task.str("question"), task.str("note"));
                return new String[] {TASK_TO_RUN.get(task.status()), summary};
            }
        }
        return new String[] {run.status(), first(run.summary(), run.note())};
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private Map<String, Object> jobCard(String name, JobInfo job) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", name);
        if (!job.usable()) {
            card.put("schedule", "");
            card.put("model", "");
            card.put("deliver", "");
            card.put("paused", false);
            card.put("running", false);
            card.put("lastResult", null);
            card.put("nextRun", null);
            card.put("error", job.error());
            return card;
        }
        List<RunInfo> runs = jobs.runs(name, 5);
        List<String[]> views = runs.stream().map(this::runView).toList();
        int last = -1;
        for (int i = 0; i < views.size(); i++) {
            if (!List.of("queued", "running", "claude").contains(views.get(i)[0])) {
                last = i;
                break;
            }
        }
        card.put("schedule", job.when() != null && !job.when().isEmpty() ? job.when() : job.schedule());
        card.put("model", job.model());
        card.put("deliver", job.deliver());
        card.put("paused", job.paused());
        card.put("running", !runs.isEmpty() && List.of("queued", "running", "claude").contains(views.get(0)[0]));
        Map<String, Object> lastResult = null;
        if (last >= 0) {
            RunInfo run = runs.get(last);
            lastResult = Result.of(0, "state", views.get(last)[0].equals("failed") ? "failed" : "ok", "at",
                    run.ended() != null ? run.ended() : run.created(), "summary", Texts.cut(views.get(last)[1], 300)).body();
        }
        card.put("lastResult", lastResult);
        card.put("nextRun", job.paused() ? null : job.cron().nextAfter(clock.instant(), settings.tz())
                .toOffsetDateTime().format(ISO_SECONDS));
        card.put("error", null);
        return card;
    }

    Result jobsList() {
        Result r = need("read");
        if (r != null) {
            return r;
        }
        List<Object> cards = new ArrayList<>();
        new TreeMap<>(jobs.jobs()).forEach((n, j) -> cards.add(jobCard(n, j)));
        return Result.of(200, "jobs", cards);
    }

    /** Run now, pause, resume: what the Jobs tab does. Creating, editing and deleting a job are not offered. */
    Result jobAction(String name, String action) {
        JobInfo job = jobs.jobs().get(name);
        if (job == null || !job.usable()) {
            return Result.error(404, "no usable job '" + name + "'");
        }
        if (action.equals("run")) {
            return chat.runJob(CLIENT, name);
        }
        if (!conversation.allowed(CLIENT, "run_job")) {
            return refuseText("run_job");
        }
        boolean pause = action.equals("pause");
        try {
            jobs.pause(name, pause, "jobs: " + name + " " + (pause ? "paused" : "resumed") + " (from kitt)");
        } catch (Unavailable e) {
            return Result.error(502, e.getMessage());
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
        notes.note("chris", CLIENT, "message", (pause ? "Paused" : "Resumed") + " job " + name, null, "job:" + name);
        return Result.of(200, "job", name, "paused", pause);
    }

    // --- health -----------------------------------------------------------------------------------------------------

    Result healthChecks() {
        Result r = need("read");
        if (r != null) {
            return r;
        }
        Map<String, String> word = Map.of("OK", "ok", "WARN", "warn", "FAIL", "fail");
        List<Object> out = new ArrayList<>();
        new TreeMap<>(health.checks()).forEach((n, c) -> out.add(Result.of(0, "name", n, "status",
                word.getOrDefault(String.valueOf(c.get("status")), "warn"), "text",
                c.get("text") == null ? "" : c.get("text").toString()).body()));
        return Result.of(200, "checks", out);
    }

    // --- routing ----------------------------------------------------------------------------------------------------

    private static long number(Map<String, String> q, String key) {
        try {
            String v = q.get(key);
            return v == null || v.isEmpty() ? 0 : Long.parseLong(v.strip().replaceFirst("^\\+", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String str(JsonNode body, String key) {
        JsonNode v = body.get(key);
        return v == null || v.isNull() ? null : v.isString() ? v.stringValue() : v.toString();
    }

    /** (status, body) for one of kitt's calls, or null when the path is not one of them. */
    Result route(String method, String path, Map<String, String> query, JsonNode body) {
        boolean get = method.equals("GET");
        boolean post = method.equals("POST");
        if (path.equals("/api/clients/kitt") && get) {
            return clientState();
        }
        if (path.equals("/api/conversation")) {
            if (get) {
                return readConversation(number(query, "after"));
            }
            if (post) {
                return send(str(body, "kind"), str(body, "text"));
            }
        }
        if (path.equals("/api/logs") && get) {
            return logsList();
        }
        Matcher m = LOG_PATH.matcher(path);
        if (m.matches() && get) {
            return readLogChunk(m.group(1), number(query, "from"));
        }
        if (path.equals("/api/approvals") && get) {
            return approvalsList();
        }
        m = APPROVAL_PATH.matcher(path);
        if (m.matches() && post) {
            return decide(m.group(1), str(body, "decision"));
        }
        if (path.equals("/api/jobs") && get) {
            return jobsList();
        }
        m = JOB_PATH.matcher(path);
        if (m.matches() && post) {
            return jobAction(m.group(1), m.group(2));
        }
        if (path.equals("/api/health") && get) {
            return healthChecks();
        }
        return null;
    }
}
