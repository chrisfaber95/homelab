package dev.homelab.inbox.core;

import dev.homelab.inbox.core.CommandRunner.Command;
import dev.homelab.inbox.core.CommandRunner.Finished;
import dev.homelab.inbox.core.Effects.JobIo;
import dev.homelab.inbox.core.Effects.ScriptResult;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** What a job run reads from the outside world ({@code jobs.gather}, {@code fetch_source}, {@code call_llm}). */
final class LiveJobIo implements JobIo {

    private static final int PAGE_BYTES = 2_000_000;

    private final CommandRunner commands;
    private final Settings conf;
    private final ServiceGit git;
    private final Gh gh;
    private final LiveClaude.Runner runner;

    LiveJobIo(CommandRunner commands, Settings conf, ServiceGit git, Gh gh, LiveClaude.Runner runner) {
        this.commands = commands;
        this.conf = conf;
        this.git = git;
        this.gh = gh;
        this.runner = runner;
    }

    @Override
    public String boxOverview() throws IOException {
        String cmd = conf.env("OVERVIEW_CMD");
        List<String> args = List.of((cmd == null || cmd.isBlank() ? "/usr/local/bin/homelab-overview" : cmd).strip().split("\\s+"));
        Finished o = commands.run(Command.of(args).withTimeout(Duration.ofSeconds(60)));
        if (o.code() != 0) {
            String why = (o.stderr().isBlank() ? o.stdout() : o.stderr()).strip();
            throw new IOException("homelab-overview: " + (why.length() > 300 ? why.substring(why.length() - 300) : why));
        }
        return o.stdout();
    }

    @Override
    public String fetchPage(String url) throws IOException {
        Finished o = commands.run(Command.of("curl", "-sSL", "--fail", "-m", "20", "-A", "homelab-jobs/1", url)
                .withTimeout(Duration.ofSeconds(30)));
        if (o.code() != 0) {
            throw new IOException(o.combined());
        }
        return o.stdout().length() > PAGE_BYTES ? o.stdout().substring(0, PAGE_BYTES) : o.stdout();
    }

    @Override
    public Object github(String path) throws IOException {
        try {
            JsonNode n = gh.get(path);
            return n.isMissingNode() ? List.of() : Gh.JSON.treeToValue(n, Object.class);
        } catch (RuntimeException e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    @Override
    public ScriptResult runScript(String repo, String script, List<String> args, long runId) throws IOException {
        String slug = git.repos().get(repo);
        if (slug == null) {
            throw new IOException("onbekende repo " + repo);
        }
        git.refresh(repo, slug);
        List<Object> cmd = new ArrayList<>(List.of("script", "j" + runId, repo, script));
        cmd.addAll(args);
        int minutes = conf.confInt("JOB_SCRIPT_MINUTES", 10);
        Effects.Result r = runner.taskCmd(Duration.ofMinutes(minutes + 1), cmd.toArray());
        return new ScriptResult(r.code(), r.out());
    }

    @Override
    public String summarize(String jobName, String instruction, String text) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", "summarize");
        body.put("user", "job:" + jobName);
        body.put("timeout", 120);
        body.put("messages", List.of(Map.of("role", "system", "content", instruction), Map.of("role", "user", "content", text)));
        Finished o = commands.run(Command.of("curl", "-sS", "--fail-with-body", "-m", "130", "-X", "POST",
                "-H", "Content-Type: application/json", "-H", "X-LLM-Caller: job:" + jobName, "--data-binary", "@-",
                conf.llmUrl() + "/v1/chat/completions").withStdin(Gh.JSON.writeValueAsString(body))
                .withTimeout(Duration.ofSeconds(140)));
        if (o.code() == 22) { // an HTTP error: the body says why
            String b = o.stdout();
            throw new IOException("LLM-helper: " + (b.length() > 300 ? b.substring(0, 300) : b));
        }
        if (o.code() != 0) {
            throw new IOException(o.combined());
        }
        try {
            return Gh.JSON.readTree(o.stdout()).path("choices").path(0).path("message").path("content").asString("").strip();
        } catch (RuntimeException e) {
            throw new IOException("LLM-helper: geen geldig antwoord", e);
        }
    }
}
