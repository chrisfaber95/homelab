package dev.homelab.inbox.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.db.ConversationImporter;
import dev.homelab.inbox.db.ThrowawayPostgres;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The conversation routes (H-024 M4) against a throwaway PostgreSQL: the chat door's entries and state per client of a
 * clients.json (permissions, switches, refusals, scrubbing, error bodies), the internal routes the Python inbox uses,
 * and the rule that nothing works before the import has run. Runs in the order written: the first test is the
 * "not imported" state.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "backend.secret=test-secret")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ConversationStoreTest {

    static final String CLIENTS = """
            {"clients": {
              "discord": {"permissions": ["read", "write", "ask", "answer", "approve", "ssh_key", "bogus"]},
              "kitt": {"permissions": ["read", "write", "ask"]},
              "reader": {"permissions": ["read"]},
              "mute": {"permissions": []}}}""";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static ThrowawayPostgres pg;
    static String url;
    static Path config;
    static Path sqlite;

    static {
        try {
            pg = ThrowawayPostgres.start();
            try (Connection c = DriverManager.getConnection(pg.jdbcUrl(), pg.user(), null); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE conv");
            }
            url = pg.jdbcUrl("conv");
            config = Files.createTempDirectory("conv-conf");
            Files.writeString(config.resolve("clients.json"), CLIENTS);
            sqlite = Files.createTempDirectory("conv-sqlite").resolve("inbox.db");
            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite); Statement s = c.createStatement()) {
                s.execute("CREATE TABLE conversation (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL, author TEXT NOT NULL,"
                        + " client TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, link TEXT, ref TEXT)");
                s.execute("CREATE TABLE conversation_refusals (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL,"
                        + " client TEXT NOT NULL, action TEXT NOT NULL, why TEXT NOT NULL)");
                s.execute("CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT)");
                s.execute("INSERT INTO conversation VALUES (5, '2026-11-01T10:00:00+00:00', 'chris', 'page', 'message', 'oud ✓', NULL, NULL),"
                        + " (9, '2026-11-01T10:01:00+00:00', 'claude', 'inbox', 'answer', 'antwoord', 'http://x/task/1', 'task:1')");
                s.execute("INSERT INTO conversation_refusals VALUES (3, '2026-11-01T10:02:00+00:00', 'discord', 'approve', 'no permission')");
                s.execute("INSERT INTO kv VALUES ('switch:kitt', 'off'), ('other', 'keep me in sqlite')");
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", pg::user);
        r.add("inbox.config-dir", () -> config.toString());
    }

    @AfterAll
    static void stop() throws Exception {
        pg.close();
    }

    @Value("${local.server.port}")
    int port;

    private HttpResponse<String> send(String method, String path, String client, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Inbox-Facade", "test-secret")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (client != null) {
            b.header("X-Inbox-Client", client);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> r, int status) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
        return JSON.readTree(r.body());
    }

    private void switchTo(String name, boolean on) throws Exception {
        body(send("POST", "/internal/conversation/switch/" + name, null, "{\"on\":" + on + "}"), 200);
    }

    @Test
    @Order(1)
    void nothingWorksBeforeTheImport() throws Exception {
        assertThat(body(send("GET", "/api/chat/state", "discord", null), 503).path("error").asString()).contains("not imported");
        body(send("POST", "/internal/conversation/append", null,
                "{\"author\":\"chris\",\"client\":\"page\",\"kind\":\"message\",\"text\":\"x\"}"), 503);
        body(send("GET", "/api/chat/entries", "discord", null), 503);
    }

    @Test
    @Order(2)
    void theImportMovesTheThreeSetsAndKeepsIdsAndTheRest() throws Exception {
        var counts = ConversationImporter.importFrom(sqlite.toString(), url, pg.user(), false);
        assertThat(counts).isEqualTo(new ConversationImporter.Counts(2, 1, 1));
        JsonNode e = body(send("GET", "/api/chat/entries?after=0", "discord", null), 200);
        assertThat(e.path("entries")).hasSize(2);
        assertThat(e.path("entries").get(0).path("id").asInt()).isEqualTo(5);
        assertThat(e.path("entries").get(1).path("ref").asString()).isEqualTo("task:1");
        assertThat(e.path("next").asInt()).isEqualTo(9);
        // only switch:* moved: kitt is off, the other kv key stayed in SQLite
        assertThat(body(send("GET", "/internal/conversation/switch/kitt", null, null), 200).path("on").asBoolean()).isFalse();
        // a second import refuses (Java owns the store now) and --if-not-imported is a quiet no-op via the command
        org.junit.jupiter.api.Assertions.assertThrows(ConversationImporter.AlreadyImported.class,
                () -> ConversationImporter.importFrom(sqlite.toString(), url, pg.user(), false));
        // the next generated id comes after the imported ones
        assertThat(body(send("POST", "/api/chat/entries", "discord", "{\"text\":\"eerste\"}"), 200).path("entry").path("id").asInt())
                .isEqualTo(10);
        switchTo("kitt", true);
    }

    @Test
    @Order(3)
    void stateAndSwitches() throws Exception {
        assertThat(body(send("GET", "/api/chat/state", "reader", null), 200).toString())
                .isEqualTo("{\"discord\":true,\"discord_approvals\":true}");
        switchTo("discord_approvals", false);
        assertThat(body(send("GET", "/api/chat/state", "reader", null), 200).toString())
                .isEqualTo("{\"discord\":true,\"discord_approvals\":false}");
        switchTo("discord", false);
        assertThat(body(send("GET", "/api/chat/state", "reader", null), 200).toString())
                .isEqualTo("{\"discord\":false,\"discord_approvals\":false}");
        assertThat(body(send("POST", "/api/chat/state", "reader", "{}"), 405).path("error").asString()).isEqualTo("GET only");
        switchTo("discord", true);
        switchTo("discord_approvals", true);
        body(send("POST", "/internal/conversation/switch/nope", null, "{\"on\":true}"), 400);
    }

    @Test
    @Order(4)
    void aSwitchedOffClientIsRefusedWithTheStateAndNothingIsLogged() throws Exception {
        int before = body(send("GET", "/internal/conversation/refusals?limit=1000", null, null), 200).path("refusals").size();
        switchTo("discord", false);
        JsonNode r = body(send("GET", "/api/chat/entries", "discord", null), 403);
        assertThat(r.path("error").asString()).isEqualTo("refused: Discord is switched off on the homelab page");
        assertThat(r.path("discord").asBoolean()).isFalse();
        body(send("POST", "/api/chat/entries", "discord", "{\"text\":\"x\"}"), 403);
        assertThat(body(send("GET", "/api/chat/entries", "reader", null), 200).has("entries")).isTrue();  // others are unaffected
        switchTo("discord", true);
        assertThat(body(send("GET", "/internal/conversation/refusals?limit=1000", null, null), 200).path("refusals").size())
                .isEqualTo(before);
        switchTo("kitt", false);
        assertThat(body(send("GET", "/api/chat/entries", "kitt", null), 403).path("error").asString())
                .isEqualTo("refused: Kitt is switched off on the homelab page");
        switchTo("kitt", true);
    }

    @Test
    @Order(5)
    void readNeedsThePermissionAndTheCursorWorks() throws Exception {
        assertThat(body(send("GET", "/api/chat/entries?after=0&limit=2", "reader", null), 200).path("entries")).hasSize(2);
        JsonNode page = body(send("GET", "/api/chat/entries?after=9", "reader", null), 200);
        assertThat(page.path("entries").get(0).path("text").asString()).isEqualTo("eerste");
        assertThat(body(send("GET", "/api/chat/entries?after=999", "reader", null), 200).toString()).isEqualTo("{\"entries\":[],\"next\":999}");
        assertThat(body(send("GET", "/api/chat/entries?limit=0", "reader", null), 200).path("entries")).hasSize(1);  // the limit is clamped to 1..1000
        assertThat(body(send("GET", "/api/chat/entries", "mute", null), 403).path("error").asString()).isEqualTo("refused: no permission 'read'");
        assertThat(body(send("GET", "/api/chat/entries", "nobody", null), 403).path("error").asString()).isEqualTo("refused: no permission 'read'");
        assertThat(body(send("GET", "/api/chat/entries?after=abc", "reader", null), 400).path("error").asString())
                .isEqualTo("after and limit are numbers");
        body(send("GET", "/api/chat/entries", null, null), 401);
        JsonNode refusals = body(send("GET", "/internal/conversation/refusals?limit=3", null, null), 200).path("refusals");
        assertThat(refusals.get(0).path("client").asString()).isEqualTo("nobody");
        assertThat(refusals.get(0).path("why").asString()).isEqualTo("unknown client");
        assertThat(refusals.get(1).path("why").asString()).isEqualTo("no permission 'read'");
    }

    @Test
    @Order(6)
    void writeIsAlwaysAsChrisAndTakesThePermissionOfTheKind() throws Exception {
        JsonNode e = body(send("POST", "/api/chat/entries", "kitt", "{\"kind\":\"question\",\"text\":\"  hoi  \",\"ref\":\"task:3\"}"), 200)
                .path("entry");
        assertThat(e.path("author").asString()).isEqualTo("chris");
        assertThat(e.path("client").asString()).isEqualTo("kitt");
        assertThat(e.path("kind").asString()).isEqualTo("question");
        assertThat(e.path("text").asString()).isEqualTo("hoi");
        assertThat(e.path("ref").asString()).isEqualTo("task:3");
        assertThat(e.path("link").isNull()).isTrue();
        assertThat(e.path("time").asString()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+00:00");
        assertThat(body(send("POST", "/api/chat/entries", "kitt", "{\"kind\":\"answer\",\"text\":\"x\"}"), 403).path("error").asString())
                .isEqualTo("refused: kitt may not write 'answer'");
        assertThat(body(send("POST", "/api/chat/entries", "reader", "{\"text\":\"x\"}"), 403).path("error").asString())
                .isEqualTo("refused: reader may not write 'message'");
        body(send("POST", "/api/chat/entries", "discord", "{\"kind\":\"notification\",\"text\":\"x\"}"), 403);
        body(send("POST", "/api/chat/entries", "discord", "{\"kind\":\"approval\",\"text\":\"x\"}"), 403);
        body(send("POST", "/api/chat/entries", "discord", "{\"kind\":\"answer\",\"text\":\"ja\"}"), 200);
        String refusal = body(send("GET", "/internal/conversation/refusals?limit=100", null, null), 200).toString();
        assertThat(refusal).contains("append:notification", "append:approval");
    }

    @Test
    @Order(7)
    void badRequestsAndScrubbing() throws Exception {
        assertThat(body(send("POST", "/api/chat/entries", "discord", "{\"kind\":\"message\"}"), 400).path("error").asString())
                .isEqualTo("bad request: 'text'");
        assertThat(body(send("POST", "/api/chat/entries", "discord", "{\"text\":\"   \"}"), 400).path("error").asString())
                .isEqualTo("bad request: the text is empty");
        body(send("POST", "/api/chat/entries", "discord", "nonsense"), 400);
        body(send("POST", "/api/chat/entries", "discord", "[1]"), 400);
        String fake = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4";
        String text = body(send("POST", "/api/chat/entries", "discord",
                "{\"text\":\"mijn token " + fake + " en password: hunter2hunter2 ok\"}"), 200).path("entry").path("text").asString();
        assertThat(text).doesNotContain(fake).doesNotContain("hunter2hunter2").contains("[removed: looks like a secret]").endsWith("ok");
        String long20k = "x".repeat(25000);
        assertThat(body(send("POST", "/api/chat/entries", "discord", "{\"text\":\"" + long20k + "\"}"), 200).path("entry").path("text")
                .asString()).hasSize(20000);
        String ref = body(send("POST", "/api/chat/entries", "discord", "{\"text\":\"r\",\"ref\":\"" + "r".repeat(100) + "\"}"), 200)
                .path("entry").path("ref").asString();
        assertThat(ref).hasSize(80);
    }

    @Test
    @Order(8)
    void theInternalRoutesAreForThePythonInboxAndNeedTheSecret() throws Exception {
        JsonNode e = body(send("POST", "/internal/conversation/append", null,
                "{\"author\":\"box\",\"client\":\"ntfy\",\"kind\":\"notification\",\"text\":\"Backup klaar\","
                        + "\"link\":\"http://x/?tskey-abcdefghijklmnop\",\"ref\":null}"), 200).path("entry");
        assertThat(e.path("author").asString()).isEqualTo("box");
        assertThat(e.path("link").asString()).contains("[removed: looks like a secret]");
        body(send("POST", "/internal/conversation/append", null,
                "{\"author\":\"mallory\",\"client\":\"x\",\"kind\":\"message\",\"text\":\"x\"}"), 400);
        body(send("POST", "/internal/conversation/append", null,
                "{\"author\":\"chris\",\"client\":\"x\",\"kind\":\"gossip\",\"text\":\"x\"}"), 400);
        assertThat(body(send("GET", "/internal/conversation/entries?after=0&limit=5", null, null), 200).path("entries")).hasSize(5);
        // allowed(): the same checks as the door, and a forbidden action is never allowed
        assertThat(body(send("POST", "/internal/conversation/allowed", null, "{\"client\":\"discord\",\"action\":\"approve\"}"), 200)
                .path("allowed").asBoolean()).isTrue();
        assertThat(body(send("POST", "/internal/conversation/allowed", null, "{\"client\":\"discord\",\"action\":\"ssh_key\"}"), 200)
                .path("allowed").asBoolean()).isFalse();
        assertThat(body(send("POST", "/internal/conversation/allowed", null, "{\"client\":\"discord\",\"action\":\"bogus\"}"), 200)
                .path("allowed").asBoolean()).isFalse();
        assertThat(body(send("POST", "/internal/conversation/allowed", null, "{\"client\":\"reader\",\"action\":\"write\"}"), 200)
                .path("allowed").asBoolean()).isFalse();
        switchTo("discord_approvals", false);
        assertThat(body(send("POST", "/internal/conversation/allowed", null, "{\"client\":\"discord\",\"action\":\"approve\"}"), 200)
                .path("allowed").asBoolean()).isFalse();
        switchTo("discord_approvals", true);
        String why = body(send("GET", "/internal/conversation/refusals?limit=100", null, null), 200).toString();
        assertThat(why).contains("chat clients can never do this", "approvals from Discord are off", "no permission 'write'");
        // no secret, no answer
        HttpResponse<String> naked = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/api/chat/state")).header("X-Inbox-Client", "discord").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(naked.statusCode()).isEqualTo(403);
    }
}
