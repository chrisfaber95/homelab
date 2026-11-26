package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The follow-up chain (H-026 K3). Ports {@code tests/test_inbox_followups.py}: tasks 70 (routed parent), 74 (refused),
 * 75 and 76 (cancelled follow-ups, 76 after 75). The two cases that also run the GitHub poller
 * ({@code check_blocking_pr}) are K5; their router half is here.
 */
class FollowupChainTest {

    private static final String CANCELLED = "niet gestart: de voorgaande taak #74 was geweigerd";

    private ConnectionSource db;
    private TaskStore store;
    private CoreFixture.Fakes fakes;

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        store = new TaskStore(db, new CoreFixture.MutableClock("2026-11-18T10:00:00Z"));
        fakes = new CoreFixture.Fakes();
        CoreFixture.insert(db, 70, "brief", "routed", "ended", "2026-10-31T19:00:00+00:00");
        CoreFixture.insert(db, 74, "homelab", "refused", "ended", "2026-10-31T19:00:00+00:00", "parent", "70",
                "pr_number", "68", "pr_url", "u");
        for (int[] ip : new int[][] {{75, 74}, {76, 75}}) {
            CoreFixture.insert(db, ip[0], "homelab", "cancelled", "ended", "2026-10-31T19:00:00+00:00", "parent", "70",
                    "after_task", String.valueOf(ip[1]), "note", CANCELLED);
        }
    }

    private FollowupChain chain(boolean schedulerOn) {
        return new FollowupChain(store, CoreSettings.shadowDefaults().withSchedulerEnabled(schedulerOn), fakes, fakes,
                fakes, fakes, "http://homelab:8105");
    }

    private Task task(long id) {
        return store.get(id).orElseThrow();
    }

    private List<String> statuses(long... ids) {
        return java.util.Arrays.stream(ids).mapToObj(i -> task(i).status()).toList();
    }

    /** test_merged_pr_requeues_followups_in_a_chain (the router half: the revive the poller calls with automatic=true) */
    @Test
    void anAutomaticReviveQueuesTheFollowupsInAChainAndReopensTheParent() {
        store.update(74, TaskStore.fields("status", "merged"));
        assertThat(chain(false).blockedFollowups(task(74), true)).extracting(Task::id).containsExactly(75L);
        assertThat(chain(false).reviveFollowups(task(74), true)).isEqualTo(2);
        assertThat(statuses(74, 75, 76)).containsExactly("merged", "queued", "queued");
        assertThat(task(70).has("ended")).isFalse();
        assertThat(task(75).has("note") || task(75).has("ended")).isFalse();
    }

    private void checkBlocking(String state, boolean merged) {
        chain(false).checkBlockingPr(task(74), new Effects.Repos() {
            public java.util.Map<String, String> list() {
                return java.util.Map.of("homelab", "o/homelab");
            }

            public String refresh(String repo, String slug) {
                return "";
            }
        }, (slug, n) -> java.util.Optional.of(new Effects.PrState(state, merged)));
    }

    /** test_merged_pr_requeues_followups_in_a_chain (the whole of it: the PR check, then the revive) */
    @Test
    void aMergedPrMarksTheTaskMergedAndQueuesTheFollowupsInAChain() {
        checkBlocking("closed", true);
        assertThat(statuses(74, 75, 76)).containsExactly("merged", "queued", "queued");
        assertThat(task(70).has("ended")).isFalse();
        assertThat(fakes.calls()).contains("cleanup 74", "remember 74", "notify Inbox 74: PR #68 merged",
                "log t74 info PR #68 merged: 2 follow-up(s) back in the queue");
    }

    /** test_open_or_closed_pr_changes_nothing */
    @Test
    void anOpenOrUnmergedClosedPrChangesNothing() {
        checkBlocking("open", false);
        checkBlocking("closed", false);
        assertThat(statuses(74, 75)).containsExactly("refused", "cancelled");
        assertThat(fakes.calls()).isEmpty();
    }

    /** test_merge_does_not_restart_a_followup_from_before_this_feature (the whole of it: the PR is merged, 75 stays) */
    @Test
    void aMergedPrDoesNotRestartAFollowupFromBeforeTheFeature() {
        store.update(75, TaskStore.fields("no_auto_revive", 1));
        checkBlocking("closed", true);
        assertThat(statuses(74, 75)).containsExactly("merged", "cancelled");
    }

    /** test_merge_does_not_restart_a_followup_from_before_this_feature (the router half: blocked_followups(t, True)) */
    @Test
    void aFollowupFromBeforeTheFeatureIsNotRestartedAutomatically() {
        store.update(75, TaskStore.fields("no_auto_revive", 1));
        assertThat(chain(false).blockedFollowups(task(74), true)).isEmpty();
        assertThat(chain(false).reviveFollowups(task(74), true)).isZero();
        assertThat(statuses(74, 75)).containsExactly("refused", "cancelled");
        assertThat(chain(false).blockedFollowups(task(74), false)).extracting(Task::id).containsExactly(75L);
    }

    /** test_mark_as_fine_requeues_followups_and_not_the_task */
    @Test
    void markAsFineQueuesTheFollowupsNotTheTaskAndTheSchedulerKeepsThemThere() {
        FollowupChain chain = chain(true);
        assertThat(chain.acceptTask(task(74))).isEqualTo(2);
        assertThat(statuses(74, 75, 76)).containsExactly("refused", "queued", "queued");
        assertThat(task(74).num("accepted")).isEqualTo(1);
        chain.settleRouted(); // the scheduler must not cancel them again
        assertThat(task(75).status()).isEqualTo("queued");
        assertThat(chain.blockedBy(task(75))).isEmpty();
        assertThat(fakes.calls()).contains("log t74 info marked as fine on the page: follow-ups back in the queue");
    }

    /** test_mark_as_fine_needs_a_blocked_followup */
    @Test
    void markAsFineNeedsABlockedFollowup() {
        store.update(75, TaskStore.fields("status", "merged"));
        assertThat(chain(false).acceptTask(task(74))).isZero();
        assertThat(task(74).num("accepted")).isZero();
    }

    /** test_unmarked_task_still_stops_followups */
    @Test
    void anUnmarkedTaskStillStopsItsFollowups() {
        store.update(70, TaskStore.fields("ended", null));
        store.update(75, TaskStore.fields("status", "queued", "ended", null, "note", null));
        chain(true).settleRouted();
        assertThat(task(75).status()).isEqualTo("cancelled");
        assertThat(task(75).str("note")).isEqualTo(CANCELLED);
        assertThat(chain(true).blockedBy(task(75))).map(Task::id).contains(74L);
    }

    /** test_retry_clears_the_mark (with the real chain behind TaskActions) */
    @Test
    void aRetryClearsTheMark() {
        chain(false).acceptTask(task(74));
        store.update(74, TaskStore.fields("status", "failed"));
        actions().retry(task(74), null);
        assertThat(task(74).num("accepted")).isZero();
    }

    /** test_retry_a_refused_task, the follow-ups half: its two follow-ups are queued again */
    @Test
    void retryingTheTaskQueuesItsFollowupsAgain() {
        store.update(74, TaskStore.fields("rounds", 1, "summary", "mag niet: F-019"));
        fakes.workingCopy = false;
        actions().retry(task(74), "  it is fine: Chris said so  ");
        assertThat(task(74).status()).isEqualTo("queued");
        assertThat(statuses(75, 76)).containsExactly("queued", "queued");
    }

    /** test_retry_note_of_a_task_that_never_ran_goes_in_its_text (with a follow-up chain behind it) */
    @Test
    void aFollowupThatRanBeforeResumesItsSessionInARebuiltCopy() {
        store.update(75, TaskStore.fields("rounds", 2));
        fakes.workingCopy = false;
        assertThat(chain(false).reviveFollowups(task(74), false)).isEqualTo(2);
        assertThat(task(75).num("reprepare")).isEqualTo(1);
        assertThat(task(75).str("pending_input")).startsWith("The task before this one stopped early");
        assertThat(task(76).has("pending_input")).isFalse(); // never ran: its first round reads the task text
    }

    /** queue.retry_task: a task that is not under a routed parent has nothing to revive; no Python test */
    @Test
    void aTaskWithoutARoutedParentRevivesNothing() {
        store.update(70, TaskStore.fields("status", "failed"));
        assertThat(chain(false).reviveFollowups(task(74), false)).isZero();
        assertThat(chain(false).blockedFollowups(task(74), false)).isEmpty();
        assertThat(statuses(75)).containsExactly("cancelled");
    }

    /** test_migration_flags_only_existing_followups: the SQLite ALTER TABLE is not Java's; V1 has the column, default 0 */
    @Test
    void theColumnNoAutoReviveDefaultsToZero() {
        assertThat(task(74).num("no_auto_revive")).isZero();
        assertThat(task(75).num("no_auto_revive")).isZero();
    }

    // ---- settle_routed: no Python test (tests/test_inbox_builds.py touches it for the build only)

    /** router.settle_routed, close the message once every child is done; no Python test */
    @Test
    void aRoutedMessageIsClosedWhenAllChildrenAreDone() {
        store.update(70, TaskStore.fields("ended", null));
        store.update(75, TaskStore.fields("status", "merged", "answer", "klaar\nmeer"));
        store.update(76, TaskStore.fields("status", "failed", "note", "boem"));
        chain(true).settleRouted();
        Task p = task(70);
        assertThat(p.has("ended")).isTrue();
        assertThat(p.str("summary")).isEqualTo("- homelab (#74): geweigerd, PR #68 u - taak\n"
                + "- homelab (#75): gemerged - klaar\n- homelab (#76): mislukt - boem");
        assertThat(fakes.calls()).contains("notify Inbox 70: klaar", "remember 70");
    }

    /** a child that is still queued, running or waiting keeps the message open; so does a brief PR not yet reviewed */
    @Test
    void aBusyChildKeepsTheMessageOpen() {
        store.update(70, TaskStore.fields("ended", null));
        store.update(74, TaskStore.fields("status", "merged"));
        store.update(75, TaskStore.fields("status", "waiting", "ended", null));
        store.update(76, TaskStore.fields("status", "merged"));
        chain(true).settleRouted();
        assertThat(task(70).has("ended")).isFalse();
        store.update(75, TaskStore.fields("status", "pr_open", "brief", "H-1"));
        chain(true).settleRouted();
        assertThat(task(70).has("ended")).isFalse();
        store.update(75, TaskStore.fields("review", "ok"));
        chain(true).settleRouted();
        assertThat(task(70).has("ended")).isTrue();
    }

    /** a queued follow-up is also kept when the predecessor was marked as fine or merged; with the scheduler off the pass is a no-op */
    @Test
    void withTheSchedulerOffSettleRoutedDoesNothing() {
        store.update(70, TaskStore.fields("ended", null));
        store.update(75, TaskStore.fields("status", "queued", "ended", null, "note", null));
        chain(false).settleRouted();
        assertThat(task(75).status()).isEqualTo("queued");
        assertThat(task(70).has("ended")).isFalse();
        assertThat(fakes.calls()).isEmpty();
    }

    private TaskActions actions() {
        return new TaskActions(store, CoreSettings.shadowDefaults(), new Cancellations(), fakes, fakes, fakes, fakes,
                chain(false), Runnable::run, new CoreFixture.MutableClock("2026-11-18T10:00:00Z"), "http://homelab:8105");
    }
}
