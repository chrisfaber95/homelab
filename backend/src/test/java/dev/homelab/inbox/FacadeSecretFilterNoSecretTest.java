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

/** With no secret configured the filter fails closed: even an empty header does not pass. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class FacadeSecretFilterNoSecretTest {

    @Value("${local.server.port}")
    int port;

    @Test
    void noSecretConfiguredRefusesEverythingButHealth() throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/anything"))
                .header("X-Inbox-Facade", "").build();
        assertThat(HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }
}
