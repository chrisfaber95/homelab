package dev.homelab.inbox.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read routes over real HTTP (H-028 coverage check): status codes, content types and error bodies of
 * {@code /api/overview}, {@code /api/live}, {@code /api/log/*}, {@code /api/skills}, {@code /api/memory*} and the
 * catch-all under {@code /api/}. The data halves are in {@code MemoryReaderTest}, {@code CronTest} and the parity tests.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "backend.secret=test-secret")
class ReadControllerTest {

    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("read-routes");
            Files.writeString(DIR.resolve("inbox.conf"), "RUNNER_HOME=\"" + DIR.resolve("home") + "\"\n");
            Files.createDirectories(DIR.resolve("home/logs"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("inbox.config-dir", DIR::toString);
    }

    @Value("${local.server.port}")
    int port;

    final JsonMapper json = JsonMapper.builder().build();

    private HttpResponse<String> send(String method, String path) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Inbox-Facade", "test-secret").method(method, HttpRequest.BodyPublishers.noBody()).build();
        return HttpClient.newHttpClient().send(r, HttpResponse.BodyHandlers.ofString());
    }

    private String type(HttpResponse<String> r) {
        return r.headers().firstValue("Content-Type").orElse("").replace(" ", "");
    }

    @Test
    void overviewAndSkillsAreJsonReads() throws Exception {
        for (String path : new String[] {"/api/overview", "/api/skills"}) {
            HttpResponse<String> r = send("GET", path);
            assertThat(r.statusCode()).as(path).isEqualTo(200);
            assertThat(type(r)).isEqualTo("application/json;charset=utf-8");
            assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(json.readTree(r.body()).isObject()).isTrue();
        }
    }

    @Test
    void liveHasItsQueueAndAnActivitySentence() throws Exception {
        HttpResponse<String> r = send("GET", "/api/live");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(type(r)).isEqualTo("application/json");
        JsonNode live = json.readTree(r.body());
        assertThat(live.has("items")).isTrue();
        assertThat(live.has("queued")).isTrue();
        assertThat(live.has("activity")).isTrue();
    }

    @Test
    void theMemoryListTakesAQueryAndAnUnknownEntryIs404() throws Exception {
        assertThat(send("GET", "/api/memory").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/memory?q=box").statusCode()).isEqualTo(200);
        HttpResponse<String> r = send("GET", "/api/memory/e/feiten/box/none");
        assertThat(r.statusCode()).isEqualTo(404);
        assertThat(r.body()).isEqualTo("{\"error\":\"No such memory entry.\"}");
        assertThat(send("GET", "/api/memory/e/").body()).isEqualTo("{\"error\":\"Not found.\"}");
    }

    @Test
    void aLogIsReadByTaskOrRunAndAnythingElseIsNotFound() throws Exception {
        HttpResponse<String> r = send("GET", "/api/log/t999999");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(type(r)).isEqualTo("application/json");
        assertThat(send("GET", "/api/log/r999999?from=5").statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/log/t1?from=-3").statusCode()).isEqualTo(200);  // negative counts as 0
        assertThat(send("GET", "/api/log/x1").body()).isEqualTo("{\"error\":\"Not found.\"}");
        HttpResponse<String> bad = send("GET", "/api/log/t1?from=abc");
        assertThat(bad.statusCode()).isEqualTo(400);
        assertThat(bad.body()).isEqualTo("{\"error\":\"from must be a number\"}");
    }

    @Test
    void anythingElseUnderApiIsNotFoundOrNotSupported() throws Exception {
        assertThat(send("GET", "/api/nothing").body()).isEqualTo("{\"error\":\"Not found.\"}");
        assertThat(send("POST", "/api/nothing").statusCode()).isEqualTo(404);
        HttpResponse<String> put = send("PUT", "/api/live");
        assertThat(put.statusCode()).isEqualTo(405);
        assertThat(put.body()).isEqualTo("{\"error\":\"PUT is not supported\"}");
        assertThat(send("DELETE", "/api/overview").statusCode()).isEqualTo(405);
    }
}
