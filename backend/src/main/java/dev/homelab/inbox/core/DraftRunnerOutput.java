package dev.homelab.inbox.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * What {@code jobs.make_draft} makes of a finished {@code runner/draft.py}: the job file out of the {@code job} block of
 * the last {@code result} event, or the failure text the draft then shows. The process itself is the runner's contract
 * and stays Python's ({@code tests/test_runner_draft.py}); this is the reading side, like {@link RouteRunnerOutput}.
 */
public final class DraftRunnerOutput {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern JOB_BLOCK = Pattern.compile("```job\\n(.*?)```", Pattern.DOTALL);

    private DraftRunnerOutput() {}

    /** The text of the job file (ending in a newline). @throws IllegalStateException with the text of {@code error}. */
    public static String jobFile(String stdout, int exitCode, String stderr) {
        JsonNode result = null;
        for (String line : (stdout == null ? "" : stdout).split("\\R")) {
            try {
                JsonNode ev = JSON.readTree(line);
                if (ev != null && ev.isObject() && "result".equals(ev.path("event").asString(""))) {
                    result = ev; // the last one counts
                }
            } catch (RuntimeException e) {
                // a line that is not JSON is skipped
            }
        }
        if (exitCode != 0 || result == null || result.path("is_error").asBoolean(false)) {
            String said = result == null ? "" : result.path("text").asString("");
            if (said.isEmpty() && result != null) {
                said = result.path("output_tail").asString("");
            }
            if (said.isEmpty()) {
                said = ((stdout == null ? "" : stdout) + (stderr == null ? "" : stderr)).strip();
            }
            throw new IllegalStateException("Claude: " + said.substring(Math.max(0, said.length() - 300)));
        }
        Matcher m = JOB_BLOCK.matcher(result.path("text").asString(""));
        if (!m.find()) {
            throw new IllegalStateException("Claude gaf geen job-bestand terug");
        }
        return m.group(1).strip() + "\n";
    }

    /** The prompt of a draft: a new job from the user's description, or a change of an existing job (kept under its name). */
    public static String prompt(String request, String base, String baseText) {
        if (base != null && !base.isEmpty() && baseText != null) {
            return "Pas deze bestaande job aan (houd de naam " + base + "):\n\n" + baseText + "\n\nGevraagde wijziging:\n\n"
                    + request;
        }
        return "Maak een job uit deze beschrijving van de gebruiker:\n\n" + request;
    }
}
