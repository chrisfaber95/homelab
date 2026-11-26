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

/** No {@code inbox.public.port}: no second connector and no guard (H-028 W5b). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"backend.secret=test-secret", "inbox.front-door.enabled=true", "inbox.public.port="})
class PublicConnectorOffTest {


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

    @Autowired
    ApplicationContext context;

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
    void thereIsNoSecondConnector() throws Exception {
        assertThat(context.getBeansOfType(PublicPortGuard.class)).isEmpty();
        org.apache.catalina.connector.Connector[] all = ((org.springframework.boot.tomcat.TomcatWebServer)
                ((org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext) context)
                        .getWebServer()).getTomcat().getService().findConnectors();
        assertThat(all).hasSize(1);
        assertThat(all[0].getLocalPort()).isEqualTo(port);
    }

    @Test
    void theCustomizerAddsNothing() {
        var factory = new org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory(0);
        new PublicConnector("", "tailscale0", true, 8130, "100.64.0.0/10").customize(factory);
        new PublicConnector("  ", "tailscale0", false, 8130, "100.64.0.0/10").customize(factory);
        assertThat(factory.getAdditionalConnectors()).isEmpty();
    }
}
