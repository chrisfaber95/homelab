package dev.homelab.inbox.frontdoor;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.ApplicationContext;

/** Exposure is unchanged: by default (H-026 K8, until H-027) the front door is not in the running backend at all. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = "backend.secret=test-secret")
class FrontDoorOffTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ApplicationContext context;

    @Test
    void noFrontDoorBeanExistsByDefault() {
        for (Class<?> type : new Class<?>[] {FrontDoorFilter.class, SessionController.class, SshController.class,
                TailnetAccess.class, Csrf.class, NotFoundAdvice.class}) {
            assertThat(context.getBeanNamesForType(type)).as(type.getSimpleName()).isEmpty();
        }
    }

    @Test
    void theSessionAndSshRoutesDoNotExistAndAddNoHeaders() throws Exception {
        for (String path : new String[] {"/api/session", "/api/ssh"}) {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("X-Inbox-Facade", "test-secret")
                            .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(r.statusCode()).as(path).isEqualTo(404);
            assertThat(r.headers().firstValue("Content-Security-Policy")).as(path).isEmpty();
        }
    }

    @Test
    void theFacadeSecretStillGuardsEverything() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/session")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).isEqualTo(403);
    }
}
