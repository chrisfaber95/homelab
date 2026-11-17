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

/** Starts the real server on a random loopback port and calls it over HTTP. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class HealthControllerTest {

    @Value("${local.server.port}")
    int port;

    @Value("${server.address}")
    String address;

    @Test
    void healthAnswersOkAsJson() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/backend/health")).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.body()).isEqualTo("{\"status\":\"ok\",\"service\":\"homelab-inbox-backend\"}");
    }

    @Test
    void bindsToLoopbackByDefault() {
        assertThat(address).isEqualTo("127.0.0.1");
    }
}
