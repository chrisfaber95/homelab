package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.RouteAnswer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@code router.run_route} makes of a finished {@code runner/route.py}: the last {@code result} event of its NDJSON
 * output, or an error. The process itself (start, deadline, arguments) is the runner's contract and stays Python's
 * ({@code tests/test_runner_route.py}); this is the reading side the Java router needs.
 */
public final class RouteRunnerOutput {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RouteRunnerOutput() {}

    /** The result event of {@code stdout}, read from the last line backwards; a non-JSON line is skipped. */
    public static RouteAnswer parse(String stdout, int exitCode, String stderr) {
        String[] lines = (stdout == null ? "" : stdout).split("\\R");
        for (int i = lines.length - 1; i >= 0; i--) {
            JsonNode ev;
            try {
                ev = JSON.readTree(lines[i]);
            } catch (RuntimeException e) {
                continue;
            }
            if (ev != null && ev.isObject() && "result".equals(ev.path("event").asString(""))) {
                String own = ev.path("stderr").asString("");
                return new RouteAnswer(ev.path("text").asString(""), !ev.has("is_error") || ev.path("is_error").asBoolean(true),
                        own.isEmpty() ? strip(stderr) : own);
            }
        }
        String err = strip(stderr);
        return new RouteAnswer("", true, err.isEmpty() ? "de route-runner gaf geen resultaat" : err);
    }

    /** The answer when the runner took longer than the deadline. */
    public static RouteAnswer timedOut(int seconds) {
        return new RouteAnswer("", true, "de router duurde langer dan " + seconds + " s");
    }

    private static String strip(String s) {
        return s == null ? "" : s.strip();
    }
}
