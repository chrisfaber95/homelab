package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.read.Jobs.Job;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The data of the job pages. Ports of test_inbox_jobs_api.py (GET /api/jobs-page) and test_inbox_job_pages_api.py
 * (/api/job, /api/draft, /api/run, /api/research); the HTTP side of those tests (routes, content type, the frontend
 * index, CSRF and the JSON errors of a failed POST) stays with the front door (K8).
 */
class JobPagesTest {

    private static final String JOB = "---\nname: a-job\nwhen: daily at 7:00\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: local\n"
            + "deliver: ntfy\nskills: []\n---\nReport what failed.\n\nNOTHING TO REPORT when all is well.\n";
    private static final String AGENT = "---\nwhen: daily at 8:00\nschedule: \"0 8 * * *\"\nkind: box-report\nmodel: local\ndeliver: ntfy\n---\n"
            + "Summarise the night.\n\nSecond paragraph.\n\nNOTHING TO REPORT when all is well.\n";

    @TempDir Path agents;
    private ConnectionSource db;
    private final Map<String, Job> jobs = new HashMap<>();
    private JobPages pages;

    private void sql(String statement) throws Exception {
        try (Connection c = db.open(); Statement s = c.createStatement()) {
            s.execute(statement);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        JobFixture.clean(db);
        Files.writeString(agents.resolve("night-digest.md"), AGENT);
        Files.writeString(agents.resolve("broken-agent.md"), "no header at all");
        jobs.clear();
        jobs.put("a-job", JobFixture.parse("a-job", JOB));
        jobs.put("b-broken", new Job("b-broken", "kapot <b>bestand</b>", null, null, null, null, null, false, null, List.of(),
                null, null, List.of(), List.of(), "page", null, ""));
        sql("INSERT INTO drafts (created, request, status) VALUES ('2026-11-16T07:00:00+00:00', 'Eerste regel <x>\nTweede regel', 'drafting')");
        sql("INSERT INTO drafts (created, request, status) VALUES ('2026-11-16T07:01:00+00:00', 'klaar', 'discarded')");
        sql("INSERT INTO job_runs (job, kind, model, due, created, status, summary, manual) VALUES ('a-job', 'box-report', 'local', "
                + "'2026-11-16T06:00:00+00:00', '2026-11-16T06:00:00+00:00', 'ok', 'Alles goed\nregel twee', 1)");
        sql("INSERT INTO job_runs (job, kind, model, due, created, status, mode) VALUES ('research', 'research', 'claude', "
                + "'2026-11-16T05:00:00+00:00', '2026-11-16T05:00:00+00:00', 'queued', 'quick')");
        CoreFixture.MutableClock clock = new CoreFixture.MutableClock("2026-11-18T10:00:00Z");
        JobRuns runs = new JobRuns(db);
        TaskStore store = new TaskStore(db, clock);
        JobEngine engine = new JobEngine(runs, store, JobFixture.ON, JobFixture.SETTINGS, () -> jobs, new JobFixture.Recorder(),
                new JobFixture.Recorder(), new JobFixture.Recorder(), new JobFixture.Recorder(), new JobFixture.Recorder(),
                new Cancellations(), Runnable::run, clock, "http://h:8105");
        pages = new JobPages(engine, runs, JobFixture.SETTINGS, () -> jobs, new AgentCatalogue(agents, JobFixture.parser(), runs, store),
                JobFixture.parser(), clock);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object o) {
        return (List<Map<String, Object>>) o;
    }

    @Test // jobs_api test_shape
    void theShapeIsTheOneOfTheJobsPage() {
        Map<String, Object> d = pages.jobsData();
        assertThat(d.keySet()).containsExactlyInAnyOrder("claude_runs_today", "claude_cap", "timezone", "refresh", "drafts",
                "catalogue", "jobs", "runs");
        assertThat(d.get("claude_cap")).isEqualTo(10);
        assertThat(d.get("timezone")).isEqualTo("Europe/Amsterdam");
    }

    @Test // jobs_api test_jobs_sorted_and_broken_job_kept
    void jobsAreSortedAndABrokenJobIsKept() {
        List<Map<String, Object>> list = list(pages.jobsData().get("jobs"));
        Map<String, Object> a = list.get(0);
        assertThat(List.of(a.get("name"), a.get("kind"), a.get("model"), a.get("when"), a.get("paused")))
                .containsExactly("a-job", "boxrapport", "lokaal", "daily at 7:00", false);
        assertThat((String) a.get("next")).isEqualTo("morgen 07:00"); // 2026-11-18 10:00 UTC is past 07:00 Amsterdam
        assertThat(a.get("last")).isEqualTo("ok");
        assertThat(list.get(1)).isEqualTo(Map.of("name", "b-broken", "error", "kapot <b>bestand</b>"));
    }

    @Test // jobs_api test_catalogue_lists_good_and_broken_agents
    void theCatalogueListsGoodAndBrokenAgents() {
        List<Map<String, Object>> cat = list(pages.jobsData().get("catalogue"));
        Map<String, Object> broken = cat.get(0);
        Map<String, Object> good = cat.get(1);
        assertThat(broken.get("name")).isEqualTo("broken-agent");
        assertThat(broken.get("error")).isNotNull();
        assertThat(broken).doesNotContainKey("on");
        assertThat(List.of(good.get("name"), good.get("on"), good.get("what"), good.get("when"), good.get("model"), good.get("deliver")))
                .containsExactly("night-digest", false, "Summarise the night.", "daily at 8:00", "lokaal", "ntfy");
        assertThat(good.get("fill")).isNull();
    }

    @Test // jobs_api test_agent_with_a_job_of_that_name_is_on
    void anAgentWithAJobOfThatNameIsOn() {
        jobs.put("night-digest", jobs.get("a-job"));
        assertThat(list(pages.jobsData().get("catalogue")).stream().filter(a -> a.get("name").equals("night-digest")).findFirst()
                .orElseThrow().get("on")).isEqualTo(true);
    }

    @Test // jobs_api test_only_open_drafts_with_the_first_line
    void onlyOpenDraftsComeWithTheirFirstLine() {
        Map<String, Object> d = pages.jobsData();
        assertThat(list(d.get("drafts"))).hasSize(1);
        Map<String, Object> draft = list(d.get("drafts")).get(0);
        assertThat(List.of(draft.get("status"), draft.get("request"))).containsExactly("drafting", "Eerste regel <x>");
        assertThat(d.get("refresh")).isEqualTo(true);
    }

    @Test // jobs_api test_runs_newest_first_with_links
    void runsComeNewestFirstWithLinks() {
        List<Map<String, Object>> runs = list(pages.jobsData().get("runs"));
        Map<String, Object> research = runs.get(0);
        Map<String, Object> ok = runs.get(1);
        assertThat(List.of(research.get("job"), research.get("job_url"), research.get("status"), research.get("status_label"),
                research.get("url"), research.get("model"), research.get("manual"), research.get("summary")))
                .containsExactly("research", "/research", "queued", "in de rij", "/run/" + research.get("id"), "Claude", false, "");
        assertThat(List.of(ok.get("job_url"), ok.get("due"), ok.get("manual"), ok.get("summary")))
                .containsExactly("/job/a-job", "16-11 07:00", true, "Alles goed");
        assertThat(ok.get("pr")).isNull();
    }

    @Test // job_pages test_job_detail_has_facts_file_and_history
    void aJobDetailHasFactsFileAndHistory() throws Exception {
        sql("UPDATE job_runs SET status='running', summary='Alles <goed>' WHERE job='a-job'");
        Map<String, Object> d = pages.jobDetail("a-job", JOB).orElseThrow();
        assertThat(List.of(d.get("name"), d.get("paused"), d.get("text"), d.get("refresh"))).containsExactly("a-job", false, JOB, true);
        assertThat(d.get("error")).isNull();
        Map<String, Object> f = map(d.get("facts"));
        assertThat(List.of(f.get("when"), f.get("schedule"), f.get("timezone"), f.get("model"), f.get("deliver")))
                .containsExactly("daily at 7:00", "0 7 * * *", "Europe/Amsterdam", "local", "ntfy");
        assertThat(f.get("mode")).isNull();
        assertThat(f.get("repo")).isNull();
        assertThat(f.get("script")).isNull();
        assertThat((String) f.get("nothing")).contains("NOTHING TO REPORT");
        assertThat((List<?>) f.get("next_times")).hasSize(3);
        assertThat(list(d.get("runs")).stream().map(r -> r.get("job"))).containsExactly("a-job");
        assertThat(list(d.get("runs")).get(0).get("summary")).isEqualTo("Alles <goed>");
    }

    @Test // job_pages test_a_broken_job_has_an_error_and_no_facts_but_its_file
    void aBrokenJobHasAnErrorAndNoFactsButItsFile() {
        Map<String, Object> d = pages.jobDetail("b-broken", "no header").orElseThrow();
        assertThat(d.get("error")).isEqualTo("kapot <b>bestand</b>");
        assertThat(d.get("facts")).isNull();
        assertThat(d.get("text")).isEqualTo("no header");
        assertThat((List<?>) d.get("runs")).isEmpty();
    }

    @Test // job_pages test_unknown_job_is_a_json_404 (the 404 itself is the front door's)
    void anUnknownJobHasNoDetail() {
        assertThat(pages.jobDetail("nope", "")).isEmpty();
        assertThat(pages.draftDetail(99999)).isEmpty();
        assertThat(pages.runDetail(99999)).isEmpty();
    }

    @Test // job_pages test_draft_detail_by_status
    void aDraftDetailDependsOnItsStatus() throws Exception {
        sql("TRUNCATE drafts RESTART IDENTITY");
        sql("INSERT INTO drafts (created, request, base, status, text) VALUES ('2026-11-16T07:00:00+00:00', 'Maak een job', NULL, 'ready', '"
                + JOB.replace("'", "''") + "')");
        sql("INSERT INTO drafts (created, request, status) VALUES ('2026-11-16T07:01:00+00:00', 'wacht', 'drafting')");
        sql("INSERT INTO drafts (created, request, status, error) VALUES ('2026-11-16T07:02:00+00:00', 'stuk', 'failed', 'claude faalde')");
        sql("INSERT INTO drafts (created, request, status, text) VALUES ('2026-11-16T07:03:00+00:00', 'kapot', 'ready', 'geen kop')");
        Map<String, Object> ready = pages.draftDetail(1).orElseThrow();
        assertThat(List.of(ready.get("status"), ready.get("text"))).containsExactly("ready", JOB);
        assertThat(ready.get("text_error")).isNull();
        assertThat(ready.get("refresh")).isEqualTo(false);
        assertThat(map(ready.get("facts")).get("kind")).isEqualTo("boxrapport");
        Map<String, Object> drafting = pages.draftDetail(2).orElseThrow();
        assertThat(drafting.get("facts")).isNull();
        assertThat(drafting.get("refresh")).isEqualTo(true);
        Map<String, Object> failed = pages.draftDetail(3).orElseThrow();
        assertThat(List.of(failed.get("status"), failed.get("error"))).containsExactly("failed", "claude faalde");
        assertThat(failed.get("facts")).isNull();
        Map<String, Object> bad = pages.draftDetail(4).orElseThrow();
        assertThat(bad.get("facts")).isNull();
        assertThat(bad.get("text_error")).isNotNull();
    }

    @Test // job_pages test_run_detail_for_local_and_claude_runs
    void aRunDetailForALocalAndAClaudeRun() throws Exception {
        sql("UPDATE job_runs SET status='running', summary='Alles <goed>', note='een notitie' WHERE job='a-job'");
        Map<String, Object> local = pages.runDetail(1).orElseThrow();
        assertThat(List.of(local.get("job"), local.get("job_url"), local.get("status"), local.get("text"), local.get("note"),
                local.get("cancellable"), local.get("log_key"), local.get("model")))
                .containsExactly("a-job", "/job/a-job", "running", "Alles <goed>", "een notitie", true, "r1", "lokaal");
        Map<String, Object> claude = pages.runDetail(2).orElseThrow();
        assertThat(List.of(claude.get("job_url"), claude.get("status"), claude.get("cancellable"), claude.get("refresh")))
                .containsExactly("/research", "queued", false, true);
        assertThat(claude.get("log_key")).isNull();
        assertThat(claude.get("note")).isNull();
    }

    @Test // job_pages test_research_data + research Page.test_form_offers_the_modes_and_the_counter / asked_question_shows
    void theResearchPageOffersTheModesTheCounterAndTheHistory() {
        Map<String, Object> d = pages.researchData();
        assertThat(d.get("modes")).isEqualTo(List.of("quick", "deep"));
        assertThat(d.get("claude_cap")).isEqualTo(10);
        assertThat(list(d.get("runs")).stream().map(r -> r.get("job"))).containsExactly("research");
        assertThat(d.get("refresh")).isEqualTo(true);
        assertThat(d.get("claude_runs_today")).isEqualTo(0);
    }
}
