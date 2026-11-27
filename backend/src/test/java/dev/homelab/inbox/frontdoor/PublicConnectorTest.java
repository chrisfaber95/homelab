package dev.homelab.inbox.frontdoor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homelab.inbox.core.CoreFixture;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tailnet connector with {@code inbox.public.port} set (H-028 W5b). The interface is {@code lo} here, because a test box
 * has no tailscale0: the connector must bind exactly that interface's address, answer the pages, and keep the loopback-only
 * doors (chat and kitt) refused.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {"backend.secret=test-secret",
        "inbox.front-door.enabled=true", "inbox.public.interface=lo",
        "inbox.public.cidr=127.0.0.0/8"})
class PublicConnectorTest {

    static final int PUBLIC;

    static {
        try (ServerSocket s = new ServerSocket(0)) {
            PUBLIC = s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void publicPort(DynamicPropertyRegistry r) {
        r.add("inbox.public.port", () -> String.valueOf(PUBLIC));
    }

    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("public-connector");
            Files.createDirectories(DIR.resolve("config"));
            Files.createDirectories(DIR.resolve("home"));
            Files.writeString(DIR.resolve("config/inbox.conf"), "RUNNER_HOME=\"" + DIR.resolve("home") + "\"\n");
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
    }

    @Value("${local.server.port}")
    int port;

    static HttpResponse<String> send(int port, String path, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static boolean listening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Test
    void theSecondConnectorAnswersOnItsPort() throws Exception {
        assertThat(PUBLIC).isNotEqualTo(port);
        assertThat(listening(PUBLIC)).isTrue();
        assertThat(send(PUBLIC, "/api/backend/health").statusCode()).isEqualTo(200);
        assertThat(send(port, "/api/backend/health").statusCode()).isEqualTo(200);
    }

    @Test
    void thePagesOnItAreBehindTheFrontDoor() throws Exception {
        HttpResponse<String> r = send(PUBLIC, "/api/session", "X-Inbox-Facade", "test-secret");
        assertThat(r.headers().firstValue("X-Frame-Options")).isPresent(); // the front door's headers
        HttpResponse<String> post = HttpClient.newHttpClient().send(HttpRequest
                .newBuilder(URI.create("http://127.0.0.1:" + PUBLIC + "/api/forge/start"))
                .header("X-Inbox-Facade", "test-secret").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(403); // no Origin, no CSRF token
    }

    @Test
    void theChatDoorStaysRefusedOnIt() throws Exception {
        HttpResponse<String> r = send(PUBLIC, "/api/chat/action", "Authorization", "Bearer anything");
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).contains("loopback only");
    }

    @Test
    void theKittDoorStaysRefusedOnIt() throws Exception {
        for (String path : new String[] {"/api/health", "/api/conversation", "/api/jobs", "/api/clients/kitt"}) {
            HttpResponse<String> r = send(PUBLIC, path, "X-Homelab-Client", "kitt", "X-Inbox-Facade", "test-secret");
            assertThat(r.statusCode()).as(path).isEqualTo(403);
        }
    }

    @Test
    void aMissingInterfaceStopsTheStartInsteadOfWideningTheBind() {
        assertThatThrownBy(() -> PublicConnector.tailnetAddress("no-such-nic0", "100.64.0.0/10")).isInstanceOf(IllegalStateException.class);
        assertThat(PublicConnector.tailnetAddress("lo", "127.0.0.0/8").getHostAddress()).isEqualTo("127.0.0.1");
    }

    @Test
    void anAddressOutsideTheTailnetRangeIsRefused() throws Exception {
        assertThatThrownBy(() -> PublicConnector.tailnetAddress("lo", "100.64.0.0/10")).hasMessageContaining("not in");
        assertThat(PublicConnector.inside(java.net.InetAddress.getByName("100.85.70.34"), "100.64.0.0/10")).isTrue();
        assertThat(PublicConnector.inside(java.net.InetAddress.getByName("192.168.1.5"), "100.64.0.0/10")).isFalse();
        assertThat(PublicConnector.inside(java.net.InetAddress.getByName("100.128.0.1"), "100.64.0.0/10")).isFalse();
    }

    @Test
    void pathVariantsOfTheChatDoorAreRecognised() {
        for (String p : new String[] {"/api/chat/action", "/api/chat/action/", "//api/chat/action", "/api/chat/action;x=1"}) {
            assertThat(PublicPortGuard.chatPath(p)).as(p).isTrue();
        }
        assertThat(PublicPortGuard.chatPath("/api/chat")).isFalse();
    }

    @Test
    void badPortsAreRefused() {
        assertThatThrownBy(() -> PublicConnector.parse("0")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PublicConnector.parse("70000")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PublicConnector.parse("abc")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new PublicConnector("8130", "lo", true, 8130, "127.0.0.0/8", "0.0.0.0").customize(null))
                .hasMessageContaining("loopback");
        assertThatThrownBy(() -> new PublicConnector("8130", "lo", true, 8130, "127.0.0.0/8", "100.85.70.34")
                .customize(null)).hasMessageContaining("loopback");
        assertThatThrownBy(() -> new PublicConnector("8131", "lo", false, 8130, "127.0.0.0/8", "127.0.0.1")
                .customize(null)).hasMessageContaining("front-door");
    }

    /** The cut-over shape (X1b): the public port may equal the main port when the main address is loopback. */
    @Test
    void theSamePortIsAllowedBesideALoopbackMainAddress() {
        var factory = new org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory(0);
        new PublicConnector("8105", "lo", true, 8105, "127.0.0.0/8", "127.0.0.1").customize(factory);
        assertThat(factory.getAdditionalConnectors()).hasSize(1);
        assertThat(PublicConnector.loopbackAddress("127.0.0.1")).isTrue();
        assertThat(PublicConnector.loopbackAddress("localhost")).isTrue();
        assertThat(PublicConnector.loopbackAddress("0.0.0.0")).isFalse();
        assertThat(PublicConnector.loopbackAddress("100.85.70.34")).isFalse();
    }

    /** On the shared port the guard tells the connectors apart by address: loopback keeps its doors. */
    @Test
    void theGuardOnASharedPortSeparatesByAddress() {
        var guard = new PublicPortGuard("8105", 8105);
        var loop = new org.springframework.mock.web.MockHttpServletRequest();
        loop.setLocalPort(8105);
        loop.setLocalAddr("127.0.0.1");
        var tail = new org.springframework.mock.web.MockHttpServletRequest();
        tail.setLocalPort(8105);
        tail.setLocalAddr("100.85.70.34");
        assertThat(guard.onPublicConnector(loop)).isFalse();
        assertThat(guard.onPublicConnector(tail)).isTrue();
        assertThat(new PublicPortGuard("8125", 8131).onPublicConnector(tail)).isFalse();
    }
}
