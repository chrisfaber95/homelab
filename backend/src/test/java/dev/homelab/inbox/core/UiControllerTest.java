package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.frontdoor.Csrf;
import dev.homelab.inbox.frontdoor.Tailscale;
import dev.homelab.inbox.frontdoor.TailnetAccess;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The built UI over real HTTP (H-028 W5a): the pages from {@code frontend/dist}, {@code /app/*} and {@code /static/*}, the
 * Java counterpart of the page and static tests in {@code tests/test_inbox_routes.py}. The config directory sits in a
 * temp directory, so {@code DIR/frontend/dist} and {@code DIR/web} are the installed layout the defaults point at.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"backend.secret=test-secret", "inbox.front-door.enabled=true"})
class UiControllerTest {

    static final String JSON_UTF8 = "application/json;charset=utf-8";
    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("ui-routes");
            Files.createDirectories(DIR.resolve("config"));
            Files.createDirectories(DIR.resolve("home/logs"));
            Files.writeString(DIR.resolve("config/inbox.conf"), "RUNNER_HOME=\"" + DIR.resolve("home") + "\"\n");
            Files.writeString(DIR.resolve("home/new-repos.json"), "{\"alpha\": \"owner/alpha\"}");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
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

        /** Everyone is the owner here (INBOX_ALLOW_ALL): the access check has its own tests. */
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

    @Autowired
    Csrf csrf;

    final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final JsonMapper json = JsonMapper.builder().build();
    ConnectionSource db;

    @BeforeEach
    void clean() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        for (String f : List.of("frontend/dist/index.html", "frontend/dist/assets/index-abc.js", "frontend/dist/assets/index-abc.css")) {
            Files.deleteIfExists(DIR.resolve(f));
        }
    }

    Reply get(String path) throws Exception {
        HttpRequest r = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("X-Inbox-Facade", "test-secret").build();
        return new Reply(client.send(r, HttpResponse.BodyHandlers.ofString()));
    }

    record Reply(HttpResponse<String> raw) {
        int status() {
            return raw.statusCode();
        }

        String body() {
            return raw.body();
        }

        String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    static final List<String> PAGES = List.of("/", "/taken", "/jobs", "/briefs", "/memory", "/search", "/skills", "/ssh", "/research");

    void build() throws Exception {
        Path dist = DIR.resolve("frontend/dist");
        Files.createDirectories(dist.resolve("assets"));
        Files.writeString(dist.resolve("index.html"), "<!doctype html><title>homelab</title>");
        Files.writeString(dist.resolve("assets/index-abc.js"), "console.log('é');");
        Files.writeString(dist.resolve("assets/index-abc.css"), "body{}");
        Files.writeString(DIR.resolve("secret.txt"), "outside dist");
    }

    @Test
    void thePagesAre503WithoutABuild() throws Exception {
        for (String path : PAGES) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(503);
            assertThat(r.body()).contains("De frontend is niet gebouwd (frontend/dist ontbreekt).");
        }
        assertThat(get("/app/assets/index-abc.js").status()).isEqualTo(404);
    }

    @Test
    void thePagesAreTheBuiltIndex() throws Exception {
        build();
        for (String path : PAGES) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(200);
            assertThat(r.header("Content-Type")).isEqualTo("text/html;charset=utf-8");
            assertThat(r.header("Cache-Control")).isEqualTo("no-store");
            assertThat(r.body()).isEqualTo("<!doctype html><title>homelab</title>");
        }
    }

    @Test
    void aMemoryEntryHasItsPageOnlyWhenItExists() throws Exception {
        build();
        Path entry = DIR.resolve("home/memory-repo/feiten/box/ui-test.md");
        Files.createDirectories(entry.getParent());
        Files.writeString(entry, "---\nover: box\n---\nbody");
        assertThat(get("/memory/e/feiten/box/ui-test").status()).isEqualTo(200);
        assertThat(get("/memory/e/feiten/box/nope").status()).isEqualTo(404);
        assertThat(get("/memory/e/").status()).isEqualTo(404);
    }

    @Test
    void builtAssetsComeFromDistAndNeverLeaveIt() throws Exception {
        build();
        Reply js = get("/app/assets/index-abc.js");
        assertThat(js.status()).isEqualTo(200);
        assertThat(js.header("Content-Type")).isEqualTo("text/javascript;charset=utf-8");
        assertThat(js.header("Cache-Control")).isEqualTo("private, max-age=3600");
        assertThat(js.body()).isEqualTo("console.log('é');");
        assertThat(get("/app/assets/index-abc.css").header("Content-Type")).isEqualTo("text/css;charset=utf-8");
        assertThat(get("/app/assets/missing.js").status()).isEqualTo(404);
        assertThat(get("/app/").status()).isEqualTo(404);
        assertThat(get("/app/assets").status()).isEqualTo(404);
        for (String escape : List.of("/app/..%2Fsecret.txt", "/app/assets/..%2F..%2Fsecret.txt", "/app/%2e%2e/secret.txt")) {
            Reply r = get(escape);
            assertThat(r.status()).as(escape).isIn(400, 404); // Tomcat refuses an encoded slash itself
            assertThat(r.body()).doesNotContain("outside dist");
        }
    }

    @Test
    void staticFilesAreTheStylesheetAndFontsOfWeb() throws Exception {
        Path web = DIR.resolve("web");
        Files.createDirectories(web.resolve("fonts"));
        Files.writeString(web.resolve("app.css"), "body{color:red}");
        Files.write(web.resolve("fonts/space-grotesk.woff2"), new byte[] {1, 2, 3});
        Files.writeString(web.resolve("notes.txt"), "no");
        Reply css = get("/static/app.css");
        assertThat(css.status()).isEqualTo(200);
        assertThat(css.header("Content-Type")).isEqualTo("text/css;charset=utf-8");
        assertThat(css.header("Cache-Control")).isEqualTo("private, max-age=86400");
        assertThat(css.body()).isEqualTo("body{color:red}");
        Reply font = get("/static/fonts/space-grotesk.woff2");
        assertThat(font.status()).isEqualTo(200);
        assertThat(font.header("Content-Type")).isEqualTo("font/woff2");
        assertThat(get("/static/notes.txt").status()).isEqualTo(404); // the extension is not on the list
        assertThat(get("/static/nothing.css").status()).isEqualTo(404);
        assertThat(get("/static/..%2Fsecret.css").status()).isIn(400, 404);
        assertThat(get("/static/fonts/..%2Fapp.css").status()).isIn(400, 404);
        assertThat(get("/static/").status()).isEqualTo(404);
    }

    @Test
    void theStylesheetAlsoComesFromTheJarWhenNoDirectoryHasIt() throws Exception {
        // an empty installed layout: Settings falls back to ui/web on the classpath (the pom copies web/ there)
        var settings = new dev.homelab.inbox.read.Settings(Files.createTempDirectory("empty").resolve("config"), java.util.Map.of());
        assertThat(settings.webDir().resolve("app.css")).isRegularFile();
        assertThat(settings.webDir().resolve("fonts")).isDirectory();
    }
}
