package dev.homelab.inbox.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Nothing listens on the upstream addresses (port 1): every proxy answers 502 with the Python inbox's messages. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "backend.secret=test-secret")
class DeadUpstreamTest {

    @DynamicPropertySource
    static void dead(DynamicPropertyRegistry registry) {
        String dead = "http://127.0.0.1:1";
        registry.add("inbox.config-dir", () -> ProxyControllersTest.configDir(dead, dead, dead));
    }

    @Value("${local.server.port}")
    int port;

    private HttpResponse<String> send(String method, String path, String form) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Inbox-Facade", "test-secret")
                .header("Content-Type", "application/x-www-form-urlencoded")
                .method(method, form == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(form));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void forgeIsNotReachable() throws Exception {
        String unreachable = "{\"error\":\"Forge is not reachable (is homelab-app-forge running?).\"}";
        assertThat(send("GET", "/api/forge", null).body()).isEqualTo(unreachable);
        assertThat(send("GET", "/api/forge", null).statusCode()).isEqualTo(502);
        HttpResponse<String> control = send("POST", "/api/forge/start", "");
        assertThat(control.statusCode()).isEqualTo(502);
        assertThat(control.body()).startsWith("{\"error\":\"Forge refused start (HTTP 502): The search service is unreachable: ");
    }

    @Test
    void searchIsNotReachable() throws Exception {
        for (HttpResponse<String> r : new HttpResponse[] {send("GET", "/api/search/filters", null),
                send("GET", "/api/search/results?q=x", null), send("POST", "/api/search/ask", "question=x"),
                send("POST", "/api/search/feedback", "request_id=req_12345")}) {
            assertThat(r.statusCode()).isEqualTo(502);
            assertThat(r.body()).startsWith("{\"error\":\"The search service is unreachable: ");
        }
    }
}
