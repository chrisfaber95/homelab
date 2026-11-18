package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * answer, cancel, retry and the sweep of capped working copies. Ports the retry and sweep cases of
 * {@code tests/test_inbox_followups.py} (the follow-up chain they also touch is K3: only the hook is checked here) and
 * the answer cases of {@code tests/test_inbox_chat.py}.
 */
class TaskActionsTest {

    private ConnectionSource db;
    private TaskStore store;
    private CoreFixture.Fakes fakes;
    private CoreFixture.MutableClock clock;
    private Cancellations cancellations;
    private final List<Runnable> background = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        clock = new CoreFixture.MutableClock("2026-11-18T10:00:00Z");
        store = new TaskStore(db, clock);
        fakes = new CoreFixture.Fakes();
        cancellations = new Cancellations();
    }

    private TaskActions actions(boolean schedulerOn) {
        CoreSettings s = CoreSettings.shadowDefaults().withSchedulerEnabled(schedulerOn);
        return new TaskActions(store, s, cancellations, fakes, fakes, fakes, fakes, fakes, Runnable::run, clock,
                "http://homelab:8105");
    }

    private Task task(long id) {
        return store.get(id).orElseThrow();
    }

    // ---- answer: tests/test_inbox_chat.py, class Answer

    /** test_answer_continues_the_task_like_the_page */
    @Test
    void anAnswerQueuesTheWaitingTaskAgainWithTheReplyAndNotesItInTheConversation() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "waiting", "question", "Which?");
        assertThat(actions(false).answer(task(1), "the second one", "discord")).isTrue();
        Task t = task(1);
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.str("pending_input")).contains("the second one").contains("GEHEUGEN: voorkeur");
        assertThat(t.has("answer")).isFalse();
        assertThat(t.str("mem_source")).isEqualTo("jouw antwoord in inbox 1");
        assertThat(t.str("mem_link")).isEqualTo("http://homelab:8105/task/1");
        assertThat(fakes.calls()).containsExactly("note message task:1");
    }

    /** test_not_waiting_unknown_and_forbidden (the not-waiting half; unknown and forbidden are the route's, H-027) */
    @Test
    void anAnswerToATaskThatIsNotWaitingOrAnsweredIsNotTaken() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "queued");
        CoreFixture.insert(db, 2, "homelab", "waiting");
        assertThat(actions(false).answer(task(1), "x", null)).isFalse();
        assertThat(actions(false).answer(task(2), "   ", null)).isFalse();
        assertThat(task(1).status()).isEqualTo("queued");
        assertThat(fakes.calls()).isEmpty();
    }

    /** queue.answer_task, "answered" branch (a reaction to an answer: same session, new working copy); no Python test */
    @Test
    void aReactionToAnAnswerQueuesItWithANewWorkingCopyButNotForAJob() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "answered", "answer", "the plan", "ended", "2026-10-31T19:00:00+00:00");
        CoreFixture.insert(db, 2, "homelab", "answered", "answer", "report", "job", "daily");
        assertThat(actions(false).answer(task(2), "more", null)).isFalse();
        assertThat(actions(false).answer(task(1), "more", null)).isTrue();
        Task t = task(1);
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.str("question")).isEqualTo("the plan");
        assertThat(t.has("answer") || t.has("ended")).isFalse();
        assertThat(t.num("reprepare")).isEqualTo(1);
        assertThat(t.str("pending_input")).startsWith("Reactie van de gebruiker op je antwoord");
    }

    // ---- retry: tests/test_inbox_followups.py

    private void followupsFixture() throws Exception {
        CoreFixture.insert(db, 70, "brief", "routed");
        CoreFixture.insert(db, 74, "homelab", "refused", "parent", "70", "pr_number", "68");
        CoreFixture.insert(db, 75, "homelab", "cancelled", "parent", "70", "after_task", "74");
    }

    /** test_retry_a_refused_task (the follow-ups queueing again is K3: here the hook is called) */
    @Test
    void retryOfARefusedTaskQuotesTheReasonAndTheUsersNote() throws Exception {
        followupsFixture();
        store.update(74, TaskStore.fields("rounds", 1, "summary", "mag niet: F-019"));
        fakes.workingCopy = false;
        actions(false).retry(task(74), "  it is fine: Chris said so  ");
        Task t = task(74);
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.num("reprepare")).isEqualTo(1);
        assertThat(t.has("summary")).isFalse();
        assertThat(t.str("pending_input")).contains("mag niet: F-019").contains("it is fine: Chris said so")
                .contains("fresh copy of main on inbox/74");
        assertThat(fakes.calls()).contains("revive 74");
    }

    /** test_capped_round_keeps_its_working_copy_for_continue (the retry half: Continue keeps the copy, no reprepare) */
    @Test
    void continueOfACappedTaskKeepsItsWorkingCopy() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "capped", "rounds", "1", "note", "gestopt bij 80 beurten");
        actions(false).retry(task(1), "");
        Task t = task(1);
        assertThat(List.of(t.status(), String.valueOf(t.num("reprepare")))).containsExactly("queued", "0");
        assertThat(t.str("pending_input")).contains("asked to continue it. Your working copy is as you left it")
                .contains("gestopt bij 80 beurten");
        assertThat(fakes.calls()).doesNotContain("cleanup 1");
    }

    /** queue.retry_task, rebuilt-from-commits branch; no Python test */
    @Test
    void aCleanedUpCopyIsRebuiltFromThePushedCommits() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "failed", "rounds", "2");
        fakes.workingCopy = false;
        fakes.pushed = true;
        actions(false).retry(task(1), "");
        assertThat(task(1).str("pending_input")).contains("rebuilt from your commits on inbox/1");
        assertThat(task(1).num("reprepare")).isEqualTo(1);
    }

    /** test_retry_clears_the_mark (and restarts, note, ended) */
    @Test
    void retryClearsTheAcceptedMarkTheRestartsAndTheNote() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "failed", "rounds", "1", "accepted", "1", "restarts", "2", "note", "boom",
                "ended", "2026-10-31T19:00:00+00:00");
        actions(false).retry(task(1), "");
        Task t = task(1);
        assertThat(t.num("accepted")).isZero();
        assertThat(t.num("restarts")).isZero();
        assertThat(t.has("note") || t.has("ended")).isFalse();
        assertThat(t.str("pending_input")).contains("(boom)");
    }

    /** test_retry_note_of_a_task_that_never_ran_goes_in_its_text */
    @Test
    void theNoteOfATaskThatNeverRanGoesInItsText() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "failed");
        actions(false).retry(task(1), "try again");
        assertThat(task(1).str("text")).endsWith("try again").startsWith("text 1");
        assertThat(task(1).status()).isEqualTo("queued");
    }

    // ---- cancel: queue.cancel_task, no Python test

    @Test
    void cancellingARunningTaskAsksItsRoundToStop() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "running");
        actions(false).cancel(task(1));
        assertThat(cancellations.requested(1)).isTrue();
        assertThat(task(1).status()).isEqualTo("running");
        assertThat(fakes.calls()).containsExactly("stop 1");
    }

    @Test
    void cancellingAWaitingTaskWithoutPrCleansUpItsWorkingCopy() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "waiting");
        CoreFixture.insert(db, 2, "homelab", "waiting", "pr_number", "5");
        CoreFixture.insert(db, 3, "homelab", "queued");
        TaskActions a = actions(false);
        a.cancel(task(1));
        a.cancel(task(2));
        a.cancel(task(3));
        assertThat(List.of(task(1).status(), task(2).status(), task(3).status())).containsOnly("cancelled");
        assertThat(task(1).has("ended")).isTrue();
        assertThat(fakes.calls()).containsExactly("cleanup 1");
    }

    // ---- sweep: test_sweep_removes_only_old_capped_working_copies

    @Test
    void theSweepRemovesOnlyOldCappedWorkingCopiesAndOnlyOncePerTask() throws Exception {
        String old = Instant.parse("2026-11-10T10:00:00Z").toString().replace("Z", "+00:00");
        String recent = "2026-11-18T10:00:00+00:00";
        CoreFixture.insert(db, 74, "homelab", "capped", "ended", old);
        CoreFixture.insert(db, 75, "homelab", "capped", "ended", recent);
        CoreFixture.insert(db, 76, "homelab", "failed", "ended", old);
        TaskActions a = actions(true);
        a.sweepCapped();
        a.sweepCapped();
        assertThat(fakes.calls()).containsExactly("has 74", "cleanup 74", "log t74 info working copy removed: not continued since it stopped at the limit");
    }

    @Test
    void theSweepSkipsACappedTaskWithAPrAndOneWithoutAWorkingCopy() throws Exception {
        String old = "2026-11-01T10:00:00+00:00";
        CoreFixture.insert(db, 1, "homelab", "capped", "ended", old, "pr_number", "3");
        CoreFixture.insert(db, 2, "homelab", "capped", "ended", old);
        fakes.workingCopy = false;
        actions(true).sweepCapped();
        assertThat(fakes.calls()).containsExactly("has 2");
    }

    @Test
    void theSweepKeepsTheCopyForAWeek() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "capped", "ended", "2026-11-11T10:00:01+00:00"); // 6 days 23:59:59 ago
        actions(true).sweepCapped();
        clock.advance(Duration.ofSeconds(1));
        assertThat(fakes.calls()).isEmpty();
    }

    @Test
    void theSweepDoesNothingWithTheSchedulerOff() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "capped", "ended", "2026-01-01T10:00:00+00:00");
        actions(false).sweepCapped();
        assertThat(fakes.calls()).isEmpty();
    }

    /** the retry path of a capped task continued in the same moment as the sweep: its round needs a new copy */
    @Test
    void aTaskContinuedDuringTheSweepGetsANewWorkingCopy() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "capped", "ended", "2026-01-01T10:00:00+00:00");
        CoreFixture.Fakes racing = new CoreFixture.Fakes() {
            @Override
            public void cleanup(long taskId) {
                super.cleanup(taskId);
                store.update(taskId, TaskStore.fields("status", "queued"));
            }
        };
        new TaskActions(store, CoreSettings.shadowDefaults().withSchedulerEnabled(true), cancellations, racing, racing,
                racing, racing, racing, Runnable::run, clock, "http://h").sweepCapped();
        assertThat(task(1).num("reprepare")).isEqualTo(1);
    }
}
