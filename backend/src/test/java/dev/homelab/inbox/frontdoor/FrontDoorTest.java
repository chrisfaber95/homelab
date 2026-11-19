package dev.homelab.inbox.frontdoor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.UnixDomainSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The front door end to end (H-026 K8), over real HTTP on a random port: the Java counterpart of
 * {@code tests/test_inbox_session.py} and {@code tests/test_inbox_ssh_api.py}, plus the access and header checks that
 * Python tests only touch in passing. The tailnet is faked; the homelab-admin socket is a real unix socket.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"backend.secret=test-secret", "inbox.front-door.enabled=true"})
class FrontDoorTest {

    static final String JSON_UTF8 = "application/json; charset=utf-8";
    static final String OWNER = "chris@example.com";
    static final String PYTHON_CSP = "default-src 'none'; style-src 'self' 'unsafe-inline'; script-src 'self'; "
            + "font-src 'self'; connect-src 'self'; img-src 'self'; form-action 'self'; base-uri 'none'; "
            + "frame-ancestors 'none'";
    static final List<String[]> MUTATING = List.of(new String[] {"POST", "/api/forge/start"},
            new String[] {"POST", "/api/search/ask"}, new String[] {"POST", "/api/search/feedback"},
            new String[] {"POST", "/api/nothing"}, new String[] {"POST", "/api/ssh"},
            new String[] {"PUT", "/api/forge/start"}, new String[] {"PATCH", "/api/live"},
            new String[] {"DELETE", "/api/live"});

    static final Path DIR;
    static final Path SOCKET;
    static final Map<String, String> WHOIS = new HashMap<>();
    static final List<String> ADMIN_SAW = new CopyOnWriteArrayList<>();
    static volatile String adminAnswer = "{\"ok\": true}";
    static ServerSocketChannel admin;

    static {
        try {
            DIR = Files.createTempDirectory("front-door");
            // unix socket paths are short: the system temp dir, not the test's long one
            SOCKET = Files.createTempFile("admin", ".sock");
            Files.delete(SOCKET);
            Files.writeString(DIR.resolve("inbox.conf"), "# nothing the front door needs\n");
            Files.writeString(DIR.resolve("admin.conf"), "SSH_KEY_USER=\"chris\"\nCONFIRM_SECONDS=300\nSOCKET=\"" + SOCKET
                    + "\"\nLOG_FILE=\"" + DIR.resolve("changes.jsonl") + "\"\n");
            admin = ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX);
            admin.bind(UnixDomainSocketAddress.of(SOCKET));
            Thread.ofVirtual().start(FrontDoorTest::serveAdmin);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void serveAdmin() {
        while (admin.isOpen()) {
            try (SocketChannel ch = admin.accept()) {
                var in = new java.io.BufferedReader(new java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(ch),
                        StandardCharsets.UTF_8));
                ADMIN_SAW.add(in.readLine());
                ch.write(java.nio.ByteBuffer.wrap((adminAnswer + "\n").getBytes(StandardCharsets.UTF_8)));
            } catch (IOException e) {
                return;
            }
        }
    }

    @AfterAll
    static void closeAdmin() throws IOException {
        admin.close();
        Files.deleteIfExists(SOCKET);
    }

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry registry) {
        registry.add("inbox.config-dir", DIR::toString);
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        Csrf csrf() {
            return new Csrf(DIR.resolve("csrf-secret"));
        }

        @Bean
        @Primary
        TailnetAccess tailnet() {
            Tailscale fake = new Tailscale() {
                @Override
                public String status() throws IOException {
                    throw new IOException("no tailscale in tests");
                }

                @Override
                public String whois(String ip) throws IOException {
                    String answer = WHOIS.get(ip);
                    if (answer == null) {
                        throw new IOException("unknown");
                    }
                    return answer;
                }
            };
            // every lookup finds the cache expired, so each test's WHOIS answer counts (the cache itself is in TailnetAccessTest)
            java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
            return new TailnetAccess(fake, JsonMapper.builder().build(), () -> clock.addAndGet(400_000_000_000L), false);
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    TailnetAccess tailnet;

    @Autowired
    Csrf csrf;

    final HttpClient client = HttpClient.newHttpClient();
    final JsonMapper json = JsonMapper.builder().build();
    Path log = DIR.resolve("changes.jsonl");

    record Reply(int status, HttpResponse<String> raw) {
        String body() {
            return raw.body();
        }

        /** Tomcat writes {@code type;charset=utf-8} where Python writes {@code type; charset=utf-8}: same value, no space. */
        String contentType() {
            String ct = header("Content-Type");
            return ct == null ? null : ct.replace("; ", ";");
        }

        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    @BeforeEach
    void ownerAtTheDoor() throws IOException {
        // The test client connects from 127.0.0.1; tailscale says that address is the owner's phone.
        tailnet.setIdentity(OWNER, Set.of("100.85.70.34"));
        WHOIS.clear();
        WHOIS.put("127.0.0.1", TailnetAccessTest.whois(OWNER, "phone"));
        ADMIN_SAW.clear();
        adminAnswer = "{\"ok\": true}";
        Files.deleteIfExists(log);
    }

    Reply call(String method, String path, Map<String, String> headers, String body, boolean origin) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("X-Inbox-Facade", "test-secret");
        if (origin) {
            b.header("Origin", "http://127.0.0.1:" + port);
        }
        headers.forEach(b::header);
        HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Reply(r.statusCode(), r);
    }

    Reply call(String method, String path, Map<String, String> headers) throws Exception {
        return call(method, path, headers, null, true);
    }

    Reply get(String path) throws Exception {
        return call("GET", path, Map.of());
    }

    String token() throws Exception {
        Reply r = get("/api/session");
        assertThat(r.status()).isEqualTo(200);
        return json.readTree(r.body()).get("csrf_token").asString();
    }

    String error(Reply r, int status) {
        assertThat(r.status()).as(r.body()).isEqualTo(status);
        assertThat(r.contentType()).isEqualTo(JSON_UTF8.replace("; ", ";"));
        JsonNode body = json.readTree(r.body());
        assertThat(body.propertyNames()).containsExactly("error");
        assertThat(body.get("error").isString()).isTrue();
        return body.get("error").asString();
    }

    // -- GET /api/session --

    @Test
    void sessionReturnsTheTokenOfThePages() throws Exception {
        assertThat(json.readTree(get("/api/session").body()).get("csrf_token").asString()).isEqualTo(csrf.token());
        assertThat(token()).matches("[0-9a-f]{64}");
    }

    @Test
    void sessionIsNotCachedAndIsForGetOnly() throws Exception {
        assertThat(get("/api/session").header("Cache-Control")).isEqualTo("no-store");
        assertThat(error(call("POST", "/api/session", Map.of("X-CSRF-Token", token())), 404)).isEqualTo("Not found.");
    }

    // -- access: who may open what --

    @Test
    void theBoxItselfDoesNotGetTheToken() throws Exception {
        tailnet.setIdentity(OWNER, Set.of("127.0.0.1"));
        String body = error(get("/api/session"), 403);
        assertThat(body).isEqualTo("Only from your own devices on the tailnet.");
        assertThat(get("/api/session").body()).doesNotContain(csrf.token());
    }

    @Test
    void someoneWhoIsNotTheOwnerGetsNothing() throws Exception {
        WHOIS.put("127.0.0.1", TailnetAccessTest.whois("friend@example.com", "laptop"));
        for (String path : List.of("/api/session", "/api/ssh", "/api/live", "/ssh", "/")) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(403);
            assertThat(r.body()).as(path).doesNotContain(csrf.token());
        }
    }

    @Test
    void anAddressTailscaleDoesNotKnowGetsNothing() throws Exception {
        WHOIS.clear();
        tailnet.setIdentity(OWNER, Set.of("100.85.70.34"));
        error(get("/api/ssh"), 403);
    }

    @Test
    void anUnknownOwnerShutsTheDoorForEveryone() throws Exception {
        tailnet.setIdentity(null, Set.of());
        error(get("/api/session"), 403);
    }

    @Test
    void aRefusedPageIsHtmlNotJson() throws Exception {
        WHOIS.put("127.0.0.1", TailnetAccessTest.whois("friend@example.com", "laptop"));
        Reply r = get("/ssh");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.contentType()).isEqualTo("text/html;charset=utf-8");
        assertThat(r.body()).contains("Alleen vanaf je eigen apparaten op het tailnet.");
    }

    @Test
    void aRefusedWriteNeverReachesHomelabAdmin() throws Exception {
        WHOIS.put("127.0.0.1", TailnetAccessTest.whois("friend@example.com", "laptop"));
        Reply r = call("POST", "/api/ssh", Map.of("X-CSRF-Token", token0(), "Content-Type", "application/x-www-form-urlencoded"),
                "name=pc&key=ssh-ed25519+AAAA", true);
        error(r, 403);
        assertThat(ADMIN_SAW).isEmpty();
    }

    String token0() {
        return csrf.token();
    }

    // -- the token check --

    @Test
    void aMissingTokenIs403OnEveryMutatingMethod() throws Exception {
        for (String[] m : MUTATING) {
            assertThat(error(call(m[0], m[1], Map.of()), 403)).as("%s %s", m[0], m[1]).contains("X-CSRF-Token");
        }
        assertThat(ADMIN_SAW).isEmpty();
    }

    @Test
    void aWrongTokenIs403() throws Exception {
        String good = token();
        for (String bad : List.of("wrong", "x", good.substring(0, good.length() - 1), good + "0")) {
            for (String[] m : MUTATING) {
                error(call(m[0], m[1], Map.of("X-CSRF-Token", bad)), 403);
            }
        }
        assertThat(ADMIN_SAW).isEmpty();
    }

    @Test
    void theOldFormFieldDoesNotCountForApi() throws Exception {
        String body = "csrf=" + token() + "&question=x";
        Reply r = call("POST", "/api/search/ask", Map.of("Content-Type", "application/x-www-form-urlencoded"), body, true);
        assertThat(error(r, 403)).contains("X-CSRF-Token");
    }

    @Test
    void aWrongOriginIs403EvenWithTheToken() throws Exception {
        Map<String, String> h = Map.of("X-CSRF-Token", token());
        assertThat(error(call("POST", "/api/forge/start", Map.of("X-CSRF-Token", token(), "Origin", "http://evil.example"), null, false), 403))
                .isEqualTo("Wrong origin.");
        assertThat(error(call("POST", "/api/forge/start", h, null, false), 403)).isEqualTo("Wrong origin.");
    }

    @Test
    void theRightTokenGetsThroughTheChecks() throws Exception {
        Map<String, String> h = Map.of("X-CSRF-Token", token());
        assertThat(error(call("POST", "/api/nothing", h), 404)).isEqualTo("Not found.");
        for (String method : List.of("PUT", "PATCH", "DELETE")) {
            assertThat(error(call(method, "/api/live", h), 405)).isEqualTo(method + " is not supported");
        }
    }

    @Test
    void aGetNeedsNoToken() throws Exception {
        assertThat(get("/api/ssh").status()).isEqualTo(200);
    }

    @Test
    void theErrorShapeIsAFlatErrorString() throws Exception {
        assertThat(error(get("/api/nothing"), 404)).isEqualTo("Not found.");
    }

    @Test
    void aBadContentLengthIsA400BeforeAnythingElse() throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            String req = "POST /api/ssh HTTP/1.1\r\nHost: 127.0.0.1:" + port + "\r\nOrigin: http://127.0.0.1:" + port
                    + "\r\nX-Inbox-Facade: test-secret\r\nX-CSRF-Token: " + csrf.token() + "\r\nContent-Length: x\r\n"
                    + "Connection: close\r\n\r\n";
            s.getOutputStream().write(req.getBytes(StandardCharsets.UTF_8));
            String answer = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // Tomcat itself refuses a malformed Content-Length before the filter sees it; either way: 400, nothing done.
            assertThat(answer).startsWith("HTTP/1.1 400");
        }
        assertThat(ADMIN_SAW).isEmpty();
    }

    // -- the pages keep working --

    @Test
    void aPageFormStillUsesItsCsrfField() throws Exception {
        Map<String, String> form = Map.of("Content-Type", "application/x-www-form-urlencoded");
        Reply ok = call("POST", "/tasks", form, "csrf=" + token() + "&repo=nope&text=", true);
        assertThat(ok.status()).isEqualTo(404);  // through the door; this backend has no /tasks page yet (H-027)
        Reply bad = call("POST", "/tasks", form, "repo=x", true);
        assertThat(bad.status()).isEqualTo(403);
        assertThat(bad.header("Content-Type")).contains("text/html");
        assertThat(bad.body()).contains("Verlopen formulier");
        Reply origin = call("POST", "/tasks", form, "csrf=" + token(), false);
        assertThat(origin.status()).isEqualTo(403);
        assertThat(origin.body()).contains("Verkeerde herkomst");
    }

    @Test
    void pagesStayHtml() throws Exception {
        Reply r = get("/nope");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.contentType()).isEqualTo("text/html;charset=utf-8");
    }

    @Test
    void aPutOutsideTheApiIsUnsupported() throws Exception {
        assertThat(call("PUT", "/tasks", Map.of()).status()).isEqualTo(501);
    }

    // -- the headers --

    @Test
    void everyAnswerCarriesPythonsContentSecurityPolicy() throws Exception {
        List<Reply> replies = new ArrayList<>(List.of(get("/api/session"), get("/api/ssh"), get("/nope"), get("/api/nothing"),
                call("POST", "/api/session", Map.of()), call("PUT", "/api/live", Map.of("X-CSRF-Token", token())),
                call("POST", "/api/ssh", Map.of("X-CSRF-Token", token(), "Content-Type", "application/x-www-form-urlencoded"),
                        "name=pc&key=k", true)));
        WHOIS.put("127.0.0.1", TailnetAccessTest.whois("friend@example.com", "laptop"));
        replies.add(get("/api/session"));
        replies.add(get("/ssh"));
        for (Reply r : replies) {
            assertThat(r.header("Content-Security-Policy")).as(r.body()).isEqualTo(PYTHON_CSP);
            assertThat(r.header("X-Frame-Options")).isEqualTo("DENY");
            assertThat(r.header("Referrer-Policy")).isEqualTo("same-origin");
            assertThat(r.header("Cache-Control")).isEqualTo("no-store");
        }
    }

    @Test
    void thePolicyIsTheOneInServerPy() throws Exception {
        // server.py is the source of truth until H-027; compare the strings, not a copy of them.
        Path server = Path.of("..", "bin", "homelab_inbox", "server.py");
        if (!Files.isRegularFile(server)) {
            server = Path.of("bin", "homelab_inbox", "server.py");
        }
        String py = Files.readString(server);
        int from = py.indexOf("\"Content-Security-Policy\", ") + "\"Content-Security-Policy\", ".length();
        int to = py.indexOf(")", from);
        String literal = Stream.of(py.substring(from, to).split("\n")).map(String::strip)
                .map(l -> l.replaceAll("^\"|\"$", "")).collect(Collectors.joining());
        assertThat(SecurityHeaders.CONTENT_SECURITY_POLICY).isEqualTo(literal);
    }

    // -- the SSH tab --

    String entry(int n, String extra) {
        return String.format("{\"kind\": \"sshkey\", \"at\": \"2026-11-16T10:%02d:00+00:00\", \"summary\": \"key %d\", "
                + "\"result\": \"ok\", \"ok\": true, \"user\": \"inbox\"%s}", n, n, extra);
    }

    JsonNode ssh() throws Exception {
        Reply r = get("/api/ssh");
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.contentType()).isEqualTo(JSON_UTF8.replace("; ", ";"));
        return json.readTree(r.body());
    }

    @Test
    void aMissingLogGivesAnEmptyHistory() throws Exception {
        JsonNode body = ssh();
        assertThat(body.get("history").size()).isZero();
        assertThat(body.propertyNames()).containsExactlyInAnyOrder("user", "confirm_minutes", "history");
        assertThat(body.get("user").isString()).isTrue();
        assertThat(body.get("confirm_minutes").isInt()).isTrue();
    }

    @Test
    void theHistoryIsNewestFirstWithStatuses() throws Exception {
        String other = "{\"kind\": \"other\", \"at\": \"x\", \"summary\": \"sshkey mention\"}";
        // A garbled line makes the whole log unreadable, like the page: the history is empty.
        Files.writeString(log, String.join("\n", entry(1, ""), entry(2, ", \"ok\": false, \"result\": \"mislukt\""),
                entry(3, ", \"result\": \"aangevraagd, wacht op bevestiging\""), other, "not json {\"sshkey\""));
        assertThat(ssh().get("history").size()).isZero();
        Files.writeString(log, String.join("\n", entry(1, ""), entry(2, ", \"ok\": false, \"result\": \"mislukt\""),
                entry(3, ", \"result\": \"aangevraagd, wacht op bevestiging\""), other));
        JsonNode history = ssh().get("history");
        assertThat(IntStream.range(0, history.size()).mapToObj(i -> history.get(i).get("summary").asString()).toList())
                .containsExactly("key 3", "key 2", "key 1");
        assertThat(IntStream.range(0, history.size()).mapToObj(i -> history.get(i).get("status").asString()).toList())
                .containsExactly("waiting", "fail", "ok");
        assertThat(history.get(0)).isEqualTo(json.readTree(
                "{\"status\":\"waiting\",\"summary\":\"key 3\",\"at\":\"2026-11-16T10:03:00+00:00\","
                        + "\"result\":\"aangevraagd, wacht op bevestiging\",\"by\":\"inbox\"}"));
    }

    @Test
    void onlyTheLast15Entries() throws Exception {
        Files.writeString(log, IntStream.range(0, 20).mapToObj(n -> entry(n, "")).collect(Collectors.joining("\n")));
        JsonNode history = ssh().get("history");
        assertThat(history.size()).isEqualTo(15);
        assertThat(history.get(0).get("summary").asString()).isEqualTo("key 19");
        assertThat(history.get(14).get("summary").asString()).isEqualTo("key 5");
    }

    @Test
    void theSshPageIsNotRenderedByTheServer() throws Exception {
        // The Python test checks that the form comes from TypeScript; here the page route does not exist at all (H-027).
        Reply r = get("/ssh");
        assertThat(r.body()).doesNotContain("Sleutel aanvragen");
    }

    Reply postKey(String form) throws Exception {
        return call("POST", "/api/ssh", Map.of("X-CSRF-Token", token(), "Content-Type", "application/x-www-form-urlencoded"),
                form, true);
    }

    @Test
    void postRequestsAKey() throws Exception {
        adminAnswer = "{\"ok\": true, \"fingerprint\": \"SHA256:x\", \"deadline\": \"2026-11-16T10:05:00+00:00\", "
                + "\"id\": \"abc\", \"extra\": 1}";
        Reply r = postKey("name=+pc+&key=+ssh-ed25519+AAAA+");
        assertThat(r.status()).isEqualTo(200);
        assertThat(json.readTree(r.body())).isEqualTo(json.readTree(
                "{\"fingerprint\": \"SHA256:x\", \"deadline\": \"2026-11-16T10:05:00+00:00\", \"id\": \"abc\"}"));
        assertThat(ADMIN_SAW).hasSize(1);
        JsonNode sent = json.readTree(ADMIN_SAW.get(0));
        assertThat(sent.get("cmd").asString()).isEqualTo("sshkey");
        assertThat(sent.get("label").asString()).isEqualTo("pc");
        assertThat(sent.get("key").asString()).isEqualTo("ssh-ed25519 AAAA");
        assertThat(sent.get("by").asString()).isEqualTo("phone");  // the device tailscale named, as self.who in Python
    }

    @Test
    void postRefusalIsA400WithTheMessage() throws Exception {
        adminAnswer = "{\"ok\": false, \"text\": \"geen geldige sleutel\"}";
        Reply r = postKey("name=pc&key=x");
        assertThat(r.status()).isEqualTo(400);
        assertThat(json.readTree(r.body())).isEqualTo(json.readTree("{\"error\": \"geen geldige sleutel\"}"));
    }

    @Test
    void postRefusalWithoutAMessageSaysMislukt() throws Exception {
        adminAnswer = "{\"ok\": false}";
        assertThat(error(postKey("name=pc&key=x"), 400)).isEqualTo("mislukt");
    }

    @Test
    void postWhenHomelabAdminDoesNotAnswerIsA400() throws Exception {
        adminAnswer = "not json";
        assertThat(error(postKey("name=pc&key=x"), 400)).startsWith("homelab-admin antwoordt niet");
    }

    @Test
    void postWithoutTheTokenDoesNotAskHomelabAdmin() throws Exception {
        Reply r = call("POST", "/api/ssh", Map.of("Content-Type", "application/x-www-form-urlencoded"), "name=pc&key=k", true);
        error(r, 403);
        assertThat(ADMIN_SAW).isEmpty();
    }

    @Test
    void configValuesComeFromTheConfFiles() throws Exception {
        JsonNode body = ssh();
        assertThat(body.get("user").asString()).isEqualTo("chris");
        assertThat(body.get("confirm_minutes").asInt()).isEqualTo(5);
    }
}
