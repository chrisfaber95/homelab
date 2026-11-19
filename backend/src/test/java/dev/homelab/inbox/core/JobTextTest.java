package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.read.Jobs.Job;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * The text and the pure rules of research and job delivery. Ports of: test_inbox_research.py (Prompt, report problems),
 * test_inbox_job_delivery.py (Format.page_shows_the_statement, Delivery.marker_only_counts_as_first_line),
 * test_inbox_agents.py (RepoWatcher.since_day tests).
 */
class JobTextTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");

    @Test // Prompt.test_prompt_demands_sources_private_first_and_no_guessing
    void thePromptDemandsSourcesPrivateSearchFirstAndNoGuessing() throws Exception {
        Job job = JobFixture.research("");
        String p = JobText.researchPrompt("quick", job.instruction(), JobFixture.SETTINGS.researchLimits("quick"));
        assertThat(p).contains("Tailscale ACL");
        assertThat(p.indexOf("homelab-search")).isLessThan(p.indexOf("WebSearch"));
        for (String word : new String[] {"link", "bron, plaats en datum", "NIETS GEVONDEN", "## Doorzocht", "Gok niet",
                "geen commit"}) {
            assertThat(p).contains(word);
        }
        assertThat(p).contains("Modus: quick");
    }

    @Test // Prompt.test_quick_is_smaller_than_deep
    void quickIsSmallerThanDeepAndDeepHasTheLimitsOfAnInboxTask() {
        int[] quick = JobFixture.SETTINGS.researchLimits("quick");
        int[] deep = JobFixture.SETTINGS.researchLimits("deep");
        assertThat(quick[0]).isLessThan(deep[0]);
        assertThat(quick[1]).isLessThan(deep[1]);
        assertThat(deep).containsExactly(30, 80);
        assertThat(JobText.researchPrompt("quick", "q", quick)).contains("5");
        assertThat(JobText.researchPrompt("deep", "q", deep)).contains("30 minuten, 80 beurten");
    }

    @Test // Prompt.test_report_problems
    void aReportIsCheckedForSourcesAndForWhatWasSearched() {
        assertThat(Reports.problems("## Antwoord\nx\n## Bronnen\n- y\n## Doorzocht\n- z")).isEmpty();
        assertThat(Reports.problems("NIETS GEVONDEN\n\n## Doorzocht\n- z")).isEmpty();
        assertThat(Reports.problems("## Antwoord\nx\n## Doorzocht\n- z")).contains("no sources");
        assertThat(Reports.problems("NIETS GEVONDEN")).contains("no list of what was searched");
    }

    @Test // Finish.test_report_has_mode_and_time_and_sources (the header line)
    void theReportHeaderHasTheModeAndTheTimeTaken() {
        assertThat(Reports.header("deep", 0)).isEqualTo("Research report | mode: deep | time taken: 0 s");
        assertThat(Reports.header("quick", 125)).isEqualTo("Research report | mode: quick | time taken: 2 min 5 s");
        assertThat(Reports.header("quick", -4)).endsWith("0 s");
    }

    @Test // Delivery.test_marker_only_counts_as_first_line
    void theMarkerOnlyCountsAsTheFirstLine() {
        assertThat(JobText.isNothing("  **Nothing to report**.\n")).isTrue();
        assertThat(JobText.isNothing("NOTHING TO REPORT\nall quiet")).isTrue();
        assertThat(JobText.isNothing("Disk full.\nNOTHING TO REPORT else")).isFalse();
        assertThat(JobText.isNothing("")).isFalse();
        assertThat(JobText.isNothing(null)).isFalse();
    }

    @Test // Format.test_page_shows_the_statement
    void theStatementOfNothingToReportIsTheLineOfTheInstruction() throws Exception {
        Job j = JobFixture.local("demo", "conversation");
        assertThat(JobText.nothingStatement(j)).contains("NOTHING TO REPORT");
        assertThat(JobText.nothingStatement(JobFixture.parse("demo", "---\nschedule: '0 7 * * *'\nkind: box-report\n"
                + "model: local\n---\nReport what failed.\n"))).isEmpty();
    }

    @Test // RepoWatcher.test_since_is_the_start_of_the_previous_runs_day
    void sinceIsTheStartOfThePreviousRunsDay() {
        String got = JobText.sinceDay("2026-11-12T15:30:00+00:00", Instant.parse("2026-11-14T08:00:00Z"), AMS, 31);
        assertThat(got).isEqualTo("2026-11-11T23:00:00Z"); // 2026-11-12 00:00 in Amsterdam
        assertThat(Instant.parse(got).atZone(AMS).toLocalDateTime().toString()).isEqualTo("2026-11-12T00:00");
    }

    @Test // RepoWatcher.test_first_run_starts_yesterday_and_the_look_back_is_capped
    void theFirstRunStartsYesterdayAndTheLookBackIsCapped() {
        Instant now = Instant.parse("2026-11-14T08:00:00Z");
        assertThat(Instant.parse(JobText.sinceDay(null, now, AMS, 31)).atZone(AMS).toLocalDate().toString())
                .isEqualTo("2026-11-13");
        assertThat(JobText.sinceDay("2025-01-01T00:00:00+00:00", now, AMS, 31).substring(0, 10))
                .isGreaterThanOrEqualTo("2026-10-13");
    }

    @Test // (written from jobs.job_prompt: Python has no test for the prompts of the other kinds)
    void aClaudeJobPromptSaysWhatKindOfRunItIsAndHowToEnd() throws Exception {
        Job report = JobFixture.local("demo", "page");
        String p = JobText.jobPrompt(report, "THE DATA", JobFixture.SETTINGS.researchLimits("quick"));
        assertThat(p).startsWith("Dit is de geplande job 'demo' (boxrapport)").contains("THE DATA").contains("ANTWOORD:");
        Job script = JobFixture.parse("run-it", "---\nschedule: '0 7 * * *'\nkind: script\nmodel: claude\nscript: scripts/a.sh\n"
                + "args: [x, y]\n---\nSummarise.\n");
        assertThat(JobText.jobPrompt(script, "", new int[] {6, 25})).contains("`scripts/a.sh x y`");
        Job r = JobFixture.research("skills: []\n");
        assertThat(JobText.jobPrompt(r, "", new int[] {6, 25})).startsWith("Dit is een onderzoeksrun");
    }

    @Test // (written from jobs.html_text)
    void htmlBecomesPlainTextWithoutScriptsAndNavigation() {
        assertThat(JobText.htmlText("<nav>menu</nav><script>x()</script><h1>Title &amp; more</h1><p>one\n two</p>"))
                .isEqualTo("Title & more one two");
    }
}
