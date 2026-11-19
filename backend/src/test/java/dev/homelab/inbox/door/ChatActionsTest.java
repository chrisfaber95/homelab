package dev.homelab.inbox.door;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.conversation.Clients;
import dev.homelab.inbox.core.Task;
import dev.homelab.inbox.core.TaskStore;
import dev.homelab.inbox.door.DoorFixture.FakeJobs;
import dev.homelab.inbox.door.Ports.JobInfo;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ports {@code tests/test_inbox_chat.py}: what a chat client may do (start a task, answer, list, run a job), the daily
 * cap, the switches, the actions no client can ever do, and the two-step approval. Same cases, same statuses.
 */
class ChatActionsTest {

    static final String CLIENTS = """
            {"clients": {
              "discord": {"permissions": ["read", "write", "ask", "start_task", "answer", "approve", "run_job",
                                          "ssh_key", "memory_edit", "merge_homelab"], "tasks_per_day": 3},
              "reader": {"permissions": ["read"]}}}""";

    DoorFixture f;

    @BeforeEach
    void setUp() throws Exception {
        f = new DoorFixture();
        f.reset(CLIENTS);
    }

    private long count(String sql) throws Exception {
        return f.count(sql);
    }

    // --- StartTask ---------------------------------------------------------------------------------------------------

    @Test
    void aTaskIsQueuedLikeAPageTaskAndMarkedDiscord() {
        Result r = f.chat.startTask("discord", "homelab", "fix the thing");
        assertThat(r.status()).isEqualTo(200);
        Task t = f.tasks.get(((Number) r.get("task")).longValue()).orElseThrow();
        assertThat(t.repo()).isEqualTo("homelab");
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.str("source")).isEqualTo("discord"); // its page says where it came from
        assertThat(t.num("unattended")).isZero();
        long page = f.tasks.newTask("homelab", "from the page", null);
        assertThat(f.tasks.get(page).orElseThrow().has("source")).isFalse();
        assertThat(f.entriesOfKind("message")).extracting(e -> e.get("client")).containsExactly("discord");
        assertThat(f.entriesOfKind("message").get(0).get("ref")).isEqualTo("task:" + r.get("task"));
    }

    @Test
    void autoAndUnknownRepo() {
        assertThat(f.chat.startTask("discord", "auto", "something").status()).isEqualTo(200);
        assertThat(f.chat.startTask("discord", "no-such-repo", "x").status()).isEqualTo(400);
        assertThat(f.chat.startTask("discord", "homelab", "  ").status()).isEqualTo(400);
    }

    @Test
    void startingATaskNeedsThePermission() {
        assertThat(f.chat.startTask("reader", "homelab", "x").status()).isEqualTo(403);
        assertThat(f.chat.startTask("nobody", "homelab", "x").status()).isEqualTo(403);
        assertThat(f.store.refusals(100)).hasSize(2);
    }

    @Test
    void theDailyCapRefusesClearlyAndStartsNothing() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(f.chat.startTask("discord", "homelab", "task " + i).status()).isEqualTo(200);
        }
        Result r = f.chat.startTask("discord", "homelab", "one too many");
        assertThat(r.status()).isEqualTo(429);
        assertThat(r.error()).contains("daily limit");
        assertThat(count("SELECT COUNT(*) FROM tasks")).isEqualTo(3);
        f.tasks.newTask("homelab", "page tasks do not count", null);
        assertThat(f.chat.startTask("discord", "homelab", "still too many").status()).isEqualTo(429);
        // the refusal is also said in the conversation, by the box
        assertThat(f.entriesOfKind("notification")).extracting(e -> e.get("text").toString()).anyMatch(t -> t.contains("daily limit"));
    }

    @Test
    void theCapCountsFromLocalMidnight() throws Exception {
        for (int i = 0; i < 3; i++) {
            f.chat.startTask("discord", "homelab", "task " + i);
        }
        assertThat(f.chat.startTask("discord", "homelab", "x").status()).isEqualTo(429);
        f.clock.advance(Duration.ofDays(1)); // the next day the count starts again
        assertThat(f.chat.startTask("discord", "homelab", "x").status()).isEqualTo(200);
    }

    @Test
    void theCapDefaultComesFromConfig() {
        f.setClients("{\"clients\": {\"discord\": {\"permissions\": [\"start_task\"]}}}");
        assertThat(f.chat.dailyCap("discord")).isEqualTo(f.door.defaultTasksPerDay());
        f.setClients("{\"clients\": {\"discord\": {\"permissions\": [\"start_task\"], \"tasks_per_day\": \"x\"}}}");
        assertThat(f.chat.dailyCap("discord")).isEqualTo(10); // an unreadable value falls back, as in Python
    }

    @Test
    void switchingDiscordOffStopsTasks() {
        f.store.setSwitch("discord", false);
        assertThat(f.chat.startTask("discord", "homelab", "x").status()).isEqualTo(403);
        f.store.setSwitch("discord", true);
    }

    // --- Answer ------------------------------------------------------------------------------------------------------

    private long waiting() {
        long tid = f.tasks.newTask("homelab", "q", null);
        f.tasks.update(tid, TaskStore.fields("status", "waiting", "question", "Which?"));
        return tid;
    }

    @Test
    void anAnswerContinuesTheTaskLikeThePage() {
        long tid = waiting();
        assertThat(f.chat.answer("discord", String.valueOf(tid), "the second one").status()).isEqualTo(200);
        Task t = f.tasks.get(tid).orElseThrow();
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.str("pending_input")).contains("the second one");
        List<Map<String, Object>> messages = f.entriesOfKind("message");
        assertThat(messages.get(messages.size() - 1).get("client")).isEqualTo("discord");
    }

    @Test
    void notWaitingUnknownAndForbidden() {
        long tid = f.tasks.newTask("homelab", "q", null);
        assertThat(f.chat.answer("discord", String.valueOf(tid), "x").status()).isEqualTo(409);
        assertThat(f.chat.answer("discord", "99999", "x").status()).isEqualTo(404);
        assertThat(f.chat.answer("discord", "None", "x").status()).isEqualTo(404);
        assertThat(f.chat.answer("reader", String.valueOf(waiting()), "x").status()).isEqualTo(403);
    }

    // --- Lists -------------------------------------------------------------------------------------------------------

    @Test
    void stateListsRunningWaitingAndQueued() {
        long a = f.tasks.newTask("homelab", "one", null);
        long b = f.tasks.newTask("homelab", "two", null);
        f.tasks.update(a, TaskStore.fields("status", "running"));
        long done = f.tasks.newTask("homelab", "three", null);
        f.tasks.update(done, TaskStore.fields("status", "merged"));
        Result r = f.chat.tasksState("discord");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) r.get("tasks");
        assertThat(rows.stream().map(t -> t.get("id") + ":" + t.get("status")).collect(Collectors.toList()))
                .containsExactly(a + ":running", b + ":queued");
        assertThat(rows.get(0).get("what")).isEqualTo("one");
        assertThat(f.chat.tasksState("nobody").status()).isEqualTo(403);
    }

    @Test
    void jobsAreListedAndRunNow() {
        FakeJobs jobs = f.jobs;
        jobs.jobs.put("demo", new JobInfo("demo", null, "script", "", "daily", "local", "page", false, null));
        jobs.jobs.put("broken", new JobInfo("broken", "bad", null, null, null, null, null, false, null));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list = (List<Map<String, Object>>) f.chat.jobsList("discord").get("jobs");
        assertThat(list).extracting(j -> j.get("name")).containsExactly("broken", "demo");
        assertThat(list.get(1).get("when")).isEqualTo("daily");
        assertThat(f.chat.runJob("discord", "demo").status()).isEqualTo(200);
        assertThat(f.chat.runJob("discord", "broken").status()).isEqualTo(404);
        assertThat(f.chat.runJob("reader", "demo").status()).isEqualTo(403);
        assertThat(jobs.dispatched).containsExactly("demo");
        assertThat(f.entriesOfKind("message")).extracting(e -> e.get("ref")).contains("job:demo");
    }

    @Test
    void researchAnswersAreRecordedAndTheCapIsSaid() {
        f.setClients("{\"clients\": {\"discord\": {\"permissions\": [\"research\"]}}}");
        assertThat(f.chat.research("discord", "why?", "quick").status()).isEqualTo(200);
        assertThat(f.entriesOfKind("message")).extracting(e -> e.get("text")).contains("[research:quick] why?");
        f.jobs.research = Result.error(429, "daily limit reached: 10 Claude runs today");
        assertThat(f.chat.research("discord", "again", "quick").status()).isEqualTo(429);
        assertThat(f.entriesOfKind("notification")).hasSize(1);
        f.setClients(CLIENTS);
        assertThat(f.chat.research("discord", "x", "quick").status()).isEqualTo(403); // no research permission
    }

    @Test
    void draftsListTheDraftBriefs() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> briefs = (List<Map<String, Object>>) f.chat.drafts("discord").get("briefs");
        assertThat(briefs).hasSize(1);
        assertThat(briefs.get(0).get("id")).isEqualTo(DoorFixture.BID);
    }

    // --- Approval ----------------------------------------------------------------------------------------------------

    private String request(String mode) {
        Result r = f.chat.approvalRequest("discord", DoorFixture.BID, mode);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        return (String) r.get("confirm");
    }

    private String brief() {
        return f.briefs.briefs.get(DoorFixture.BID).status();
    }

    @Test
    void aRequestAloneApprovesNothing() throws Exception {
        request("build");
        assertThat(f.briefs.commits).isEmpty();
        assertThat(brief()).isEqualTo("draft");
        assertThat(f.entriesOfKind("approval_request")).hasSize(1);
        assertThat(count("SELECT COUNT(*) FROM tasks")).isZero();
    }

    @Test
    void confirmApprovesRecordsAndNotifies() {
        String token = request("without-me");
        Result r = f.chat.approvalConfirm("discord", token);
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        assertThat(brief()).isEqualTo("approved");
        assertThat(f.briefs.commits.get(0)).contains("approved from discord").contains("build without me"); // on the brief's commit
        Task t = f.tasks.get(((Number) r.get("task")).longValue()).orElseThrow();
        assertThat(t.repo()).isEqualTo("brief");
        assertThat(t.str("source")).isEqualTo("discord");
        assertThat(t.num("unattended")).isEqualTo(1);
        assertThat(f.entriesOfKind("approval").get(0).get("client")).isEqualTo("discord");
        assertThat(f.notified.calls()).hasSize(1); // and an ntfy notification, which here is only remembered
        assertThat(f.notified.calls().get(0)).contains("from Discord");
    }

    @Test
    void theTokenIsNotInTheConversation() {
        String token = request("build");
        assertThat(f.store.read(0, 1000).toString()).doesNotContain(token);
    }

    @Test
    void anExpiredConfirmApprovesNothing() {
        String token = request("build");
        f.clock.advance(Duration.ofSeconds(f.chat.ttl() + 1));
        Result r = f.chat.approvalConfirm("discord", token);
        assertThat(r.status()).isEqualTo(410);
        assertThat(r.error()).contains("expired");
        assertThat(f.briefs.commits).isEmpty();
        assertThat(brief()).isEqualTo("draft");
    }

    @Test
    void aTokenWorksOnce() {
        String token = request("build");
        assertThat(f.chat.approvalConfirm("discord", token).status()).isEqualTo(200);
        Result again = f.chat.approvalConfirm("discord", token);
        assertThat(again.status()).isEqualTo(410);
        assertThat(again.error()).contains("already used");
        assertThat(f.briefs.commits).hasSize(1);
    }

    @Test
    void aWrongTokenOrAnotherClientIsUnknown() {
        String token = request("build");
        assertThat(f.chat.approvalConfirm("discord", "guess").status()).isEqualTo(404);
        f.setClients("{\"clients\": {\"discord\": {\"permissions\": [\"approve\"]}, \"other\": {\"permissions\": [\"approve\"]}}}");
        assertThat(f.chat.approvalConfirm("other", token).status()).isEqualTo(404);
        assertThat(f.briefs.commits).isEmpty();
    }

    @Test
    void freeTextNeverApproves() throws Exception {
        for (String text : List.of("approve H-016", "Approve & build H-016", "confirm " + DoorFixture.BID)) {
            for (String kind : List.of("message", "question", "answer")) {
                f.store.append("chris", "discord", kind, text, null, "brief:" + DoorFixture.BID);
            }
        }
        f.chat.startTask("discord", "homelab", "approve H-016"); // a task text is only a task text
        assertThat(f.briefs.commits).isEmpty();
        assertThat(brief()).isEqualTo("draft");
        assertThat(f.entriesOfKind("approval")).isEmpty();
        assertThat(count("SELECT COUNT(*) FROM tasks WHERE repo='brief'")).isZero();
        // a client cannot write an approval entry itself: the kind is not one clients may append
        assertThat(f.store.allowed("discord", "append:approval")).isFalse();
    }

    @Test
    void theApprovalsSwitchRefusesBothSteps() {
        String token = request("build");
        f.store.setSwitch("discord_approvals", false);
        Result r = f.chat.approvalRequest("discord", DoorFixture.BID, "build");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.error()).contains("approvals from Discord are off");
        assertThat(f.chat.approvalConfirm("discord", token).status()).isEqualTo(403); // switched off between the steps
        assertThat(f.briefs.commits).isEmpty();
        f.store.setSwitch("discord_approvals", true);
        assertThat(f.chat.approvalConfirm("discord", token).status()).isEqualTo(200);
    }

    @Test
    void approvingNeedsThePermission() {
        assertThat(f.chat.approvalRequest("reader", DoorFixture.BID, "build").status()).isEqualTo(403);
    }

    @Test
    void onlyDraftsAndUnbuiltAreApproved() {
        f.briefs.briefs.put(DoorFixture.BID, new Ports.Brief(DoorFixture.BID, "Chat", "approved"));
        assertThat(f.chat.approvalRequest("discord", DoorFixture.BID, "build").status()).isEqualTo(409);
        assertThat(f.chat.approvalRequest("discord", "../x", "build").status()).isEqualTo(400);
        f.briefs.briefs.put(DoorFixture.BID, new Ports.Brief(DoorFixture.BID, "Chat", "draft"));
        assertThat(f.chat.approvalRequest("discord", DoorFixture.BID, "yolo").status()).isEqualTo(400);
        assertThat(f.chat.approvalRequest("discord", DoorFixture.BID, null).status()).isEqualTo(400);
    }

    @Test
    void theCapAppliesAtConfirm() {
        String token = request("build");
        for (int i = 0; i < 3; i++) {
            f.chat.startTask("discord", "homelab", "t" + i);
        }
        Result r = f.chat.approvalConfirm("discord", token);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.error()).contains("daily limit");
        assertThat(f.briefs.commits).isEmpty();
    }

    @Test
    void aBuildThatFailsMarksTheApprovalFailedAndSaysWhy() throws Exception {
        String token = request("build");
        f.briefs.briefs.put(DoorFixture.BID, new Ports.Brief(DoorFixture.BID, "Chat", "parked")); // changed meanwhile
        Result r = f.chat.approvalConfirm("discord", token);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.error()).contains("no longer a draft");
        assertThat(count("SELECT COUNT(*) FROM approvals WHERE status='failed'")).isEqualTo(1);
        assertThat(f.chat.approvalConfirm("discord", token).error()).contains("already failed");
    }

    // --- Forbidden ---------------------------------------------------------------------------------------------------

    @Test
    void theConfigCannotGrantTheForbiddenActions() {
        for (String action : Clients.FORBIDDEN) {
            assertThat(f.clients.all().get("discord")).doesNotContain(action);
            assertThat(f.store.allowed("discord", action)).isFalse();
        }
        assertThat(f.store.refusals(100).stream().map(r -> r.get("action")).collect(Collectors.toSet()))
                .isEqualTo(Set.copyOf(Clients.FORBIDDEN));
    }

    @Test
    void notEvenWithEveryPermissionListed() {
        f.setClients("{\"clients\": {\"discord\": {\"permissions\": [\"ssh_key\", \"memory_edit\", \"merge_homelab\"]}}}");
        assertThat(f.store.allowed("discord", "merge_homelab")).isFalse();
        assertThat(f.store.allowed("discord", "approve")).isFalse();
    }
}
