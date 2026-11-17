package dev.homelab.inbox.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** The forge and search proxies (H-024 M3) against a fake upstream: same bodies, statuses and errors as the Python inbox. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "backend.secret=test-secret")
class ProxyControllersTest {

    record Canned(int status, String body) {}

    static final Map<String, Canned> ANSWERS = new java.util.concurrent.ConcurrentHashMap<>();
    static final List<String> SEEN = new CopyOnWriteArrayList<>();
    static HttpServer upstream;

    static {
        try {
            upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        upstream.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getRawPath();
            SEEN.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + (body.isEmpty() ? "" : " " + body));
            Canned c = ANSWERS.getOrDefault(path, new Canned(404, "{\"error\":\"no such route\"}"));
            byte[] data = c.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(c.status(), data.length == 0 ? -1 : data.length);
            if (data.length > 0) {
                exchange.getResponseBody().write(data);
            }
            exchange.close();
        });
        upstream.start();
    }

    @DynamicPropertySource
    static void upstreams(DynamicPropertyRegistry registry) {
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        registry.add("forge.url", () -> base);
        registry.add("search.url", () -> base);
        registry.add("router.url", () -> base);
    }

    @AfterAll
    static void stop() {
        upstream.stop(0);
    }

    @Value("${local.server.port}")
    int port;

    @BeforeEach
    void reset() {
        SEEN.clear();
        ANSWERS.clear();
        ANSWERS.put("/api/state", new Canned(200, "{\"running\":true,\"step\":2,\"project\":\"demo\",\"activity\":\"Edit: a.py\","
                + "\"started_at\":\"2026-11-16T10:00:00+00:00\",\"paused\":false}"));
        ANSWERS.put("/api/log", new Canned(200, "{\"lines\":[\"2026-11-16T10:00:01+00:00 Bash: ls\",\"plain line\"],\"next\":2,\"running\":false}"));
        ANSWERS.put("/api/start", new Canned(200, "{}"));
        ANSWERS.put("/api/stop", new Canned(200, "{}"));
        ANSWERS.put("/v1/filters", new Canned(200, "{\"projects\":[\"homelab\"],\"types\":[\"doc\"]}"));
        ANSWERS.put("/v1/search", new Canned(200, "{\"query\":\"x\",\"results\":[]}"));
        ANSWERS.put("/v1/ask", new Canned(200, "{\"found\":false}"));
        ANSWERS.put("/v1/feedback", new Canned(200, "{\"ok\":true}"));
    }

    private HttpResponse<String> send(String method, String path, String form) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Inbox-Facade", "test-secret")
                .method(method, form == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(form));
        if (form != null) {
            b.header("Content-Type", "application/x-www-form-urlencoded");
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private void assertJson(HttpResponse<String> r, int status, String body) {
        assertThat(r.statusCode()).isEqualTo(status);
        assertThat(r.headers().firstValue("Content-Type").orElse("").replace(" ", "")).isEqualTo("application/json;charset=utf-8");
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(r.body()).isEqualTo(body);
    }

    @Test
    void forgeState() throws Exception {
        assertJson(send("GET", "/api/forge", null), 200, "{\"running\":true,\"step\":2,\"project\":\"demo\",\"activity\":\"Edit: a.py\","
                + "\"started\":\"2026-11-16T10:00:00+00:00\",\"paused\":false,\"pause_reason\":null,\"paused_until\":null}");
    }

    @Test
    void forgeStateReadsAStatusWordAndUnreachableIs502() throws Exception {
        ANSWERS.put("/api/state", new Canned(200, "{\"status\":\"Running\",\"paused\":1}"));
        assertThat(send("GET", "/api/forge", null).body()).startsWith("{\"running\":true,").contains("\"paused\":true");
        ANSWERS.put("/api/state", new Canned(500, "{}"));
        assertJson(send("GET", "/api/forge", null), 502, "{\"error\":\"Forge is not reachable (is homelab-app-forge running?).\"}");
    }

    @Test
    void forgeControl() throws Exception {
        HttpResponse<String> r = send("POST", "/api/forge/stop", "");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).contains("\"running\":true");
        assertThat(SEEN).contains("POST /api/stop {}");
        ANSWERS.put("/api/start", new Canned(409, "{\"error\":\"busy\"}"));
        assertJson(send("POST", "/api/forge/start", ""), 502, "{\"error\":\"Forge refused start (HTTP 409): busy\"}");
        ANSWERS.put("/api/start", new Canned(409, "{\"error\":{\"message\":\"nested\"}}"));
        assertJson(send("POST", "/api/forge/start", ""), 502, "{\"error\":\"Forge refused start (HTTP 409): nested\"}");
        ANSWERS.put("/api/start", new Canned(500, "{}"));
        assertJson(send("POST", "/api/forge/start", ""), 502, "{\"error\":\"Forge refused start (HTTP 500): no reason given\"}");
    }

    @Test
    void unknownActionsAndWrongMethodsAre404() throws Exception {
        assertJson(send("POST", "/api/forge/restart", ""), 404, "{\"error\":\"Not found.\"}");
        assertJson(send("GET", "/api/forge/start", null), 404, "{\"error\":\"Not found.\"}");
        assertJson(send("GET", "/api/log/f1/extra", null), 404, "{\"error\":\"Not found.\"}");
        assertThat(SEEN).isEmpty();
    }

    @Test
    void forgeLog() throws Exception {
        HttpResponse<String> r = send("GET", "/api/log/f1?from=3", null);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(SEEN).contains("GET /api/log?from=3");
        JsonNode body = Upstream.JSON.readTree(r.body());
        assertThat(body.get("next").intValue()).isEqualTo(2);
        assertThat(body.get("done").booleanValue()).isTrue();
        assertThat(body.get("lines").get(0).toString()).isEqualTo(
                "{\"at\":\"2026-11-16T10:00:01+00:00\",\"kind\":\"tool\",\"text\":\"Bash: ls\"}");
        JsonNode plain = body.get("lines").get(1);
        assertThat(plain.get("kind").stringValue()).isEqualTo("info");
        assertThat(plain.get("text").stringValue()).isEqualTo("plain line");
        assertThat(plain.get("at").stringValue()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+00:00");
    }

    @Test
    void forgeLogOffsetsAndErrors() throws Exception {
        send("GET", "/api/log/f1?from=-5", null);
        send("GET", "/api/log/f1", null);
        assertThat(SEEN).containsExactly("GET /api/log?from=0", "GET /api/log?from=0");
        assertJson(send("GET", "/api/log/f1?from=abc", null), 400, "{\"error\":\"from must be a number\"}");
        ANSWERS.put("/api/log", new Canned(500, "{}"));
        assertJson(send("GET", "/api/log/f1", null), 502, "{\"error\":\"Forge is not reachable (is homelab-app-forge running?).\"}");
    }

    @Test
    void logKindsFollowForgesWords() {
        assertThat(ForgeController.kindOf("Refused to start")).isEqualTo("error");
        assertThat(ForgeController.kindOf("step 3 failed")).isEqualTo("error");
        assertThat(ForgeController.kindOf("Claude finished")).isEqualTo("claude");
        assertThat(ForgeController.kindOf("Edit: src/app.py")).isEqualTo("tool");
        assertThat(ForgeController.kindOf("just words")).isEqualTo("info");
    }

    @Test
    void searchFiltersAndResultsPassThrough() throws Exception {
        assertJson(send("GET", "/api/search/filters", null), 200, "{\"projects\":[\"homelab\"],\"types\":[\"doc\"]}");
        send("GET", "/api/search/results?q=ports&project=homelab&type=&junk=1", null);
        send("GET", "/api/search/results", null);
        send("GET", "/api/search/results?q=a%20b~*&source=x", null);
        assertThat(SEEN).containsExactly("GET /v1/filters", "GET /v1/search?q=ports&project=homelab", "GET /v1/search?q=",
                "GET /v1/search?q=a+b~%2A&source=x");
    }

    @Test
    void searchErrorsAreFlattenedAndUpstreamTroubleIs502() throws Exception {
        ANSWERS.put("/v1/filters", new Canned(503, "{\"error\":{\"message\":\"index is locked\",\"request_id\":\"abc123\"}}"));
        assertJson(send("GET", "/api/search/filters", null), 503, "{\"request_id\":\"abc123\",\"error\":\"index is locked\"}");
        ANSWERS.put("/v1/filters", new Canned(500, "not json"));
        assertJson(send("GET", "/api/search/filters", null), 500, "{\"error\":\"Upstream answered HTTP 500.\"}");
        ANSWERS.put("/v1/filters", new Canned(200, "not json"));
        HttpResponse<String> r = send("GET", "/api/search/filters", null);
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.body()).startsWith("{\"error\":\"The search service is unreachable: ");
    }

    @Test
    void searchAsk() throws Exception {
        send("POST", "/api/search/ask", "question=++which+port%3F++&project=homelab&type=&hard=on");
        assertThat(SEEN).containsExactly("POST /v1/ask {\"question\":\"which port?\",\"project\":\"homelab\",\"hard\":true}");
        assertJson(send("POST", "/api/search/ask", "question=+++"), 400, "{\"error\":\"Type a question first.\"}");
        assertJson(send("POST", "/api/search/ask", ""), 400, "{\"error\":\"Type a question first.\"}");
        SEEN.clear();
        send("POST", "/api/search/ask", "question=" + "x".repeat(2500) + "&hard=no");
        assertThat(SEEN.get(0)).isEqualTo("POST /v1/ask {\"question\":\"" + "x".repeat(2000) + "\"}");
    }

    @Test
    void searchFeedback() throws Exception {
        assertJson(send("POST", "/api/search/feedback", "request_id=req_12345&note=" + "n".repeat(2000)), 200, "{\"ok\":true}");
        assertThat(SEEN).containsExactly("POST /v1/feedback {\"request_id\":\"req_12345\",\"note\":\"" + "n".repeat(1000) + "\"}");
        SEEN.clear();
        send("POST", "/api/search/feedback", "request_id=req_12345");
        assertThat(SEEN).containsExactly("POST /v1/feedback {\"request_id\":\"req_12345\",\"note\":null}");
        for (String id : List.of("", "short", "bad+id%21", "x".repeat(65))) {
            assertJson(send("POST", "/api/search/feedback", "request_id=" + id), 400,
                    "{\"error\":\"That answer has no request id to mark.\"}");
        }
    }
}
