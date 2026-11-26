package dev.homelab.inbox.core;

import dev.homelab.inbox.core.CommandRunner.Command;
import dev.homelab.inbox.core.CommandRunner.Finished;
import dev.homelab.inbox.core.Effects.BriefClaude;
import dev.homelab.inbox.core.Effects.ClaudeAnswer;
import dev.homelab.inbox.core.Effects.RouteAnswer;
import dev.homelab.inbox.core.Effects.RouteClaude;
import dev.homelab.inbox.read.Settings;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The Claude calls without tools: the router ({@code runner/route.py}), the brief planner ({@code homelab-inbox-task plan})
 * and the brief reviewer ({@code runner/review.py}). Each writes {@code share/r<id>} or {@code share/v<id>} and removes it
 * afterwards, as the Python {@code route_task}, {@code plan_task} and {@code review_pr} do.
 */
final class LiveClaude implements RouteClaude, BriefClaude {

    private static final Duration ROUTE_TIMEOUT = Duration.ofSeconds(300);
    private static final Duration PLAN_TIMEOUT = Duration.ofSeconds(900);
    private static final Duration REVIEW_TIMEOUT = Duration.ofSeconds(900);

    private final CommandRunner commands;
    private final Settings conf;
    private final Runner runner;

    /** What the runner scripts and {@code homelab-inbox-task} need: the commands (see {@link LiveEffects}). */
    interface Runner {
        List<String> taskCmdBase();

        Path runnerDir();

        Effects.Result taskCmd(Duration timeout, Object... args);
    }

    LiveClaude(CommandRunner commands, Settings conf, Runner runner) {
        this.commands = commands;
        this.conf = conf;
        this.runner = runner;
    }

    private Path share(String id) {
        return conf.runnerHome().resolve("share").resolve(id);
    }

    /** {@code helpers.run_runner}'s result event of a runner script, or null; the last {@code result} line wins. */
    private JsonNode resultEvent(String stdout) {
        JsonNode found = null;
        for (String line : stdout.split("\n")) {
            try {
                JsonNode ev = Gh.JSON.readTree(line);
                if (ev.isObject() && "result".equals(ev.path("event").asString(""))) {
                    found = ev;
                }
            } catch (RuntimeException e) {
                // not JSON: ignored
            }
        }
        return found;
    }

    private Finished runScript(String script, long taskId, Duration timeout) {
        return commands.run(Command.of("python3", "-I", runner.runnerDir().resolve(script).toString(), String.valueOf(taskId))
                .withEnv(Map.of("TASK_CMD", String.join(" ", runner.taskCmdBase()))).withTimeout(timeout));
    }

    @Override
    public RouteAnswer route(long taskId, String system, String prompt) {
        Path dir = share("r" + taskId);
        try {
            Share.write(dir, prompt, system);
            Finished o = runScript("route.py", taskId, ROUTE_TIMEOUT);
            if (o.timedOut()) {
                return new RouteAnswer("", true, "de router duurde langer dan " + ROUTE_TIMEOUT.toSeconds() + " s");
            }
            JsonNode ev = resultEvent(o.stdout());
            if (ev == null) {
                String err = o.stderr().strip();
                return new RouteAnswer("", true, err.isEmpty() ? "de route-runner gaf geen resultaat" : err);
            }
            String stderr = ev.path("stderr").asString("");
            return new RouteAnswer(ev.path("text").asString(""), ev.path("is_error").asBoolean(true),
                    stderr.isEmpty() ? o.stderr().strip() : stderr);
        } finally {
            Share.remove(dir);
        }
    }

    /** The planner: {@code claude_result} of {@code homelab-inbox-task plan r<id>}, its own action for its own model. */
    @Override
    public ClaudeAnswer plan(long taskId, String system, String prompt) {
        Path dir = share("r" + taskId);
        try {
            Share.write(dir, prompt, system);
            Effects.Result r = runner.taskCmd(PLAN_TIMEOUT, "plan", "r" + taskId);
            JsonNode result = claudeResult(r.out());
            boolean error = r.code() != 0 || result.path("is_error").asBoolean(false);
            return new ClaudeAnswer(!error, result.path("result").asString(""), r.out());
        } finally {
            Share.remove(dir);
        }
    }

    @Override
    public ClaudeAnswer review(long taskId, String system, String prompt) {
        Path dir = share("v" + taskId);
        try {
            Share.write(dir, prompt, system);
            Finished o = runScript("review.py", taskId, REVIEW_TIMEOUT);
            if (o.timedOut()) {
                return new ClaudeAnswer(false, "", "timed out after " + REVIEW_TIMEOUT.toSeconds() + " s");
            }
            JsonNode ev = resultEvent(o.stdout());
            if (ev == null) {
                return new ClaudeAnswer(false, "", tail(o.stdout() + o.stderr(), 1000));
            }
            return new ClaudeAnswer(ev.path("ok").asBoolean(false), ev.path("text").asString(""),
                    ev.path("detail").asString(""));
        } finally {
            Share.remove(dir);
        }
    }

    private static String tail(String s, int n) {
        String t = s.strip();
        return t.length() > n ? t.substring(t.length() - n) : t;
    }

    /** {@code helpers.claude_result}: the first JSON object after the first {@code {}; a warning line after it is ignored. */
    static JsonNode claudeResult(String out) {
        int start = out.indexOf('{');
        if (start < 0) {
            return tools.jackson.databind.node.MissingNode.getInstance();
        }
        try {
            JsonNode n = Gh.JSON.reader().without(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(out.substring(start));
            return n.isObject() ? n : tools.jackson.databind.node.MissingNode.getInstance();
        } catch (RuntimeException e) {
            return tools.jackson.databind.node.MissingNode.getInstance();
        }
    }
}
