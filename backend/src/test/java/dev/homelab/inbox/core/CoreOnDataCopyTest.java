package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/**
 * Acceptance (H-026 criterion 2): the whole Java core, every part wired the way the application wires it and run over a
 * copy of the data with the scheduler off, never starts a real task, merge or notification. The copy holds a task in every
 * state a real inbox has (running, queued, waiting, capped, a PR open on a brief build without me, a routed message with
 * cancelled follow-ups, a brief build), plus a job that is due and a run that is held. Every effect of the core is a
 * recording shadow effect that fails the test on any call, and the data is compared before and after.
 */
class CoreOnDataCopyTest {

    private static String dump(ConnectionSource db, String table) throws Exception {
        try (Connection c = db.open(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT * FROM " + table + " ORDER BY 1")) {
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

    @Test
    void theCoreOnACopyOfTheDataWithTheSchedulerOffStartsNoTaskMergeOrNotification() throws Exception {
        ConnectionSource db = CoreFixture.db();
        CoreFixture.clear(db);
        JobFixture.clean(db);
        String old = "2026-10-31T19:00:00+00:00";
        CoreFixture.insert(db, 1, "homelab", "running", "restarts", "1");
        CoreFixture.insert(db, 2, "homelab", "queued");
        CoreFixture.insert(db, 3, "other", "queued", "after_task", "1");
        CoreFixture.insert(db, 4, "homelab", "waiting", "question", "Welke kant op?");
        CoreFixture.insert(db, 5, "homelab", "capped", "ended", old);
        CoreFixture.insert(db, 6, "brief", "routed", "brief", "H-1", "unattended", "1");
        CoreFixture.insert(db, 7, "kitt", "pr_open", "brief", "H-1", "parent", "6", "pr_number", "7", "review_ok", "1");
        CoreFixture.insert(db, 8, "auto", "routed");
        CoreFixture.insert(db, 9, "homelab", "refused", "parent", "8", "ended", old);
        CoreFixture.insert(db, 10, "homelab", "cancelled", "parent", "8", "after_task", "9", "ended", old, "note",
                "niet gestart: de voorgaande taak #9 was geweigerd");
        CoreFixture.insert(db, 11, "homelab", "pr_open", "pr_number", "12", "conflicts", "1");
        JobRuns runs = new JobRuns(db);
        runs.setLastDue("daily", "2026-11-10T10:00:00+00:00");
        runs.insertRun("research", "research", "claude", "x", "2026-11-17T08:00:00+00:00", "held", false, null, "quick", null, null);

        String tasksBefore = dump(db, "tasks");
        String runsBefore = dump(db, "job_runs");

        CoreFixture.MutableClock clock = new CoreFixture.MutableClock("2026-11-18T10:00:00Z");
        TaskStore store = new TaskStore(db, clock);
        CoreSettings off = CoreSettings.shadowDefaults();
        assertThat(off.schedulerEnabled()).isFalse();
        CoreFixture.Fakes fx = new CoreFixture.Fakes();
        FollowupChain chain = new FollowupChain(store, off, fx, fx, fx, fx, "http://h");
        Cancellations cancellations = new Cancellations();

        assertThat(new Scheduler(store, off, fx, fx, Runnable::run).tick()).isEmpty();
        new Recovery(store, off, fx, fx).recover();
        new TaskActions(store, off, cancellations, fx, fx, fx, fx, fx, Runnable::run, clock, "http://h").sweepCapped();
        chain.settleRouted();
        new GitHubPoller(store, off, new GitHubPoller.GitHubSettings("chris", 2), fx, fx, fx, fx, fx, fx, chain, fx, fx, fx,
                fx, clock, "http://h").poll();
        java.util.Map<String, dev.homelab.inbox.read.Jobs.Job> jobs = java.util.Map.of("daily",
                JobFixture.parse("daily", "---\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: claude\n---\nReport.\n"));
        JobEngine engine = new JobEngine(runs, store, off, JobFixture.SETTINGS, () -> jobs, fx, fx, fx, fx, fx,
                cancellations, Runnable::run, clock, "http://h");
        engine.tick();
        engine.releaseHeld();
        Briefs briefs = new Briefs(store, fx, fx, fx, fx, fx, Briefs.Settings.defaults(), clock);
        new BriefRounds(store, briefs, fx, fx, fx, fx, fx, fx, fx, fx, fx, cancellations, "http://h").settle();

        assertThat(fx.calls()).as("no real task, merge or notification: no effect of the core is called at all").isEmpty();
        assertThat(dump(db, "tasks")).isEqualTo(tasksBefore);
        assertThat(dump(db, "job_runs")).isEqualTo(runsBefore);
    }
}
