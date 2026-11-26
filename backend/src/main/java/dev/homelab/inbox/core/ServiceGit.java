package dev.homelab.inbox.core;

import dev.homelab.inbox.core.CommandRunner.Command;
import dev.homelab.inbox.core.CommandRunner.Finished;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Git in the service's own copies of the repos ({@code helpers.git}, {@code refresh_cache}, {@code repo_list}): never in a
 * directory of Claude, the token through a credential helper. One lock per repo name: parallel tasks on one repo share
 * its copy.
 */
final class ServiceGit {

    private static final Pattern REPO_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    private final CommandRunner commands;
    private final Settings conf;
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    ServiceGit(CommandRunner commands, Settings conf) {
        this.commands = commands;
        this.conf = conf;
    }

    Settings conf() {
        return conf;
    }

    ReentrantLock lock(String name) {
        return locks.computeIfAbsent(name, k -> new ReentrantLock());
    }

    private String credentialHelper() {
        return "!f() { test \"$1\" = get && echo username=x-access-token && echo \"password=$(cat "
                + conf.ghTokenFile() + ")\"; }; f";
    }

    /** Runs git in {@code dir}; the stdout, or an {@link IllegalStateException} "git &lt;action&gt;: &lt;last 300 chars&gt;". */
    String git(Path dir, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git", "-c", "credential.helper=", "-c",
                "credential.helper=" + credentialHelper(), "-C", dir.toString()));
        cmd.addAll(List.of(args));
        Finished o = commands.run(Command.of(cmd).withEnv(Map.of("GIT_TERMINAL_PROMPT", "0")));
        if (o.code() != 0) {
            String why = (o.stderr().isBlank() ? o.stdout() : o.stderr()).strip();
            throw new IllegalStateException("git " + args[0] + ": "
                    + (why.length() > 300 ? why.substring(why.length() - 300) : why));
        }
        return o.stdout();
    }

    String remoteUrl(String name, String slug) {
        String fake = conf.env("REMOTE_" + name.toUpperCase().replace('-', '_'));
        return fake != null && !fake.isEmpty() ? fake : "https://github.com/" + slug + ".git";
    }

    /** {@code helpers.refresh_cache}: the service's copy of a repo, on origin/main. */
    Path refresh(String repo, String slug) {
        ReentrantLock lock = lock(repo);
        lock.lock();
        try {
            Path d = conf.runnerHome().resolve("repos").resolve(repo);
            if (!Files.isDirectory(d.resolve(".git"))) {
                try {
                    Files.createDirectories(d.getParent());
                } catch (IOException e) {
                    throw new IllegalStateException("clone " + repo + ": " + e.getMessage(), e);
                }
                Finished o = commands.run(Command.of("git", "clone", "--quiet", remoteUrl(repo, slug), d.toString())
                        .withEnv(Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_CONFIG_COUNT", "2",
                                "GIT_CONFIG_KEY_0", "credential.helper", "GIT_CONFIG_VALUE_0", "",
                                "GIT_CONFIG_KEY_1", "credential.helper", "GIT_CONFIG_VALUE_1", credentialHelper())));
                if (o.code() != 0) {
                    throw new IllegalStateException("git clone: " + o.combined());
                }
            }
            git(d, "fetch", "--quiet", "origin", "main");
            git(d, "checkout", "--quiet", "-B", "main", "origin/main");
            return d;
        } finally {
            lock.unlock();
        }
    }

    /** {@code helpers.repo_list}: homelab, the lines of repos.conf and the inbox's own list of new repos. */
    Map<String, String> repos() {
        Map<String, String> repos = new LinkedHashMap<>();
        repos.put("homelab", conf.conf("HOMELAB_REPO", "chrisfaber95/homelab"));
        try {
            for (String line : Files.readAllLines(conf.reposConf())) {
                int hash = line.indexOf('#');
                String[] parts = (hash >= 0 ? line.substring(0, hash) : line).strip().split("\\s+");
                if (parts.length == 3 && REPO_NAME.matcher(parts[0]).matches()) {
                    repos.put(parts[0], parts[1]);
                }
            }
        } catch (IOException e) {
            // no list: only homelab, as Python
        }
        try {
            JsonNode extra = Gh.JSON.readTree(Files.readString(conf.newReposFile()));
            extra.properties().forEach(e -> repos.putIfAbsent(e.getKey(), e.getValue().asString()));
        } catch (IOException | RuntimeException e) {
            // no file, or not JSON: no new repos
        }
        return repos;
    }
}
