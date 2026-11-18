package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.read.Jobs.Job;
import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Jobs and research against a throwaway PostgreSQL, with every outside effect recorded. Each test names the Python test
 * it ports; a test marked "written from" has none in Python (the scheduler tick, the hold and release, gather).
 */
class JobEngineTest {

    private ConnectionSource db;
    private JobRuns runs;
    private TaskStore store;
    private JobFixture.Recorder fx;
    private CoreFixture.MutableClock clock;
    private final Map<String, Job> jobs = new HashMap<>();
    private Cancellations cancelled;

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        JobFixture.clean(db);
        clock = new CoreFixture.MutableClock("2026-11-18T10:00:00Z");
        runs = new JobRuns(db);
        store = new TaskStore(db, clock);
        fx = new JobFixture.Recorder();
        cancelled = new Cancellations();
        jobs.clear();
    }

    private JobEngine engine() {
        return engine(JobFixture.SETTINGS, JobFixture.ON);
    }

    private JobEngine engine(JobSettings settings, CoreSettings core) {
        return new JobEngine(runs, store, core, settings, () -> jobs, fx, fx, fx, fx, fx, cancelled, Runnable::run, clock,
                "http://h:8105");
    }

    private Map<String, Object> run(long id) {
        return runs.run(id).orElseThrow();
    }

    private Task taskOfRun(long runId) {
        return store.get(((Number) run(runId).get("task_id")).longValue()).orElseThrow();
    }

    private long count(String table) throws Exception {
        try (Connection c = db.open(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long insertRun(Job job, String status) {
        return runs.insertRun(job.name(), job.kind(), job.model(), "x", store.now(), status, false, null, job.mode(), null, null);
    }

    // ---- asking research ----

    @Test // Ask.test_a_question_is_one_claude_run_against_the_cap
    void aQuestionIsOneClaudeRunAgainstTheCap() {
        JobEngine.Asked a = engine().askResearch("What is X?", "deep", null);
        assertThat(a.code()).isEqualTo(200);
        assertThat(engine().claudeRunsToday()).isEqualTo(1);
        long runId = ((Number) a.body().get("run")).longValue();
        Map<String, Object> r = run(runId);
        assertThat(List.of(r.get("kind"), r.get("mode"), r.get("deliver"))).containsExactly("research", "deep", "page");
        assertThat(r.get("source")).isNull();
        Task t = taskOfRun(runId);
        assertThat(t.str("job")).isEqualTo("research");
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.str("text")).contains("What is X?").contains("Modus: deep");
        assertThat(a.body().get("url")).isEqualTo("http://h:8105/research");
    }

    @Test // Ask.test_above_the_cap_it_is_refused_with_a_message_and_nothing_starts
    void aboveTheCapItIsRefusedWithAMessageAndNothingStarts() throws Exception {
        JobEngine e = engine(JobFixture.SETTINGS.withDailyCap(1), JobFixture.ON);
        assertThat(e.askResearch("one", "quick", null).code()).isEqualTo(200);
        JobEngine.Asked second = e.askResearch("two", "quick", null);
        assertThat(second.code()).isEqualTo(429);
        assertThat((String) second.body().get("error")).contains("daily limit");
        assertThat(e.claudeRunsToday()).isEqualTo(1);
        assertThat(count("tasks")).isEqualTo(1);
        assertThat(count("job_runs")).isEqualTo(1);
    }

    @Test // Ask.test_chat_refusal_above_the_cap_is_a_clear_message (the cap part; the chat permission is K7)
    void aCapOfZeroRefusesEveryQuestionWithAClearMessage() {
        JobEngine.Asked a = engine(JobFixture.SETTINGS.withDailyCap(0), JobFixture.ON).askResearch("q?", "quick", "discord");
        assertThat(a.code()).isEqualTo(429);
        assertThat((String) a.body().get("error")).contains("daily limit");
    }

    @Test // Ask.test_a_scheduled_research_job_counts_and_waits_like_any_claude_job
    void aScheduledResearchJobCountsAndWaitsLikeAnyClaudeJob() throws Exception {
        JobEngine e = engine(JobFixture.SETTINGS.withDailyCap(1), JobFixture.ON);
        Job job = JobFixture.research("");
        for (int i = 0; i < 2; i++) {
            e.startClaude(insertRun(job, "queued"), job);
        }
        try (Connection c = db.open(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT status FROM job_runs ORDER BY id")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("claude");
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("held");
        }
    }

    @Test // Ask.test_bad_input
    void badInputIsA400() {
        assertThat(engine().askResearch("", "quick", null).code()).isEqualTo(400);
        assertThat(engine().askResearch("q", "slow", null).code()).isEqualTo(400);
        assertThat(engine().askResearch(null, "quick", null).code()).isEqualTo(400);
    }

    @Test // Ask.test_chat_needs_the_research_permission_and_delivers_to_the_conversation (the data part)
    void aQuestionFromAChatClientRemembersTheClientAndDeliversToTheConversation() {
        long runId = ((Number) engine().askResearch("q?", "quick", "discord").body().get("run")).longValue();
        assertThat(List.of(run(runId).get("source"), run(runId).get("deliver"))).containsExactly("discord", "conversation");
    }

    // ---- finishing research ----

    private Task started(String source, String mode) {
        long runId = ((Number) engine().askResearch("What is X?", mode, source).body().get("run")).longValue();
        return taskOfRun(runId);
    }

    private Task finish(Task t, String kind, String body, String text) {
        engine().finishResearch(t, engine().researchRun(t).orElseThrow(), kind, body, text, null);
        return store.get(t.id()).orElseThrow();
    }

    private static final String REPORT = "## Antwoord\nOne claim [1].\n\n## Bronnen\n[1] https://example.org/a\n\n## Doorzocht\n"
            + "private: tailscale acl; web: tailscale acl syntax";

    @Test // Finish.test_report_has_mode_and_time_and_sources_and_stays_on_the_page
    void theReportHasModeTimeAndSourcesStaysOnThePageAndThrowsTheWorkingCopyAway() {
        Task t = finish(started(null, "deep"), "ANTWOORD", REPORT, "ANTWOORD: " + REPORT);
        assertThat(t.status()).isEqualTo("answered");
        assertThat(t.str("answer").lines().findFirst().orElse(""))
                .matches("^Research report \\| mode: deep \\| time taken: \\d+ (min \\d+ )?s$");
        assertThat(t.str("answer")).contains("https://example.org/a");
        assertThat(fx.calls()).contains("cleanup " + t.id());
        assertThat(fx.calls()).noneMatch(c -> c.startsWith("merge") || c.startsWith("runRound"));
        assertThat(runs.runs("research", 5).get(0).get("mode")).isEqualTo("deep");
        assertThat(t.str("ended")).isNotNull();
    }

    @Test // Finish.test_report_has_mode_and_time (the time is the time since the task started)
    void theTimeTakenIsMeasuredFromTheStartOfTheTask() {
        Task t = started(null, "quick");
        store.update(t.id(), TaskStore.fields("started", "2026-11-18T09:57:35+00:00"));
        clock.advance(Duration.ZERO);
        Task done = finish(store.get(t.id()).orElseThrow(), "ANTWOORD", REPORT, REPORT);
        assertThat(done.str("answer")).startsWith("Research report | mode: quick | time taken: 2 min 25 s\n\n");
    }

    @Test // Finish.test_page_question_delivers_nothing_extra
    void aPageQuestionDeliversNothingExtra() {
        finish(started(null, "quick"), "ANTWOORD", REPORT, REPORT);
        assertThat(fx.notes).isEmpty();
        assertThat(fx.pushes).isEmpty();
    }

    @Test // Finish.test_discord_question_gets_its_report_in_the_conversation
    void aDiscordQuestionGetsItsReportInTheConversation() {
        Task t = started("discord", "quick");
        finish(t, "ANTWOORD", REPORT, REPORT);
        assertThat(fx.notes).hasSize(1);
        JobFixture.Recorder.Note n = fx.notes.get(0);
        assertThat(n.text()).contains("Research report").contains("mode: quick").contains("https://example.org/a");
        assertThat(n.link()).isEqualTo("http://h:8105/task/" + t.id());
        assertThat(n.ref()).startsWith("job:research:");
        assertThat(List.of(n.author(), n.client(), n.kind())).containsExactly("box", "inbox", "notification");
    }

    @Test // Finish.test_nothing_found_is_delivered_as_the_report
    void nothingFoundIsDeliveredAsTheReport() {
        String body = "NIETS GEVONDEN\nNo source covers this.\n\n## Doorzocht\n- private: x\n- web: y";
        Task t = finish(started("discord", "quick"), "ANTWOORD", body, "ANTWOORD: " + body);
        assertThat(t.str("answer")).contains("NIETS GEVONDEN").contains("## Doorzocht");
        assertThat(t.str("note")).isNull();
        assertThat(fx.notes).hasSize(1);
    }

    @Test // Finish.test_report_without_sources_is_flagged
    void aReportWithoutSourcesIsFlaggedOnTheTask() {
        Task t = finish(started(null, "quick"), "ANTWOORD", "## Antwoord\nA claim.\n\n## Doorzocht\n- x", "x");
        assertThat(t.str("note")).contains("no sources");
        assertThat(t.status()).isEqualTo("answered");
    }

    @Test // Finish.test_no_report_is_a_failure_not_a_pr
    void noReportIsAFailureNotAPr() {
        Task t = finish(started(null, "quick"), "KLAAR", "I changed things", "KLAAR: I changed things");
        assertThat(t.status()).isEqualTo("failed");
        assertThat(t.numOrNull("pr_number")).isNull();
        assertThat(t.str("note")).contains("gave no report");
        assertThat(fx.pushes).hasSize(1);
        assertThat(fx.pushes.get(0).title()).endsWith("mislukt");
        assertThat(fx.calls()).noneMatch(c -> c.startsWith("merge"));
    }

    // ---- delivery ----

    @Test // Delivery.test_page_sends_nothing
    void thePageDeliversNothing() throws Exception {
        assertThat(engine().deliverResult(JobFixture.local("demo", "page"), 1L, "result", "http://x/run/1")).isFalse();
        assertThat(fx.pushes).isEmpty();
        assertThat(fx.notes).isEmpty();
    }

    @Test // Delivery.test_ntfy_sends_in_full_with_link
    void ntfySendsTheFullResultWithTheLinkAndTheLongLimit() throws Exception {
        String text = "result ".repeat(100);
        assertThat(engine().deliverResult(JobFixture.local("demo", "ntfy"), 2L, text, "http://x/run/2")).isTrue();
        JobFixture.Recorder.Push p = fx.pushes.get(0);
        assertThat(p.url()).isEqualTo("http://x/run/2");
        assertThat(p.limit()).isGreaterThan(300);
        assertThat(p.body()).isEqualTo(text.strip());
        assertThat(p.title()).isEqualTo("Job demo");
    }

    @Test // Delivery.test_conversation_gets_full_scrubbed_text_and_link (scrubbing is the store's: ConversationStoreTest)
    void theConversationGetsTheFullTextAndTheLink() throws Exception {
        String text = "A long result. ".repeat(300);
        assertThat(engine().deliverResult(JobFixture.local("demo", "conversation"), 3L, text, "http://x/task/3")).isTrue();
        JobFixture.Recorder.Note n = fx.notes.get(0);
        assertThat(n.link()).isEqualTo("http://x/task/3");
        assertThat(n.text()).isEqualTo("Job demo\n" + text.strip());
        assertThat(n.ref()).isEqualTo("job:demo:3");
    }

    @Test // Delivery.test_nothing_to_report_sends_nothing
    void nothingToReportSendsNothing() throws Exception {
        for (String d : List.of("ntfy", "conversation")) {
            assertThat(engine().deliverResult(JobFixture.local("demo", d), 4L, "NOTHING TO REPORT\nall quiet", "http://x")).isFalse();
        }
        assertThat(fx.pushes).isEmpty();
        assertThat(fx.notes).isEmpty();
        assertThat(fx.calls()).contains("log r4 info niets te melden: niets bezorgd");
    }

    @Test // (written from jobs.deliver_result: an empty result and a missing job deliver nothing)
    void anEmptyResultAndAMissingJobDeliverNothing() throws Exception {
        assertThat(engine().deliverResult(JobFixture.local("demo", "ntfy"), 5L, "  ", "u")).isFalse();
        assertThat(engine().deliverResult(null, 5L, "text", "u")).isFalse();
    }

    // ---- local runs ----

    private long localRun(Job job) {
        return insertRun(job, "queued");
    }

    @Test // LocalRun.test_result_stays_in_history_when_nothing_to_report
    void aLocalResultStaysInTheHistoryWhenThereIsNothingToReport() throws Exception {
        Job job = JobFixture.local("demo", "conversation");
        fx.summary = "NOTHING TO REPORT";
        long id = localRun(job);
        engine().runLocal(id, job);
        assertThat(List.of(run(id).get("status"), run(id).get("summary"))).containsExactly("ok", "NOTHING TO REPORT");
        assertThat(fx.notes).isEmpty();
    }

    @Test // (written from jobs.run_local: an ok result is stored and delivered)
    void anOkLocalRunStoresTheSummaryAndDelivers() throws Exception {
        Job job = JobFixture.local("demo", "ntfy");
        fx.summary = "Disk is full.";
        long id = localRun(job);
        engine().runLocal(id, job);
        assertThat(run(id).get("status")).isEqualTo("ok");
        assertThat(run(id).get("started")).isNotNull();
        assertThat(fx.pushes).hasSize(1);
        assertThat(fx.pushes.get(0).url()).isEqualTo("http://h:8105/run/" + id);
        assertThat(fx.calls()).contains("log r" + id + " info boxrapport: tekst verzamelen");
    }

    @Test // LocalRun.test_failure_notification_unchanged
    void aFailureNotificationIsUnchanged() throws Exception {
        Job job = JobFixture.local("demo", "ntfy");
        fx.failure = new IOException("boom");
        long id = localRun(job);
        engine().runLocal(id, job);
        assertThat(run(id).get("status")).isEqualTo("failed");
        assertThat(run(id).get("note")).isEqualTo("boom");
        assertThat(fx.pushes).hasSize(1);
        assertThat(fx.pushes.get(0).title()).isEqualTo("Job demo mislukt");
        assertThat(fx.pushes.get(0).limit()).isEqualTo(300);
        assertThat(fx.pushes.get(0).url()).isEqualTo("http://h:8105/job/demo");
    }

    @Test // (written from jobs.run_local: a cancelled run ends cancelled, without a failure notice)
    void aCancelledLocalRunEndsCancelledWithoutANotification() throws Exception {
        Job job = JobFixture.local("demo", "ntfy");
        long id = localRun(job);
        cancelled.request(id);
        engine().runLocal(id, job);
        assertThat(run(id).get("status")).isEqualTo("cancelled");
        assertThat(fx.pushes).isEmpty();
        assertThat(cancelled.requested(id)).isFalse();
    }

    @Test // (written from jobs.run_local: the text for the model is cut at the limit)
    void tooLongATextIsCutForTheLocalModel() throws Exception {
        Job job = JobFixture.local("demo", "page");
        fx.overview = "x".repeat(9500);
        long id = localRun(job);
        engine().runLocal(id, job);
        assertThat(fx.calls()).contains("log r" + id + " info afgekapt op 9000 tekens");
    }

    // ---- the scheduler ----

    private Job daily() throws Exception {
        Job j = JobFixture.parse("daily", "---\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: local\n---\nReport.\n");
        jobs.put("daily", j);
        return j;
    }

    @Test // (written from jobs.tick: a new job counts from now, without a catch-up run)
    void aNewJobCountsFromNowWithoutACatchUpRun() throws Exception {
        daily();
        engine().tick();
        assertThat(runs.lastDue("daily")).contains("2026-11-18T10:00:00+00:00");
        assertThat(count("job_runs")).isZero();
    }

    @Test // (written from jobs.tick: after downtime a job runs once, not once per missed moment)
    void aJobThatWasDueSeveralTimesRunsOnceAfterDowntime() throws Exception {
        daily();
        runs.setLastDue("daily", "2026-11-10T10:00:00+00:00");
        engine().tick();
        assertThat(count("job_runs")).isEqualTo(1);
        Map<String, Object> r = runs.runs("daily", 1).get(0);
        assertThat(r.get("due")).isEqualTo("2026-11-18T06:00:00+00:00"); // 07:00 Amsterdam, the last moment due
        assertThat(r.get("status")).isEqualTo("ok");
        assertThat(r.get("manual")).isEqualTo(0);
        assertThat(runs.lastDue("daily")).contains("2026-11-18T07:00:00+01:00");
        engine().tick();
        assertThat(count("job_runs")).isEqualTo(1);
    }

    @Test // (written from jobs.tick: paused and broken jobs do not run)
    void pausedAndBrokenJobsDoNotRun() throws Exception {
        Job paused = JobFixture.parse("sleeper", "---\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: local\npaused: true\n---\nR.\n");
        jobs.put("sleeper", paused);
        jobs.put("broken", new Job("broken", "kapot", null, null, null, null, null, false, null, List.of(), null, null,
                List.of(), List.of(), "page", null, ""));
        runs.setLastDue("sleeper", "2026-11-10T10:00:00+00:00");
        engine().tick();
        assertThat(count("job_runs")).isZero();
        assertThat(runs.lastDue("sleeper")).contains("2026-11-18T10:00:00+00:00"); // counts from now again
        assertThat(runs.lastDue("broken")).isEmpty();
    }

    @Test // acceptance: with the scheduler off the tick does nothing at all
    void withTheSchedulerOffTheTickDoesNothing() throws Exception {
        daily();
        runs.setLastDue("daily", "2026-11-10T10:00:00+00:00");
        engine(JobFixture.SETTINGS, CoreSettings.shadowDefaults()).tick();
        assertThat(count("job_runs")).isZero();
        assertThat(runs.lastDue("daily")).contains("2026-11-10T10:00:00+00:00");
        assertThat(fx.calls()).isEmpty();
    }

    @Test // (written from jobs.dispatch + start_claude: a Claude job becomes a queued task, nothing is started)
    void aDispatchedClaudeJobBecomesAQueuedTaskAndNothingIsStarted() throws Exception {
        Job job = JobFixture.parser().parse("fixer", "---\nschedule: \"0 7 * * *\"\nkind: repo-chore\nmodel: claude\n"
                + "repo: kitt\n---\nFix.\n", java.util.Set.of("kitt"), java.util.Set.of());
        long id = engine().dispatch(job, "2026-11-18T06:00:00+00:00", true);
        assertThat(run(id).get("status")).isEqualTo("claude");
        assertThat(run(id).get("manual")).isEqualTo(1);
        Task t = taskOfRun(id);
        assertThat(List.of(t.status(), t.str("repo"), t.str("job"))).containsExactly("queued", "kitt", "fixer");
        assertThat(t.str("text")).contains("eindig met KLAAR:");
        assertThat(fx.calls()).isEmpty();
    }

    // ---- the cap: hold and release ----

    @Test // (written from jobs.start_claude: at the cap a run is held, one held run per job, one notice a day)
    void atTheCapARunIsHeldTheOlderHeldRunWinsAndTheUserIsToldOnceADay() throws Exception {
        JobEngine e = engine(JobFixture.SETTINGS.withDailyCap(1), JobFixture.ON);
        Job job = JobFixture.research("");
        e.startClaude(insertRun(job, "queued"), job);
        long held = insertRun(job, "queued");
        e.startClaude(held, job);
        long dropped = insertRun(job, "queued");
        e.startClaude(dropped, job);
        assertThat(run(held).get("status")).isEqualTo("held");
        assertThat((String) run(held).get("note")).isEqualTo("daglimiet van 1 Claude-runs bereikt; draait morgen");
        assertThat(runs.run(dropped)).isEmpty();
        assertThat(fx.pushes).hasSize(1);
        assertThat(fx.pushes.get(0).title()).isEqualTo("Jobs: daglimiet Claude bereikt");
        assertThat(fx.pushes.get(0).url()).isEqualTo("http://h:8105/jobs");
    }

    @Test // (written from jobs.release_held: yesterday's held run goes first today; broken or paused ones are cancelled)
    void whatWasHeldYesterdayGoesFirstTodayAndAGoneJobIsCancelled() throws Exception {
        Job job = JobFixture.research("");
        jobs.put(job.name(), job);
        long held = runs.insertRun(job.name(), "research", "claude", "x", "2026-11-17T08:00:00+00:00", "held", false, null,
                "quick", null, null);
        long gone = runs.insertRun("vanished", "research", "claude", "x", "2026-11-17T08:30:00+00:00", "held", false, null,
                "quick", null, null);
        long today = runs.insertRun(job.name(), "research", "claude", "x", store.now(), "held", false, null, "quick", null, null);
        engine().releaseHeld();
        assertThat(run(held).get("status")).isEqualTo("claude");
        assertThat(run(gone).get("status")).isEqualTo("cancelled");
        assertThat(run(gone).get("note")).isEqualTo("job weg, gepauzeerd of met een fout");
        assertThat(run(today).get("status")).isEqualTo("held");
        assertThat(taskOfRun(held).status()).isEqualTo("queued");
    }

    @Test // (written from jobs.release_held: the cap stops the release)
    void theCapStopsTheRelease() throws Exception {
        JobEngine e = engine(JobFixture.SETTINGS.withDailyCap(1), JobFixture.ON);
        Job job = JobFixture.research("");
        jobs.put(job.name(), job);
        e.startClaude(insertRun(job, "queued"), job);
        long held = runs.insertRun(job.name(), "research", "claude", "x", "2026-11-17T08:00:00+00:00", "held", false, null,
                "quick", null, null);
        e.releaseHeld();
        assertThat(run(held).get("status")).isEqualTo("held");
    }

    @Test // (written from jobs.start_claude: a box report brings its gathered data into the task text)
    void aClaudeBoxReportPutsTheGatheredDataInTheTaskAndStartsNothing() throws Exception {
        Job job = JobFixture.parse("rep", "---\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: claude\n---\nReport.\n");
        fx.overview = "DISK 91%";
        long id = insertRun(job, "queued");
        engine().startClaude(id, job);
        assertThat(taskOfRun(id).str("text")).contains("DISK 91%").contains("Eindig met ANTWOORD:");
        assertThat(taskOfRun(id).str("repo")).isEqualTo("homelab");
        assertThat(taskOfRun(id).status()).isEqualTo("queued");
        assertThat(fx.calls()).containsExactly("boxOverview"); // read, nothing started, nothing sent
    }

    @Test // (written from jobs.run_job: a failure to start marks the run failed and tells the user)
    void aFailureToStartMarksTheRunFailedAndNotifies() throws Exception {
        Job job = JobFixture.parse("rep", "---\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: claude\n---\nReport.\n");
        fx.failure = new IOException("overview down");
        long id = insertRun(job, "queued");
        engine().runJob(id, job);
        assertThat(run(id).get("status")).isEqualTo("failed");
        assertThat(fx.pushes.get(0).title()).isEqualTo("Job rep mislukt");
    }

    // ---- gather ----

    @Test // (written from jobs.gather: a digest fails only when every source fails)
    void aDigestFailsOnlyWhenEverySourceFails() throws Exception {
        Job digest = JobFixture.parse("dg", "---\nschedule: \"0 7 * * *\"\nkind: digest\nmodel: local\n"
                + "sources: [https://a.example/feed, https://b.example/x]\n---\nSummarise.\n");
        fx.pageText = "<p>hello</p>";
        assertThat(engine().gather(digest, 1)).contains("## https://a.example/feed\nhello").contains("## https://b.example/x");
        fx.failure = new IOException("down");
        long id = insertRun(digest, "queued");
        engine().runLocal(id, digest);
        assertThat(run(id).get("status")).isEqualTo("failed");
        assertThat((String) run(id).get("note")).contains("geen enkele bron");
    }

    @Test // (written from jobs.gather: a script that did not run (code 65) is an error, any other exit code is output)
    void aScriptThatDidNotRunIsAnErrorAndOtherExitCodesAreOutput() throws Exception {
        Job script = JobFixture.parse("sc", "---\nschedule: \"0 7 * * *\"\nkind: script\nmodel: local\nscript: scripts/a.sh\nargs: [-v]\n---\nS.\n");
        fx.script = new Effects.ScriptResult(2, "failed output");
        assertThat(engine().gather(script, 1)).isEqualTo("$ scripts/a.sh -v\n(exitcode 2)\n\nfailed output");
        fx.script = new Effects.ScriptResult(65, "bad path");
        long id = insertRun(script, "queued");
        engine().runLocal(id, script);
        assertThat(run(id).get("status")).isEqualTo("failed");
        assertThat(run(id).get("note")).isEqualTo("bad path");
    }

    @Test // (written from jobs.gather: the repo watcher looks back from the day of the last ok run)
    void theRepoWatcherLooksBackFromTheDayOfTheLastOkRun() throws Exception {
        Job watch = JobFixture.parse("w", "---\nschedule: \"0 7 * * *\"\nkind: repo-watch\nmodel: local\nsources: [github:o/r]\n---\nW.\n");
        runs.insertRun("w", "repo-watch", "local", "x", "2026-11-10T08:00:00+00:00", "ok", false, null, null, null, null);
        long id = insertRun(watch, "queued");
        String text = engine().gather(watch, id);
        assertThat(text).startsWith("## GitHub o/r, sinds 2026-11-09").contains("geen activiteit");
        assertThat(fx.calls()).contains("github /repos/o/r/commits?since=2026-11-09T23:00:00Z&per_page=30");
    }

    // ---- the history ----

    @Test // (written from jobs.run_view: a Claude run shows the state of its task, a local run its own)
    void aClaudeRunShowsItsTaskAndALocalRunItsOwnState() throws Exception {
        Job job = JobFixture.research("");
        long id = insertRun(job, "queued");
        engine().startClaude(id, job);
        Task t = taskOfRun(id);
        store.update(t.id(), TaskStore.fields("status", "pr_open", "pr_url", "http://pr", "summary", "did it"));
        JobEngine.RunView v = engine().runView(run(id));
        assertThat(List.of(v.status(), v.summary(), v.url(), v.prUrl())).containsExactly("pr", "did it", "/task/" + t.id(), "http://pr");
        long local = insertRun(JobFixture.local("demo", "page"), "ok");
        runs.updateRun(local, TaskStore.fields("summary", "all good"));
        assertThat(engine().runView(run(local))).isEqualTo(new JobEngine.RunView("ok", "all good", "/run/" + local, null));
    }

    @Test // (written from jobs.job_of_task: a research question has a made-up job, a file's job is the file's)
    void aResearchQuestionHasAMadeUpJobAndAFileJobIsTheFilesJob() throws Exception {
        Task t = started("discord", "deep");
        Job made = engine().jobOfTask(t).orElseThrow();
        assertThat(List.of(made.name(), made.kind(), made.deliver(), made.mode())).containsExactly("research", "research", "conversation", "deep");
        Job file = daily();
        store.update(t.id(), TaskStore.fields("job", "daily"));
        assertThat(engine().jobOfTask(store.get(t.id()).orElseThrow())).contains(file);
        store.update(t.id(), TaskStore.fields("job", null));
        assertThat(engine().jobOfTask(store.get(t.id()).orElseThrow())).isEmpty();
    }
}
