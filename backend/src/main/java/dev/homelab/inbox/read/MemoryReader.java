package dev.homelab.inbox.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * The memory entries of the {@code memory-repo} clone, read the way {@code memory.py} reads them (H-024 M2): one
 * Markdown file per entry with a YAML front matter, the three sections of the Memory page, and one entry. Read-only:
 * the Python inbox is still the only writer (it commits and pushes on the {@code memory} branch and refreshes the
 * clone); this class reads the files of the clone on each request, where Python keeps them in memory between writes.
 * Known difference: a hand-edited front matter with an unquoted date and time ({@code 2026-11-02T10:00:00Z}) is
 * a {@code datetime} for PyYAML, which shows it with a space; here it stays the text written. A plain date and
 * the quoted values the inbox writes are the same.
 */
@Component
public class MemoryReader {

    private static final List<String> KINDS = List.of("voorkeuren", "feiten", "geschiedenis");
    private static final Map<String, String> KIND_LABELS =
            Map.of("voorkeuren", "voorkeur", "feiten", "feit", "geschiedenis", "geschiedenis");
    private static final Pattern ID = Pattern.compile("(?:(?:voorkeuren|feiten)/[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9-]{0,40}"
            + "|geschiedenis/[0-9]{4}-[0-9]{2}/[a-z0-9][a-z0-9-]{0,40})");
    private static final int HISTORY_SHOWN = 30;

    /** YAML 1.1 as SnakeYAML reads it, but a date stays the text that was written ({@code 2026-11-02}), as {@code str(date)} shows it. */
    private static final class NoTimestamps extends Resolver {
        @Override
        protected void addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, BOOL, "yYnNtTfFoO");
            addImplicitResolver(Tag.INT, INT, "-+0123456789");
            addImplicitResolver(Tag.FLOAT, FLOAT, "-+0123456789.");
            addImplicitResolver(Tag.MERGE, MERGE, "<");
            addImplicitResolver(Tag.NULL, NULL, "~nN\0");
            addImplicitResolver(Tag.NULL, EMPTY, null);
        }
    }

    private final Settings settings;
    private final Jobs jobs;

    public MemoryReader(Settings settings, Jobs jobs) {
        this.settings = settings;
        this.jobs = jobs;
    }

    /** memory.parse_entry */
    static Map<String, Object> parse(String id, String text) {
        Map<?, ?> meta = Map.of();
        String body = text;
        if (text.startsWith("---\n")) {
            String after = text.substring(4);
            int close = after.indexOf("\n---");
            if (close >= 0) {
                try {
                    Object loaded = yaml().load(after.substring(0, close));
                    if (loaded instanceof Map<?, ?> m) {
                        meta = m;
                    }
                } catch (RuntimeException e) {
                    meta = Map.of();
                }
                String rest = after.substring(close + 4);
                body = rest.indexOf('\n') < 0 ? "" : rest.substring(rest.indexOf('\n') + 1);
            }
        }
        String kind = id.substring(0, id.indexOf('/'));
        String[] parts = id.split("/");
        Object changed = Text.truthy(meta.get("gewijzigd")) ? meta.get("gewijzigd") : meta.get("toegevoegd");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", id);
        entry.put("kind", kind);
        entry.put("about", field(meta, "over", kind.equals("geschiedenis") ? "algemeen" : parts[1]));
        entry.put("name", parts[parts.length - 1]);
        entry.put("body", body.strip());
        entry.put("added", field(meta, "toegevoegd", ""));
        entry.put("changed", Text.truthy(changed) ? Text.pyStr(changed) : "");
        entry.put("source", field(meta, "bron", ""));
        entry.put("link", field(meta, "link", ""));
        entry.put("status", field(meta, "status", ""));
        entry.put("pr", field(meta, "pr", ""));
        return entry;
    }

    private static Yaml yaml() {
        LoaderOptions options = new LoaderOptions();
        return new Yaml(new SafeConstructor(options), new Representer(new DumperOptions()), new DumperOptions(), options,
                new NoTimestamps());
    }

    /** {@code str(meta.get(key) or fallback)} */
    private static String field(Map<?, ?> meta, String key, String fallback) {
        Object v = meta.get(key);
        return Text.truthy(v) ? Text.pyStr(v) : fallback;
    }

    /** memory.mem_json: the entry plus what the page derives from it. */
    private Map<String, Object> json(Map<String, Object> m) {
        String kind = (String) m.get("kind");
        String body = (String) m.get("body");
        Map<String, Object> out = new LinkedHashMap<>(m);
        out.put("title", kind.equals("geschiedenis") ? Text.firstLine(body).replaceFirst("^[# ]+", "") : m.get("name"));
        out.put("summary", kind.equals("geschiedenis") ? "" : Text.firstLine(body));
        out.put("kind_label", KIND_LABELS.get(kind));
        out.put("added_local", Text.local((String) m.get("added"), settings.tz()));
        out.put("changed_local", Text.local((String) m.get("changed"), settings.tz()));
        return out;
    }

    /** All entries, in the order Python loads them: sorted by path (compared part by part, with the {@code .md}). */
    List<Map<String, Object>> load() {
        Path root = settings.memoryDir();
        List<String> ids = new ArrayList<>();
        for (String kind : KINDS) {
            Path kindDir = root.resolve(kind);
            if (!Files.isDirectory(kindDir)) {
                continue;
            }
            try (Stream<Path> groups = Files.list(kindDir)) {
                for (Path group : groups.filter(Files::isDirectory).toList()) {
                    try (Stream<Path> files = Files.list(group)) {
                        files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".md"))
                                .map(n -> kind + "/" + group.getFileName() + "/" + n.substring(0, n.length() - 3))
                                .filter(id -> ID.matcher(id).matches()).forEach(ids::add);
                    }
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot list " + kindDir + ": " + e.getMessage(), e);
            }
        }
        ids.sort(Comparator.comparing(MemoryReader::pathParts, MemoryReader::compareParts));
        List<Map<String, Object>> found = new ArrayList<>();
        ids.forEach(id -> read(id).ifPresent(found::add));
        return found;
    }

    private static String[] pathParts(String id) {
        String[] parts = id.split("/");
        parts[parts.length - 1] += ".md";
        return parts;
    }

    private static int compareParts(String[] a, String[] b) {
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            int c = a[i].compareTo(b[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private Optional<Map<String, Object>> read(String id) {
        if (!ID.matcher(id).matches()) {
            return Optional.empty();
        }
        Path file = settings.memoryDir().resolve(id + ".md");
        try {
            return Files.isRegularFile(file) ? Optional.of(parse(id, Files.readString(file))) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty(); // a file that vanished (a sync of the clone) or is no text: not an entry
        }
    }

    /** memory.memory_view: what the Memory page renders. */
    public Map<String, Object> view(String q) {
        String lowered = q.toLowerCase(Locale.ROOT).strip();
        String[] words = lowered.isEmpty() ? new String[0] : lowered.split("\\s+");
        List<Map<String, Object>> hits = load().stream().filter(m -> matches(m, words)).toList();
        List<Map<String, Object>> sections = new ArrayList<>();
        for (String kind : KINDS) {
            List<Map<String, Object>> items = new ArrayList<>(hits.stream().filter(m -> m.get("kind").equals(kind)).toList());
            if (kind.equals("geschiedenis")) {
                items.sort(Comparator.comparing((Map<String, Object> m) -> (String) m.get("added")).reversed());
            }
            boolean all = !q.isEmpty() || !kind.equals("geschiedenis") || items.size() <= HISTORY_SHOWN;
            List<Map<String, Object>> shown = all ? items : items.subList(0, HISTORY_SHOWN);
            Map<String, Object> section = new LinkedHashMap<>();
            section.put("kind", kind);
            section.put("count", items.size());
            section.put("truncated", shown.size() < items.size());
            section.put("entries", shown.stream().map(this::json).toList());
            sections.add(section);
        }
        List<String> abouts = new ArrayList<>(List.of("algemeen", "box"));
        abouts.addAll(jobs.repoNames());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("q", q);
        out.put("sections", sections);
        out.put("abouts", abouts);
        return out;
    }

    private static boolean matches(Map<String, Object> m, String[] words) {
        String hay = String.join(" ", (String) m.get("id"), (String) m.get("about"), (String) m.get("source"),
                (String) m.get("status"), (String) m.get("body")).toLowerCase(Locale.ROOT);
        for (String w : words) {
            if (!hay.contains(w)) {
                return false;
            }
        }
        return true;
    }

    /** memory.entry_view for one id: the entry and where it lives; empty when there is no such entry. */
    public Optional<Map<String, Object>> entry(String id) {
        return read(id).map(m -> {
            Map<String, Object> out = json(m);
            out.put("branch", settings.memoryBranch());
            out.put("data_repo", settings.dataRepo());
            return out;
        });
    }
}
