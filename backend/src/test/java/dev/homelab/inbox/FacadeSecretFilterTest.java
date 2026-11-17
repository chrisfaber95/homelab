package dev.homelab.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;

/** Every route but health needs the facade secret. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "backend.secret=test-secret")
class FacadeSecretFilterTest {

    @Value("${local.server.port}")
    int port;

    private int status(String method, String path, String secret) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (secret != null) {
            b.header("X-Inbox-Facade", secret);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void healthNeedsNoSecret() throws Exception {
        assertThat(status("GET", "/api/backend/health", null)).isEqualTo(200);
    }

    @Test
    void otherRoutesRefuseAMissingOrWrongSecret() throws Exception {
        assertThat(status("GET", "/api/anything", null)).isEqualTo(403);
        assertThat(status("GET", "/api/anything", "wrong")).isEqualTo(403);
        assertThat(status("POST", "/api/backend/health", null)).isEqualTo(403);
        assertThat(status("GET", "/internal/x", "")).isEqualTo(403);
    }

    @Test
    void theRightSecretGetsThrough() throws Exception {
        assertThat(status("GET", "/api/anything", "test-secret")).isEqualTo(404);  // past the filter: no such route
    }
}
