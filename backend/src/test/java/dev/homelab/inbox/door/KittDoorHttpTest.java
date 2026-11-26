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
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The kitt door over real HTTP with the front door on (H-028 W4b), the HTTP half of {@code tests/test_inbox_kitt.py}'s
 * {@code Door}: the real {@link ProcPeers} reads the kernel's socket table for the test's own connection, so the peer
 * check is the production one; only the passwd file (kitt's account) and the owner's login are the test's. The routes
 * have their own tests ({@link KittDoorTest}).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"backend.secret=test-secret", "inbox.front-door.enabled=true"})
class KittDoorHttpTest {

    static final Path DIR;
    static final Path TOKENS;
    static final Path PASSWD;
    static final String OWNER = "chris@example.com";

    static {
        try {
            DIR = Files.createTempDirectory("kitt-door-http");
            PASSWD = DIR.resolve("passwd");
            Files.createDirectories(DIR.resolve("config"));
            Files.writeString(DIR.resolve("config/inbox.conf"), "RUNNER_HOME=\"" + DIR.resolve("home") + "\"\n");
            Files.writeString(DIR.resolve("config/clients.json"), KittDoorTest.clients(KittDoorTest.ALL));
            Files.writeString(PASSWD, "root:x:0:0:root:/root:/bin/bash\n");
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
            return new DoorSettings(300, 10, "http://homelab:8105", "kitt-test", "http://127.0.0.1:8122", "", 8121,
                    TOKENS, ZoneId.of("UTC"));
        }

        /** The production peer check, on the real /proc/net and a passwd file the test writes. */
        @Bean
        @Primary
        Ports.Peers peers() {
            return new ProcPeers(Path.of("/proc/net"), PASSWD, "kitt-test");
        }

        @Bean
        @Primary
        Ports.OwnerCheck owner() {
            return new Ports.OwnerCheck() {
                @Override
                public String owner() {
                    return OWNER;
                }

                @Override
                public Ports.Verdict allowed(String ip) {
                    throw new UnsupportedOperationException("not used by the kitt door");
                }
            };
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    KittDoorFilter filter;

    final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void imported() throws Exception {
        try (var c = CoreFixture.db().open(); var st = c.createStatement()) {
            st.execute("INSERT INTO kv (key, value) VALUES ('" + ConversationStore.MARKER
                    + "', 'imported') ON CONFLICT (key) DO NOTHING");
        }
        account(ownUid());
    }

    static long ownUid() throws IOException {
        return ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).longValue();
    }

    static void account(long uid) throws IOException {
        Files.writeString(PASSWD, "root:x:0:0:root:/root:/bin/bash\nkitt-test:x:" + uid + ":" + uid + "::/:/usr/sbin/nologin\n");
    }

    HttpResponse<String> get(String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theAccountThatOwnsTheConnectionActingForChrisIsLetIn() throws Exception {
        HttpResponse<String> r = get("/api/clients/kitt", "X-Homelab-Client", "kitt", "Tailscale-User-Login", OWNER);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).contains("application/json;charset=utf-8");
        assertThat(r.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(json.readTree(r.body()).path("enabled").isBoolean()).isTrue();
        HttpResponse<String> health = get("/api/health", "X-Homelab-Client", "kitt", "Tailscale-User-Login", OWNER);
        assertThat(health.statusCode()).as(health.body()).isNotEqualTo(403);
        HttpResponse<String> unknown = get("/api/nope", "X-Homelab-Client", "kitt", "Tailscale-User-Login", OWNER);
        assertThat(unknown.statusCode()).isEqualTo(404);
    }

    @Test
    void anotherAccountOnTheSameMachineIsRefusedByTheSocketOwner() throws Exception {
        account(ownUid() + 1); // kitt's account is not the one that owns this connection
        HttpResponse<String> r = get("/api/clients/kitt", "X-Homelab-Client", "kitt", "Tailscale-User-Login", OWNER);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json.readTree(r.body()).path("error").asString()).isEqualTo("refused: not from kitt's service account");
        Files.writeString(PASSWD, "root:x:0:0:root:/root:/bin/bash\n"); // the unit is not running: no account
        assertThat(get("/api/clients/kitt", "X-Homelab-Client", "kitt", "Tailscale-User-Login", OWNER).statusCode())
                .isEqualTo(403);
    }

    @Test
    void theClientHeaderAndChrisIdentityAreChecked() throws Exception {
        assertThat(get("/api/clients/kitt", "X-Homelab-Client", "other", "Tailscale-User-Login", OWNER).statusCode())
                .isEqualTo(403);
        HttpResponse<String> r = get("/api/clients/kitt", "X-Homelab-Client", "kitt", "Tailscale-User-Login", "x@y");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(json.readTree(r.body()).path("error").asString()).isEqualTo("refused: not Chris at the app");
        assertThat(get("/api/clients/kitt", "X-Homelab-Client", "kitt").statusCode()).isEqualTo(403);
    }

    @Test
    void anAddressThatIsNotLoopbackIsRefusedEvenWithEveryHeader() throws Exception {
        for (String ip : new String[] {"100.85.70.34", "192.168.1.20", "10.0.0.1", "2001:db8::1"}) {
            MockHttpServletRequest in = new MockHttpServletRequest("GET", "/api/clients/kitt");
            in.setRemoteAddr(ip);
            in.setRemotePort(50000);
            in.setLocalPort(8105);
            in.addHeader("X-Homelab-Client", "kitt");
            in.addHeader("Tailscale-User-Login", OWNER);
            MockHttpServletResponse out = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();
            filter.doFilter(in, out, chain);
            assertThat(out.getStatus()).as(ip).isEqualTo(403);
            assertThat(json.readTree(out.getContentAsString()).path("error").asString())
                    .isEqualTo("refused: not from kitt's service account");
            assertThat(chain.getRequest()).as("not passed on: " + ip).isNull();
        }
    }

    @Test
    void aCallWithoutTheClientHeaderGoesOnToTheNormalChecks() throws Exception {
        MockHttpServletRequest in = new MockHttpServletRequest("GET", "/api/clients/kitt");
        in.setRemoteAddr("127.0.0.1");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(in, new MockHttpServletResponse(), chain);
        assertThat(chain.getRequest()).isNotNull();
    }
}
