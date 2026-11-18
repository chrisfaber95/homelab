package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Restart recovery ({@code queue.recover}). No Python test calls {@code recover}; these cases are written from the
 * Python code, one per rule (the PR text says so).
 */
class RecoveryTest {

    private ConnectionSource db;
    private TaskStore store;
    private CoreFixture.Fakes fakes;
    private static final CoreSettings ON = CoreSettings.shadowDefaults().withSchedulerEnabled(true);

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        store = new TaskStore(db, new CoreFixture.MutableClock("2026-11-18T10:00:00Z"));
        fakes = new CoreFixture.Fakes();
    }

    private Task task(long id) {
        return store.get(id).orElseThrow();
    }

    @Test
    void aRunningTaskIsStoppedAndGoesBackInTheQueueWithANote() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "running", "restarts", "0", "pending_input", "old");
        new Recovery(store, ON, fakes, fakes).recover();
        Task t = task(1);
        assertThat(fakes.calls()).contains("stop 1");
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.num("restarts")).isEqualTo(1);
        assertThat(t.str("pending_input")).startsWith("The inbox restarted while you were working").contains("git status");
        assertThat(fakes.calls()).anyMatch(c -> c.startsWith("log t1 info interrupted by a restart"));
    }

    @Test
    void aTaskCutOffTwiceInARowFailsInsteadOfComingBack() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "running", "restarts", "2");
        new Recovery(store, ON, fakes, fakes).recover();
        Task t = task(1);
        assertThat(t.status()).isEqualTo("failed");
        assertThat(t.str("note")).isEqualTo("onderbroken door een herstart");
        assertThat(t.has("ended")).isTrue();
        assertThat(fakes.calls()).contains("stop 1");
    }

    @Test
    void theRouterAndThePlannerStartOverWithoutPendingInput() throws Exception {
        CoreFixture.insert(db, 1, "auto", "running", "pending_input", "x");
        CoreFixture.insert(db, 2, "brief", "running", "pending_input", "y");
        new Recovery(store, ON, fakes, fakes).recover();
        assertThat(task(1).status()).isEqualTo("queued");
        assertThat(task(1).has("pending_input")).isFalse();
        assertThat(task(2).has("pending_input")).isFalse();
        assertThat(task(2).num("restarts")).isEqualTo(1);
    }

    @Test
    void otherStatusesAreLeftAlone() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "waiting");
        CoreFixture.insert(db, 2, "homelab", "queued", "pending_input", "keep");
        CoreFixture.insert(db, 3, "homelab", "pr_open");
        new Recovery(store, ON, fakes, fakes).recover();
        assertThat(task(1).status()).isEqualTo("waiting");
        assertThat(task(2).str("pending_input")).isEqualTo("keep");
        assertThat(fakes.calls()).isEmpty();
    }

    @Test
    void aBriefTaskWhoseReviewWasCutOffIsReviewedAgain() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "pr_open", "brief", "H-1");
        CoreFixture.insert(db, 2, "homelab", "pr_open", "brief", "H-1", "review", "fine");
        CoreFixture.insert(db, 3, "homelab", "pr_open");
        new Recovery(store, ON, fakes, fakes).recover();
        assertThat(fakes.calls()).containsExactly("reviewAgain 1");
    }

    @Test
    void doesNothingWithTheSchedulerOff() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "running");
        CoreFixture.insert(db, 2, "homelab", "pr_open", "brief", "H-1");
        new Recovery(store, CoreSettings.shadowDefaults(), fakes, fakes).recover();
        assertThat(task(1).status()).isEqualTo("running");
        assertThat(fakes.calls()).isEmpty();
    }
}
