package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The reading side of {@code runner/draft.py} ({@code jobs.make_draft}). Ports {@code tests/test_runner_draft.py}
 * {@code MakeDraftUsesRunner} and the result-line half of {@code Draft}; the runner's own contract (arguments, exit 2,
 * no prompt or token in it) stays Python's, like {@code test_runner_contract}.
 */
class DraftRunnerOutputTest {

    private static String result(boolean isError, String text) {
        return "{\"event\":\"result\",\"v\":1,\"is_error\":" + isError + ",\"text\":" + quote(text) + "}\n";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\"";
    }

    /** MakeDraftUsesRunner.test_make_draft_reads_the_runner_result */
    @Test
    void theJobFileComesOutOfTheJobBlock() {
        assertThat(DraftRunnerOutput.jobFile(result(false, "ok\n```job\nname: x\n```\n"), 0, "")).isEqualTo("name: x\n");
    }

    /** Draft.test_warning_line_after_the_json_is_ignored */
    @Test
    void aWarningLineIsSkipped() {
        String out = result(false, "```job\nx\n```") + "[claude-code:unrecognized_model]\n";
        assertThat(DraftRunnerOutput.jobFile(out, 0, "")).isEqualTo("x\n");
    }

    /** Draft.test_claude_error_and_exit_code */
    @Test
    void aClaudeErrorFailsTheDraftWithWhatClaudeSaid() {
        assertThatThrownBy(() -> DraftRunnerOutput.jobFile(result(true, "kapot"), 1, "warn\n"))
                .isInstanceOf(IllegalStateException.class).hasMessage("Claude: kapot");
    }

    /** Draft.test_no_json_keeps_exit_code_and_tail */
    @Test
    void noJsonAtAllFailsWithTheOutput() {
        assertThatThrownBy(() -> DraftRunnerOutput.jobFile("", 7, "boom\n")).hasMessage("Claude: boom");
    }

    @Test
    void theErrorTextIsTheLast300Characters() {
        String long400 = "x".repeat(100) + "y".repeat(300);
        assertThatThrownBy(() -> DraftRunnerOutput.jobFile(result(true, long400), 1, ""))
                .hasMessage("Claude: " + "y".repeat(300));
    }

    @Test
    void aResultWithoutAJobBlockIsAFailure() {
        assertThatThrownBy(() -> DraftRunnerOutput.jobFile(result(false, "geen blok"), 0, ""))
                .hasMessage("Claude gaf geen job-bestand terug");
    }

    @Test
    void theLastResultEventCounts() {
        String out = result(true, "first") + result(false, "```job\nb\n```");
        assertThat(DraftRunnerOutput.jobFile(out, 0, "")).isEqualTo("b\n");
    }

    @Test
    void thePromptIsANewJobOrAChangeOfAnExistingOne() {
        assertThat(DraftRunnerOutput.prompt("doe iets", null, null))
                .isEqualTo("Maak een job uit deze beschrijving van de gebruiker:\n\ndoe iets");
        assertThat(DraftRunnerOutput.prompt("sneller", "daily", "name: daily\n"))
                .isEqualTo("Pas deze bestaande job aan (houd de naam daily):\n\nname: daily\n\n\nGevraagde wijziging:\n\nsneller");
        assertThat(DraftRunnerOutput.prompt("sneller", "gone", null)).startsWith("Maak een job");
    }
}
