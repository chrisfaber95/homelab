package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.core.Effects.RouteAnswer;
import org.junit.jupiter.api.Test;

/** Reads the output of {@code runner/route.py} like {@code router.run_route}; ports {@code test_runner_route.RouterUsesRunner}. */
class RouteRunnerOutputTest {

    /** test_run_route_returns_the_runner_result (and test_recorded_route_gives_one_result_line: one v1 line) */
    @Test
    void theResultLineGivesTextAndErrorFlag() {
        RouteAnswer a = RouteRunnerOutput.parse(
                "{\"event\":\"result\",\"v\":1,\"text\":\"fake claude\",\"is_error\":false,\"exit_code\":0,\"raw\":null}\n", 0, "");
        assertThat(a).isEqualTo(new RouteAnswer("fake claude", false, ""));
    }

    /** test_exit_code_passes_through_and_marks_an_error */
    @Test
    void anErrorResultStaysAnError() {
        RouteAnswer a = RouteRunnerOutput.parse("{\"event\":\"result\",\"text\":\"\",\"is_error\":true,\"exit_code\":1}", 1, "");
        assertThat(a.error()).isTrue();
    }

    /** test_warning_line_after_the_json_does_not_break_the_parse (a line after, or before, the result is skipped) */
    @Test
    void linesThatAreNotTheResultAreSkipped() {
        RouteAnswer a = RouteRunnerOutput.parse("{\"event\":\"log\",\"text\":\"x\"}\n"
                + "{\"event\":\"result\",\"text\":\"model unavailable\",\"is_error\":true}\n[claude-code:unrecognized_model] {}\n", 0, "");
        assertThat(a.text()).isEqualTo("model unavailable");
        assertThat(a.error()).isTrue();
    }

    /** test_no_json_is_an_error_with_stderr, and test_run_route_without_a_result_line_is_an_error */
    @Test
    void noResultLineIsAnErrorWithTheStderrOfTheRunner() {
        RouteAnswer a = RouteRunnerOutput.parse("boom\n", 99, "oops\n");
        assertThat(a).isEqualTo(new RouteAnswer("", true, "oops"));
        assertThat(RouteRunnerOutput.parse("", 2, "").stderr()).isEqualTo("de route-runner gaf geen resultaat");
    }

    /** the stderr of the result event wins over the process's; the deadline message is Python's */
    @Test
    void theResultsOwnStderrWinsAndATimeoutHasItsMessage() {
        assertThat(RouteRunnerOutput.parse("{\"event\":\"result\",\"text\":\"\",\"is_error\":true,\"stderr\":\"inner\"}", 1,
                "outer").stderr()).isEqualTo("inner");
        assertThat(RouteRunnerOutput.timedOut(300)).isEqualTo(new RouteAnswer("", true, "de router duurde langer dan 300 s"));
    }
}
