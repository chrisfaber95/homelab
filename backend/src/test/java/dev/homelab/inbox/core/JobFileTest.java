package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.Jobs.Job;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The job file as the core reads it: the research and delivery fields. Ports of test_inbox_research.py (Format) and
 * test_inbox_job_delivery.py (Format). The other fields of the file are covered by the M1 and M6 tests. */
class JobFileTest {

    private static final String HEAD = "---\nwhen: daily\nschedule: \"0 7 * * *\"\nkind: box-report\nmodel: local\n%s---\n%s\n";

    private static Job report(String extra, String body) throws Exception {
        return JobFixture.parse("demo", HEAD.formatted(extra, body));
    }

    @Test // research Format.test_default_mode_is_quick_and_deep_is_allowed
    void theDefaultModeIsQuickAndDeepIsAllowed() throws Exception {
        assertThat(JobFixture.research("").mode()).isEqualTo("quick");
        assertThat(JobFixture.research("mode: deep\n").mode()).isEqualTo("deep");
    }

    @Test // research Format.test_bad_mode_local_model_and_mode_elsewhere_are_errors
    void aBadModeALocalModelAndAModeElsewhereAreErrors() {
        assertThatThrownBy(() -> JobFixture.research("mode: slow\n")).hasMessageContaining("mode moet");
        assertThatThrownBy(() -> JobFixture.parse("x", JobFixture.RESEARCH_FILE.formatted("").replace("model: claude", "model: local")))
                .hasMessageContaining("op Claude");
        assertThatThrownBy(() -> JobFixture.parse("x", "---\nschedule: '0 7 * * *'\nkind: box-report\nmodel: local\nmode: deep\n---\nhi\n"))
                .hasMessageContaining("alleen voor kind: research");
    }

    @Test // research Format.test_reserved_name
    void theNameResearchIsReserved() {
        assertThatThrownBy(() -> JobFixture.parse("research", JobFixture.RESEARCH_FILE.formatted("")))
                .hasMessageContaining("naam research");
    }

    @Test // research Format.test_report_needs_no_nothing_marker_to_deliver
    void aResearchReportNeedsNoNothingMarkerToDeliver() throws Exception {
        assertThat(JobFixture.research("deliver: conversation\n").deliver()).isEqualTo("conversation");
    }

    @Test // research Format.test_old_job_kinds_unchanged
    void oldJobKindsHaveNoMode() throws Exception {
        assertThat(JobFixture.parse("x", "---\nschedule: '0 7 * * *'\nkind: box-report\nmodel: local\n---\nhi\n").mode()).isNull();
    }

    @Test // delivery Format.test_default_is_page_and_old_files_parse
    void theDefaultDeliveryIsThePage() throws Exception {
        assertThat(report("", "Report what failed.").deliver()).isEqualTo("page");
    }

    @Test // delivery Format.test_values
    void ntfyConversationAndPageAreAllowed() throws Exception {
        for (String v : List.of("ntfy", "conversation")) {
            assertThat(report("deliver: " + v + "\n", JobFixture.NOTHING).deliver()).isEqualTo(v);
        }
        assertThat(report("deliver: page\n", "Report what failed.").deliver()).isEqualTo("page");
    }

    @Test // delivery Format.test_unknown_value_is_an_error
    void anUnknownDeliveryIsAnError() {
        assertThatThrownBy(() -> report("deliver: mail\n", JobFixture.NOTHING)).hasMessageContaining("deliver moet");
    }

    @Test // delivery Format.test_delivering_job_must_define_nothing_to_report
    void aDeliveringJobMustSayWhatNothingToReportIs() {
        assertThatThrownBy(() -> report("deliver: ntfy\n", "Report what failed.")).hasMessageContaining("NOTHING TO REPORT");
    }

    @Test // agents FirstAgents.test_sources_start_empty_and_are_asked_for (the parser side)
    void aDigestWithoutSourcesNeedsInputAndCarriesItsJobSoFar() {
        String text = "---\nschedule: '0 7 * * *'\nkind: digest\nmodel: local\nsources: []\n---\nSummarise.\n";
        assertThatThrownBy(() -> JobFixture.parse("dg", text)).isInstanceOf(Jobs.NeedsInput.class)
                .satisfies(e -> assertThat(((Jobs.NeedsInput) e).job().sources()).isEmpty())
                .hasMessageContaining("sources");
    }

    @Test // agents FirstAgents.test_watcher_takes_only_github_repos (the parser side)
    void aRepoWatcherTakesOnlyGithubRepos() throws Exception {
        String head = "---\nschedule: '0 7 * * *'\nkind: repo-watch\nmodel: local\nsources: [%s]\n---\nW.\n";
        assertThat(JobFixture.parse("w", head.formatted("github:chrisfaber95/homelab")).kind()).isEqualTo("repo-watch");
        assertThatThrownBy(() -> JobFixture.parse("w", head.formatted("https://example.org/x"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test // (written from jobs.parse_job: a script job defaults to the homelab repo, its command shows script and args)
    void aScriptJobDefaultsToTheHomelabRepo() throws Exception {
        Job j = JobFixture.parse("sc", "---\nschedule: '0 7 * * *'\nkind: script\nmodel: local\nscript: scripts/a.sh\nargs: [x]\n---\nS.\n");
        assertThat(j.repo()).isEqualTo("homelab");
        assertThat(j.args()).containsExactly("x");
    }
}
