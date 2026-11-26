package dev.homelab.inbox.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The job files of the {@code jobs-repo} clone, read the way {@code jobs.load_jobs} reads them (H-024 M1): one
 * {@code jobs/<name>.md} per job, checked by the same rules. Only what the overview needs is kept; a file that breaks a
 * rule is a job with an {@code error}. The clone is only read: syncing it from GitHub stays with the Python inbox.
 */
@Component
public class Jobs {

    /** A job file: valid ({@code error == null}) or not. */
    public record Job(String name, String error, Cron cron, String when, String schedule, String kind, String model,
                      boolean paused, Long minutes, List<String> skills, String repo, String script, List<String> args,
                      List<String> sources, String deliver, String mode, String instruction) {

        public boolean ok() {
            return error == null;
        }

        public boolean active() {
            return ok() && !paused;
        }
    }

    /** A catalogue file that is valid except for the sources the user fills in when switching it on; {@link #job} is
     * the job as far as it goes ({@code jobs.NeedsInput}). */
    public static final class NeedsInput extends IllegalArgumentException {
        private final transient Job job;

        public NeedsInput(String message, Job job) {
            super(message);
            this.job = job;
        }

        public Job job() {
            return job;
        }
    }

    /** Job kinds and how the page names them (jobs.JOB_KINDS). */
    public static final Map<String, String> KINDS = kinds();

    private static Map<String, String> kinds() {
        Map<String, String> m = new LinkedHashMap<>(); // a plain map: a null kind is looked up, and may not throw
        m.put("box-report", "boxrapport");
        m.put("repo-chore", "repo-klus");
        m.put("digest", "digest");
        m.put("script", "script");
        m.put("repo-watch", "repo-waker");
        m.put("research", "onderzoek");
        return java.util.Collections.unmodifiableMap(m);
    }

    private static final Set<String> FIELDS = Set.of("name", "when", "schedule", "kind", "model", "repo", "skills",
            "script", "args", "sources", "minutes", "paused", "deliver", "mode");
    private static final List<String> DELIVER = List.of("page", "ntfy", "conversation");
    private static final List<String> RESEARCH_MODES = List.of("quick", "deep");
    private static final String RESEARCH_JOB = "research";
    private static final String NOTHING_MARKER = "NOTHING TO REPORT";
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,40}$");
    private static final Pattern SCRIPT = Pattern.compile("^[A-Za-z0-9_][A-Za-z0-9._/-]*$");
    private static final Pattern REPO_NAME = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final Pattern SOURCE = Pattern.compile("^(https?://\\S+|github:[\\w.-]+/[\\w.-]+)$",
            Pattern.UNICODE_CHARACTER_CLASS);

    private final Settings settings;
    private final JsonMapper json;

    public Jobs(Settings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    /** name -> job, in the order of the file names. */
    public Map<String, Job> load() {
        Set<String> repos = repoNames();
        Set<String> skills = skillNames();
        Map<String, Job> found = new TreeMap<>();
        Path dir = settings.jobsDir();
        if (!Files.isDirectory(dir)) {
            return found;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(f -> f.getFileName().toString().endsWith(".md") && Files.isRegularFile(f)).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + dir, e);
        }
        for (Path f : files) {
            String file = f.getFileName().toString();
            String name = file.substring(0, file.length() - 3);
            String text;
            try {
                text = Files.readString(f);
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + f, e);
            }
            try {
                found.put(name, parse(name, text, repos, skills));
            } catch (IllegalArgumentException | IllegalStateException e) {
                found.put(name, new Job(name, String.valueOf(e.getMessage()), null, null, null, null, null, false, null, List.of(), null, null, List.of(), List.of(), "page", null, ""));
            }
        }
        return found;
    }

    /** The raw text of {@code jobs/<name>.md}, as {@code JOBS[name]["text"]}; empty when there is no such file. */
    public java.util.Optional<String> fileText(String name) {
        try {
            return java.util.Optional.of(Files.readString(settings.jobsDir().resolve(name + ".md")));
        } catch (IOException | RuntimeException e) {
            return java.util.Optional.empty();
        }
    }

    /** helpers.repo_list: homelab, the lines of repos.conf and new-repos.json. */
    public Set<String> repoNames() {
        Set<String> names = new TreeSet<>();
        names.add("homelab");
        try {
            for (String line : Files.readAllLines(settings.reposConf())) {
                int hash = line.indexOf('#');
                String[] parts = (hash < 0 ? line : line.substring(0, hash)).strip().split("\\s+");
                if (parts.length == 3 && REPO_NAME.matcher(parts[0]).matches()) {
                    names.add(parts[0]);
                }
            }
        } catch (IOException e) {
            // no repos.conf: only homelab and the new repos
        }
        Object extra = JsonFiles.readJson(json, settings.newReposFile());
        if (extra instanceof Map<?, ?> m) {
            m.keySet().forEach(k -> names.add(String.valueOf(k)));
        }
        return names;
    }

    /** jobs.skill_list: the merged skills, from the service's copy of main. */
    public Set<String> skillNames() {
        Set<String> names = new TreeSet<>();
        Path dir = settings.skillsDir();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(d -> Files.isRegularFile(d.resolve("SKILL.md"))).forEach(d -> names.add(d.getFileName().toString()));
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + dir, e);
        }
        return names;
    }

    /** helpers.as_list */
    private static List<String> asList(Object v) {
        List<String> out = new ArrayList<>();
        if (v == null || "".equals(v)) {
            return out;
        }
        Iterable<?> items = v instanceof List<?> l ? l : List.of(Text.pyStr(v).split(",", -1));
        for (Object item : items) {
            String s = Text.pyStr(item).strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /** jobs.parse_job, as far as the overview needs the result; throws IllegalArgumentException with the reason. */
    public Job parse(String name, String text, Set<String> repos, Set<String> skills) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("de naam mag alleen kleine letters, cijfers en streepjes hebben");
        }
        if (!text.startsWith("---\n")) {
            throw new IllegalArgumentException("het bestand begint niet met een kop tussen --- en ---");
        }
        String afterOpen = text.substring(4);
        int close = afterOpen.indexOf("\n---");
        if (close < 0) {
            throw new IllegalArgumentException("de kop sluit niet af met ---");
        }
        String head = afterOpen.substring(0, close);
        String rest = afterOpen.substring(close + 4);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(head);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("de kop is geen geldige YAML: " + e.getMessage());
        }
        if (loaded == null) {
            loaded = new LinkedHashMap<>();
        }
        if (!(loaded instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("de kop is geen lijst van velden");
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        raw.forEach((k, v) -> meta.put(Text.pyStr(k), v));
        List<String> unknown = meta.keySet().stream().filter(k -> !FIELDS.contains(k)).sorted().toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("onbekende velden: " + String.join(", ", unknown));
        }
        Object metaName = meta.get("name");
        if (metaName != null && !Objects.equals(metaName, name)) {
            throw new IllegalArgumentException("name (" + metaName + ") is niet gelijk aan de bestandsnaam (" + name + ")");
        }
        String when = Text.truthy(meta.get("when")) ? Text.pyStr(meta.get("when")).strip() : "";
        String schedule = Text.truthy(meta.get("schedule")) ? Text.pyStr(meta.get("schedule")) : "";
        Object kind = meta.get("kind");
        Object model = meta.get("model");
        String repo = Text.truthy(meta.get("repo")) ? Text.pyStr(meta.get("repo")) : null;
        List<String> skillList = asList(meta.get("skills"));
        Object script = meta.get("script");
        List<String> sources = asList(meta.get("sources"));
        Object minutes = meta.get("minutes");
        boolean paused = Boolean.TRUE.equals(meta.get("paused"));
        String deliver = Text.truthy(meta.get("deliver")) ? Text.pyStr(meta.get("deliver")) : "page";
        Object mode = meta.get("mode");
        String blank = rest.indexOf('\n') < 0 ? "" : rest.substring(rest.indexOf('\n') + 1);
        String instruction = blank.strip();

        Cron cron = new Cron(schedule);
        if (!(kind instanceof String k) || !KINDS.containsKey(k)) {
            throw new IllegalArgumentException("kind moet een van " + String.join(", ", KINDS.keySet()) + " zijn");
        }
        if (!"local".equals(model) && !"claude".equals(model)) {
            throw new IllegalArgumentException("model moet local of claude zijn");
        }
        if (instruction.isEmpty()) {
            throw new IllegalArgumentException("de instructie onder de kop ontbreekt");
        }
        if (repo != null && !repos.contains(repo)) {
            throw new IllegalArgumentException("repo " + repo + " staat niet in de lijst (F-018) en is niet homelab");
        }
        if (k.equals("repo-chore") && (!"claude".equals(model) || repo == null)) {
            throw new IllegalArgumentException("een repo-klus draait op Claude en heeft een repo nodig");
        }
        if (k.equals("script")) {
            String path = Text.truthy(script) ? Text.pyStr(script) : "";
            if (!SCRIPT.matcher(path).matches() || path.contains("..")) {
                throw new IllegalArgumentException("script moet een pad in de repo zijn, bijvoorbeeld scripts/iets.sh");
            }
        }
        String scriptPath = Text.truthy(script) ? Text.pyStr(script) : null;
        String jobRepo = k.equals("script") && repo == null ? "homelab" : repo;
        Job partial = new Job(name, null, cron, when, schedule, k, (String) model, paused, null, skillList, jobRepo,
                scriptPath, asList(meta.get("args")), sources, deliver, Text.truthy(mode) ? Text.pyStr(mode) : null,
                instruction);
        if (k.equals("digest") && sources.isEmpty()) {
            throw new NeedsInput("een digest heeft sources nodig (URL's of github:eigenaar/repo)", partial);
        }
        if (k.equals("repo-watch") && sources.isEmpty()) {
            throw new NeedsInput("een repo-waker heeft sources nodig (github:eigenaar/repo, een of meer)", partial);
        }
        if (k.equals("repo-watch") && !sources.stream().allMatch(s -> s.startsWith("github:"))) {
            throw new IllegalArgumentException("een repo-waker bekijkt alleen GitHub-repo's: github:eigenaar/repo");
        }
        for (String s : sources) {
            if (!SOURCE.matcher(s).matches()) {
                throw new IllegalArgumentException("bron " + s + " is geen http(s)-URL of github:eigenaar/repo");
            }
        }
        if (!skillList.isEmpty() && !"claude".equals(model)) {
            throw new IllegalArgumentException("skills gelden alleen voor Claude-jobs (model: claude)");
        }
        List<String> missing = skillList.stream().filter(s -> !skills.contains(s)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("skill(s) " + String.join(", ", missing) + " staan (nog) niet op main");
        }
        if (!DELIVER.contains(deliver)) {
            throw new IllegalArgumentException("deliver moet een van " + String.join(", ", DELIVER) + " zijn");
        }
        if (k.equals("research")) {
            String m = Text.truthy(mode) ? Text.pyStr(mode) : "quick";
            if (!RESEARCH_MODES.contains(m)) {
                throw new IllegalArgumentException("mode moet een van " + String.join(", ", RESEARCH_MODES) + " zijn");
            }
            if (!"claude".equals(model)) {
                throw new IllegalArgumentException("een onderzoek draait op Claude (model: claude)");
            }
            if (name.equals(RESEARCH_JOB)) {
                throw new IllegalArgumentException("de naam " + RESEARCH_JOB + " is voor vragen van de pagina en uit de chat");
            }
        } else if (mode != null) {
            throw new IllegalArgumentException("mode geldt alleen voor kind: research");
        }
        if (!deliver.equals("page") && !k.equals("research") && !instruction.contains(NOTHING_MARKER)) {
            throw new IllegalArgumentException("een job met deliver: " + deliver + " noemt in zijn instructie wat "
                    + "'niets te melden' is en antwoordt dan met " + NOTHING_MARKER + " als eerste regel");
        }
        Long mins = null;
        if (minutes != null) {
            // Python: bool is an int, so `minutes: true` passes; anything but a whole number of 0 or more fails.
            if (minutes instanceof Boolean b) {
                mins = b ? 1L : 0L;
            } else if (minutes instanceof Integer || minutes instanceof Long) {
                mins = ((Number) minutes).longValue();
            }
            if (mins == null || mins < 0) {
                throw new IllegalArgumentException("minutes is een heel aantal minuten (0 of meer)");
            }
        }
        String finalMode = k.equals("research") ? (Text.truthy(mode) ? Text.pyStr(mode) : "quick") : null;
        return new Job(name, null, cron, when, schedule, k, (String) model, paused, mins, skillList, jobRepo,
                scriptPath, asList(meta.get("args")), sources, deliver, finalMode, instruction);
    }
}
