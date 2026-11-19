package dev.homelab.inbox.door;

import dev.homelab.inbox.conversation.Clients;
import dev.homelab.inbox.conversation.ConversationDb;
import dev.homelab.inbox.conversation.ConversationStore;
import dev.homelab.inbox.core.Cancellations;
import dev.homelab.inbox.core.ConnectionSource;
import dev.homelab.inbox.core.CoreSettings;
import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.ShadowEffects;
import dev.homelab.inbox.core.TaskActions;
import dev.homelab.inbox.core.TaskStore;
import dev.homelab.inbox.db.ThrowawayPostgres;
import dev.homelab.inbox.door.Ports.Brief;
import dev.homelab.inbox.door.Ports.Briefs;
import dev.homelab.inbox.door.Ports.Chunk;
import dev.homelab.inbox.door.Ports.JobInfo;
import dev.homelab.inbox.door.Ports.JobsPort;
import dev.homelab.inbox.door.Ports.OwnerCheck;
import dev.homelab.inbox.door.Ports.Peers;
import dev.homelab.inbox.door.Ports.RunInfo;
import dev.homelab.inbox.door.Ports.Search;
import dev.homelab.inbox.door.Ports.Verdict;
import dev.homelab.inbox.read.Settings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.flywaydb.core.Flyway;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Everything the door tests share: one throwaway PostgreSQL per JVM with the Flyway schema and the conversation marker,
 * the real store, task store, task actions and door classes on it, and fakes for the ports and effects. The fakes are
 * the Python tests' monkeypatches: a brief that is a draft until approved, a job list, a search, a peer uid.
 */
final class DoorFixture {

    static final String OWNER = "chris@example.com";
    static final String BID = "H-016";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private static ThrowawayPostgres pg;
    private static String url;
    private static String user;
    private static Path tokens;

    static synchronized void start() throws Exception {
        if (pg != null) {
            return;
        }
        pg = ThrowawayPostgres.start();
        user = pg.user();
        try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), user, null); Statement s = c.createStatement()) {
            s.execute("CREATE DATABASE door");
        }
        url = pg.jdbcUrl("door");
        Flyway.configure().dataSource(url, user, null).load().migrate();
        try (Connection c = DriverManager.getConnection(url, user, null); Statement s = c.createStatement()) {
            s.execute("INSERT INTO kv (key, value) VALUES ('" + ConversationStore.MARKER + "', 'imported')");
        }
        tokens = Files.createTempDirectory("door-tokens");
        Files.writeString(tokens.resolve("chat-discord-token"), "tok-discord\n");
        Files.writeString(tokens.resolve("chat-reader-token"), "tok-reader\n");
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                pg.close();
            } catch (Exception ignored) {
                // the temp directory goes with the machine
            }
        }));
    }

    static Path tokenDir() {
        return tokens;
    }

    // --- the fakes -------------------------------------------------------------------------------------------------

    /** Writes a conversation note into the real store, as {@code conversation.note} does; never fails the caller. */
    static final class StoreNotes implements ConversationNotes {
        private final ConversationStore store;

        StoreNotes(ConversationStore store) {
            this.store = store;
        }

        @Override
        public void note(String author, String client, String kind, String text, String link, String ref) {
            try {
                store.append(author, client, kind, text, link, ref);
            } catch (RuntimeException e) {
                // a note never fails an action
            }
        }
    }

    /** A product repo with briefs: approving a draft is a commit that is only remembered, then a task row is queued. */
    static final class FakeBriefs implements Briefs {
        final Map<String, Brief> briefs = new LinkedHashMap<>();
        final List<String> commits = new ArrayList<>();
        private final TaskStore tasks;

        FakeBriefs(TaskStore tasks) {
            this.tasks = tasks;
        }

        @Override
        public Optional<Brief> byId(String id) {
            return Optional.ofNullable(briefs.get(id));
        }

        @Override
        public List<Brief> all() {
            return List.copyOf(briefs.values());
        }

        @Override
        public boolean liveBuild(String id) {
            return false;
        }

        @Override
        public long startBuild(String id, boolean unattended, String who, String source) {
            Brief b = briefs.get(id);
            if (b == null || !(b.status().equals("draft") || b.status().equals("approved"))) {
                throw new IllegalStateException(id + " is not an approved brief on main of the product repo");
            }
            if (b.status().equals("draft")) {
                commits.add("approved from " + source + " by " + who + (unattended ? ", build without me" : ""));
                briefs.put(id, new Brief(id, b.title(), "approved"));
            }
            long tid = tasks.newTask("brief", "Build " + id + ": " + b.title(), source);
            tasks.update(tid, TaskStore.fields("brief", id, "unattended", unattended ? 1 : 0));
            return tid;
        }
    }

    static final class FakeJobs implements JobsPort {
        final Map<String, JobInfo> jobs = new LinkedHashMap<>();
        final List<RunInfo> runs = new ArrayList<>();
        final List<String> dispatched = new ArrayList<>();
        final List<String> paused = new ArrayList<>();
        Result research = Result.of(200, "run", 5, "mode", "quick", "url", "http://homelab:8105/research");
        RuntimeException pauseFails;

        @Override
        public Map<String, JobInfo> jobs() {
            return jobs;
        }

        @Override
        public List<RunInfo> runs(String name, int limit) {
            return runs.stream().filter(r -> name == null || r.job().equals(name)).limit(limit).toList();
        }

        @Override
        public Optional<RunInfo> run(long id) {
            return runs.stream().filter(r -> r.id() == id).findFirst();
        }

        @Override
        public long dispatch(String name) {
            dispatched.add(name);
            return 7;
        }

        @Override
        public void pause(String name, boolean pause, String message) {
            if (pauseFails != null) {
                throw pauseFails;
            }
            paused.add(name + "|" + pause + "|" + message);
        }

        @Override
        public Result research(String question, String mode, String source) {
            return research;
        }
    }

    static final class FakeSearch implements Search {
        Result answer = Result.error(502, "no search here");
        final List<String> asked = new ArrayList<>();

        @Override
        public Result ask(String question) {
            asked.add(question);
            return answer;
        }
    }

    static final class FakeOwner implements OwnerCheck {
        volatile String owner = OWNER;
        final Map<String, Verdict> verdicts = new ConcurrentHashMap<>();

        @Override
        public String owner() {
            return owner;
        }

        @Override
        public Verdict allowed(String ip) {
            return verdicts.getOrDefault(ip, new Verdict(true, "phone"));
        }
    }

    static final class FakePeers implements Peers {
        volatile OptionalLong account = OptionalLong.of(4242);
        volatile Function<Integer, OptionalLong> peer = port -> OptionalLong.of(4242);
        final List<OptionalLong> seen = new ArrayList<>();
        volatile Peers real;

        @Override
        public OptionalLong accountUid() {
            return account;
        }

        @Override
        public OptionalLong peerUid(String ip, int port, int serverPort) {
            OptionalLong uid = real != null ? real.peerUid(ip, port, serverPort) : peer.apply(port);
            seen.add(uid);
            return uid;
        }
    }

    // --- the wiring ------------------------------------------------------------------------------------------------

    final Map<String, String> env = new ConcurrentHashMap<>();
    final MutableClock clock = new MutableClock("2026-11-18T10:00:00Z");
    final ConnectionSource db;
    final Settings settings;
    final Clients clients;
    final ConversationStore store;
    final TaskStore tasks;
    final ShadowEffects shadow = new ShadowEffects();
    final ShadowEffects notified = new ShadowEffects(); // what would have been sent to ntfy
    final ConversationNotes notes;
    final TaskActions taskActions;
    final FakeBriefs briefs;
    final FakeJobs jobs = new FakeJobs();
    final FakeSearch search = new FakeSearch();
    final FakeOwner owner = new FakeOwner();
    final FakePeers peers = new FakePeers();
    final List<Runnable> background = new ArrayList<>();
    final Map<String, Map<String, Object>> checks = new LinkedHashMap<>();
    final Map<String, Chunk> logs = new LinkedHashMap<>();
    final DoorSettings door;
    final ChatActions chat;
    final KittDoor kitt;
    final ChatDoor chatDoor;
    final Path configDir;

    DoorFixture() throws Exception {
        start();
        configDir = Files.createTempDirectory("door-conf");
        settings = new Settings(configDir, env);
        ConversationDb conversationDb = new ConversationDb(url, user);
        db = conversationDb::open;
        clients = new Clients(settings, JSON);
        store = new ConversationStore(conversationDb, clients);
        tasks = new TaskStore(db, clock);
        notes = new StoreNotes(store);
        taskActions = new TaskActions(tasks, CoreSettings.shadowDefaults(), new Cancellations(), shadow, shadow, shadow,
                notes, shadow, Runnable::run, clock, "http://homelab:8105");
        briefs = new FakeBriefs(tasks);
        door = new DoorSettings(300, 10, "http://homelab:8105", "homelab-app-kitt", "http://127.0.0.1:8122", "", 8121,
                tokens, ZoneId.of("UTC"));
        chat = new ChatActions(tasks, taskActions, store, clients, notes, notified, briefs, jobs,
                () -> Set.of("homelab", "visualshopping"), db, clock, door);
        kitt = new KittDoor(chat, store, clients, notes, tasks, briefs, jobs, search, () -> checks,
                (key, start) -> logs.getOrDefault(key, new Chunk(List.of(), 0)), owner, peers, background::add, clock,
                door, JSON);
        chatDoor = new ChatDoor(chat, store, clients, door, JSON);
    }

    /** Empty tables, default clients, every switch on, one draft brief: the start state of each test. */
    void reset(String clientsJson) throws Exception {
        try (Connection c = db.open(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE tasks, conversation, conversation_refusals, approvals RESTART IDENTITY");
            s.execute("DELETE FROM kv WHERE key <> '" + ConversationStore.MARKER + "'");
        }
        env.put("CLIENTS_JSON", clientsJson);
        briefs.briefs.clear();
        briefs.commits.clear();
        briefs.briefs.put(BID, new Brief(BID, "Chat", "draft"));
        jobs.jobs.clear();
        jobs.runs.clear();
        jobs.dispatched.clear();
        jobs.paused.clear();
        jobs.pauseFails = null;
        search.asked.clear();
        search.answer = Result.error(502, "no search here");
        checks.clear();
        logs.clear();
        background.clear();
        owner.owner = OWNER;
        owner.verdicts.clear();
        peers.account = OptionalLong.of(4242);
        peers.peer = port -> OptionalLong.of(4242);
        peers.real = null;
        peers.seen.clear();
        clock.set("2026-11-18T10:00:00Z");
    }

    void setClients(String json) {
        env.put("CLIENTS_JSON", json);
    }

    List<Map<String, Object>> entriesOfKind(String kind) {
        return store.read(0, 1000).stream().filter(e -> kind.equals(e.get("kind"))).toList();
    }

    int count(String sql) throws Exception {
        try (Connection c = db.open(); Statement s = c.createStatement(); var rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    // --- HTTP ------------------------------------------------------------------------------------------------------

    record Reply(int status, JsonNode body) {}

    static Reply call(int port, String method, String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        headers.forEach(b::header);
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        String text = r.body();
        return new Reply(r.statusCode(), text.startsWith("{") || text.startsWith("[") ? JSON.readTree(text)
                : JSON.createObjectNode().put("text", text));
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        private volatile Instant now;

        MutableClock(String iso) {
            now = Instant.parse(iso);
        }

        void set(String iso) {
            now = Instant.parse(iso);
        }

        void advance(java.time.Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zone;
                }

                @Override
                public Clock withZone(ZoneId z) {
                    return MutableClock.this.withZone(z);
                }

                @Override
                public Instant instant() {
                    return MutableClock.this.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
