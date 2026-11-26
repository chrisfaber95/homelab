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
 * The draft and run routes over real HTTP behind the front door (H-028 W2b): the Java counterpart of the draft
 * and run parts of {@code tests/test_inbox_jobs.py}, {@code tests/test_inbox_routes.py} and {@code tests/test_inbox_contract.py}. The shadow effects are wired, so no task, merge or notification starts.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"backend.secret=test-secret", "inbox.front-door.enabled=true"})
class DraftRunControllerTest {

    static final String JSON_UTF8 = "application/json;charset=utf-8";
    static final Path DIR;

    static {
        try {
            DIR = Files.createTempDirectory("task-routes");
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

    /** Records the saves of a confirmed draft: the jobs clone is not part of this test. */
    static final List<String> SAVED = new java.util.concurrent.CopyOnWriteArrayList<>();
    static volatile RuntimeException saveFails;

    @TestConfiguration
    static class FakeWriter {
        @Bean
        @Primary
        dev.homelab.inbox.jobs.JobsWriter writer() {
            return new dev.homelab.inbox.jobs.JobsWriter(null, null) {
                @Override
                public boolean save(String name, String text, String message) {
                    if (saveFails != null) {
                        throw saveFails;
                    }
                    SAVED.add(name + "|" + message);
                    return true;
                }
            };
        }
    }

    @Autowired
    ShadowEffects effects;

    @Value("${local.server.port}")
    int port;

    @Autowired
    Csrf csrf;

    final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    final JsonMapper json = JsonMapper.builder().build();
    ConnectionSource db;
    long next = 1000;

    @BeforeEach
    void clean() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        try (Connection c = db.open(); var st = c.createStatement()) {
            st.execute("TRUNCATE drafts, job_runs RESTART IDENTITY");
        }
        SAVED.clear();
        saveFails = null;
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

    long task(String status, String... cols) throws Exception {
        long id = next++;
        CoreFixture.insert(db, id, "homelab", status, cols);
        return id;
    }

    String column(long id, String column) throws Exception {
        try (Connection c = db.open(); var st = c.prepareStatement("SELECT " + column + " FROM tasks WHERE id=?")) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    void log(long id, String... lines) throws Exception {
        Files.writeString(DIR.resolve("home/logs/t" + id + ".log"), String.join("\n", lines) + "\n");
    }

    void assertRedirect(Reply r, String location) {
        assertThat(r.status()).as(r.body()).isEqualTo(303);
        assertThat(r.header("Location")).isEqualTo(location);
    }

    static final String VALID = "---\nname: pulse\nkind: box-report\nmodel: local\nschedule: 0 7 * * *\n---\nVat het samen.";

    long draft(String status, String base, String text) throws Exception {
        try (Connection c = db.open(); var st = c.prepareStatement(
                "INSERT INTO drafts(created, request, base, status, text) VALUES (?,?,?,?,?) RETURNING id")) {
            st.setString(1, "2026-11-26T09:00:00+00:00");
            st.setString(2, "een boxrapport");
            st.setString(3, base);
            st.setString(4, status);
            st.setString(5, text);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    long run(String status, String kind, String taskId) throws Exception {
        try (Connection c = db.open(); var st = c.prepareStatement(
                "INSERT INTO job_runs(job, kind, model, created, status, task_id) VALUES (?,?,?,?,?,?) RETURNING id")) {
            st.setString(1, "pulse");
            st.setString(2, kind);
            st.setString(3, "local");
            st.setString(4, "2026-11-26T09:00:00+00:00");
            st.setString(5, status);
            if (taskId == null) {
                st.setObject(6, null);
            } else {
                st.setInt(6, Integer.parseInt(taskId));
            }
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    String scalar(String sql, long id) throws Exception {
        try (Connection c = db.open(); var st = c.prepareStatement(sql)) {
            st.setLong(1, id);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    // ---- GET /api/draft/{id}

    @Test
    void apiDraftGivesTheDraftWithItsFacts() throws Exception {
        long id = draft("ready", null, VALID);
        JsonNode d = ok("/api/draft/" + id);
        assertThat(d.get("id").asLong()).isEqualTo(id);
        assertThat(d.get("status").asString()).isEqualTo("ready");
        assertThat(d.get("request").asString()).isEqualTo("een boxrapport");
        assertThat(d.get("text").asString()).isEqualTo(VALID);
        assertThat(d.get("text_error").isNull()).isTrue();
        assertThat(d.get("refresh").asBoolean()).isFalse();
    }

    @Test
    void apiDraftOfABadFileSaysWhyAndADraftingOneRefreshes() throws Exception {
        long bad = draft("ready", null, "geen kop");
        assertThat(ok("/api/draft/" + bad).get("text_error").asString()).startsWith("de naam mag alleen");
        long busy = draft("drafting", null, null);
        assertThat(ok("/api/draft/" + busy).get("refresh").asBoolean()).isTrue();
    }

    @Test
    void apiDraftUnknownIs404WithAJsonError() throws Exception {
        for (String id : List.of("999", "99999999999999999999")) {
            Reply r = get("/api/draft/" + id);
            assertThat(r.status()).isEqualTo(404);
            assertThat(json.readTree(r.body()).get("error").asString()).isEqualTo("Not found.");
        }
    }

    // ---- GET /api/run/{id}

    @Test
    void apiRunGivesTheRun() throws Exception {
        long id = run("running", "script", null);
        JsonNode r = ok("/api/run/" + id);
        assertThat(r.get("cancellable").asBoolean()).isTrue();
        assertThat(r.get("log_key").asString()).isEqualTo("r" + id);
        assertThat(r.get("status").asString()).isEqualTo("running");
    }

    @Test
    void apiRunUnknownIs404WithAJsonError() throws Exception {
        Reply r = get("/api/run/999");
        assertThat(r.status()).isEqualTo(404);
        assertThat(json.readTree(r.body()).get("error").asString()).isEqualTo("Not found.");
    }

    // ---- /api/log/{t|r}{id}

    @Test
    void apiLogReadsATaskAndARunLog() throws Exception {
        long t = task("running");
        long r = run("running", "script", null);
        Files.writeString(DIR.resolve("home/logs/t" + t + ".log"),
                "{\"kind\":\"info\",\"text\":\"een\"}\n{\"kind\":\"info\",\"text\":\"twee\"}\n");
        Files.writeString(DIR.resolve("home/logs/r" + r + ".log"), "{\"kind\":\"out\",\"text\":\"x\"}\n");
        JsonNode tl = json.readTree(get("/api/log/t" + t + "?from=1").body());
        assertThat(tl.get("lines").size()).isEqualTo(1);
        assertThat(tl.get("next").asInt()).isEqualTo(2);
        JsonNode rl = json.readTree(get("/api/log/r" + r).body());
        assertThat(rl.get("lines").size()).isEqualTo(1);
        assertThat(get("/api/log/x1").status()).isEqualTo(404);
        assertThat(get("/api/log/t" + t + "?from=abc").status()).isEqualTo(400);
    }

    // ---- GET /draft/{id}, /run/{id}

    @Test
    void thePagesExistOnlyForADraftOrRunThatExists() throws Exception {
        long d = draft("ready", null, VALID);
        long r = run("ok", "box-report", null);
        assertThat(get("/draft/" + d).status()).isEqualTo(503); // no frontend build in this test
        assertThat(get("/run/" + r).status()).isEqualTo(503);
        Path dist = DIR.resolve("frontend/dist");
        Files.createDirectories(dist);
        Files.writeString(dist.resolve("index.html"), "<!doctype html>spa");
        assertThat(get("/draft/" + d).body()).isEqualTo("<!doctype html>spa");
        assertThat(get("/run/" + r).status()).isEqualTo(200);
        assertThat(get("/draft/999").status()).isEqualTo(404);
        assertThat(get("/run/999").body()).contains("Niet gevonden.");
    }

    // ---- POST /draft/{id}/cancel and /confirm

    @Test
    void cancellingADraftRedirectsToJobs() throws Exception {
        long id = draft("ready", null, VALID);
        assertRedirect(post("/draft/" + id + "/cancel", ""), "/jobs");
        assertThat(scalar("SELECT status FROM drafts WHERE id=?", id)).isEqualTo("cancelled");
    }

    @Test
    void confirmingADraftSavesTheJobAndRedirectsToIt() throws Exception {
        long id = draft("ready", null, "old");
        assertRedirect(post("/draft/" + id + "/confirm", "text=" + java.net.URLEncoder.encode(VALID.replace("\n", "\r\n") + "\r\n",
                java.nio.charset.StandardCharsets.UTF_8)), "/job/pulse");
        assertThat(SAVED).hasSize(1);
        assertThat(SAVED.get(0)).startsWith("pulse|jobs: pulse nieuw (bevestigd op de pagina vanaf ");
        assertThat(scalar("SELECT status FROM drafts WHERE id=?", id)).isEqualTo("confirmed");
        assertThat(scalar("SELECT text FROM drafts WHERE id=?", id)).isEqualTo(VALID);
    }

    @Test
    void confirmingAChangeKeepsTheNameOfTheJob() throws Exception {
        long id = draft("ready", "pulse", VALID);
        Reply renamed = post("/draft/" + id + "/confirm", "text=" + java.net.URLEncoder.encode(VALID.replace("pulse", "other"),
                java.nio.charset.StandardCharsets.UTF_8));
        assertThat(renamed.status()).isEqualTo(400);
        assertThat(json.readTree(renamed.body()).get("error").asString()).isEqualTo("de naam blijft pulse");
        assertThat(SAVED).isEmpty();
        assertRedirect(post("/draft/" + id + "/confirm", "text=" + java.net.URLEncoder.encode(VALID,
                java.nio.charset.StandardCharsets.UTF_8)), "/job/pulse");
        assertThat(SAVED.get(0)).contains("jobs: pulse gewijzigd");
    }

    @Test
    void confirmingARefusedFileIs400AndTheDraftStaysReady() throws Exception {
        long id = draft("ready", null, "x");
        Reply r = post("/draft/" + id + "/confirm", "text=geen+kop");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.header("Content-Type")).isEqualTo(JSON_UTF8);
        assertThat(json.readTree(r.body()).get("error").asString()).startsWith("de naam mag alleen kleine letters");
        saveFails = new IllegalStateException("the clone says no");
        Reply s = post("/draft/" + id + "/confirm", "text=" + java.net.URLEncoder.encode(VALID, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(s.status()).isEqualTo(400);
        assertThat(json.readTree(s.body()).get("error").asString()).isEqualTo("the clone says no");
        assertThat(scalar("SELECT status FROM drafts WHERE id=?", id)).isEqualTo("ready");
    }

    @Test
    void aDraftThatIsNotReadyOrUnknownIsThe404Page() throws Exception {
        long done = draft("confirmed", null, VALID);
        for (String path : List.of("/draft/" + done + "/confirm", "/draft/" + done + "/cancel", "/draft/999/confirm",
                "/draft/" + done + "/confirm?x=1")) {
            Reply r = post(path, "text=x");
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.body()).as(path).contains("Niet gevonden.");
        }
        assertThat(SAVED).isEmpty();
    }

    // ---- POST /run/{id}/cancel

    @Test
    void cancellingARunningLocalRunStopsItAndRedirectsToIt() throws Exception {
        long id = run("running", "script", null);
        assertRedirect(post("/run/" + id + "/cancel", ""), "/run/" + id);
        assertThat(scalar("SELECT status FROM job_runs WHERE id=?", id)).isEqualTo("cancelled");
        assertThat(scalar("SELECT note FROM job_runs WHERE id=?", id)).isEqualTo("geannuleerd op de pagina");
        assertThat(scalar("SELECT ended FROM job_runs WHERE id=?", id)).isNotNull();
        assertThat(effects.calls()).contains("log r" + id + " info geannuleerd op de pagina");
    }

    @Test
    void cancellingARunThatIsNotRunningChangesNothing() throws Exception {
        long id = run("ok", "box-report", null);
        assertRedirect(post("/run/" + id + "/cancel", ""), "/run/" + id);
        assertThat(scalar("SELECT status FROM job_runs WHERE id=?", id)).isEqualTo("ok");
    }

    @Test
    void cancellingAClaudeRunSendsYouToItsTask() throws Exception {
        long t = task("running");
        long id = run("running", "repo-chore", String.valueOf(t));
        assertRedirect(post("/run/" + id + "/cancel", ""), "/task/" + t);
        assertThat(scalar("SELECT status FROM job_runs WHERE id=?", id)).isEqualTo("running");
    }

    @Test
    void cancellingAnUnknownRunIsThe404Page() throws Exception {
        Reply r = post("/run/999/cancel", "");
        assertThat(r.status()).isEqualTo(404);
        assertThat(r.body()).contains("Niet gevonden.");
    }

    // ---- CSRF: every mutating draft and run route is behind the front door

    /** A POST without the token is a 403 and does nothing. */
    @Test
    void everyMutatingDraftAndRunRouteRefusesARequestWithoutAToken() throws Exception {
        long d = draft("ready", null, VALID);
        long r = run("running", "script", null);
        for (String path : List.of("/draft/" + d + "/confirm", "/draft/" + d + "/cancel", "/run/" + r + "/cancel")) {
            Reply none = send("POST", path, "text=x", true, false);
            assertThat(none.status()).as(path).isEqualTo(403);
            assertThat(none.body()).as(path).contains("Verlopen formulier; ververs de pagina.");
            assertThat(send("POST", path, "csrf=0000&text=x", true, false).status()).as(path + " wrong token").isEqualTo(403);
            Reply origin = send("POST", path, "text=x", false, true);
            assertThat(origin.status()).as(path + " origin").isEqualTo(403);
            assertThat(origin.body()).as(path).contains("Verkeerde herkomst.");
        }
        assertThat(scalar("SELECT status FROM drafts WHERE id=?", d)).isEqualTo("ready");
        assertThat(scalar("SELECT status FROM job_runs WHERE id=?", r)).isEqualTo("running");
        assertThat(SAVED).isEmpty();
    }

    /** In shadow nothing is started: cancelling a script run only records the kill, it runs no command. */
    @Test
    void inShadowACancelledScriptRunKillsNothingForReal() throws Exception {
        long id = run("running", "script", null);
        post("/run/" + id + "/cancel", "");
        long until = System.currentTimeMillis() + 5000;
        while (!effects.calls().contains("killScript " + id) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertThat(effects.calls()).contains("killScript " + id);
    }
}
