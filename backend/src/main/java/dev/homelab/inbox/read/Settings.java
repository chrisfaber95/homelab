package dev.homelab.inbox.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * What the read routes need to know about the box (H-024 M1), read the way the Python inbox reads it
 * ({@code bin/homelab_inbox/config.py}): the {@code *.conf} files of the installed config directory, where an
 * environment variable of the same name overrides a key that is in a file, plus the few variables the Python code
 * reads straight from the environment ({@code INBOX_RUNNER_HOME}, {@code REPOS_CONF}, {@code DEPLOY_STATE},
 * {@code LLM_URL}, {@code CLIENTS_JSON}). Nothing here is written to.
 */
@Component
public class Settings {

    public record Service(String name, String address, String note) {}

    private static final List<String> CONF_FILES =
            List.of("deploy.conf", "admin.conf", "llm.conf", "backup.conf", "inbox.conf");

    private final Path configDir;
    private final Map<String, String> env;
    private final Map<String, String> conf = new LinkedHashMap<>();

    @Autowired
    public Settings(@Value("${inbox.config-dir}") String configDir) {
        this(Path.of(configDir), System.getenv());
    }

    public Settings(Path configDir, Map<String, String> env) {
        this.configDir = configDir;
        this.env = env;
        for (String name : CONF_FILES) {
            Path file = configDir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            for (String raw : lines(file)) {
                String line = raw.strip();
                int eq = line.indexOf('=');
                if (line.isEmpty() || line.startsWith("#") || eq < 0) {
                    continue;
                }
                String key = line.substring(0, eq).strip();
                String value = stripQuotes(line.substring(eq + 1).strip());
                conf.put(key, env.getOrDefault(key, value));
            }
        }
    }

    private static String stripQuotes(String s) {
        int from = 0;
        int to = s.length();
        while (from < to && s.charAt(from) == '"') {
            from++;
        }
        while (to > from && s.charAt(to - 1) == '"') {
            to--;
        }
        return s.substring(from, to);
    }

    private static List<String> lines(Path file) {
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** {@code CONF.get(key, fallback)} */
    public String conf(String key, String fallback) {
        return conf.getOrDefault(key, fallback);
    }

    public int confInt(String key, int fallback) {
        try {
            return Integer.parseInt(conf(key, String.valueOf(fallback)).strip());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Path runnerHome() {
        return Path.of(env.getOrDefault("INBOX_RUNNER_HOME", conf("RUNNER_HOME", "/var/lib/homelab-inbox")));
    }

    public Path inboxDb() {
        return runnerHome().resolve("inbox.db");
    }

    public Path logsDir() {
        return runnerHome().resolve("logs");
    }

    public Path jobsDir() {
        return runnerHome().resolve("jobs-repo").resolve("jobs");
    }

    /** The clone of the {@code memory} branch of DATA_REPO (entries {@code voorkeuren/}, {@code feiten/}, {@code geschiedenis/}). */
    public Path memoryDir() {
        return runnerHome().resolve("memory-repo");
    }

    public String memoryBranch() {
        return conf("MEMORY_BRANCH", "memory");
    }

    public String dataRepo() {
        return conf("DATA_REPO", "");
    }

    public Path skillsDir() {
        return runnerHome().resolve("repos").resolve("homelab").resolve("skills");
    }

    public Path newReposFile() {
        return runnerHome().resolve("new-repos.json");
    }

    public Path reposConf() {
        return Path.of(env.getOrDefault("REPOS_CONF", "/srv/homelab/repos/homelab/config/repos.conf"));
    }

    public Path monitorDir() {
        return Path.of(conf("MONITOR_DIR", "/var/lib/homelab/monitor"));
    }

    public Path deployState() {
        return Path.of(env.getOrDefault("DEPLOY_STATE", "/srv/homelab/state"));
    }

    public Path changesLog() {
        return Path.of(conf("LOG_FILE", "/var/log/homelab/changes.jsonl"));
    }

    public Path backupStatus() {
        return Path.of(conf("BACKUP_STATUS", monitorDir().resolve("backup.json").toString()));
    }

    public ZoneId tz() {
        return ZoneId.of(conf("JOB_TIMEZONE", "Europe/Amsterdam"));
    }

    public String llmUrl() {
        String url = env.get("LLM_URL");
        return url != null ? url : "http://127.0.0.1:" + conf("LLM_PORT", "8017");
    }

    public String forgeUrl() {
        return stripSlash(conf("FORGE_URL", "http://100.85.70.34:8120"));
    }

    public String forgePageUrl() {
        return stripSlash(conf("FORGE_PAGE_URL", forgeUrl()));
    }

    private static String stripSlash(String s) {
        int to = s.length();
        while (to > 0 && s.charAt(to - 1) == '/') {
            to--;
        }
        return s.substring(0, to);
    }

    /** {@code config/clients.json} (or {@code CLIENTS_JSON}) as text, or null when it cannot be read. */
    public String clientsJson() {
        String fromEnv = env.get("CLIENTS_JSON");
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        try {
            return Files.readString(configDir.resolve("clients.json"));
        } catch (IOException e) {
            return null;
        }
    }

    /** {@code config/services.conf}: one {@code name | address | what it is} per line. */
    public List<Service> services() {
        List<Service> out = new ArrayList<>();
        Path file = configDir.resolve("services.conf");
        if (!Files.isRegularFile(file)) {
            return out;
        }
        for (String line : lines(file)) {
            String[] parts = line.split("\\|", -1);
            if (line.strip().startsWith("#") || parts.length != 3) {
                continue;
            }
            String a = parts[0].strip();
            String b = parts[1].strip();
            String c = parts[2].strip();
            if (!a.isEmpty() && !b.isEmpty() && !c.isEmpty()) {
                out.add(new Service(a, b, c));
            }
        }
        return out;
    }
}
