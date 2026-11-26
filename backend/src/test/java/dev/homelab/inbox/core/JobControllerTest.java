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
 * The job routes over real HTTP behind the front door (H-028 W3a): the Java counterpart of the job parts of
 * {@code tests/test_inbox_jobs.py}, {@code tests/test_inbox_jobs_api.py}, {@code tests/test_inbox_job_pages_api.py} and
 * {@code tests/test_inbox_routes.py}. The shadow effects are wired, so no Claude run, task or notification starts.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"backend.secret=test-secret", "inbox.front-door.enabled=true"})
class JobControllerTest {

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

    /** Records the writes of the job files: the jobs clone is not part of this test. */
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
                    SAVED.add("save " + name + "|" + (text == null ? "-" : text) + "|" + message);
                    return true;
                }

                @Override
                public boolean pause(String name, boolean paused, String message) {
                    if (saveFails != null) {
                        throw saveFails;
                    }
                    SAVED.add((paused ? "pause " : "resume ") + name + "|" + message);
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
            st.execute("DELETE FROM kv WHERE key LIKE 'switch:%'");
            st.execute("INSERT INTO kv (key, value) VALUES ('" + dev.homelab.inbox.conversation.ConversationStore.MARKER
                    + "', 'imported') ON CONFLICT (key) DO NOTHING"); // the conversation store is imported
        }
        Path agents = DIR.resolve("home/repos/homelab/agents");
        if (Files.isDirectory(agents)) {
            try (var files = Files.list(agents)) {
                for (Path p : files.toList()) {
                    Files.delete(p);
                }
            }
        }
        SAVED.clear();
        saveFails = null;
        Path jobs = DIR.resolve("home/jobs-repo/jobs");
        Files.createDirectories(jobs);
        try (var files = Files.list(jobs)) {
            for (Path p : files.toList()) {
                Files.delete(p);
            }
        }
        Files.writeString(jobs.resolve("pulse.md"), VALID);
        Files.writeString(jobs.resolve("broken.md"), "geen kop");
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

    String scalar(String sql) throws Exception {
        try (Connection c = db.open(); var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    static String form(String key, String value) {
        return key + "=" + java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    void assertNotFoundPage(Reply r, String what) {
        assertThat(r.status()).as(what).isEqualTo(404);
        assertThat(r.body()).as(what).contains("Niet gevonden.");
    }

    // ---- GET /api/jobs-page

    @Test
    void apiJobsPageListsTheJobs() throws Exception {
        JsonNode d = ok("/api/jobs-page");
        java.util.Set<String> names = new java.util.TreeSet<>();
        d.get("jobs").forEach(j -> names.add(j.get("name").asString()));
        assertThat(names).contains("pulse", "broken");
        assertThat(d.has("drafts")).isTrue();
        assertThat(d.has("runs")).isTrue();
    }

    // ---- GET /api/job/{name}

    @Test
    void apiJobGivesTheJobWithItsFileAndFacts() throws Exception {
        long run = run("ok", "box-report", null);
        JsonNode d = ok("/api/job/pulse");
        assertThat(d.get("name").asString()).isEqualTo("pulse");
        assertThat(d.get("text").asString()).isEqualTo(VALID);
        assertThat(d.get("paused").asBoolean()).isFalse();
        assertThat(d.get("error").isNull()).isTrue();
        assertThat(d.get("facts").isObject()).isTrue();
        assertThat(d.get("runs").get(0).get("id").asLong()).isEqualTo(run);
        assertThat(d.get("refresh").asBoolean()).isFalse();
    }

    @Test
    void apiJobOfABrokenFileSaysWhatIsWrong() throws Exception {
        JsonNode d = ok("/api/job/broken");
        assertThat(d.get("error").asString()).isNotEmpty();
        assertThat(d.get("facts").isNull()).isTrue();
        assertThat(d.get("text").asString()).isEqualTo("geen kop");
    }

    @Test
    void apiJobUnknownIs404WithAJsonError() throws Exception {
        for (String name : List.of("nope", "Pulse")) {
            Reply r = get("/api/job/" + name);
            assertThat(r.status()).as(name).isEqualTo(404);
            assertThat(r.header("Content-Type")).isEqualTo(JSON_UTF8);
            assertThat(json.readTree(r.body()).get("error").asString()).isEqualTo("Not found.");
        }
    }

    // ---- GET /jobs, /job/{name}

    @Test
    void theJobPagesComeFromTheFrontendBuild() throws Exception {
        assertThat(get("/jobs").status()).isEqualTo(503); // no frontend build in this test
        assertThat(get("/job/pulse").status()).isEqualTo(503);
        Path dist = DIR.resolve("frontend/dist");
        Files.createDirectories(dist);
        Files.writeString(dist.resolve("index.html"), "<!doctype html>spa");
        assertThat(get("/jobs").body()).isEqualTo("<!doctype html>spa");
        assertThat(get("/job/pulse").body()).isEqualTo("<!doctype html>spa");
        assertThat(get("/job/pulse?x=1").status()).isEqualTo(200);
        assertThat(get("/job/research").status()).isEqualTo(200); // the research page, as no job has that name
        assertNotFoundPage(get("/job/nope"), "/job/nope");
    }

    // ---- POST /jobs/draft

    @Test
    void aDescriptionStartsADraftAndRedirectsToIt() throws Exception {
        Reply r = post("/jobs/draft", form("text", "  een boxrapport \r\n elke dag  "));
        assertThat(r.status()).isEqualTo(303);
        long id = Long.parseLong(r.header("Location").substring("/draft/".length()));
        assertThat(scalar("SELECT request FROM drafts WHERE id=?", id)).isEqualTo("een boxrapport \n elke dag");
        assertThat(scalar("SELECT base FROM drafts WHERE id=?", id)).isNull();
        // In shadow Claude does not run: the draft ends as failed, with a reason.
        long until = System.currentTimeMillis() + 5000;
        while (!"failed".equals(scalar("SELECT status FROM drafts WHERE id=?", id)) && System.currentTimeMillis() < until) {
            Thread.sleep(50);
        }
        assertThat(scalar("SELECT status FROM drafts WHERE id=?", id)).isEqualTo("failed");
        assertThat(scalar("SELECT error FROM drafts WHERE id=?", id)).contains("shadow");
        assertThat(effects.calls()).contains("draftClaude " + id);
    }

    @Test
    void aDraftRequestIsCutAt4000Characters() throws Exception {
        Reply r = post("/jobs/draft", form("text", "x".repeat(5000)));
        long id = Long.parseLong(r.header("Location").substring("/draft/".length()));
        assertThat(scalar("SELECT length(request) FROM drafts WHERE id=?", id)).isEqualTo("4000");
    }

    @Test
    void aDraftWithoutATextIsThe404Page() throws Exception {
        assertNotFoundPage(post("/jobs/draft", "text=+++"), "blank text");
        assertNotFoundPage(post("/jobs/draft", ""), "no text");
        assertNotFoundPage(post("/jobs/other", form("text", "x")), "/jobs/other");
        assertNotFoundPage(post("/jobs/draft?x=1", form("text", "x")), "with a query");
        assertThat(scalar("SELECT count(*) FROM drafts")).isEqualTo("0");
    }

    // ---- POST /job/{name}/run

    @Test
    void runNowQueuesAManualRunAndRedirectsToTheJob() throws Exception {
        assertRedirect(post("/job/pulse/run", ""), "/job/pulse");
        assertThat(scalar("SELECT count(*) FROM job_runs WHERE job='pulse' AND manual=1")).isEqualTo("1");
    }

    @Test
    void runNowOfABrokenJobDoesNothing() throws Exception {
        assertRedirect(post("/job/broken/run", ""), "/job/broken");
        assertThat(scalar("SELECT count(*) FROM job_runs")).isEqualTo("0");
    }

    // ---- POST /job/{name}/(pause|resume)

    @Test
    void pausingAndResumingGoThroughTheJobStore() throws Exception {
        assertRedirect(post("/job/pulse/pause", ""), "/job/pulse");
        assertRedirect(post("/job/pulse/resume", ""), "/job/pulse");
        assertThat(SAVED).hasSize(2);
        assertThat(SAVED.get(0)).startsWith("pause pulse|jobs: pulse gepauzeerd (bevestigd op de pagina vanaf ");
        assertThat(SAVED.get(1)).startsWith("resume pulse|jobs: pulse hervat (bevestigd op de pagina vanaf ");
    }

    @Test
    void pausingABrokenJobDoesNothing() throws Exception {
        assertRedirect(post("/job/broken/pause", ""), "/job/broken");
        assertThat(SAVED).isEmpty();
    }

    // ---- POST /job/{name}/save and /delete

    @Test
    void savingAnEditedFileGoesThroughTheJobStore() throws Exception {
        String edited = VALID.replace("Vat het samen.", "Vat het kort samen.");
        assertRedirect(post("/job/pulse/save", form("text", edited.replace("\n", "\r\n") + "\r\n")), "/job/pulse");
        assertThat(SAVED).hasSize(1);
        assertThat(SAVED.get(0)).startsWith("save pulse|" + edited + "|jobs: pulse bewerkt (bevestigd op de pagina vanaf ");
    }

    @Test
    void savingWithoutATextChangesNothing() throws Exception {
        assertRedirect(post("/job/pulse/save", "text="), "/job/pulse");
        assertThat(SAVED).isEmpty();
    }

    @Test
    void deletingRemovesTheJobAndRedirectsToJobs() throws Exception {
        assertRedirect(post("/job/pulse/delete", ""), "/jobs");
        assertThat(SAVED).hasSize(1);
        assertThat(SAVED.get(0)).startsWith("save pulse|-|jobs: pulse verwijderd (bevestigd op de pagina vanaf ");
    }

    @Test
    void aRefusedWriteIs400WithTheReason() throws Exception {
        saveFails = new dev.homelab.inbox.jobs.JobsWriter.Refused(400, "de naam mag alleen kleine letters, cijfers en streepjes hebben");
        for (String path : List.of("/job/pulse/save", "/job/pulse/pause", "/job/pulse/delete")) {
            Reply r = post(path, form("text", VALID));
            assertThat(r.status()).as(path).isEqualTo(400);
            assertThat(r.header("Content-Type")).as(path).isEqualTo(JSON_UTF8);
            assertThat(json.readTree(r.body()).get("error").asString()).as(path)
                    .isEqualTo("de naam mag alleen kleine letters, cijfers en streepjes hebben");
        }
    }

    // ---- POST /job/{name}/change

    @Test
    void changeStartsADraftOfTheJobAndRedirectsToIt() throws Exception {
        Reply r = post("/job/pulse/change", form("text", "elke middag"));
        assertThat(r.status()).isEqualTo(303);
        long id = Long.parseLong(r.header("Location").substring("/draft/".length()));
        assertThat(scalar("SELECT base FROM drafts WHERE id=?", id)).isEqualTo("pulse");
        assertThat(scalar("SELECT request FROM drafts WHERE id=?", id)).isEqualTo("elke middag");
        assertRedirect(post("/job/pulse/change", "text="), "/job/pulse");
    }

    // ---- unknown job

    @Test
    void anUnknownJobOrAQueryStringIsThe404Page() throws Exception {
        for (String path : List.of("/job/nope/run", "/job/nope/delete", "/job/pulse/run?x=1")) {
            assertNotFoundPage(post(path, ""), path);
        }
        assertThat(scalar("SELECT count(*) FROM job_runs")).isEqualTo("0");
        assertThat(SAVED).isEmpty();
    }

    // ---- research, agents and the Discord switches (W3b)

    @Test
    void apiResearchGivesTheCounterTheModesAndTheLatestQuestions() throws Exception {
        JsonNode d = ok("/api/research");
        assertThat(d.get("modes").toString()).isEqualTo("[\"quick\",\"deep\"]");
        assertThat(d.get("claude_runs_today").asInt()).isZero();
        assertThat(d.get("runs")).isEmpty();
        assertThat(d.get("refresh").asBoolean()).isFalse();
    }

    @Test
    void theResearchPageComesFromTheFrontendBuild() throws Exception {
        assertThat(get("/research").status()).isEqualTo(503);
        Path dist = DIR.resolve("frontend/dist");
        Files.createDirectories(dist);
        Files.writeString(dist.resolve("index.html"), "<!doctype html>spa");
        assertThat(get("/research").body()).isEqualTo("<!doctype html>spa");
        assertThat(get("/research?x=1").status()).isEqualTo(200);
    }

    @Test
    void aQuestionQueuesOneRunAndRedirectsToTheResearchPage() throws Exception {
        assertRedirect(post("/research/ask", form("question", "Waarom is de lucht blauw?") + "&mode=deep"), "/research");
        assertThat(scalar("SELECT count(*) FROM job_runs WHERE job = 'research' AND kind = 'research'")).isEqualTo("1");
        assertThat(scalar("SELECT mode FROM job_runs")).isEqualTo("deep");
        assertThat(scalar("SELECT deliver FROM job_runs")).isEqualTo("page");
        assertThat(ok("/api/research").get("runs")).hasSize(1);
    }

    @Test
    void aQuestionWithoutAModeIsAQuickOne() throws Exception {
        assertRedirect(post("/research/ask", form("question", "Hoe laat is het?")), "/research");
        assertThat(scalar("SELECT mode FROM job_runs")).isEqualTo("quick");
    }

    @Test
    void anEmptyQuestionOrAnUnknownModeIs400WithTheReason() throws Exception {
        for (String body : List.of("question=", form("question", "  "), form("question", "x") + "&mode=slow")) {
            Reply r = post("/research/ask", body);
            assertThat(r.status()).as(body).isEqualTo(400);
            assertThat(r.header("Content-Type")).isEqualTo(JSON_UTF8);
            assertThat(json.readTree(r.body()).get("error").asString()).isEqualTo("give a question and a mode: quick or deep");
        }
        assertThat(scalar("SELECT count(*) FROM job_runs")).isEqualTo("0");
    }

    @Test
    void aQuestionQueryStringIsThe404Page() throws Exception {
        assertNotFoundPage(post("/research/ask?x=1", form("question", "x")), "/research/ask?x=1");
        assertThat(scalar("SELECT count(*) FROM job_runs")).isEqualTo("0");
    }

    @Test
    void switchingAnAgentOnMakesAReadyDraftAndRedirectsToIt() throws Exception {
        Path agents = DIR.resolve("home/repos/homelab/agents");
        Files.createDirectories(agents);
        Files.writeString(agents.resolve("pulse-agent.md"),
                "---\nname: pulse-agent\nkind: box-report\nmodel: local\nschedule: 0 7 * * *\n---\nVat het samen.");
        Files.writeString(agents.resolve("kapot.md"), "geen kop");
        Reply r = post("/agents/pulse-agent/on", "");
        assertThat(r.status()).as(r.body()).isEqualTo(303);
        String id = r.header("Location").substring("/draft/".length());
        assertThat(scalar("SELECT status FROM drafts WHERE id = ?", Long.parseLong(id))).isEqualTo("ready");
        assertThat(scalar("SELECT request FROM drafts WHERE id = ?", Long.parseLong(id))).isEqualTo("Agent aanzetten: pulse-agent");
        assertThat(scalar("SELECT text FROM drafts WHERE id = ?", Long.parseLong(id))).contains("name: pulse-agent");
        assertThat(SAVED).isEmpty(); // nothing is saved until the draft is confirmed
        assertThat(scalar("SELECT count(*) FROM job_runs")).isEqualTo("0");
        for (String path : List.of("/agents/nope/on", "/agents/kapot/on", "/agents/pulse-agent/off", "/agents/pulse-agent/on?x=1")) {
            Reply bad = post(path, "");
            assertThat(bad.status()).as(path).isEqualTo(404);
            assertThat(bad.body()).as(path).contains("Onbekende of kapotte agent.");
        }
        assertThat(scalar("SELECT count(*) FROM drafts")).isEqualTo("1");
    }

    @Test
    void theDiscordSwitchesAreSetAndRedirectToTheirCard() throws Exception {
        for (String name : List.of("discord", "discord_approvals", "kitt")) {
            String card = name.equals("kitt") ? "/#kitt" : "/#discord";
            assertRedirect(post("/discord/" + name + "/off", ""), card);
            assertThat(scalar("SELECT value FROM kv WHERE key = 'switch:" + name + "'")).isEqualTo("off");
            assertRedirect(post("/discord/" + name + "/on", ""), card);
            assertThat(scalar("SELECT value FROM kv WHERE key = 'switch:" + name + "'")).isEqualTo("on");
        }
        assertNotFoundPage(post("/discord/nope/on", ""), "/discord/nope/on");
        assertNotFoundPage(post("/discord/kitt/on?x=1", ""), "/discord/kitt/on?x=1");
    }

    @Test
    void everyMutatingResearchAgentAndSwitchRouteRefusesARequestWithoutAToken() throws Exception {
        List<String> paths = List.of("/research/ask", "/agents/pulse-agent/on", "/discord/discord/off",
                "/discord/discord_approvals/off", "/discord/kitt/off");
        for (String path : paths) {
            Reply none = send("POST", path, "question=x", true, false);
            assertThat(none.status()).as(path).isEqualTo(403);
            assertThat(none.body()).as(path).contains("Verlopen formulier; ververs de pagina.");
            Reply origin = send("POST", path, "question=x", false, true);
            assertThat(origin.status()).as(path + " origin").isEqualTo(403);
            assertThat(origin.body()).as(path).contains("Verkeerde herkomst.");
        }
        assertThat(scalar("SELECT count(*) FROM job_runs")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM kv WHERE key LIKE 'switch:%'")).isEqualTo("0");
    }

    // ---- CSRF: every mutating job route is behind the front door

    /** A POST without the token is a 403 and does nothing. */
    @Test
    void everyMutatingJobRouteRefusesARequestWithoutAToken() throws Exception {
        List<String> paths = List.of("/jobs/draft", "/job/pulse/run", "/job/pulse/pause", "/job/pulse/resume",
                "/job/pulse/save", "/job/pulse/delete", "/job/pulse/change");
        for (String path : paths) {
            Reply none = send("POST", path, "text=x", true, false);
            assertThat(none.status()).as(path).isEqualTo(403);
            assertThat(none.body()).as(path).contains("Verlopen formulier; ververs de pagina.");
            assertThat(send("POST", path, "csrf=0000&text=x", true, false).status()).as(path + " wrong token").isEqualTo(403);
            Reply origin = send("POST", path, "text=x", false, true);
            assertThat(origin.status()).as(path + " origin").isEqualTo(403);
            assertThat(origin.body()).as(path).contains("Verkeerde herkomst.");
        }
        assertThat(scalar("SELECT count(*) FROM drafts")).isEqualTo("0");
        assertThat(scalar("SELECT count(*) FROM job_runs")).isEqualTo("0");
        assertThat(SAVED).isEmpty();
    }
}
