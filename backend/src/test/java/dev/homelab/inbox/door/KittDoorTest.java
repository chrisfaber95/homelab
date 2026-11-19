package dev.homelab.inbox.door;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.core.TaskStore;
import dev.homelab.inbox.door.DoorFixture.Reply;
import dev.homelab.inbox.door.Ports.Chunk;
import dev.homelab.inbox.door.Ports.JobInfo;
import dev.homelab.inbox.door.Ports.RunInfo;
import dev.homelab.inbox.read.Cron;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Ports {@code tests/test_inbox_kitt.py} (the door, approvals, conversation, jobs and logs, health and the page switch):
 * kitt's permission list and switch decide what the app can do; the door accepts only kitt's own service account acting
 * for Chris. Every call goes over a real HTTP connection, so the peer check reads the kernel's socket table.
 */
class KittDoorTest {

    static final String OWNER = DoorFixture.OWNER;
    static final String BID = "H-018";
    static final List<String> ALL = List.of("read", "write", "ask", "start_task", "run_job", "approve");

    DoorFixture f;
    DoorHttp http;

    static String clients(List<String> perms) {
        return "{\"clients\": {\"kitt\": {\"permissions\": [" + String.join(",", perms.stream().map(p -> "\"" + p + "\"").toList())
                + "], \"tasks_per_day\": 5}}}";
    }

    @BeforeEach
    void setUp() throws Exception {
        f = new DoorFixture();
        f.reset(clients(ALL));
        f.briefs.briefs.clear();
        f.briefs.briefs.put(BID, new Ports.Brief(BID, "Kitt", "draft"));
        http = new DoorHttp(f.kitt, f.chatDoor, DoorFixture.JSON);
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    Reply call(String method, String path, String body, Map<String, String> extra) throws Exception {
        Map<String, String> h = new HashMap<>(Map.of("X-Homelab-Client", "kitt", "Tailscale-User-Login", OWNER,
                "Content-Type", "application/json"));
        h.putAll(extra);
        return DoorFixture.call(http.port(), method, path, body, h);
    }

    Reply get(String path) throws Exception {
        return call("GET", path, null, Map.of());
    }

    Reply post(String path, String body) throws Exception {
        return call("POST", path, body, Map.of());
    }

    // --- Door --------------------------------------------------------------------------------------------------------

    @Test
    void onlyKittsAccountActingForChris() throws Exception {
        assertThat(get("/api/clients/kitt").status()).isEqualTo(200);
        f.peers.peer = port -> OptionalLong.of(1000); // another local account claiming to be kitt
        assertThat(get("/api/clients/kitt").status()).isEqualTo(403);
        f.peers.peer = port -> OptionalLong.of(4242);
        assertThat(call("GET", "/api/clients/kitt", null, Map.of("Tailscale-User-Login", "other@x")).status()).isEqualTo(403);
        assertThat(call("GET", "/api/clients/kitt", null, Map.of("Tailscale-User-Login", "")).status()).isEqualTo(403);
        f.peers.account = OptionalLong.empty(); // the unit is not running
        assertThat(get("/api/clients/kitt").status()).isEqualTo(403);
    }

    @Test
    void aRequestWithoutAValidPeerIsRefusedAndItsBodyIsNotRead() throws Exception {
        // no uid found for the connection (empty): not kitt, even though the account exists and the headers are right
        f.peers.peer = port -> OptionalLong.empty();
        Reply r = post("/api/conversation", "{\"kind\":\"task\",\"text\":\"do a thing\"}");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body().path("error").asString()).contains("not from kitt's service account");
        assertThat(f.count("SELECT COUNT(*) FROM tasks")).isZero();
        assertThat(f.store.read(0, 10)).isEmpty();
        // the check comes before the body is parsed: a broken body from a wrong peer is a 403, not a 400
        assertThat(post("/api/conversation", "{not json").status()).isEqualTo(403);
        // and a wrong peer is refused on every route, reads included
        for (String path : List.of("/api/conversation", "/api/logs", "/api/approvals", "/api/jobs", "/api/health")) {
            assertThat(get(path).status()).as(path).isEqualTo(403);
        }
    }

    @Test
    void aRequestWithoutTheClientHeaderIsNotKittsAndFallsThrough() throws Exception {
        Reply r = DoorFixture.call(http.port(), "GET", "/api/clients/kitt", null, Map.of("Tailscale-User-Login", OWNER));
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body().path("error").asString()).contains("not a call this door answers");
        Reply wrongClient = call("GET", "/api/clients/kitt", null, Map.of("X-Homelab-Client", "discord"));
        assertThat(wrongClient.status()).isEqualTo(403);
        assertThat(wrongClient.body().path("error").asString()).contains("not the kitt client");
    }

    @Test
    void theRealPeerUidIsTheOwnerOfTheConnection() throws Exception {
        long me = ((Number) Files.getAttribute(Path.of("/proc/self"), "unix:uid")).longValue();
        f.peers.account = OptionalLong.of(me);
        f.peers.real = ProcPeers.system("nobody-in-particular");
        assertThat(get("/api/clients/kitt").status()).isEqualTo(200);
        assertThat(f.peers.seen).containsExactly(OptionalLong.of(me));
        f.peers.account = OptionalLong.of(me + 1); // some other account is kitt's: this connection is not its
        assertThat(get("/api/clients/kitt").status()).isEqualTo(403);
    }

    @Test
    void unknownPathsAndNoForbiddenRoutes() throws Exception {
        for (String path : List.of("/api/memory", "/api/ssh", "/api/merge", "/api/jobs/x/delete", "/api/jobs/x/edit")) {
            assertThat(post(path, "{}").status()).as(path).isEqualTo(404);
        }
        assertThat(call("PUT", "/api/jobs", "{}", Map.of()).status()).isEqualTo(405);
    }

    @Test
    void aBodyThatIsNotAnObjectIsABadRequest() throws Exception {
        assertThat(post("/api/conversation", "[1]").status()).isEqualTo(400);
        assertThat(post("/api/conversation", "{not json").status()).isEqualTo(400);
    }

    @Test
    void theStateFollowsThePermissionListAndTheSwitch() throws Exception {
        assertThat(get("/api/clients/kitt").body().toString()).isEqualTo(
                "{\"enabled\":true,\"permissions\":{\"chat\":true,\"jobs\":true,\"approvals\":true,\"usage\":true,\"logs\":true}}");
        f.setClients(clients(ALL.stream().filter(p -> !p.equals("approve")).toList()));
        assertThat(get("/api/clients/kitt").body().path("permissions").path("approvals").asBoolean()).isFalse();
        f.store.setSwitch("kitt", false);
        JsonNode s = get("/api/clients/kitt").body();
        assertThat(s.path("enabled").asBoolean()).isFalse();
        assertThat(s.path("permissions").properties().stream().anyMatch(e -> e.getValue().asBoolean())).isFalse();
        assertThat(get("/api/conversation").status()).isEqualTo(403);
        assertThat(get("/api/jobs").status()).isEqualTo(403);
    }

    @Test
    void theKittSwitchIsOnThePageAndOffStopsTheApp() throws Exception {
        assertThat(f.store.switchOn("kitt")).isTrue();
        f.store.setSwitch("kitt", false);
        assertThat(f.store.switchOn("kitt")).isFalse();
        assertThat(get("/api/conversation").body().path("error").asString()).contains("Kitt is switched off");
    }

    // --- Approvals ---------------------------------------------------------------------------------------------------

    @Test
    void theBellListsAndApprovesAsKitt() throws Exception {
        JsonNode items = get("/api/approvals").body().path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("id").asString()).isEqualTo(BID);
        assertThat(items.get(0).path("title").asString()).isEqualTo("H-018: Kitt");
        Reply r = post("/api/approvals/" + BID, "{\"client\":\"kitt\",\"decision\":\"approve_unattended\"}");
        assertThat(r.status()).as(r.body().toString()).isEqualTo(200);
        assertThat(f.briefs.commits).containsExactly("approved from kitt by Chris, build without me");
        var t = f.tasks.get(r.body().path("task").asLong()).orElseThrow();
        assertThat(t.str("source")).isEqualTo("kitt");
        assertThat(t.num("unattended")).isEqualTo(1);
        assertThat(f.entriesOfKind("approval")).anyMatch(e -> e.get("text").toString().contains("from Kitt"));
        assertThat(f.notified.calls()).hasSize(1);
    }

    @Test
    void plainApproveAndDenyAndQuestions() throws Exception {
        assertThat(post("/api/approvals/" + BID, "{\"decision\":\"approve\"}").status()).isEqualTo(200);
        assertThat(f.briefs.commits).containsExactly("approved from kitt by Chris");
        assertThat(post("/api/approvals/" + BID, "{\"decision\":\"deny\"}").status()).isEqualTo(400);
        assertThat(post("/api/approvals/question:3", "{\"decision\":\"approve\"}").status()).isEqualTo(409);
    }

    @Test
    void withoutThePermissionTheBellListsButDoesNotApprove() throws Exception {
        f.setClients(clients(ALL.stream().filter(p -> !p.equals("approve")).toList()));
        assertThat(get("/api/approvals").body().path("items")).hasSize(1);
        Reply r = post("/api/approvals/" + BID, "{\"decision\":\"approve\"}");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body().path("error").asString()).contains("approvals from kitt are off");
        assertThat(f.briefs.commits).isEmpty();
        assertThat(f.notified.calls()).isEmpty();
    }

    @Test
    void waitingQuestionsAreOnTheBell() throws Exception {
        long tid = f.tasks.newTask("homelab", "q", null);
        f.tasks.update(tid, TaskStore.fields("status", "waiting", "question", "Which one?"));
        JsonNode items = get("/api/approvals").body().path("items");
        assertThat(items.toString()).contains("question:" + tid).contains("Which one?");
    }

    // --- Conversation ------------------------------------------------------------------------------------------------

    @Test
    void aTaskAndAQuestionCarryKittAndOtherClientsShow() throws Exception {
        f.store.append("chris", "discord", "message", "hello from discord", null, null);
        Reply task = post("/api/conversation", "{\"kind\":\"task\",\"text\":\"do a thing\"}");
        assertThat(task.status()).isEqualTo(200);
        assertThat(task.body().path("entry").path("client").asString()).isEqualTo("kitt");
        assertThat(task.body().path("entry").path("kind").asString()).isEqualTo("task");
        assertThat(f.tasks.get(1).orElseThrow().str("source")).isEqualTo("kitt");
        Reply question = post("/api/conversation", "{\"kind\":\"question\",\"text\":\"what?\"}");
        assertThat(question.status()).isEqualTo(200);
        assertThat(question.body().path("entry").path("kind").asString()).isEqualTo("question");
        assertThat(f.background).hasSize(1); // answered in the background, not here
        JsonNode page = get("/api/conversation?after=0").body();
        assertThat(page.path("entries").valueStream().map(e -> e.path("client").asString()).toList())
                .containsExactly("discord", "kitt", "kitt");
        JsonNode last = page.path("entries").get(2);
        assertThat(page.path("cursor").asInt()).isEqualTo(last.path("id").asInt());
        assertThat(get("/api/conversation?after=" + page.path("cursor").asInt()).body().path("entries")).isEmpty();
    }

    @Test
    void aBadSendIsA400() throws Exception {
        assertThat(post("/api/conversation", "{\"kind\":\"message\",\"text\":\"x\"}").status()).isEqualTo(400);
        assertThat(post("/api/conversation", "{\"kind\":\"task\",\"text\":\"  \"}").status()).isEqualTo(400);
    }

    @Test
    void theSearchAnswerLandsInTheThread() {
        f.search.answer = Result.of(200, "found", true, "answer", "Port 8105.", "citations",
                List.of(Map.of("n", 1, "path", "docs/inbox.md", "location", "top")));
        f.kitt.answerQuestion("which port?");
        Map<String, Object> e = f.store.read(0, 10).get(0);
        assertThat(e.get("kind")).isEqualTo("answer");
        assertThat(e.get("client")).isEqualTo("kitt");
        assertThat(e.get("author")).isEqualTo("box");
        assertThat(e.get("text").toString()).contains("docs/inbox.md").contains("Port 8105.");
    }

    @Test
    void whenTheSearchFindsNothingOrFailsTheThreadSaysSo() {
        f.search.answer = Result.of(200, "found", false);
        f.kitt.answerQuestion("a");
        f.search.answer = Result.error(502, "down");
        f.kitt.answerQuestion("b");
        List<Map<String, Object>> entries = f.store.read(0, 10);
        assertThat(entries.get(0).get("text")).isEqualTo("Nothing relevant found in your private search for that question.");
        assertThat(entries.get(1).get("text")).isEqualTo("The search could not answer: down.");
    }

    @Test
    void askingNeedsThePermission() throws Exception {
        f.setClients(clients(List.of("read", "write")));
        assertThat(post("/api/conversation", "{\"kind\":\"question\",\"text\":\"x\"}").status()).isEqualTo(403);
        assertThat(f.background).isEmpty();
    }

    // --- Jobs and logs -----------------------------------------------------------------------------------------------

    @Test
    void jobCardsAndActions() throws Exception {
        f.jobs.jobs.put("nightly", new JobInfo("nightly", null, "digest", "daily 7:00", "0 7 * * *", "local", "page", false,
                new Cron("0 7 * * *")));
        JsonNode card = get("/api/jobs").body().path("jobs").get(0);
        assertThat(card.path("name").asString()).isEqualTo("nightly");
        assertThat(card.path("model").asString()).isEqualTo("local");
        assertThat(card.path("deliver").asString()).isEqualTo("page");
        assertThat(card.path("paused").asBoolean()).isFalse();
        assertThat(card.path("lastResult").isNull()).isTrue();
        assertThat(card.path("nextRun").asString()).isEqualTo("2026-11-19T07:00:00+00:00");
        assertThat(card.path("schedule").asString()).isEqualTo("daily 7:00");
        assertThat(post("/api/jobs/nightly/pause", "{}").status()).isEqualTo(200);
        assertThat(f.jobs.paused).containsExactly("nightly|true|jobs: nightly paused (from kitt)");
        f.setClients(clients(List.of("read", "write", "ask")));
        assertThat(post("/api/jobs/nightly/resume", "{}").status()).isEqualTo(403);
        assertThat(post("/api/jobs/nightly/run", "{}").status()).isEqualTo(403);
        assertThat(post("/api/jobs/nope/run", "{}").status()).isEqualTo(404);
    }

    @Test
    void runPauseAndResumeAreRecordedAndFailuresAreAnswered() throws Exception {
        f.jobs.jobs.put("nightly", new JobInfo("nightly", null, "digest", "", "0 7 * * *", "local", "page", false,
                new Cron("0 7 * * *")));
        assertThat(post("/api/jobs/nightly/run", "{}").status()).isEqualTo(200);
        assertThat(f.jobs.dispatched).containsExactly("nightly");
        assertThat(post("/api/jobs/nightly/resume", "{}").body().path("paused").asBoolean()).isFalse();
        assertThat(f.entriesOfKind("message")).extracting(e -> e.get("text").toString())
                .contains("Ran job nightly now", "Resumed job nightly");
        f.jobs.pauseFails = new Ports.Unavailable("job files: the backend is down");
        assertThat(post("/api/jobs/nightly/pause", "{}").status()).isEqualTo(502);
        f.jobs.pauseFails = new IllegalArgumentException("the job file has an error");
        assertThat(post("/api/jobs/nightly/pause", "{}").status()).isEqualTo(400);
    }

    @Test
    void aPausedJobHasNoNextRunAndALastResultComesFromItsRuns() throws Exception {
        f.jobs.jobs.put("paused-one", new JobInfo("paused-one", null, "digest", "", "0 7 * * *", "local", "page", true,
                new Cron("0 7 * * *")));
        f.jobs.jobs.put("broken", new JobInfo("broken", "bad header", null, null, null, null, null, false, null));
        f.jobs.runs.add(new RunInfo(2, "paused-one", "queued", "2026-11-18T09:00:00+00:00", null, null, null, "", ""));
        f.jobs.runs.add(new RunInfo(1, "paused-one", "failed", "2026-11-17T09:00:00+00:00", "2026-11-17T09:00:01+00:00",
                "2026-11-17T09:01:00+00:00", null, "it broke", ""));
        JsonNode cards = get("/api/jobs").body().path("jobs");
        JsonNode paused = cards.get(1);
        assertThat(paused.path("nextRun").isNull()).isTrue();
        assertThat(paused.path("running").asBoolean()).isTrue();
        assertThat(paused.path("lastResult").path("state").asString()).isEqualTo("failed");
        assertThat(paused.path("lastResult").path("summary").asString()).isEqualTo("it broke");
        assertThat(paused.path("lastResult").path("at").asString()).isEqualTo("2026-11-17T09:01:00+00:00");
        assertThat(cards.get(0).path("error").asString()).isEqualTo("bad header");
    }

    @Test
    void logsListAndRead() throws Exception {
        long tid = f.tasks.newTask("homelab", "first line\nsecond", null);
        f.tasks.update(tid, TaskStore.fields("status", "running", "started", f.tasks.now()));
        f.logs.put("t" + tid, new Chunk(List.of(
                Map.of("at", "2026-11-18T10:00:00+00:00", "kind", "claude", "text", "working")), 2));
        JsonNode list = get("/api/logs").body().path("logs");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("id").asString()).isEqualTo("t" + tid);
        assertThat(list.get(0).path("state").asString()).isEqualTo("running");
        assertThat(list.get(0).path("kind").asString()).isEqualTo("task");
        assertThat(list.get(0).path("title").asString()).isEqualTo("Task #" + tid + ": first line");
        JsonNode chunk = get("/api/logs/t" + tid + "?from=1").body();
        assertThat(chunk.path("lines")).hasSize(1);
        assertThat(chunk.path("next").asInt()).isEqualTo(2);
        assertThat(chunk.path("state").asString()).isEqualTo("running");
        assertThat(chunk.path("lines").get(0).asString()).isEqualTo("10:00:00 [claude] working");
        assertThat(get("/api/logs/t99999").status()).isEqualTo(404);
        assertThat(get("/api/logs/..%2Fetc").status()).isEqualTo(404);
        assertThat(get("/api/logs/t99999999999999999999").status()).isEqualTo(404);
    }

    @Test
    void runLogsAreListedWhenTheyHaveNoTask() throws Exception {
        f.jobs.runs.add(new RunInfo(4, "nightly", "ok", "2026-11-18T09:00:00+00:00", null, null, null, "", ""));
        f.jobs.runs.add(new RunInfo(5, "other", "ok", "2026-11-18T09:30:00+00:00", null, null, 3L, "", ""));
        JsonNode list = get("/api/logs").body().path("logs");
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("id").asString()).isEqualTo("r4");
        assertThat(list.get(0).path("state").asString()).isEqualTo("finished");
        assertThat(get("/api/logs/r4").body().path("state").asString()).isEqualTo("finished");
    }

    @Test
    void healthChecksAreSortedAndWordsMapped() throws Exception {
        f.checks.put("gpu", Map.of("status", "OK", "text", "fine"));
        f.checks.put("disk", Map.of("status", "FAIL", "text", "full"));
        f.checks.put("odd", Map.of("status", "???", "text", "?"));
        JsonNode checks = get("/api/health").body().path("checks");
        assertThat(checks.valueStream().map(c -> c.path("name").asString() + ":" + c.path("status").asString()).toList())
                .containsExactly("disk:fail", "gpu:ok", "odd:warn");
    }
}
