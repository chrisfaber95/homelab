package dev.homelab.inbox.core;

import dev.homelab.inbox.conversation.ConversationStore;
import dev.homelab.inbox.core.CommandRunner.Command;
import dev.homelab.inbox.core.CommandRunner.Finished;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real effects (H-028 W1), selected by {@code core.effects=live}: a port of what {@code bin/homelab_inbox/} does
 * outside the process. Every external command goes through the injected {@link CommandRunner}.
 *
 * <p>Ported here: the rounds (the runner {@code runner/round.py}, {@code homelab-inbox-task}), the working copies, the
 * pushes of {@code inbox/<id>}, the repo list and the service's copies, the PRs ({@code gh pr create}, {@code gh pr
 * merge}, {@code gh api} for reads), the task log files and ntfy. It extends {@link ShadowEffects}: the effects of
 * briefs, jobs, research, memory, the router and the planner are not ported yet and stay shadow (recorded, nothing
 * sent); later slices override them.
 */
public class LiveEffects extends ShadowEffects {

    private static final Logger log = LoggerFactory.getLogger(LiveEffects.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final Pattern HTTP_CODE = Pattern.compile("HTTP (\\d{3})");
    private static final Pattern REPO_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final Pattern COMMIT_LINE = Pattern.compile("^[0-9a-f]{7,} .*");
    private static final String DEFAULT_TASK_CMD = "sudo -n -u inbox /usr/local/lib/homelab/bin/homelab-inbox-task";
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(30);

    public static final String SYSTEM_PROMPT = """
            Je werkt voor de homelab-inbox: de gebruiker heeft vanaf zijn telefoon een taak gestuurd. Je zit in een
            eigen werkkopie van de repo {repo} op branch inbox/{id}; de gebruiker kijkt niet mee.
            - Je kunt niet pushen en geen PR openen of mergen; de inbox pusht na afloop jouw commits op deze branch en opent de
              PR. Commit dus je werk op deze branch (kleine, duidelijke commits). Push niet en probeer het niet.
            - Werk alleen in deze werkkopie. Raak geen andere repo's, dev-clones (~/dev) of deploy-clones (/srv/homelab) aan.
            - Is de taak een vraag (opzoeken, uitleggen, "waarom ..."), dan commit je niets.
            - Machinewijzigingen (alleen in de homelab-repo) volgen F-019 (docs/admin.md): wat op de vaste lijst staat doe je met
              `homelab-admin <actie> --reason "inbox-taak {id}: ..."`; al het andere als changes/NNNN-naam/apply.sh in een commit.
              Verbiedt F-019 iets (kernel, driver, schijven, reboot, SSH/LAN-regels), doe het dan niet en zoek geen omweg.
            - Volg de CLAUDE.md van de repo, behalve waar die zegt dat je zelf pusht of een PR opent: dat doet de inbox.
            - Je laatste bericht begint met precies één van deze woorden, gevolgd door een dubbele punt:
              KLAAR: wat je veranderd hebt, in 1 tot 3 zinnen (je hebt commits gemaakt)
              ANTWOORD: het antwoord op een vraag (geen commits)
              VRAAG: één vraag, als de taak te onduidelijk is om zonder te raden te beginnen (doe dan verder nog niets)
              GEWEIGERD: waarom dit niet mag of niet kan
            - Stel hooguit één vraag per ronde. Heb je al een antwoord gekregen, neem dan een redelijke aanname en noem die.
            - Skills: de gemergde skills van de gebruiker staan in je skills; gebruik ze waar ze passen.
            - Ging de taak goed (KLAAR of ANTWOORD) en is jouw manier van werken herbruikbaar voor latere taken, en is er nog geen
              skill voor, dan mag je onder je laatste bericht één skill voorstellen. Meestal doe je dat niet. Precies zo:
              SKILL-VOORSTEL: naam-met-streepjes
              beschrijving: één regel: waarvoor en wanneer je hem gebruikt
              de instructies in markdown (stappen, valkuilen, commando's)
              De inbox maakt er een PR van; de gebruiker beslist. Een bestaande skill verander je nooit zelf: een verbetering
              stel je op dezelfde manier voor, onder dezelfde naam.""";

    private final CommandRunner commands;
    private final Settings conf;
    private final TaskStore store;
    private final Effects.ConversationNotes conversation;
    private final Supplier<RoundFlow> flow;
    private final Cancellations cancellations;
    private final CoreSettings core;
    private final Notifications notifications;
    private final Map<String, ReentrantLock> repoLocks = new ConcurrentHashMap<>();

    public LiveEffects(CommandRunner commands, Settings conf, TaskStore store, Effects.ConversationNotes conversation,
                       Supplier<RoundFlow> flow, Cancellations cancellations, CoreSettings core) {
        this.commands = commands;
        this.conf = conf;
        this.store = store;
        this.conversation = conversation;
        this.flow = flow;
        this.cancellations = cancellations;
        this.core = core;
        this.notifications = new Notifications(this::note, this::push);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private List<String> taskCmdBase() {
        String c = conf.env("TASK_CMD");
        return List.of((c == null || c.isBlank() ? DEFAULT_TASK_CMD : c).strip().split("\\s+"));
    }

    /** {@code helpers.task_cmd}: runs {@code homelab-inbox-task} to the end; the output is stdout plus stderr, stripped. */
    Effects.Result taskCmd(Duration timeout, Object... args) {
        List<String> cmd = new ArrayList<>(taskCmdBase());
        for (Object a : args) {
            cmd.add(String.valueOf(a));
        }
        Finished o = commands.run(Command.of(cmd).withTimeout(timeout));
        return new Effects.Result(o.code(), o.combined());
    }

    Effects.Result taskCmd(Object... args) {
        return taskCmd(Duration.ofMinutes(10), args);
    }

    private ReentrantLock repoLock(String name) {
        return repoLocks.computeIfAbsent(name, k -> new ReentrantLock());
    }

    private Path runnerDir() {
        String d = conf.env("RUNNER_DIR");
        return Path.of(d == null || d.isBlank() ? "/usr/local/lib/homelab/runner" : d);
    }

    private Path taskHome() {
        String d = conf.env("INBOX_TASK_HOME");
        return Path.of(d == null || d.isBlank() ? conf.conf("TASK_HOME", "/var/lib/inbox") : d);
    }

    private String token(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return null;
        }
    }

    private Map<String, String> ghEnv() {
        String t = token(conf.ghTokenFile());
        return t == null ? Map.of("GH_PROMPT_DISABLED", "1") : Map.of("GH_TOKEN", t, "GH_PROMPT_DISABLED", "1");
    }

    private String credentialHelper() {
        return "!f() { test \"$1\" = get && echo username=x-access-token && echo \"password=$(cat "
                + conf.ghTokenFile() + ")\"; }; f";
    }

    /** {@code helpers.git}: git in a copy of the service (never in a directory of Claude), the token through a helper. */
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

    private String remoteUrl(String name, String slug) {
        String fake = conf.env("REMOTE_" + name.toUpperCase().replace('-', '_'));
        return fake != null && !fake.isEmpty() ? fake : "https://github.com/" + slug + ".git";
    }

    // ---- Repos ---------------------------------------------------------------------------------------------------

    @Override
    public Map<String, String> list() {
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
            JsonNode extra = JSON.readTree(Files.readString(conf.newReposFile()));
            extra.properties().forEach(e -> repos.putIfAbsent(e.getKey(), e.getValue().asString()));
        } catch (IOException | RuntimeException e) {
            // no file, or not JSON: no new repos
        }
        return repos;
    }

    /** {@code helpers.refresh_cache}: the service's copy of a repo, on origin/main. */
    @Override
    public String refresh(String repo, String slug) {
        ReentrantLock lock = repoLock(repo);
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
            return d.toString();
        } finally {
            lock.unlock();
        }
    }

    // ---- Rounds and the working copies of homelab-inbox-task -----------------------------------------------------

    @Override
    public void runRound(long taskId) {
        flow.get().run(taskId);
    }

    @Override
    public void stop(long taskId) {
        taskCmd(KILL_TIMEOUT, "kill", taskId);
    }

    @Override
    public boolean has(long taskId) {
        return taskCmd("has", taskId).code() == 0;
    }

    @Override
    public void cleanup(long taskId) {
        taskCmd("cleanup", taskId);
    }

    @Override
    public boolean pushed(Task task) {
        try {
            git(conf.runnerHome().resolve("repos").resolve(task.repo()), "rev-parse", "--verify", "--quiet",
                    "refs/inbox/" + task.id());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Effects.Result prepare(long taskId, String repo) {
        ReentrantLock lock = repoLock(repo);
        lock.lock();
        try {
            return taskCmd("prepare", taskId, repo);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Effects.Result session(long taskId, String sessionId) {
        return taskCmd("session", taskId, sessionId);
    }

    @Override
    public Effects.Result behind(long taskId) {
        return taskCmd("behind", taskId);
    }

    @Override
    public Effects.Result sync(long taskId) {
        return taskCmd("sync", taskId);
    }

    /** {@code rounds.push_branch}: the commits of the task (a bundle, data only) into the service's copy, then to inbox/id. */
    @Override
    public List<String> push(long taskId, String cache) {
        Effects.Result bundled = taskCmd("bundle", taskId);
        if (bundled.code() == 3) {
            return List.of();
        }
        if (bundled.code() != 0) {
            String o = bundled.out();
            throw new IllegalStateException("bundle mislukt: " + (o.length() > 300 ? o.substring(o.length() - 300) : o));
        }
        Path bundle = taskHome().resolve("out").resolve(taskId + ".bundle");
        String ref = "refs/heads/inbox/" + taskId;
        Path dir = Path.of(cache);
        ReentrantLock lock = repoLock(dir.getFileName().toString());
        lock.lock();
        try {
            git(dir, "bundle", "verify", bundle.toString());
            String[] heads = git(dir, "bundle", "list-heads", bundle.toString()).strip().split("\\s+");
            List<String> refs = new ArrayList<>();
            for (int i = 1; i < heads.length; i += 2) {
                refs.add(heads[i]);
            }
            if (!refs.equals(List.of(ref))) {
                throw new IllegalStateException("de bundle bevat iets anders dan " + ref + ": " + refs);
            }
            git(dir, "fetch", "--quiet", "--force", bundle.toString(), ref + ":refs/inbox/" + taskId);
            // Only to inbox/<id>, never to main: the destination is fixed here.
            git(dir, "push", "--quiet", "--force", "origin", "refs/inbox/" + taskId + ":" + ref);
        } finally {
            lock.unlock();
        }
        List<String> lines = new ArrayList<>();
        for (String l : bundled.out().split("\n")) {
            if (COMMIT_LINE.matcher(l).matches()) {
                lines.add(l);
            }
        }
        return lines;
    }

    // ---- The Claude round (runner/round.py) ----------------------------------------------------------------------

    @Override
    public void writePrompt(Task task, String prompt) {
        Path share = conf.runnerHome().resolve("share").resolve(String.valueOf(task.id()));
        try {
            Files.createDirectories(share);
            Files.writeString(share.resolve("prompt.txt"), prompt == null ? "" : prompt);
            // The memory part of the system prompt (memory_for) is not ported yet: the prompt is the fixed text only.
            Files.writeString(share.resolve("system.txt"),
                    SYSTEM_PROMPT.replace("{repo}", task.repo()).replace("{id}", String.valueOf(task.id())));
            for (String f : List.of("prompt.txt", "system.txt")) {
                Files.setPosixFilePermissions(share.resolve(f), PosixFilePermissions.fromString("rw-r-----"));
            }
        } catch (IOException e) {
            throw new IllegalStateException("prompt schrijven mislukt: " + e.getMessage(), e);
        }
    }

    @Override
    public Outcome stream(long taskId, String mode) {
        Task t = store.get(taskId).orElseThrow(() -> new IllegalStateException("taak " + taskId + " bestaat niet"));
        Optional<Effects.ResearchRun> research = runOf(t);
        int minutes = core.maxMinutes();
        int maxTurns = core.maxTurns();
        List<String> cmd = new ArrayList<>(List.of("python3", "-I", runnerDir().resolve("round.py").toString(),
                String.valueOf(taskId), t.str("session"), mode));
        if (research.isPresent()) {
            cmd.add("research");
        }
        int baseTurns = t.num("turns");
        int[] turns = {baseTurns};
        String[] capped = {null};
        JsonNode[] result = {null};
        Runnable stopper = () -> {
            try {
                stop(taskId);
            } catch (RuntimeException e) {
                log.warn("task {}: stop: {}", taskId, e.toString());
            }
        };
        Thread watchdog = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    if (cancellations.requested(taskId)) {
                        stopper.run();
                        return;
                    }
                    Thread.sleep(2000);
                }
            } catch (InterruptedException e) {
                // the round ended
            }
        }, "round-watch-" + taskId);
        watchdog.setDaemon(true);
        watchdog.start();
        Finished done;
        try {
            done = commands.run(Command.of(cmd).withEnv(Map.of("TASK_CMD", String.join(" ", taskCmdBase())))
                    .withTimeout(Duration.ofMinutes(minutes)).withOnLine(line -> {
                        JsonNode ev;
                        try {
                            ev = JSON.readTree(line);
                        } catch (RuntimeException e) {
                            return;
                        }
                        String kind = ev.path("event").asString("");
                        switch (kind) {
                            case "log" -> line(taskId, ev.path("kind").asString("info"), ev.path("text").asString(""));
                            case "model" -> store.update(taskId, TaskStore.fields("model", ev.path("model").asString("")));
                            case "turn" -> {
                                turns[0] = baseTurns + ev.path("turns").asInt();
                                if (turns[0] % 5 == 0) {
                                    store.update(taskId, TaskStore.fields("turns", turns[0]));
                                }
                                if (turns[0] - baseTurns >= maxTurns && capped[0] == null) {
                                    capped[0] = maxTurns + " beurten";
                                    stopper.run();
                                }
                            }
                            case "result" -> result[0] = ev.path("raw");
                            default -> { }
                        }
                    }));
        } finally {
            watchdog.interrupt();
        }
        if (cancellations.requested(taskId)) {
            capped[0] = "geannuleerd";
        } else if (done.timedOut()) {
            capped[0] = minutes + " minuten";
        }
        store.update(taskId, TaskStore.fields("turns", turns[0]));
        String err = done.stderr().length() > 300 ? done.stderr().substring(done.stderr().length() - 300) : done.stderr();
        JsonNode raw = result[0] == null || result[0].isMissingNode() || result[0].isNull() ? null : result[0];
        if (raw == null && capped[0] == null) {
            throw new IllegalStateException("Claude stopte zonder resultaat: " + err);
        }
        String text = raw == null ? "" : raw.path("result").asString("");
        if (capped[0] == null && turns[0] == baseTurns && (raw.path("is_error").asBoolean(false) || text.isBlank())) {
            line(taskId, "error", ("ronde zonder beurten: " + cut(text, 200) + " " + err).strip());
            throw new IllegalStateException(("Claude deed niets (0 beurten): " + cut(text, 200) + " " + err).strip());
        }
        return new Outcome(raw == null ? null : text, raw == null ? 0 : raw.path("permission_denials").size(), capped[0]);
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private void line(long taskId, String kind, String text) {
        line("t" + taskId, kind, text);
    }

    // ---- The task log, the conversation and ntfy -------------------------------------------------------------------

    /** {@code helpers.log_line}: one JSON line per line of text in {@code logs/<key>.log}; what looks like a secret is hidden. */
    @Override
    public void line(String key, String kind, String text) {
        String clean = ConversationStore.SECRET.matcher(String.valueOf(text)).replaceAll("[verborgen]");
        List<String> lines = "out".equals(kind) ? clean.lines().toList()
                : List.of(String.join(" ", clean.strip().split("\\s+")));
        StringBuilder b = new StringBuilder();
        for (String l : lines.isEmpty() ? List.of("") : lines) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString().replace("Z", "+00:00"));
            o.put("kind", kind);
            o.put("text", l.length() > 600 ? l.substring(0, 600) : l);
            b.append(JSON.writeValueAsString(o)).append('\n');
        }
        try {
            Files.createDirectories(conf.logsDir());
            Files.writeString(conf.logsDir().resolve(key + ".log"), b.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("log {}: {}", key, e.toString());
        }
    }

    @Override
    public void note(String author, String client, String kind, String text, String link, String ref) {
        conversation.note(author, client, kind, text, link, ref);
    }

    @Override
    public void notify(String title, String body, String url, boolean store) {
        notifications.notify(title, body, url, store);
    }

    @Override
    public void notify(String title, String body, String url, int limit) {
        notifications.notify(title, body, url, limit);
    }

    /** One ntfy message ({@code helpers.notify}): priority 4, tag robot, a "view" action when there is a link. */
    private void push(String title, String message, String click) throws IOException {
        if ("1".equals(conf.env("NTFY_FAKE"))) {
            log.info("MELDING: {} | {} | {}", title, cut(message, 300), click == null ? "" : click);
            return;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", conf.conf("NTFY_TOPIC", "homelab"));
        body.put("title", title);
        body.put("message", message);
        body.put("priority", 4);
        body.put("tags", List.of("robot"));
        if (click != null && !click.isEmpty()) {
            body.put("click", click);
            body.put("actions", List.of(Map.of("action", "view", "label", "Openen", "url", click)));
        }
        String ntfyToken = token(Path.of(conf.env("NTFY_TOKEN_FILE") == null ? "/etc/homelab/inbox-ntfy-token"
                : conf.env("NTFY_TOKEN_FILE")));
        if (ntfyToken == null) {
            throw new IOException("no ntfy token");
        }
        // The token goes through a curl config on stdin, not through the arguments (they are visible in ps).
        String cfg = "header = \"Authorization: Bearer " + ntfyToken + "\"\nheader = \"Content-Type: application/json\"\n";
        Finished o = commands.run(Command.of("curl", "-sS", "--fail", "-m", "10", "-X", "POST", "--config", "-",
                "--data-binary", JSON.writeValueAsString(body), "http://127.0.0.1:" + conf.conf("NTFY_PORT", "8102") + "/")
                .withStdin(cfg));
        if (o.code() != 0) {
            throw new IOException(o.combined());
        }
    }

    // ---- GitHub through gh ----------------------------------------------------------------------------------------

    private static int httpCode(String text, int fallback) {
        Matcher m = HTTP_CODE.matcher(text);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /** {@code gh api <path>}: read only; a failure is a {@link Effects.GitHubHttpException} with the status gh reports. */
    @Override
    public JsonNode get(String path) {
        Finished o = commands.run(Command.of("gh", "api", path).withEnv(ghEnv()).withTimeout(Duration.ofSeconds(30)));
        if (o.code() != 0) {
            throw new Effects.GitHubHttpException(httpCode(o.combined(), 500), "https://api.github.com" + path, o.combined());
        }
        String out = o.stdout().strip();
        return out.isEmpty() ? tools.jackson.databind.node.MissingNode.getInstance() : JSON.readTree(out);
    }

    @Override
    public Optional<Effects.PrState> get(String repoSlug, int number) {
        JsonNode pr = get("/repos/" + repoSlug + "/pulls/" + number);
        if (!pr.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new Effects.PrState(pr.path("state").asString(""), pr.path("merged").asBoolean(false)));
    }

    /** A merge commit, only for the head the caller checked ({@code gh pr merge --merge --match-head-commit}). */
    @Override
    public void merge(String repoSlug, int prNumber, String sha, String commitTitle) {
        List<String> cmd = new ArrayList<>(List.of("gh", "pr", "merge", String.valueOf(prNumber), "--repo", repoSlug,
                "--merge", "--subject", commitTitle));
        if (sha != null && !sha.isEmpty()) {
            cmd.addAll(List.of("--match-head-commit", sha));
        }
        Finished o = commands.run(Command.of(cmd).withEnv(ghEnv()).withTimeout(Duration.ofSeconds(60)));
        if (o.code() != 0) {
            throw new Effects.GitHubHttpException(httpCode(o.combined(), 405),
                    "https://api.github.com/repos/" + repoSlug + "/pulls/" + prNumber + "/merge", o.combined());
        }
    }

    @Override
    public Opened open(String repoSlug, String title, String head, String base, String body) {
        Finished o = commands.run(Command.of("gh", "pr", "create", "--repo", repoSlug, "--title", title, "--head", head,
                "--base", base, "--body", body).withEnv(ghEnv()).withTimeout(Duration.ofSeconds(60)));
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
