package dev.homelab.inbox.door;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.conversation.ConversationStore;
import dev.homelab.inbox.core.CoreFixture;
import dev.homelab.inbox.frontdoor.Csrf;
import dev.homelab.inbox.frontdoor.TailnetAccess;
import dev.homelab.inbox.frontdoor.Tailscale;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/chat/action} over real HTTP with the front door on (H-028 W4a), the HTTP half of the {@code Door}
 * class of {@code tests/test_inbox_chat.py}: the bearer token per client from {@code config/clients.json}, a missing or
 * wrong token, and the loopback-only rule. The door's actions have their own tests ({@link ChatDoorTest}).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"backend.secret=test-secret", "inbox.front-door.enabled=true"})
class ChatDoorHttpTest {

    static final Path DIR;
    static final Path TOKENS;

    static {
        try {
            DIR = Files.createTempDirectory("chat-door-http");
            Files.createDirectories(DIR.resolve("config"));
            Files.writeString(DIR.resolve("config/inbox.conf"), "RUNNER_HOME=\"" + DIR.resolve("home") + "\"\n");
            Files.writeString(DIR.resolve("config/clients.json"), ChatActionsTest.CLIENTS);
            TOKENS = Files.createDirectories(DIR.resolve("tokens"));
            Files.writeString(TOKENS.resolve("chat-discord-token"), "tok-discord\n");
            Files.writeString(TOKENS.resolve("chat-reader-token"), "tok-reader\n");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> {
            try {
                return CoreFixture.url();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        r.add("spring.datasource.username", () -> {
            try {
                return CoreFixture.user();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        r.add("inbox.config-dir", () -> DIR.resolve("config").toString());
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        Csrf csrf() {
            return new Csrf(DIR.resolve("csrf-secret"));
        }

        /** Everyone is the owner here: the chat door does not use the owner check, the front door's tests cover it. */
        @Bean
        @Primary
        TailnetAccess tailnet() {
            Tailscale none = new Tailscale() {
                @Override
                public String status() throws IOException {
                    throw new IOException("no tailscale in tests");
                }

                @Override
                public String whois(String ip) throws IOException {
                    throw new IOException("no tailscale in tests");
                }
            };
            return new TailnetAccess(none, JsonMapper.builder().build(), System::nanoTime, true);
        }

        @Bean
        @Primary
        DoorSettings tokenSettings() {
            return new DoorSettings(300, 10, "http://homelab:8105", "homelab-app-kitt", "http://127.0.0.1:8122", "", 8121,
                    TOKENS, ZoneId.of("UTC"));
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    ChatDoorController controller;

    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void imported() throws Exception {
        try (var c = CoreFixture.db().open(); var st = c.createStatement()) {
            st.execute("INSERT INTO kv (key, value) VALUES ('" + ConversationStore.MARKER
                    + "', 'imported') ON CONFLICT (key) DO NOTHING"); // the conversation store is imported
        }
    }

    HttpResponse<String> post(String authorization, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat/action"))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            b.header("Authorization", authorization); // no X-Inbox-Facade, no Origin, no CSRF token: not this door's rules
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aValidTokenReachesTheAction() throws Exception {
        HttpResponse<String> r = post("Bearer tok-discord", "{\"action\":\"state\"}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).contains("application/json;charset=utf-8");
        assertThat(json.readTree(r.body()).isObject()).isTrue();
    }

    @Test
    void theTokenIsPerClient() throws Exception {
        assertThat(post("Bearer tok-reader", "{\"action\":\"state\"}").statusCode()).isEqualTo(200);
        // reader has only "read": a task is refused for it, not for lack of a token
        assertThat(post("Bearer tok-reader", "{\"action\":\"task\",\"repo\":\"auto\",\"text\":\"x\"}").statusCode())
                .isEqualTo(403);
        assertThat(post("Bearer tok-discord", "{\"action\":\"nope\"}").statusCode()).isEqualTo(400);
        assertThat(post("Bearer tok-discord", "[]").statusCode()).isEqualTo(400);
    }

    @Test
    void aMissingOrWrongTokenIs401() throws Exception {
        for (String header : new String[] {null, "", "Bearer ", "Bearer wrong", "wrong", "Bearer tok-discord-x"}) {
            HttpResponse<String> r = post(header, "{\"action\":\"state\"}");
            assertThat(r.statusCode()).as("header " + header).isEqualTo(401);
            assertThat(json.readTree(r.body()).path("error").asString()).isEqualTo("unknown client or token");
        }
    }

    @Test
    void anAddressThatIsNotLoopbackIsRefusedEvenWithAValidToken() throws Exception {
        for (String ip : new String[] {"100.85.70.34", "192.168.1.20", "10.0.0.1", "2001:db8::1"}) {
            MockHttpServletRequest in = new MockHttpServletRequest("POST", "/api/chat/action");
            in.setRemoteAddr(ip);
            in.addHeader("Authorization", "Bearer tok-discord");
            in.setContent("{\"action\":\"state\"}".getBytes());
            MockHttpServletResponse out = new MockHttpServletResponse();
            controller.action(in, out);
            assertThat(out.getStatus()).as(ip).isEqualTo(403);
            assertThat(json.readTree(out.getContentAsString()).path("error").asString()).isEqualTo("loopback only");
        }
        for (String ip : new String[] {"127.0.0.1", "::1", "::ffff:127.0.0.1"}) {
            MockHttpServletRequest in = new MockHttpServletRequest("POST", "/api/chat/action");
            in.setRemoteAddr(ip);
            in.addHeader("Authorization", "Bearer tok-discord");
            in.setContent("{\"action\":\"state\"}".getBytes());
            MockHttpServletResponse out = new MockHttpServletResponse();
            controller.action(in, out);
            assertThat(out.getStatus()).as(ip).isEqualTo(200);
        }
    }
}
