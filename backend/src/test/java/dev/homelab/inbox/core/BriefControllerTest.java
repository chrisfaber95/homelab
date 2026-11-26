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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * The brief routes over real HTTP behind the front door (H-028 W2c): the Java counterpart of
 * {@code tests/test_inbox_briefs_api.py} and the brief part of {@code tests/test_inbox_routes.py}. The product repo is
 * a scripted fake; everything else is the shadow effects, so no task, merge or notification starts.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"backend.secret=test-secret", "inbox.front-door.enabled=true"})
class BriefControllerTest {

    static final String JSON_UTF8 = "application/json;charset=utf-8";
    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("brief-routes");
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

    /** The product repo: files by path; a commit to main replaces the file, as the real clone does. */
    static class Product implements Effects.ProductRepo {
        final Map<String, String> files = new LinkedHashMap<>();
        final List<String> commits = new ArrayList<>();
        RuntimeException fails;

        @Override
        public List<Effects.ProductFile> briefFiles() {
            if (fails != null) {
                throw fails;
            }
            return files.entrySet().stream().map(e -> new Effects.ProductFile(e.getKey(), e.getValue())).toList();
        }

        @Override
        public String read(String path) {
            return files.get(path);
        }

        @Override
        public void commitToMain(String path, String text, String message) {
            files.put(path, text);
            commits.add(message);
        }

        @Override
        public void pushBranch(String branch, Map<String, String> changed, String message) {
            throw new UnsupportedOperationException();
        }
    }

    static final Product PRODUCT = new Product();

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        Effects.ProductRepo product() {
            return PRODUCT;
        }

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

    @Autowired
    Briefs briefs;

    final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final JsonMapper json = JsonMapper.builder().build();
    ConnectionSource db;
    long next = 1000;

    @BeforeEach
    void clean() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        PRODUCT.files.clear();
        PRODUCT.commits.clear();
        PRODUCT.fails = null;
        briefs.forget();
        put("H-001", "draft");
        put("H-002", "approved");
        put("H-004", "needs-design");
        Path dist = DIR.resolve("frontend/dist");
        Files.deleteIfExists(dist.resolve("index.html"));
        try (var logs = Files.list(DIR.resolve("home/logs"))) {
            for (Path p : logs.toList()) {
                Files.delete(p);
            }
        }
    }

    // ---- helpers

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

    Reply send(String method, String path, String form, boolean origin, boolean token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("X-Inbox-Facade", "test-secret").header("Content-Type", "application/x-www-form-urlencoded");
        if (origin) {
            b.header("Origin", "http://127.0.0.1:" + port);
        }
        String body = form == null ? "" : form;
        if (token && !method.equals("GET")) {
            body += (body.isEmpty() ? "" : "&") + "csrf=" + csrf.token();
        }
        b.method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return new Reply(client.send(b.build(), HttpResponse.BodyHandlers.ofString()));
    }

    Reply get(String path) throws Exception {
        return send("GET", path, null, false, false);
    }

    Reply post(String path, String form) throws Exception {
        return send("POST", path, form, true, true);
    }

    JsonNode ok(String path) throws Exception {
        Reply r = get(path);
        assertThat(r.status()).as(r.body()).isEqualTo(200);
        assertThat(r.header("Content-Type")).isEqualTo(JSON_UTF8);
        return json.readTree(r.body());
    }

    void put(String id, String status) {
        PRODUCT.files.put("homelab/briefs/" + id + ".md", "---\nid: " + id + "\ntitle: Title " + id + "\nstatus: " + status
                + "   # draft | approved\npriority: should\nrepos: [homelab]\n---\nBody of " + id + ".");
        briefs.forget();
    }

    long count() throws Exception {
        try (Connection c = db.open(); var st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tasks")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    String column(long id, String column) throws Exception {
        try (Connection c = db.open(); var st = c.prepareStatement("SELECT " + column + " FROM tasks WHERE id=?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    void assertRedirect(Reply r, String location) {
        assertThat(r.status()).as(r.body()).isEqualTo(303);
        assertThat(r.header("Location")).isEqualTo(location);
    }

    // ---- GET /api/briefs

    /** test_list */
    @Test
    void theListGroupsBriefsByStatus() throws Exception {
        JsonNode body = ok("/api/briefs");
        assertThat(body.get("drafts").valueStream().map(b -> b.get("id").asString()).toList()).containsExactly("H-001");
        assertThat(body.get("approved").valueStream().map(b -> b.get("id").asString()).toList()).containsExactly("H-002");
        assertThat(body.get("needs_design").valueStream().map(b -> b.get("id").asString()).toList()).containsExactly("H-004");
        assertThat(body.get("drafts").get(0).get("actions").valueStream().map(JsonNode::asString).toList())
                .containsExactly("build", "build-without-me", "park");
        assertThat(body.get("without_me").asString()).isEqualTo(BriefPages.WITHOUT_ME);
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Amsterdam");
        assertThat(body.get("error").isNull()).isTrue();
        assertThat(body.get("refresh").asBoolean()).isFalse();
    }

    /** test_unreadable_product_repo: still a 200, with the error in the body. */
    @Test
    void anUnreadableProductRepoIsA200WithAnError() throws Exception {
        PRODUCT.fails = new IllegalStateException("no clone");
        briefs.forget();
        JsonNode body = ok("/api/briefs");
        assertThat(body.get("error").asString()).contains("no clone");
        assertThat(body.get("drafts").size()).isZero();
    }

    // ---- GET /api/briefs/{id}

    /** test_one_brief */
    @Test
    void oneBriefComesWithItsBody() throws Exception {
        JsonNode body = ok("/api/briefs/H-001");
        assertThat(body.get("brief").get("id").asString()).isEqualTo("H-001");
        assertThat(body.get("brief").get("text").asString()).isEqualTo("Body of H-001.");
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Amsterdam");
    }

    /** test_unknown_brief_is_a_json_404 */
    @Test
    void anUnknownBriefIsAJson404() throws Exception {
        for (String path : List.of("/api/briefs/H-999", "/api/briefs/x", "/api/briefs/h-001")) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(json.readTree(r.body()).get("error").asString()).as(path).isEqualTo("Not found.");
        }
    }

    // ---- GET /briefs and /briefs/{id}

    @Test
    void thePagesAre503WithoutABuildAndTheIndexWithOne() throws Exception {
        for (String path : List.of("/briefs", "/briefs/H-001")) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(503);
            assertThat(r.body()).contains("De frontend is niet gebouwd (frontend/dist ontbreekt).");
        }
        Files.createDirectories(DIR.resolve("frontend/dist"));
        Files.writeString(DIR.resolve("frontend/dist/index.html"), "<!doctype html><title>homelab</title>");
        for (String path : List.of("/briefs", "/briefs/H-001", "/briefs?x=1")) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(200);
            assertThat(r.header("Content-Type")).isEqualTo("text/html;charset=utf-8");
            assertThat(r.body()).isEqualTo("<!doctype html><title>homelab</title>");
        }
    }

    @Test
    void anUnknownBriefOrAQueryStringWithoutABuildIsTheNotFoundPage() throws Exception {
        for (String path : List.of("/briefs/H-999", "/briefs/H-001?x=1", "/briefs?x=1")) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.body()).as(path).contains("<p>Niet gevonden.</p>");
        }
    }

    // ---- POST /briefs/{id}/park

    @Test
    void parkingADraftCommitsItAndGoesBackToTheList() throws Exception {
        assertRedirect(post("/briefs/H-001/park", ""), "/briefs");
        assertThat(PRODUCT.commits).hasSize(1);
        assertThat(PRODUCT.commits.get(0)).startsWith("H-001: parked (parked on the homelab page by ");
        assertThat(PRODUCT.files.get("homelab/briefs/H-001.md")).contains("status: parked   # draft | approved");
        assertThat(count()).isZero();
    }

    @Test
    void parkingSomethingThatIsNotADraftIsA400() throws Exception {
        for (String id : List.of("H-002", "H-999")) {
            Reply r = post("/briefs/" + id + "/park", "");
            assertThat(r.status()).as(id).isEqualTo(400);
            assertThat(r.header("Content-Type")).isEqualTo(JSON_UTF8);
            assertThat(json.readTree(r.body()).get("error").asString()).isEqualTo(id + " is not a draft on main of the product repo");
        }
        assertThat(PRODUCT.commits).isEmpty();
    }

    // ---- POST /briefs/{id}/build

    @Test
    void buildingAnApprovedBriefQueuesABuildAndGoesToItsPage() throws Exception {
        Reply r = post("/briefs/H-002/build", "");
        String location = r.header("Location");
        assertThat(location).matches("/task/\\d+");
        assertRedirect(r, location);
        long id = Long.parseLong(location.substring("/task/".length()));
        assertThat(column(id, "repo")).isEqualTo("brief");
        assertThat(column(id, "brief")).isEqualTo("H-002");
        assertThat(column(id, "status")).isEqualTo("queued");
        assertThat(column(id, "text")).isEqualTo("Build H-002: Title H-002");
        assertThat(column(id, "unattended")).isEqualTo("0");
        assertThat(PRODUCT.commits).isEmpty();
    }

    @Test
    void buildingADraftApprovesItFirstAndWithoutMeSetsUnattended() throws Exception {
        Reply r = post("/briefs/H-001/build", "mode=without-me");
        long id = Long.parseLong(r.header("Location").substring("/task/".length()));
        assertThat(column(id, "unattended")).isEqualTo("1");
        assertThat(PRODUCT.commits).hasSize(1);
        assertThat(PRODUCT.commits.get(0)).startsWith("H-001: approved (approved on the homelab page by ")
                .endsWith(", build without me)");
        assertThat(PRODUCT.files.get("homelab/briefs/H-001.md")).contains("status: approved");
    }

    @Test
    void aBriefThatIsAlreadyBeingBuiltOrNotApprovedIsA400() throws Exception {
        assertThat(post("/briefs/H-002/build", "").status()).isEqualTo(303);
        Reply twice = post("/briefs/H-002/build", "");
        assertThat(twice.status()).isEqualTo(400);
        assertThat(json.readTree(twice.body()).get("error").asString()).isEqualTo("H-002 is already being built");
        Reply design = post("/briefs/H-004/build", "");
        assertThat(design.status()).isEqualTo(400);
        assertThat(json.readTree(design.body()).get("error").asString())
                .isEqualTo("H-004 is not an approved brief on main of the product repo");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void aBuildWithAnUnreachableProductRepoIsA400() throws Exception {
        PRODUCT.fails = new IllegalStateException("no clone");
        briefs.forget();
        Reply r = post("/briefs/H-002/build", "");
        assertThat(r.status()).isEqualTo(400);
        assertThat(json.readTree(r.body()).get("error").asString()).contains("no clone");
    }

    @Test
    void anUnknownActionOrAQueryStringIsTheNotFoundPage() throws Exception {
        for (String path : List.of("/briefs/H-001/delete", "/briefs/H-001/build?x=1", "/briefs/x/build")) {
            Reply r = post(path, "");
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.body()).as(path).contains("<p>Niet gevonden.</p>");
        }
        assertThat(count()).isZero();
        assertThat(PRODUCT.commits).isEmpty();
    }

    // ---- CSRF: every mutating brief route is behind the front door

    /** A POST without the token is a 403 and does nothing (test_inbox_session / test_inbox_routes). */
    @Test
    void everyMutatingBriefRouteRefusesARequestWithoutAToken() throws Exception {
        for (String path : List.of("/briefs/H-001/build", "/briefs/H-001/park", "/briefs/H-002/build")) {
            Reply r = send("POST", path, "mode=without-me", true, false);
            assertThat(r.status()).as(path).isEqualTo(403);
            assertThat(r.body()).as(path).contains("Verlopen formulier; ververs de pagina.");
            Reply wrong = send("POST", path, "csrf=0000", true, false);
            assertThat(wrong.status()).as(path + " wrong token").isEqualTo(403);
        }
        assertThat(PRODUCT.commits).isEmpty();
        assertThat(count()).isZero();
    }

    @Test
    void everyMutatingBriefRouteRefusesTheWrongOrNoOrigin() throws Exception {
        for (String path : List.of("/briefs/H-001/build", "/briefs/H-001/park")) {
            Reply r = send("POST", path, "", false, true);
            assertThat(r.status()).as(path).isEqualTo(403);
            assertThat(r.body()).as(path).contains("Verkeerde herkomst.");
        }
        assertThat(PRODUCT.commits).isEmpty();
        assertThat(count()).isZero();
    }

    /** The reads need no token, and a PUT on a brief route is refused like any other mutating method. */
    @Test
    void theApiReadsNeedNoTokenButOtherMethodsDo() throws Exception {
        assertThat(get("/api/briefs").status()).isEqualTo(200);
        assertThat(send("DELETE", "/api/briefs/H-001", null, true, false).status()).isEqualTo(403);
    }
}
