package dev.homelab.inbox.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pure part of {@code bin/homelab_inbox/briefs.py} (H-026 K4): reading a brief file, the planner's plan block, the
 * reviewer's review block, the diff selection for the reviewer, and the text edits of the brief and the roadmap. No
 * I/O: everything that touches git, GitHub, Claude or the database is behind {@link Effects}.
 */
public final class BriefParsing {

    private BriefParsing() {}

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern FRONTMATTER = Pattern.compile("^---\\n(.*?)\\n---\\n?(.*)$", Pattern.DOTALL);
    private static final Pattern BRIEF_ID = Pattern.compile("[A-Z]-\\d{3,4}");
    private static final Pattern PLAN_BLOCK = Pattern.compile("```plan\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern REVIEW_BLOCK = Pattern.compile("```review\\s*\\n(.*?)```", Pattern.DOTALL);
    private static final Pattern STATUS_VALUE = Pattern.compile("^(status:[ \\t]*)[^\\s#]+", Pattern.MULTILINE);
    private static final Pattern STATUS_LINE = Pattern.compile("^status:.*$", Pattern.MULTILINE);
    private static final Pattern NEW_REPO = Pattern.compile("([A-Za-z0-9-]+)/([a-z0-9][a-z0-9-]*)");
    /** {@code config.SETUP_PATHS}: the only files a setup PR may change. */
    static final Pattern SETUP_PATHS = Pattern.compile(
            "(CLAUDE\\.md|\\.claude/kit\\.md|\\.claude/agents/[\\w.-]+\\.md|\\.claude/skills/[\\w.-]+/.+)");
    static final Set<String> VERDICTS = Set.of("pass", "miss", "ci", "unclear");

    /** Left out of the reviewer's diff: lockfiles, generated output and fixture data. */
    static final Pattern REVIEW_SKIP = Pattern.compile(
            "(^|/)(package-lock\\.json|npm-shrinkwrap\\.json|pnpm-lock\\.yaml|yarn\\.lock|bun\\.lockb?|"
                    + "[^/]+\\.lock|go\\.sum)$|(^|/)(dist|build|out|coverage|node_modules|vendor)/|\\.min\\.(js|css)$|\\.map$|"
                    + "(^|/)(fixtures?|__snapshots__)/.*\\.(jsonl?|csv|txt|xml|html|snap)$");
    static final Pattern REVIEW_DOCS = Pattern.compile("\\.(md|mdx|txt|rst)$|(^|/)docs/", Pattern.CASE_INSENSITIVE);

    /** One brief of the product repo ({@code load_briefs}); {@code maxTasks} is whatever the front matter holds. */
    public record Brief(String id, String title, String status, String priority, List<String> repos,
                        Map<String, Object> build, Object maxTasks, String project, String path, String text) {}

    /** One task of a plan; {@code after} is the key of an earlier task, or "". */
    public record PlannedTask(String key, String repo, String text, List<String> criteria, String after) {}

    /** The planner's answer: a question, or tasks and the criteria no task can deliver. */
    public record Plan(String question, List<PlannedTask> tasks, List<String> manual) {
        public boolean isQuestion() {
            return question != null;
        }
    }

    public record ReviewRow(String criterion, String verdict, String note) {}

    public record Review(List<ReviewRow> rows, String summary) {}

    public record FrontMatter(Map<String, Object> meta, String body) {}

    /** The text cut to {@code n} characters (code points, as Python counts them). */
    static String cut(String s, int n) {
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** {@code helpers.as_list}: nothing gives an empty list, a list or a comma separated text its trimmed items. */
    static List<String> asList(Object value) {
        List<String> out = new ArrayList<>();
        if (value == null || "".equals(value)) {
            return out;
        }
        Iterable<?> items = value instanceof Iterable<?> it ? it : List.of(value.toString().split(",", -1));
        for (Object o : items) {
            String s = String.valueOf(o).strip();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    // --- the brief file ---

    public static FrontMatter splitFrontmatter(String text) {
        Matcher m = FRONTMATTER.matcher(text);
        if (!m.matches()) {
            return new FrontMatter(Map.of(), text);
        }
        Map<String, Object> meta = Map.of();
        try {
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(m.group(1));
            if (loaded instanceof Map<?, ?> raw) {
                Map<String, Object> copy = new java.util.LinkedHashMap<>();
                raw.forEach((k, v) -> copy.put(String.valueOf(k), v));
                meta = copy;
            }
        } catch (RuntimeException e) {
            meta = Map.of(); // a broken front matter reads as none, like yaml.YAMLError
        }
        return new FrontMatter(meta, m.group(2));
    }

    private static String orElse(Object v, String fallback) {
        String s = v == null ? "" : v.toString();
        return s.isEmpty() ? fallback : s;
    }

    /** One brief of a file {@code <project>/briefs/<stem>.md}; null when its id is not {@code X-123}. */
    public static Brief parseBrief(String path, String text) {
        Map<String, Object> meta = splitFrontmatter(text).meta();
        String id = orElse(meta.get("id"), "");
        if (!BRIEF_ID.matcher(id).matches()) {
            return null;
        }
        String[] parts = path.split("/");
        String file = parts[parts.length - 1];
        String stem = file.endsWith(".md") ? file.substring(0, file.length() - 3) : file;
        Map<String, Object> build = Map.of();
        if (meta.get("build") instanceof Map<?, ?> b) {
            Map<String, Object> copy = new java.util.LinkedHashMap<>();
            b.forEach((k, v) -> copy.put(String.valueOf(k), v));
            build = copy;
        }
        return new Brief(id, orElse(meta.get("title"), stem), orElse(meta.get("status"), ""),
                orElse(meta.get("priority"), ""), asList(meta.get("repos")), build, meta.get("max_tasks"),
                parts.length >= 3 ? parts[parts.length - 3] : "", path, text);
    }

    /** {@code task_limit}: the brief's own {@code max_tasks} (0 = no limit), else the {@code PLAN_MAX_TASKS} setting. */
    public static int taskLimit(Brief b, int setting) {
        Object own = b.maxTasks();
        return own instanceof Integer i && i >= 0 ? i : setting;
    }

    /** {@code brief_new_repo}: (name, slug) of the new project's repo, only under the owner's account and listed in repos. */
    public static String[] newRepo(Brief b, String ownerGithub) {
        String slug = orElse(b.build().get("new_repo"), "");
        Matcher m = NEW_REPO.matcher(slug);
        if (!m.matches() || !m.group(1).equalsIgnoreCase(ownerGithub) || !b.repos().contains(m.group(2))) {
            return null;
        }
        return new String[] {m.group(2), slug};
    }

    /** The brief text with its {@code status:} value replaced; a comment after the value stays. */
    public static String withStatus(String text, String status) {
        return STATUS_VALUE.matcher(text).replaceFirst(m -> Matcher.quoteReplacement(m.group(1) + status));
    }

    /** The handoff edit of the brief: the whole {@code status:} line becomes {@code handed-off}. */
    public static String handedOff(String text) {
        return STATUS_LINE.matcher(text).replaceFirst("status: handed-off");
    }

    /** The roadmap lines of the brief get {@code Built: <prs>.} once. */
    public static String roadmapBuilt(String roadmap, String briefId, String prs) {
        StringBuilder out = new StringBuilder();
        for (String line : roadmap.split("(?<=\\n)")) {
            boolean mark = line.contains("[" + briefId + "]") && !line.contains("Built:");
            out.append(mark ? (line.endsWith("\n") ? line.substring(0, line.length() - 1) : line) + " Built: " + prs + ".\n" : line);
        }
        return out.toString();
    }

    // --- the planner ---

    /** The plan block of the planner; {@link IllegalArgumentException} when it is wrong. {@code limit} 0 = any number. */
    public static Plan parsePlan(String text, java.util.Collection<String> repos, int limit) {
        Matcher m = PLAN_BLOCK.matcher(text);
        if (!m.find()) {
            throw new IllegalArgumentException("the planner gave no plan block");
        }
        JsonNode data;
        try {
            data = JSON.readTree(m.group(1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the planner gave no valid JSON");
        }
        if (data.isObject() && !text(data.get("question")).strip().isEmpty()) {
            return new Plan(cut(text(data.get("question")).strip(), 500), List.of(), List.of());
        }
        JsonNode items = data.isObject() ? data.get("tasks") : null;
        if (items == null || !items.isArray() || items.isEmpty()) {
            throw new IllegalArgumentException("the planner gave no tasks");
        }
        if (limit > 0 && items.size() > limit) {
            throw new IllegalArgumentException("the planner gave " + items.size() + " tasks, at most " + limit);
        }
        List<PlannedTask> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int i = 0;
        for (JsonNode it : items) {
            if (!it.isObject()) {
                throw new IllegalArgumentException("invalid task from the planner");
            }
            String key = orElse(text(it.get("id")), String.valueOf(i));
            String repo = text(it.get("repo"));
            String body = text(it.get("text")).strip();
            if (!repos.contains(repo)) {
                throw new IllegalArgumentException("the planner chose a repo outside the brief: " + cut(repo, 40));
            }
            List<String> crit = new ArrayList<>();
            for (String c : asList(nodeValue(it.get("criteria")))) {
                crit.add(cut(c, 500));
            }
            if (body.isEmpty() || crit.isEmpty()) {
                throw new IllegalArgumentException("the planner gave a task without text or criteria");
            }
            if (seen.contains(key)) {
                throw new IllegalArgumentException("the planner gave two tasks with the same id");
            }
            String after = text(it.get("after"));
            out.add(new PlannedTask(key, repo, cut(body, 6000), crit.subList(0, Math.min(20, crit.size())),
                    seen.contains(after) ? after : ""));
            seen.add(key);
            i++;
        }
        List<String> manual = new ArrayList<>();
        for (String c : asList(nodeValue(data.get("manual")))) {
            manual.add(cut(c, 300));
        }
        return new Plan(null, out, manual.subList(0, Math.min(20, manual.size())));
    }

    /** A JSON value as Python's {@code str(x or "")}: null and missing give "". */
    private static String text(JsonNode n) {
        return n == null || n.isNull() ? "" : n.isString() ? n.asString() : n.toString();
    }

    private static Object nodeValue(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        if (n.isArray()) {
            List<Object> l = new ArrayList<>();
            n.forEach(e -> l.add(text(e)));
            return l;
        }
        return text(n);
    }

    // --- the reviewer ---

    public static Review parseReview(String text) {
        Matcher m = REVIEW_BLOCK.matcher(text);
        if (!m.find()) {
            throw new IllegalArgumentException("the reviewer gave no review block");
        }
        JsonNode data;
        try {
            data = JSON.readTree(m.group(1));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("the reviewer gave no valid JSON");
        }
        List<ReviewRow> rows = new ArrayList<>();
        JsonNode list = data.isObject() ? data.get("criteria") : null;
        if (list != null && list.isArray()) {
            for (JsonNode r : list) {
                if (!r.isObject()) {
                    continue;
                }
                String verdict = orElse(text(r.get("verdict")), "unclear").toLowerCase(Locale.ROOT);
                rows.add(new ReviewRow(cut(text(r.get("criterion")), 500),
                        VERDICTS.contains(verdict) ? verdict : "unclear", cut(text(r.get("note")), 300)));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("the reviewer judged no criteria");
        }
        return new Review(rows, cut(text(data.get("summary")).strip(), 600));
    }

    /** The files of the diff for the reviewer: code, tests and config first, docs last, without lockfiles. */
    public record DiffSelection(List<List<String>> groups, List<String> skipped) {}

    public static DiffSelection selectDiff(List<String> files) {
        List<String> skipped = new ArrayList<>();
        List<String> code = new ArrayList<>();
        List<String> docs = new ArrayList<>();
        for (String f : files) {
            if (f.isEmpty()) {
                continue;
            }
            if (REVIEW_SKIP.matcher(f).find()) {
                skipped.add(f);
            } else if (REVIEW_DOCS.matcher(f).find()) {
                docs.add(f);
            } else {
                code.add(f);
            }
        }
        List<List<String>> groups = new ArrayList<>();
        if (!code.isEmpty()) {
            groups.add(code);
        }
        if (!docs.isEmpty()) {
            groups.add(docs);
        }
        return new DiffSelection(groups, skipped);
    }
}
