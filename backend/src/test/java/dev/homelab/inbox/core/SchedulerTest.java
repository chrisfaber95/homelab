package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The scheduler's concurrency limits and ordering ({@code queue.scheduler}). The Python tests do not cover the scheduler
 * (no test calls it), so these cases are written from the Python code, one per rule; the PR text says so.
 */
class SchedulerTest {

    private ConnectionSource db;
    private TaskStore store;
    private CoreFixture.Fakes fakes;
    private final List<Runnable> queued = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        store = new TaskStore(db, new CoreFixture.MutableClock("2026-11-18T10:00:00Z"));
        fakes = new CoreFixture.Fakes();
    }

    private Scheduler scheduler(CoreSettings settings) {
        return new Scheduler(store, settings, fakes, fakes, Runnable::run);
    }

    private CoreSettings on(int maxRunning, int maxPerRepo) {
        return new CoreSettings(true, maxRunning, maxPerRepo, 80, 30, 7, 2);
    }

    private String status(long id) {
        return store.get(id).orElseThrow().status();
    }

    @Test
    void startsTheOldestQueuedTaskFirstAndMarksItRunningBeforeItsRound() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "queued");
        CoreFixture.insert(db, 2, "homelab", "queued");
        assertThat(scheduler(on(1, 3)).tick()).containsExactly(1L);
        assertThat(status(1)).isEqualTo("running");
        assertThat(status(2)).isEqualTo("queued");
        assertThat(fakes.calls()).containsExactly("runRound 1");
    }

    @Test
    void neverRunsMoreThanMaxRunningInTotal() throws Exception {
        CoreFixture.insert(db, 1, "a", "running");
        CoreFixture.insert(db, 2, "b", "queued");
        CoreFixture.insert(db, 3, "c", "queued");
        CoreFixture.insert(db, 4, "d", "queued");
        assertThat(scheduler(on(3, 3)).tick()).containsExactly(2L, 3L);
        assertThat(scheduler(on(3, 3)).tick()).isEmpty();
        assertThat(status(4)).isEqualTo("queued");
    }

    @Test
    void neverRunsMoreThanMaxPerRepoButLetsOtherReposPass() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "running");
        CoreFixture.insert(db, 2, "homelab", "queued");
        CoreFixture.insert(db, 3, "product", "queued");
        assertThat(scheduler(on(4, 1)).tick()).containsExactly(3L);
        assertThat(status(2)).isEqualTo("queued");
    }

    @Test
    void theRouterAndThePlannerAreNotCountedPerRepo() throws Exception {
        CoreFixture.insert(db, 1, "auto", "running");
        CoreFixture.insert(db, 2, "auto", "queued");
        CoreFixture.insert(db, 3, "brief", "running");
        CoreFixture.insert(db, 4, "brief", "queued");
        assertThat(scheduler(on(9, 1)).tick()).containsExactly(2L, 4L);
    }

    @Test
    void aTaskWaitsForItsPredecessorUntilThatIsDone() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "waiting");
        CoreFixture.insert(db, 2, "homelab", "queued", "after_task", "1");
        assertThat(scheduler(on(4, 3)).tick()).isEmpty();
        store.update(1, TaskStore.fields("status", "merged"));
        assertThat(scheduler(on(4, 3)).tick()).containsExactly(2L);
    }

    @Test
    void aPredecessorThatIsQueuedOrRunningBlocksToo() throws Exception {
        CoreFixture.insert(db, 1, "a", "running");
        CoreFixture.insert(db, 2, "b", "queued", "after_task", "1");
        CoreFixture.insert(db, 3, "c", "queued", "after_task", "2");
        assertThat(scheduler(on(9, 9)).tick()).isEmpty();
    }

    @Test
    void aBriefTaskAlsoWaitsForTheMergeOfItsPredecessorsPr() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "pr_open");
        CoreFixture.insert(db, 2, "homelab", "queued", "after_task", "1", "brief", "H-1");
        CoreFixture.insert(db, 3, "homelab", "queued", "after_task", "1");
        assertThat(scheduler(on(9, 9)).tick()).containsExactly(3L);
        store.update(1, TaskStore.fields("status", "merged"));
        assertThat(scheduler(on(9, 9)).tick()).containsExactly(2L);
    }

    @Test
    void aPredecessorOutsideTheNewest200CountsAsQueued() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "merged");
        CoreFixture.insert(db, 2, "homelab", "queued", "after_task", "1");
        for (int i = 3; i < 3 + 200; i++) {
            CoreFixture.insert(db, i, "r" + i, "merged");
        }
        assertThat(scheduler(on(9, 9)).tick()).isEmpty(); // 2 is outside the window of 200 too: nothing queued is seen
        CoreFixture.insert(db, 300, "other", "queued", "after_task", "1");
        assertThat(scheduler(on(9, 9)).tick()).isEmpty(); // 1 is unknown: it counts as queued
    }

    @Test
    void aFailingRoundIsLoggedAndDoesNotStopTheOthers() throws Exception {
        CoreFixture.insert(db, 1, "a", "queued");
        CoreFixture.insert(db, 2, "b", "queued");
        CoreFixture.Fakes bad = new CoreFixture.Fakes() {
            @Override
            public void runRound(long taskId) {
                super.runRound(taskId);
                if (taskId == 1) {
                    throw new IllegalStateException("boom");
                }
            }
        };
        new Scheduler(store, on(9, 9), bad, bad, Runnable::run).tick();
        assertThat(bad.calls()).contains("runRound 1", "runRound 2").anyMatch(c -> c.startsWith("log t1 error"));
    }

    @Test
    void doesNothingWithTheSchedulerOff() throws Exception {
        CoreFixture.insert(db, 1, "homelab", "queued");
        assertThat(scheduler(CoreSettings.shadowDefaults()).tick()).isEmpty();
        assertThat(status(1)).isEqualTo("queued");
        assertThat(fakes.calls()).isEmpty();
    }
}
