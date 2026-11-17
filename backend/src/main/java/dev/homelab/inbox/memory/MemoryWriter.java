package dev.homelab.inbox.memory;

import dev.homelab.inbox.conversation.ConversationStore;
import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.MemoryReader;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * The memory writes of the Python inbox, moved here (H-024 M5): the page's add, save and delete ({@code server.post_memory}),
 * what Claude learned ({@code memory.save_learned}) and the history of a finished task ({@code memory.remember_task}).
 * The messages, the checks and the file format are the ones the Python code had; Python only passes on what it knows
 * (the form, the task) and shows the answer.
 */
@Component
public class MemoryWriter {

    /** A rule of the memory refused the call: the text is what the page shows (HTTP 400, or 404 for an unknown entry). */
    public static final class Refused extends RuntimeException {
        private final int status;

        public Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    public static final int MEM_MAX = 1500;
    static final int PAGE_MAX = 4000;
    static final String[] CLAUDE = {"Claude (homelab-inbox)", "noreply@anthropic.com"};
    private static final DateTimeFormatter NOW = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    public record Author(String name, String email) {}

    private final MemoryRepo repo;
    private final MemoryReader reader;
    private final Jobs jobs;

    public MemoryWriter(MemoryRepo repo, MemoryReader reader, Jobs jobs) {
        this.repo = repo;
        this.reader = reader;
        this.jobs = jobs;
    }

    static String now() {
        return OffsetDateTime.now(ZoneOffset.UTC).format(NOW);
    }

    /** {@code json.dumps(s, ensure_ascii=False)} */
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                case '\b' -> b.append("\\b");
                case '\f' -> b.append("\\f");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }

    /** {@code memory.entry_text}: the front matter (JSON-quoted values, empty link and extras left out) and the body. */
    static String entryText(String about, String source, String link, String body, String added, Map<String, String> extra) {
        Map<String, String> head = new LinkedHashMap<>();
        head.put("over", about);
        head.put("toegevoegd", added);
        head.put("gewijzigd", now());
        head.put("bron", source);
        Map<String, String> more = new LinkedHashMap<>();
        more.put("link", link);
        more.putAll(extra);
        more.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                head.put(k, v);
            }
        });
        StringBuilder b = new StringBuilder("---\n");
        head.forEach((k, v) -> b.append(k).append(": ").append(quote(v)).append('\n'));
        return b.append("---\n").append(body.strip()).append('\n').toString();
    }

    private static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    private static boolean secret(String text) {
        return ConversationStore.SECRET.matcher(text).find();
    }

    /** {@code server.check_mem_text} */
    static void checkText(String text) {
        if (text.isEmpty()) {
            throw new Refused(400, "de tekst is leeg");
        }
        if (length(text) > PAGE_MAX) {
            throw new Refused(400, "hooguit 4000 tekens");
        }
        if (secret(text)) {
            throw new Refused(400, "dit lijkt een token, wachtwoord of sleutel te bevatten; dat komt nooit in het geheugen");
        }
    }

    private static String pageText(Map<String, String> form) {
        return form.getOrDefault("text", "").replace("\r\n", "\n").strip();
    }

    private void save(Map<String, String> changes, String message, Author author) {
        try {
            repo.save(changes, message, author.name(), author.email());
        } catch (MemoryRepo.Failed e) {
            throw new Refused(400, e.getMessage());
        }
    }

    /** POST /memory/add: returns the page to go to. */
    public String add(Map<String, String> form, String who, Author author) {
        String kind = form.getOrDefault("kind", "");
        String about = form.getOrDefault("about", "");
        String name = form.getOrDefault("name", "").strip();
        String text = pageText(form);
        String mid = kind + "/" + about + "/" + name;
        Set<String> abouts = new TreeSet<>(jobs.repoNames());
        abouts.addAll(List.of("algemeen", "box"));
        if (!(kind.equals("voorkeuren") || kind.equals("feiten")) || !abouts.contains(about)
                || !MemoryReader.ID.matcher(mid).matches()) {
            throw new Refused(400, "kies voorkeur of feit, een onderwerp uit de lijst en een naam met kleine "
                    + "letters, cijfers en streepjes");
        }
        if (reader.find(mid).isPresent()) {
            throw new Refused(400, mid + " bestaat al; bewerk dat item");
        }
        checkText(text);
        save(Map.of(mid, entryText(about, "door jou toegevoegd", "", text, now(), Map.of())),
                "geheugen: " + mid + " nieuw (" + who + ")", author);
        return "/memory/e/" + mid;
    }

    private Map<String, Object> existing(String id) {
        return reader.find(id).orElseThrow(() -> new Refused(404, "Niet gevonden."));
    }

    /** POST /memory/e/&lt;id&gt;/save: returns the page to go to. */
    public String edit(String id, Map<String, String> form, String who, Author author) {
        Map<String, Object> entry = existing(id);
        String text = pageText(form);
        checkText(text);
        String old = (String) entry.get("source");
        String source = old.endsWith("bewerkt door jou") || old.equals("door jou toegevoegd") ? old
                : (old.isEmpty() ? "-" : old) + ", bewerkt door jou";
        String added = (String) entry.get("added");
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("status", (String) entry.get("status"));
        extra.put("pr", (String) entry.get("pr"));
        save(Map.of(id, entryText((String) entry.get("about"), source, (String) entry.get("link"), text,
                added.isEmpty() ? now() : added, extra)), "geheugen: " + id + " bewerkt (" + who + ")", author);
        return "/memory/e/" + id;
    }

    /** POST /memory/e/&lt;id&gt;/delete: returns the page to go to. */
    public String delete(String id, String who, Author author) {
        existing(id);
        Map<String, String> erase = new LinkedHashMap<>();
        erase.put(id, null);
        save(erase, "geheugen: " + id + " gewist (" + who + ")", author);
        return "/memory";
    }

    public record Block(String kind, String about, String name, String body) {}

    /** {@code memory.save_learned}: the GEHEUGEN: blocks of Claude's last message; returns the notes for the task. */
    public List<String> learned(List<Block> blocks, String source, String link, String labelLower) {
        Set<String> repos = jobs.repoNames();
        Map<String, String> changes = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        for (Block b : blocks) {
            String folder = b.kind().equals("voorkeur") ? "voorkeuren" : "feiten";
            String id = folder + "/" + b.about() + "/" + b.name();
            if (!repos.contains(b.about()) && !b.about().equals(b.kind().equals("voorkeur") ? "algemeen" : "box")) {
                notes.add(b.name() + ": onbekend onderwerp " + b.about());
            } else if (b.body().isEmpty() || length(b.body()) > MEM_MAX) {
                notes.add(b.name() + ": leeg of langer dan " + MEM_MAX + " tekens");
            } else if (secret(b.body())) {
                notes.add(b.name() + ": niet bewaard, het lijkt een geheim te bevatten");
            } else {
                String added = reader.find(id).map(m -> (String) m.get("added")).orElseGet(MemoryWriter::now);
                changes.put(id, entryText(b.about(), source, link, b.body(), added, Map.of()));
            }
        }
        if (!changes.isEmpty()) {
            save(changes, "geheugen: " + String.join(", ", changes.keySet()) + " (Claude, " + labelLower + "; bron: " + source + ")",
                    new Author(CLAUDE[0], CLAUDE[1]));
            notes.add(0, changes.size() + " item(s) in het geheugen bewaard");
        }
        return notes;
    }

    /** What {@code memory.remember_task} read from the task. {@code asked} and {@code result} are already cut (1000 and 2000 characters). */
    public record Task(long id, String created, String repo, String label, String labelLower, String link, String statusNl,
                       String prUrl, String job, String title, String asked, String result, boolean remembered) {}

    /** {@code memory.remember_task}: the history entry of a finished task; false when it was left alone (erased by the user). */
    public boolean history(Task t) {
        String id = "geschiedenis/" + t.created().substring(0, 7) + "/taak-" + t.id();
        Optional<Map<String, Object>> old = reader.find(id);
        if (t.remembered() && old.isEmpty()) {
            return false;
        }
        String body = "# " + t.title() + "\n\nGevraagd:\n" + t.asked() + "\n\nResultaat:\n" + t.result();
        body = ConversationStore.SECRET.matcher(body).replaceAll("[verborgen]");
        String added = old.map(m -> (String) m.get("added")).orElse(t.created());
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("status", t.statusNl());
        extra.put("pr", t.prUrl());
        extra.put("job", t.job());
        save(Map.of(id, entryText(t.repo(), t.label(), t.link(), body, added, extra)),
                "geheugen: geschiedenis " + t.labelLower() + " (" + t.statusNl() + ")",
                new Author("homelab-inbox", "homelab-inbox@homelab"));
        return true;
    }
}
