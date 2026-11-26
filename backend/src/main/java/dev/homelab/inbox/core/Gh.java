package dev.homelab.inbox.core;

import dev.homelab.inbox.core.CommandRunner.Command;
import dev.homelab.inbox.core.CommandRunner.Finished;
import dev.homelab.inbox.core.Effects.GitHubHttpException;
import dev.homelab.inbox.core.Effects.PullRequests.Opened;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

/** The GitHub calls of the live effects, all through the {@code gh} CLI and the {@link CommandRunner} ({@code helpers.gh}). */
final class Gh {

    static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Pattern HTTP_CODE = Pattern.compile("HTTP (\\d{3})");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final CommandRunner commands;
    private final Settings conf;

    Gh(CommandRunner commands, Settings conf) {
        this.commands = commands;
        this.conf = conf;
    }

    Map<String, String> env() {
        try {
            return Map.of("GH_TOKEN", Files.readString(conf.ghTokenFile()).strip(), "GH_PROMPT_DISABLED", "1");
        } catch (IOException e) {
            return Map.of("GH_PROMPT_DISABLED", "1");
        }
    }

    private static int httpCode(String text, int fallback) {
        Matcher m = HTTP_CODE.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    private JsonNode api(String method, String path, Object body) {
        List<String> cmd = new ArrayList<>(List.of("gh", "api", "-X", method, path));
        Command c = Command.of(cmd).withEnv(env()).withTimeout(TIMEOUT);
        if (body != null) {
            cmd.addAll(List.of("--input", "-"));
            c = Command.of(cmd).withEnv(env()).withTimeout(TIMEOUT).withStdin(JSON.writeValueAsString(body));
        }
        Finished o = commands.run(c);
        if (o.code() != 0) {
            throw new GitHubHttpException(httpCode(o.combined(), 500), "https://api.github.com" + path, o.combined());
        }
        String out = o.stdout().strip();
        return out.isEmpty() ? MissingNode.getInstance() : JSON.readTree(out);
    }

    /** {@code gh("GET", path)}: read only. */
    JsonNode get(String path) {
        return api("GET", path, null);
    }

    JsonNode post(String path, Object body) {
        return api("POST", path, body);
    }

    /** A merge commit, only for the head the caller checked ({@code gh pr merge --merge --match-head-commit}). */
    void merge(String slug, int number, String sha, String title) {
        List<String> cmd = new ArrayList<>(List.of("gh", "pr", "merge", String.valueOf(number), "--repo", slug, "--merge",
                "--subject", title));
        if (sha != null && !sha.isEmpty()) {
            cmd.addAll(List.of("--match-head-commit", sha));
        }
        Finished o = commands.run(Command.of(cmd).withEnv(env()).withTimeout(TIMEOUT));
        if (o.code() != 0) {
            throw new GitHubHttpException(httpCode(o.combined(), 405),
                    "https://api.github.com/repos/" + slug + "/pulls/" + number + "/merge", o.combined());
        }
    }

    Opened createPr(String slug, String title, String head, String base, String body) {
        Finished o = commands.run(Command.of("gh", "pr", "create", "--repo", slug, "--title", title, "--head", head,
                "--base", base, "--body", body).withEnv(env()).withTimeout(TIMEOUT));
        if (o.code() != 0) {
            throw new IllegalStateException("gh pr create: " + o.combined());
        }
        String url = o.stdout().strip().lines().reduce((a, b) -> b).orElse("");
        Matcher m = Pattern.compile("/pull/(\\d+)$").matcher(url);
        if (!m.find()) {
            throw new IllegalStateException("gh pr create gave no PR url: " + o.combined());
        }
        return new Opened(Integer.parseInt(m.group(1)), url);
    }
}
