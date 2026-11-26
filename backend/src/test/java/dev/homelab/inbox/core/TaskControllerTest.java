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
 * The task routes over real HTTP behind the front door (H-028 W2a): the Java counterpart of
 * {@code tests/test_inbox_tasks_api.py}, {@code tests/test_inbox_routes.py} and the task part of
 * {@code tests/test_inbox_contract.py}. The shadow effects are wired, so no task, merge or notification starts.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
        properties = {"backend.secret=test-secret", "inbox.front-door.enabled=true"})
class TaskControllerTest {

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

    // ---- GET /api/tasks

    /** test_list_has_the_form_repos_queue_and_history */
    @Test
    void listHasTheFormReposQueueAndHistory() throws Exception {
        long done = task("merged", "ended", "2026-11-18T09:00:00+00:00", "pr_url", "https://example.test/pr/7", "pr_number", "7");
        long queued = task("queued");
        long waiting = task("waiting");
        JsonNode body = ok("/api/tasks");
        assertThat(body.get("auto").asString()).isEqualTo("auto");
        assertThat(body.get("repos").valueStream().map(JsonNode::asString).toList()).contains("alpha", "homelab").isSorted();
        assertThat(body.get("queue").valueStream().map(r -> r.get("id").asLong()).toList()).containsExactly(queued, waiting);
        assertThat(body.get("history").valueStream().map(r -> r.get("id").asLong()).toList()).containsExactly(done);
        assertThat(body.get("history").get(0).get("status_text").asString()).isEqualTo("gemerged");
        assertThat(body.get("refresh").asBoolean()).isTrue();
        assertThat(body.get("timezone").asString()).isEqualTo("Europe/Amsterdam");
    }

    // ---- GET /api/tasks/{id}

    /** test_task_detail_carries_what_the_page_shows, test_the_log_so_far_and_where_to_poll_on */
    @Test
    void detailCarriesTheTaskAndTheLogSoFar() throws Exception {
        long id = task("waiting", "question", "Welke kant op?", "turns", "3", "rounds", "1");
        log(id, "{\"kind\": \"info\", \"text\": \"begin\"}", "{\"kind\": \"claude\", \"text\": \"bezig\"}");
        JsonNode d = ok("/api/tasks/" + id);
        assertThat(d.get("id").asLong()).isEqualTo(id);
        assertThat(d.get("status").asString()).isEqualTo("waiting");
        assertThat(d.get("question").asString()).isEqualTo("Welke kant op?");
        assertThat(d.get("model").asString()).isEqualTo("Claude");
        assertThat(d.get("actions").get("answer").asBoolean()).isTrue();
        assertThat(d.get("actions").get("retry").asBoolean()).isFalse();
        assertThat(d.get("log").get("lines").valueStream().map(l -> l.get("text").asString()).toList()).containsExactly("begin", "bezig");
        assertThat(d.get("log").get("next").asInt()).isEqualTo(2);
        assertThat(d.get("log").get("done").asBoolean()).isTrue(); // waiting is not "queued" or "running": the log is done
        long running = task("running");
        log(running, "{\"kind\": \"info\", \"text\": \"begin\"}");
        assertThat(ok("/api/tasks/" + running).get("log").get("done").asBoolean()).isFalse();
    }

    @Test
    void aTaskWithoutALogHasAnEmptyChunk() throws Exception {
        JsonNode log = ok("/api/tasks/" + task("failed")).get("log");
        assertThat(log.get("lines").size()).isZero();
        assertThat(log.get("next").asInt()).isZero();
        assertThat(log.get("done").asBoolean()).isTrue();
    }

    /** test_unknown_task_is_a_json_404 */
    @Test
    void anUnknownTaskIsAJson404() throws Exception {
        for (String path : List.of("/api/tasks/99999999", "/api/tasks/99999999999999999999", "/api/tasks/x")) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(json.readTree(r.body()).get("error").asString()).as(path).isEqualTo("Not found.");
        }
    }

    // ---- GET /taken and /task/{id}

    @Test
    void thePagesAre503WithoutABuildAndTheIndexWithOne() throws Exception {
        long id = task("queued");
        for (String path : List.of("/taken", "/task/" + id)) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(503);
            assertThat(r.body()).contains("De frontend is niet gebouwd (frontend/dist ontbreekt).");
        }
        Files.createDirectories(DIR.resolve("frontend/dist"));
        Files.writeString(DIR.resolve("frontend/dist/index.html"), "<!doctype html><title>homelab</title>");
        for (String path : List.of("/taken", "/task/" + id)) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(200);
            assertThat(r.header("Content-Type")).isEqualTo("text/html;charset=utf-8");
            assertThat(r.body()).isEqualTo("<!doctype html><title>homelab</title>");
        }
    }

    @Test
    void anUnknownTaskOrAQueryStringIsTheNotFoundPage() throws Exception {
        long id = task("queued");
        for (String path : List.of("/task/99999999", "/task/" + id + "?x=1")) {
            Reply r = get(path);
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.body()).contains("<p>Niet gevonden.</p>");
        }
    }

    // ---- POST /tasks

    @Test
    void aNewTaskIsQueuedAndRedirectsToItsPage() throws Exception {
        Reply r = post("/tasks", "repo=alpha&text=" + java.net.URLEncoder.encode("  Doe iets  ", "UTF-8"));
        String location = r.header("Location");
        assertThat(location).matches("/task/\\d+");
        long id = Long.parseLong(location.substring("/task/".length()));
        assertRedirect(r, location);
        assertThat(column(id, "repo")).isEqualTo("alpha");
        assertThat(column(id, "text")).isEqualTo("Doe iets");
        assertThat(column(id, "status")).isEqualTo("queued");
        assertThat(r.header("X-Frame-Options")).isEqualTo("DENY");
    }

    @Test
    void autoIsAValidRepo() throws Exception {
        assertThat(post("/tasks", "repo=auto&text=x").status()).isEqualTo(303);
    }

    @Test
    void anUnknownRepoOrAnEmptyTextIsThe400Page() throws Exception {
        for (String form : List.of("repo=nope&text=x", "repo=alpha&text=%20%20", "text=x", "repo=alpha")) {
            Reply r = post("/tasks", form);
            assertThat(r.status()).as(form).isEqualTo(400);
            assertThat(r.body()).as(form).contains("Kies een repo uit de lijst en beschrijf de taak.");
        }
        assertThat(count()).isZero();
    }

    long count() throws Exception {
        try (Connection c = db.open(); var st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tasks")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---- POST /task/{id}/answer

    @Test
    void anAnswerToAQuestionQueuesTheTaskAgain() throws Exception {
        long id = task("waiting", "question", "Welke kant op?");
        assertRedirect(post("/task/" + id + "/answer", "answer=links"), "/task/" + id);
        assertThat(column(id, "status")).isEqualTo("queued");
        assertThat(column(id, "pending_input")).startsWith("Antwoord van de gebruiker op je vraag:\n\nlinks\n\n");
    }

    @Test
    void anEmptyAnswerOrOneToARunningTaskChangesNothingButStillRedirects() throws Exception {
        long waiting = task("waiting");
        long running = task("running");
        assertRedirect(post("/task/" + waiting + "/answer", "answer=%20"), "/task/" + waiting);
        assertRedirect(post("/task/" + running + "/answer", "answer=x"), "/task/" + running);
        assertThat(column(waiting, "status")).isEqualTo("waiting");
        assertThat(column(running, "status")).isEqualTo("running");
    }

    // ---- POST /task/{id}/cancel

    @Test
    void cancelStopsAQueuedTaskAndLeavesAFinishedOneAlone() throws Exception {
        long queued = task("queued");
        long merged = task("merged");
        assertRedirect(post("/task/" + queued + "/cancel", ""), "/task/" + queued);
        assertRedirect(post("/task/" + merged + "/cancel", ""), "/task/" + merged);
        assertThat(column(queued, "status")).isEqualTo("cancelled");
        assertThat(column(queued, "ended")).isNotNull();
        assertThat(column(merged, "status")).isEqualTo("merged");
    }

    @Test
    void cancellingARoutedMessageCancelsTheChildrenThatAreNotDone() throws Exception {
        long parent = task("routed");
        long open = task("queued", "parent", String.valueOf(parent));
        long done = task("merged", "parent", String.valueOf(parent));
        post("/task/" + parent + "/cancel", "");
        assertThat(column(parent, "status")).isEqualTo("cancelled");
        assertThat(column(parent, "ended")).isNotNull();
        assertThat(column(open, "status")).isEqualTo("cancelled");
        assertThat(column(done, "status")).isEqualTo("merged");
    }

    // ---- POST /task/{id}/retry

    @Test
    void retryPutsAFailedTaskBackInTheQueueWithTheNote() throws Exception {
        long id = task("failed", "ended", "2026-11-18T09:00:00+00:00");
        assertRedirect(post("/task/" + id + "/retry", "note=probeer+het+nog+eens"), "/task/" + id);
        assertThat(column(id, "status")).isEqualTo("queued");
        assertThat(column(id, "ended")).isNull();
        assertThat(column(id, "text")).endsWith("Note from the user with this retry:\n\nprobeer het nog eens");
    }

    @Test
    void retryDoesNothingForABuildAnAutoTaskOrATaskThatCannotBeRetried() throws Exception {
        long build = taskIn("brief", "failed");
        long auto = taskIn("auto", "failed");
        long merged = task("merged");
        for (long id : List.of(build, auto, merged)) {
            String before = column(id, "status");
            assertRedirect(post("/task/" + id + "/retry", "note=x"), "/task/" + id);
            assertThat(column(id, "status")).isEqualTo(before);
        }
    }

    long taskIn(String repo, String status, String... cols) throws Exception {
        long id = next++;
        CoreFixture.insert(db, id, repo, status, cols);
        return id;
    }

    // ---- POST /task/{id}/accept

    @Test
    void acceptMarksTheTaskFineAndRevivesItsFollowups() throws Exception {
        long parent = task("routed");
        long first = task("failed", "parent", String.valueOf(parent));
        long second = task("cancelled", "parent", String.valueOf(parent), "after_task", String.valueOf(first), "note",
                FollowupChain.FOLLOWUP_NOTE + " #" + first + " was mislukt");
        assertRedirect(post("/task/" + first + "/accept", ""), "/task/" + first);
        assertThat(column(first, "accepted")).isEqualTo("1");
        assertThat(column(second, "status")).isEqualTo("queued");
    }

    @Test
    void acceptWithoutBlockedFollowupsChangesNothing() throws Exception {
        long id = task("failed");
        assertRedirect(post("/task/" + id + "/accept", ""), "/task/" + id);
        assertThat(column(id, "accepted")).isIn(null, "0");
    }

    // ---- POST /task/{id}/attended

    @Test
    void attendedStopsABuildWithoutMe() throws Exception {
        long build = taskIn("brief", "routed", "unattended", "1");
        assertRedirect(post("/task/" + build + "/attended", ""), "/task/" + build);
        assertThat(column(build, "unattended")).isEqualTo("0");
    }

    @Test
    void attendedOnAnOrdinaryTaskChangesNothing() throws Exception {
        long id = task("queued", "unattended", "1");
        assertRedirect(post("/task/" + id + "/attended", ""), "/task/" + id);
        assertThat(column(id, "unattended")).isEqualTo("1");
    }

    // ---- 404 page

    @Test
    void anUnknownTaskOrActionIsThe404Page() throws Exception {
        long id = task("queued");
        for (String path : List.of("/task/99999999/cancel", "/task/" + id + "/explode", "/task/" + id + "/cancel?x=1")) {
            Reply r = post(path, "");
            assertThat(r.status()).as(path).isEqualTo(404);
            assertThat(r.body()).as(path).contains("<p>Niet gevonden.</p>");
        }
        assertThat(column(id, "status")).isEqualTo("queued");
    }

    // ---- CSRF: every mutating task route is behind the front door

    /** A POST without the token is a 403 and does nothing (test_inbox_session / test_inbox_routes). */
    @Test
    void everyMutatingTaskRouteRefusesARequestWithoutAToken() throws Exception {
        long id = task("waiting", "question", "?");
        List<String> paths = List.of("/tasks", "/task/" + id + "/accept", "/task/" + id + "/answer",
                "/task/" + id + "/cancel", "/task/" + id + "/attended", "/task/" + id + "/retry");
        for (String path : paths) {
            Reply r = send("POST", path, "repo=alpha&text=x&answer=x", true, false);
            assertThat(r.status()).as(path).isEqualTo(403);
            assertThat(r.body()).as(path).contains("Verlopen formulier; ververs de pagina.");
            Reply wrong = send("POST", path, "csrf=0000&repo=alpha&text=x&answer=x", true, false);
            assertThat(wrong.status()).as(path + " wrong token").isEqualTo(403);
        }
        assertThat(column(id, "status")).isEqualTo("waiting");
        assertThat(count()).isEqualTo(1);
    }

    @Test
    void everyMutatingTaskRouteRefusesTheWrongOrNoOrigin() throws Exception {
        long id = task("queued");
        for (String path : List.of("/tasks", "/task/" + id + "/cancel", "/task/" + id + "/retry")) {
            Reply r = send("POST", path, "repo=alpha&text=x", false, true);
            assertThat(r.status()).as(path).isEqualTo(403);
            assertThat(r.body()).as(path).contains("Verkeerde herkomst.");
        }
        assertThat(column(id, "status")).isEqualTo("queued");
        assertThat(count()).isEqualTo(1);
    }

    /** The reads need no token, and a PUT on a task route is refused like any other mutating method. */
    @Test
    void theApiReadsNeedNoTokenButOtherMethodsDo() throws Exception {
        assertThat(get("/api/tasks").status()).isEqualTo(200);
        assertThat(send("DELETE", "/api/tasks/1", null, true, false).status()).isEqualTo(403);
    }
}
