package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homelab.inbox.InboxBackendApplication;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Acceptance: the Java core runs against a copy of the data with the scheduler off and never starts a real task, merge or
 * notification. The "copy" is a database with tasks in every state a real inbox has.
 */
class ShadowModeTest {

    private ConnectionSource db;

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        CoreFixture.insert(db, 1, "homelab", "running", "restarts", "0");
        CoreFixture.insert(db, 2, "homelab", "queued");
        CoreFixture.insert(db, 3, "homelab", "capped", "ended", "2026-01-01T10:00:00+00:00");
        CoreFixture.insert(db, 4, "homelab", "pr_open", "brief", "H-1", "pr_number", "7");
        CoreFixture.insert(db, 5, "auto", "running");
    }

    private String snapshot() throws Exception {
        try (Connection c = db.open(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM tasks ORDER BY id")) {
            StringBuilder b = new StringBuilder();
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                for (int i = 1; i <= cols; i++) {
                    b.append(rs.getObject(i)).append('|');
                }
                b.append('\n');
            }
            return b.toString();
        }
    }

    /** Every entry point of the core with the scheduler off: no change to the data and no effect, not even a stop. */
    @Test
    void withTheSchedulerOffNothingChangesAndNothingIsStartedStoppedMergedOrNotified() throws Exception {
        String before = snapshot();
        CoreFixture.Fakes fakes = new CoreFixture.Fakes();
        CoreSettings off = CoreSettings.shadowDefaults();
        assertThat(off.schedulerEnabled()).isFalse();
        TaskStore store = new TaskStore(db, new CoreFixture.MutableClock("2026-11-18T10:00:00Z"));
        assertThat(new Scheduler(store, off, fakes, fakes, Runnable::run).tick()).isEmpty();
        new Recovery(store, off, fakes, fakes).recover();
        new TaskActions(store, off, new Cancellations(), fakes, fakes, fakes, fakes, fakes, Runnable::run,
                new CoreFixture.MutableClock("2026-11-18T10:00:00Z"), "http://h").sweepCapped();
        new FollowupChain(store, off, fakes, fakes, fakes, fakes, "http://h").settleRouted(); // K3
        assertThat(snapshot()).isEqualTo(before);
        assertThat(fakes.calls()).isEmpty();
    }

    /** The shadow effects only remember what they were asked: they have nothing to start, merge or send. */
    @Test
    void theShadowEffectsRecordAndDoNothingElse() {
        ShadowEffects shadow = new ShadowEffects();
        shadow.runRound(1);
        shadow.stop(1);
        shadow.notify("title", "body", "url");
        shadow.merge("owner/repo", 3);
        assertThat(shadow.calls()).containsExactly("runRound 1", "stop 1", "notify title", "merge owner/repo#3");
    }

    @Test
    void theApplicationStartsInShadowWithoutALoopOrADatabaseConnection() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(InboxBackendApplication.class)
                .web(WebApplicationType.NONE).properties("backend.secret=x").run()) {
            assertThat(ctx.getBean(CoreSettings.class).schedulerEnabled()).isFalse();
            assertThat(ctx.getBeanNamesForType(SchedulerLoop.class)).isEmpty();
            assertThat(ctx.getBean(Effects.RoundRunner.class)).isInstanceOf(ShadowEffects.class);
            assertThat(ctx.getBean(Effects.Notifier.class)).isInstanceOf(ShadowEffects.class);
            assertThat(ctx.getBean(Effects.GitHubWrites.class)).isInstanceOf(ShadowEffects.class);
            assertThat(ctx.getBean(Scheduler.class).tick()).isEmpty();
        }
    }

    @Test
    void switchingTheSchedulerOnWithoutRealEffectsRefusesToStart() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(InboxBackendApplication.class)
                .web(WebApplicationType.NONE).properties("backend.secret=x").run("--core.scheduler.enabled=true").close())
                .hasStackTraceContaining("needs a real RoundRunner");
    }

    /** Jobs and research (K6) with the scheduler off, over a copy that holds a due job and a held run: nothing runs, nothing
     * is sent and nothing in the data changes. */
    @Test
    void jobsAndResearchWithTheSchedulerOffChangeNothingAndSendNothing() throws Exception {
        JobFixture.clean(db);
        JobRuns runs = new JobRuns(db);
        runs.setLastDue("daily", "2026-11-10T10:00:00+00:00");
        runs.insertRun("research", "research", "claude", "x", "2026-11-17T08:00:00+00:00", "held", false, null, "quick", null, null);
        java.util.Map<String, dev.homelab.inbox.read.Jobs.Job> jobs = java.util.Map.of("daily",
                JobFixture.parse("daily", "---\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: claude\n---\nReport.\n"));
        JobFixture.Recorder fx = new JobFixture.Recorder();
        CoreFixture.MutableClock clock = new CoreFixture.MutableClock("2026-11-18T10:00:00Z");
        JobEngine engine = new JobEngine(runs, new TaskStore(db, clock), CoreSettings.shadowDefaults(), JobFixture.SETTINGS,
                () -> jobs, fx, fx, fx, fx, fx, new Cancellations(), Runnable::run, clock, "http://h");
        engine.tick();
        engine.releaseHeld();
        assertThat(fx.calls()).isEmpty();
        assertThat(runs.lastDue("daily")).contains("2026-11-10T10:00:00+00:00");
        assertThat(runs.runs(null, 10)).hasSize(1);
        assertThat(runs.runs(null, 10).get(0).get("status")).isEqualTo("held");
    }

    @Test
    void theApplicationHasTheJobEngineInShadowToo() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(InboxBackendApplication.class)
                .web(WebApplicationType.NONE).properties("backend.secret=x").run()) {
            assertThat(ctx.getBean(Effects.JobIo.class)).isInstanceOf(ShadowEffects.class);
            ctx.getBean(JobEngine.class).tick(); // does nothing, and opens no database connection
            assertThat(ctx.getBean(JobPages.class)).isNotNull();
            assertThat(ctx.getBean(AgentCatalogue.class)).isNotNull();
        }
    }
}
