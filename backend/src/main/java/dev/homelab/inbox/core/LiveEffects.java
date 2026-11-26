package dev.homelab.inbox.core;

import dev.homelab.inbox.core.CommandRunner.Command;
import dev.homelab.inbox.core.CommandRunner.Finished;
import dev.homelab.inbox.read.Jobs.Job;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import dev.homelab.inbox.conversation.ConversationStore;

/**
 * The real effects (H-028 W1), selected by {@code core.effects=live}: a port of what {@code bin/homelab_inbox/} does
 * outside the process. Every external command goes through the injected {@link CommandRunner}: the rounds (the runner
 * {@code runner/round.py}) and the Claude calls of the router, planner and reviewer ({@code route.py}, {@code review.py},
 * {@code homelab-inbox-task plan}), the working copies and pushes of {@code inbox/<id>}, the PRs ({@code gh pr create},
 * {@code gh pr merge}, {@code gh api}), the product repo of the briefs, job input (overview, pages, GitHub, scripts, the
 * local model), the memory steps, skill proposals, the task log files and ntfy.
 *
 * <p>It extends {@link ShadowEffects} only to inherit the interface list; every method is overridden here or in the
 * helper it delegates to. The pieces that need a bean of the core (the round flow, the router, the planner, the research
 * lookups) come late, through {@link Late}: they themselves depend on these effects.
 */
public class LiveEffects extends ShadowEffects implements LiveClaude.Runner {

    private static final Logger log = LoggerFactory.getLogger(LiveEffects.class);
    private static final String DEFAULT_TASK_CMD = "sudo -n -u inbox /usr/local/lib/homelab/bin/homelab-inbox-task";
    private static final Duration KILL_TIMEOUT = Duration.ofSeconds(30);
    private static final java.util.regex.Pattern COMMIT_LINE = java.util.regex.Pattern.compile("^[0-9a-f]{7,} .*");

    /** The beans of the core these effects call back into (looked up when used, not when wired). */
    interface Late {
        RoundFlow flow();

        Router router();

        BriefRounds briefRounds();

        Effects.Research research();
    }

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


    // A research run (H-017) gets this instead of SYSTEM_PROMPT: it reads and reports, and changes nothing.
    public static final String RESEARCH_SYSTEM_PROMPT = """
            Je werkt voor de homelab-inbox aan een onderzoeksvraag (taak {id}); de gebruiker kijkt niet mee.
            - Je leest en rapporteert. Je commit niets, maakt geen branch of PR, schrijft geen bestanden en doet geen machinewijziging
              (ook geen `homelab-admin`); je tools laten dat ook niet toe.
            - Je tools: Read, Grep, Glob, WebSearch, WebFetch en `homelab-search` in Bash. Niets anders.
            - Volg de skill research-reports: eerst privé zoeken, dan het web; elke bewering een bron; niets gevonden zeggen en
              opsommen wat je doorzocht, nooit gokken.
            - Je laatste bericht begint met `ANTWOORD:` en bevat het rapport zoals de prompt het vraagt.""";

    private final CommandRunner commands;
    private final Settings conf;
    private final TaskStore store;
    private final Effects.ConversationNotes conversation;
    private final Late late;
    private final Cancellations cancellations;
    private final CoreSettings core;
    private final Notifications notifications;
    private final ServiceGit git;
    private final Gh gh;
    private final LiveClaude claude;
    private final LiveBriefs briefs;
    private final LiveJobIo jobIo;
    private final LiveMemory memory;
    private final LiveSkills skills;

    LiveEffects(CommandRunner commands, Settings conf, TaskStore store, Effects.ConversationNotes conversation, Late late,
                Cancellations cancellations, CoreSettings core, LiveMemory.Store memoryStore,
                Supplier<Map<String, Job>> jobs) {
        this.commands = commands;
        this.conf = conf;
        this.store = store;
        this.conversation = conversation;
        this.late = late;
        this.cancellations = cancellations;
        this.core = core;
        this.notifications = new Notifications(this::note, this::push);
        this.git = new ServiceGit(commands, conf);
        this.gh = new Gh(commands, conf);
        this.claude = new LiveClaude(commands, conf, this);
        this.briefs = new LiveBriefs(git, gh, conf);
        this.jobIo = new LiveJobIo(commands, conf, git, gh, this);
        String pageBase = "http://" + conf.conf("TAILNET_NAME", "homelab") + ":" + conf.conf("INBOX_PORT", "8105");
        this.memory = new LiveMemory(memoryStore, store, conf.runnerHome(), pageBase, conf.confInt("MEMORY_RECENT", 10), jobs);
        this.skills = new LiveSkills(git, gh, conf, store, this, pageBase);
    }

    // ---- homelab-inbox-task and the runner scripts -----------------------------------------------------------------

    @Override
    public List<String> taskCmdBase() {
        String c = conf.env("TASK_CMD");
        return List.of((c == null || c.isBlank() ? DEFAULT_TASK_CMD : c).strip().split("\\s+"));
    }

    @Override
    public Path runnerDir() {
        String d = conf.env("RUNNER_DIR");
        return Path.of(d == null || d.isBlank() ? "/usr/local/lib/homelab/runner" : d);
    }

    /** {@code helpers.task_cmd}: runs {@code homelab-inbox-task} to the end; the output is stdout plus stderr, stripped. */
    @Override
    public Effects.Result taskCmd(Duration timeout, Object... args) {
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

    private Path taskHome() {
        String d = conf.env("INBOX_TASK_HOME");
        return Path.of(d == null || d.isBlank() ? conf.conf("TASK_HOME", "/var/lib/inbox") : d);
    }

    // ---- Repos -----------------------------------------------------------------------------------------------------

    @Override
    public Map<String, String> list() {
        return git.repos();
    }

    @Override
    public String refresh(String repo, String slug) {
        return git.refresh(repo, slug).toString();
    }

    // ---- Rounds and the working copies of homelab-inbox-task -------------------------------------------------------

    @Override
    public void runRound(long taskId) {
        late.flow().run(taskId);
    }

    @Override
    public void reviewAgain(long taskId) {
        late.briefRounds().reviewPr(taskId, refreshForReview(taskId));
    }

    private String refreshForReview(long taskId) {
        Task t = store.get(taskId).orElseThrow();
        return git.refresh(t.repo(), git.repos().get(t.repo())).toString();
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
            git.git(conf.runnerHome().resolve("repos").resolve(task.repo()), "rev-parse", "--verify", "--quiet",
                    "refs/inbox/" + task.id());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Effects.Result prepare(long taskId, String repo) {
        var lock = git.lock(repo);
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
        var lock = git.lock(String.valueOf(store.get(taskId).map(Task::repo).orElse("")));
        lock.lock();
        try {
            return taskCmd("sync", taskId);
        } finally {
            lock.unlock();
        }
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
        var lock = git.lock(dir.getFileName().toString());
        lock.lock();
        try {
            git.git(dir, "bundle", "verify", bundle.toString());
            String[] heads = git.git(dir, "bundle", "list-heads", bundle.toString()).strip().split("\\s+");
            List<String> refs = new ArrayList<>();
            for (int i = 1; i < heads.length; i += 2) {
                refs.add(heads[i]);
            }
            if (!refs.equals(List.of(ref))) {
                throw new IllegalStateException("de bundle bevat iets anders dan " + ref + ": " + refs);
            }
            git.git(dir, "fetch", "--quiet", "--force", bundle.toString(), ref + ":refs/inbox/" + taskId);
            // Only to inbox/<id>, never to main: the destination is fixed here.
            git.git(dir, "push", "--quiet", "--force", "origin", "refs/inbox/" + taskId + ":" + ref);
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

    // ---- The Claude round (runner/round.py) ------------------------------------------------------------------------

    @Override
    public void writePrompt(Task task, String prompt) {
        Path dir = conf.runnerHome().resolve("share").resolve(String.valueOf(task.id()));
        String system = (runOf(task).isPresent() ? RESEARCH_SYSTEM_PROMPT : SYSTEM_PROMPT)
                .replace("{repo}", task.repo()).replace("{id}", String.valueOf(task.id()));
        Share.write(dir, prompt, system + memory.memoryFor(task));
    }

    @Override
    public void route(long taskId) {
        late.router().routeTask(taskId);
    }

    @Override
    public void plan(long taskId) {
        late.briefRounds().planTask(taskId);
    }

    @Override
    public Outcome stream(long taskId, String mode) {
        Task t = store.get(taskId).orElseThrow(() -> new IllegalStateException("taak " + taskId + " bestaat niet"));
        Optional<Effects.ResearchRun> research = runOf(t);
        // research_limits: a quick research run has its own small limits, deep the limits of an inbox task
        boolean quick = research.isPresent() && "quick".equals(research.get().mode());
        int minutes = quick ? conf.confInt("RESEARCH_QUICK_MINUTES", 6) : core.maxMinutes();
        int maxTurns = quick ? conf.confInt("RESEARCH_QUICK_TURNS", 25) : core.maxTurns();
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
                            ev = Gh.JSON.readTree(line);
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

    private void line(long taskId, String kind, String text) {
        line("t" + taskId, kind, text);
    }

    private String token(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return null;
        }
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
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
            b.append(Gh.JSON.writeValueAsString(o)).append('\n');
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
                "--data-binary", Gh.JSON.writeValueAsString(body), "http://127.0.0.1:" + conf.conf("NTFY_PORT", "8102") + "/")
                .withStdin(cfg));
        if (o.code() != 0) {
            throw new IOException(o.combined());
        }
    }

    // ---- GitHub through gh -----------------------------------------------------------------------------------------

    /** {@code gh api <path>}: read only; a failure is a {@link Effects.GitHubHttpException} with the status gh reports. */
    @Override
    public JsonNode get(String path) {
        return gh.get(path);
    }

    @Override
    public Optional<Effects.PrState> get(String repoSlug, int number) {
        JsonNode pr = gh.get("/repos/" + repoSlug + "/pulls/" + number);
        if (!pr.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new Effects.PrState(pr.path("state").asString(""), pr.path("merged").asBoolean(false)));
    }

    @Override
    public void merge(String repoSlug, int prNumber, String sha, String commitTitle) {
        gh.merge(repoSlug, prNumber, sha, commitTitle);
    }

    @Override
    public Opened open(String repoSlug, String title, String head, String base, String body) {
        return gh.createPr(repoSlug, title, head, base, body);
    }

    // ---- Research, memory, skills -------------------------------------------------------------------------------------

    @Override
    public Optional<Effects.ResearchRun> runOf(Task task) {
        return late.research().runOf(task);
    }

    @Override
    public void deliver(Task task, String text, String link) {
        late.research().deliver(task, text, link);
    }

    @Override
    public String jobInstruction(String job) {
        return late.research().jobInstruction(job);
    }

    @Override
    public void reviewPr(long taskId, String cache) {
        late.briefRounds().reviewPr(taskId, cache);
    }

    @Override
    public void proposeSkill(Task task, String name, String description, String body) {
        skills.propose(task, name, description, body);
    }

    @Override
    public void load() {
        memory.load();
    }

    @Override
    public MemorySplit split(String text) {
        return memory.split(text);
    }

    @Override
    public void afterRound(long taskId, List<String> blocks) {
        memory.afterRound(taskId, blocks);
    }

    @Override
    public void remember(long taskId) {
        memory.remember(taskId);
    }

    // ---- The briefs: product repo, GitHub, diffs, profiles ---------------------------------------------------------

    @Override
    public List<Effects.ProductFile> briefFiles() {
        return briefs.briefFiles();
    }

    @Override
    public String read(String path) {
        return briefs.read(path);
    }

    @Override
    public void commitToMain(String path, String text, String message) {
        briefs.commitToMain(path, text, message);
    }

    @Override
    public void pushBranch(String branch, Map<String, String> files, String message) {
        briefs.pushBranch(branch, files, message);
    }

    @Override
    public List<Effects.SetupPr> setupPrs(String slug, String briefId) {
        return briefs.setupPrs(slug, briefId);
    }

    @Override
    public void mergeSetupPr(String slug, int number, String title) {
        briefs.mergeSetupPr(slug, number, title);
    }

    @Override
    public void comment(String slug, int prNumber, String body) {
        briefs.comment(slug, prNumber, body);
    }

    @Override
    public void mergeHandoff(String slug, int number, String title) {
        briefs.mergeHandoff(slug, number, title);
    }

    @Override
    public List<String> changedFiles(String cache, long taskId) {
        return briefs.changedFiles(cache, taskId);
    }

    @Override
    public String diff(String cache, long taskId, List<String> paths) {
        return briefs.diff(cache, taskId, paths);
    }

    @Override
    public String profiles(Map<String, String> repos, boolean ci) {
        return briefs.profiles(repos, ci);
    }

    @Override
    public void add(String name, String slug) {
        briefs.add(name, slug);
    }

    @Override
    public Effects.ClaudeAnswer plan(long taskId, String system, String prompt) {
        return claude.plan(taskId, system, prompt);
    }

    @Override
    public Effects.ClaudeAnswer review(long taskId, String system, String prompt) {
        return claude.review(taskId, system, prompt);
    }

    @Override
    public Effects.RouteAnswer route(long taskId, String system, String prompt) {
        return claude.route(taskId, system, prompt);
    }

    // ---- Jobs --------------------------------------------------------------------------------------------------------

    @Override
    public String boxOverview() throws IOException {
        return jobIo.boxOverview();
    }

    @Override
    public String fetchPage(String url) throws IOException {
        return jobIo.fetchPage(url);
    }

    @Override
    public Object github(String path) throws IOException {
        return jobIo.github(path);
    }

    @Override
    public Effects.ScriptResult runScript(String repo, String script, List<String> args, long runId) {
        try {
            return jobIo.runScript(repo, script, args, runId);
        } catch (IOException e) { // the runs of JobEngine fail with this message either way
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Override
    public String summarize(String jobName, String instruction, String text) throws IOException {
        return jobIo.summarize(jobName, instruction, text);
    }
}
