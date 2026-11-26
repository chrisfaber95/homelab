package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.MemorySteps;
import dev.homelab.inbox.core.Effects.TaskHistory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The memory steps around a round (F-023, {@code memory.py}): the clone brought up to date before a round, Claude's
 * {@code GEHEUGEN:} blocks split off its answer and saved after it, the history of a finished task, and the memory part of
 * the system prompt. The store itself is Java's since H-024 M5: {@link Store} is the door to it.
 */
final class LiveMemory implements MemorySteps, TaskHistory {

    private static final Logger log = LoggerFactory.getLogger(LiveMemory.class);
    private static final Pattern BLOCK = Pattern.compile(
            "^GEHEUGEN:[ \\t]*(voorkeur|feit)[ \\t]+([a-z0-9][a-z0-9-]*)[ \\t]+([a-z0-9][a-z0-9-]{0,40})[ \\t]*\\n"
                    + "(.*?)(?=^GEHEUGEN:|^SKILL-VOORSTEL:|\\z)", Pattern.DOTALL | Pattern.MULTILINE);
    private static final java.util.Set<String> FINAL = java.util.Set.of("answered", "pr_open", "capped", "cancelled",
            "failed", "refused", "merged", "closed", "routed");

    static final String MEMORY_PROMPT = """

            Geheugen (F-023). De gebruiker ziet, bewerkt en wist alles hiervan op de pagina (tab Geheugen).
            Voorkeuren van de gebruiker (volg ze):
            {prefs}
            Feiten over de box en {repo}:
            {facts}
            Geschiedenis: één bestand per eerdere taak of Claude-job in {hist} (kop met datum, status en link). Vraagt de taak naar
            iets van eerder ("wanneer faalde ... het laatst?"), zoek daar dan met grep en noem in je antwoord de datum en de link
            van de taak die je vond. Recent voor {repo}:
            {recent}
            - Bewaren: leerde je iets blijvends, zet dan onder je laatste bericht (vóór een eventueel SKILL-VOORSTEL) één blok per
              item, precies zo:
              GEHEUGEN: voorkeur <algemeen|repo> <naam-met-streepjes>
              de voorkeur in één tot drie zinnen
              GEHEUGEN: feit <box|repo> <naam-met-streepjes>
              het feit in één tot drie zinnen
              Een voorkeur als de gebruiker je corrigeert of zegt hoe het anders moet (in PR-commentaar of een antwoord) en dat ook
              voor latere taken geldt. Een feit als je iets blijvends over de box of een repo ontdekt (een dienst, een build- of
              deploystap) of een bekend feit niet meer klopt. Gaat het over iets wat er al staat, gebruik dan precies die naam:
              dan wordt het bijgewerkt in plaats van verdubbeld. Niets nieuws: geen blok.
              Nooit tokens, wachtwoorden, sleutels of andere geheimen, en niets over andere mensen dan de gebruiker.""";

    /** The store of the memory branch and the clone's entries. */
    interface Store {
        /** Bring the clone to GitHub ({@code backend("sync")}); may throw. */
        void sync();

        /** The file of an entry in the clone. */
        Path file(String id);

        /** The entries as {@code memory.MEMORY} holds them: id, kind, about, name, body, added, changed, status, link. */
        List<Map<String, Object>> entries();

        List<String> learned(List<String[]> blocks, String source, String link, String labelLower);

        /** The history entry of a finished task; false when it was left alone. */
        boolean history(Task t, String label, String labelLower, String link, String statusNl, String title, String asked,
                        String result);
    }

    private final Store store;
    private final TaskStore tasks;
    private final Path runnerHome;
    private final String pageBase;
    private final int recent;
    private final Supplier<Map<String, dev.homelab.inbox.read.Jobs.Job>> jobs;

    LiveMemory(Store store, TaskStore tasks, Path runnerHome, String pageBase, int recent,
               Supplier<Map<String, dev.homelab.inbox.read.Jobs.Job>> jobs) {
        this.store = store;
        this.tasks = tasks;
        this.runnerHome = runnerHome;
        this.pageBase = pageBase;
        this.recent = recent;
        this.jobs = jobs;
    }

    @Override
    public void load() {
        try {
            store.sync();
        } catch (RuntimeException e) {
            log.warn("geheugen ophalen: {}", e.toString()); // then the clone as it is
        }
    }

    @Override
    public MemorySplit split(String text) {
        List<String> blocks = new ArrayList<>();
        Matcher m = BLOCK.matcher(text);
        while (m.find()) {
            blocks.add(m.group());
        }
        return new MemorySplit(BLOCK.matcher(text).replaceAll("").strip(), blocks);
    }

    private static List<String[]> parse(List<String> raw) {
        List<String[]> out = new ArrayList<>();
        for (String r : raw) {
            Matcher m = BLOCK.matcher(r);
            if (m.find()) {
                out.add(new String[] {m.group(1), m.group(2), m.group(3), m.group(4).strip()});
            }
        }
        return out;
    }

    @Override
    public void afterRound(long taskId, List<String> blocks) {
        List<String> notes = new ArrayList<>();
        try {
            if (!blocks.isEmpty()) {
                Task t = tasks.get(taskId).orElseThrow();
                String source = t.has("mem_source") ? t.str("mem_source") : TaskActions.label(t);
                String link = t.has("mem_link") ? t.str("mem_link") : pageBase + "/task/" + taskId;
                notes.addAll(store.learned(parse(blocks), source, link, TaskActions.label(t).toLowerCase()));
            }
        } catch (RuntimeException e) {
            log.warn("taak {}: geheugen bewaren: {}", taskId, e.toString());
            String why = "geheugen bewaren mislukt: " + e.getMessage();
            notes = new ArrayList<>(List.of(why.length() > 200 ? why.substring(0, 200) : why));
        }
        try {
            remember(taskId);
        } catch (RuntimeException e) {
            log.warn("taak {}: geschiedenis: {}", taskId, e.toString());
        }
        Share.remove(runnerHome.resolve("share").resolve(String.valueOf(taskId)).resolve("geschiedenis"));
        if (!notes.isEmpty()) {
            Task t = tasks.get(taskId).orElseThrow();
            List<String> all = new ArrayList<>();
            if (t.has("note") && !t.str("note").isEmpty()) {
                all.add(t.str("note"));
            }
            all.addAll(notes);
            String joined = String.join("; ", all);
            tasks.update(taskId, TaskStore.fields("note", joined.length() > 1000 ? joined.substring(0, 1000) : joined));
        }
    }

    /** {@code memory.remember_task}: the history entry of a finished task; left alone after the user erased it. */
    @Override
    public void remember(long taskId) {
        Task t = tasks.get(taskId).orElse(null);
        if (t == null || !FINAL.contains(t.status())) {
            return;
        }
        String label = TaskActions.label(t);
        String asked;
        String title;
        if (t.has("job") && !t.str("job").isEmpty()) {
            var job = jobs.get().get(t.str("job"));
            String instruction = job != null && job.ok() ? job.instruction() : null;
            asked = instruction != null && !instruction.isEmpty() ? instruction : "job " + t.str("job");
            title = "job " + t.str("job") + ": " + RoundFinisher.firstLine(instruction == null ? "" : instruction);
        } else {
            asked = t.str("text");
            title = "inbox " + t.id() + ": " + RoundFinisher.firstLine(t.str("text"));
        }
        String result = "";
        for (String c : new String[] {"answer", "summary", "question", "note"}) {
            if (t.has(c) && !t.str(c).isEmpty()) {
                result = t.str(c);
                break;
            }
        }
        if (store.history(t, label, label.toLowerCase(), pageBase + "/task/" + t.id(),
                TaskActions.STATUS_NL.getOrDefault(t.status(), t.status()), title, cut(asked, 1000), cut(result, 2000))) {
            tasks.update(taskId, TaskStore.fields("remembered", 1));
        }
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** {@code memory.memory_for}: the memory part of the system prompt; the history goes as files next to the task. */
    String memoryFor(Task t) {
        String repo = t.repo();
        Path hist = runnerHome.resolve("share").resolve(String.valueOf(t.id())).resolve("geschiedenis");
        Share.remove(hist);
        try {
            Files.createDirectories(hist);
        } catch (IOException e) {
            throw new IllegalStateException("geschiedenis: " + e.getMessage(), e);
        }
        List<Map<String, Object>> all = store.entries();
        List<Map<String, Object>> entries = all.stream().filter(m -> "geschiedenis".equals(m.get("kind")))
                .sorted(Comparator.comparing((Map<String, Object> m) -> String.valueOf(m.get("changed"))).reversed()).toList();
        for (Map<String, Object> m : entries) {
            String id = (String) m.get("id");
            String name = id.substring(id.indexOf('/') + 1).replace('/', '-') + ".md";
            try {
                Files.copy(store.file(id), hist.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IllegalStateException("geschiedenis: " + e.getMessage(), e);
            }
        }
        List<Map<String, Object>> prefs = all.stream().filter(m -> "voorkeuren".equals(m.get("kind"))
                && List.of("algemeen", repo).contains(m.get("about"))).toList();
        List<Map<String, Object>> facts = all.stream().filter(m -> "feiten".equals(m.get("kind"))
                && List.of("box", repo).contains(m.get("about"))).toList();
        List<String> recentLines = entries.stream().filter(m -> repo.equals(m.get("about"))).limit(recent)
                .map(m -> "- " + String.valueOf(m.get("added")).substring(0, Math.min(10, String.valueOf(m.get("added")).length()))
                        + " " + m.get("status") + ": " + RoundFinisher.firstLine(String.valueOf(m.get("body"))).replaceFirst("^[# ]+", "")
                        + " (" + m.get("link") + ")").toList();
        return MEMORY_PROMPT.replace("{prefs}", lines(prefs)).replace("{facts}", lines(facts)).replace("{repo}", repo)
                .replace("{hist}", hist.toString())
                .replace("{recent}", recentLines.isEmpty() ? "- (nog niets)" : String.join("\n", recentLines));
    }

    private static String lines(List<Map<String, Object>> items) {
        return items.isEmpty() ? "- (nog niets)" : items.stream()
                .map(m -> "- [" + m.get("about") + "/" + m.get("name") + "] " + m.get("body")).collect(java.util.stream.Collectors.joining("\n"));
    }
}
