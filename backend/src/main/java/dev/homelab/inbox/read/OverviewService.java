package dev.homelab.inbox.read;

import dev.homelab.inbox.read.Jobs.Job;
import dev.homelab.inbox.conversation.ConversationDb;
import dev.homelab.inbox.conversation.ConversationStore;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The data behind {@code GET /api/overview} and {@code GET /api/live}: a port of {@code overview.overview_json},
 * {@code live_items} and their helpers (H-024 M1). It reads the same files and the same tables as the Python inbox
 * and returns the same sentences; nothing is written. One difference: Python remembers when the running round of a
 * task started in memory ({@code _round_started}); that is not in the database, so a task in its second round or later
 * shows the start of the task itself in {@code started} (docs/inbox-backend.md, "Overview, live and log reads (M1)").
 */
@Service
public class OverviewService {

    private static final Duration TL_PAST = Duration.ofDays(7);
    private static final Duration TL_FUTURE = Duration.ofHours(24);
    private static final double TL_NOW = 70.0; // the last 24 hours get 30% of the width
    private static final Duration STALE_CHECK = Duration.ofMinutes(10); // the monitor runs every 2 minutes

    private static final Map<String, String> RUN_NL = Map.of("queued", "in de rij", "running", "bezig", "ok", "ok",
            "failed", "mislukt", "held", "wacht op limiet", "question", "vraag", "pr", "PR", "capped",
            "gestopt bij limiet", "cancelled", "geannuleerd");
    private static final Map<String, String> WHO = Map.of("t", "Claude", "r", "Het lokale model", "f", "Forge");
    private static final Map<String, String> TASK_TO_RUN = Map.ofEntries(Map.entry("queued", "queued"),
            Map.entry("running", "running"), Map.entry("waiting", "question"), Map.entry("pr_open", "pr"),
            Map.entry("merged", "pr"), Map.entry("closed", "pr"), Map.entry("answered", "ok"),
            Map.entry("capped", "capped"), Map.entry("cancelled", "cancelled"), Map.entry("failed", "failed"),
            Map.entry("refused", "failed"));
    private static final List<String> PERMISSIONS =
            List.of("read", "write", "ask", "start_task", "answer", "approve", "run_job", "research");

    private final Settings settings;
    private final InboxDb db;
    private final Jobs jobs;
    private final Forge forge;
    private final JsonMapper json;
    private final Clock clock;
    private final ConversationStore conversation;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OverviewService(Settings settings, InboxDb db, Jobs jobs, Forge forge, JsonMapper json, Clock clock,
                           ConversationStore conversation) {
        this.conversation = conversation;
        this.settings = settings;
        this.db = db;
        this.jobs = jobs;
        this.forge = forge;
        this.json = json;
        this.clock = clock;
    }

    public record Live(List<Map<String, Object>> items, int queued) {}

    private record RunView(String status, String summary, String link) {}

    // --- live -------------------------------------------------------------------------------------------------------

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String label(Map<String, Object> t) {
        return InboxDb.has(t, "job") ? "Job " + t.get("job") : "Inbox " + t.get("id");
    }

    private static String modelName(String model) {
        return model != null && !model.isEmpty() ? "Claude (" + (model.startsWith("claude-") ? model.substring(7) : model) + ")" : "Claude";
    }

    private static String first(String a, String b, String c) {
        return a != null && !a.isEmpty() ? a : b != null && !b.isEmpty() ? b : c;
    }

    /** What runs now: inbox tasks and Claude jobs (as tasks), local job runs and forge (H-015). */
    public Live live(Map<String, Job> jobFiles) {
        List<Map<String, Object>> items = new ArrayList<>();
        List<Map<String, Object>> recent = db.query("SELECT * FROM tasks ORDER BY id DESC LIMIT 100");
        for (Map<String, Object> t : recent) {
            if (!"running".equals(t.get("status"))) {
                continue;
            }
            String what;
            if (InboxDb.has(t, "job")) {
                Job job = jobFiles.get(InboxDb.str(t, "job"));
                what = job != null && job.kind() != null ? Jobs.KINDS.getOrDefault(job.kind(), "job") : "job";
            } else {
                what = Text.firstLine(InboxDb.str(t, "text"));
            }
            items.add(map("key", "t" + t.get("id"), "title", label(t), "what", what, "repo", t.get("repo"),
                    "model", modelName(InboxDb.str(t, "model")), "url", "/task/" + t.get("id"),
                    "cancel", "/task/" + t.get("id") + "/cancel",
                    "started", first(InboxDb.str(t, "started"), InboxDb.str(t, "created"), null)));
        }
        for (Map<String, Object> r : db.query("SELECT * FROM job_runs WHERE status='running' ORDER BY id")) {
            String kind = InboxDb.str(r, "kind");
            items.add(map("key", "r" + r.get("id"), "title", "Job " + r.get("job"), "what", Jobs.KINDS.getOrDefault(kind, kind),
                    "repo", "box", "model", "lokaal model (" + settings.conf("LLM_MODEL", "?") + ")",
                    "url", "/run/" + r.get("id"), "cancel", "/run/" + r.get("id") + "/cancel",
                    "started", first(InboxDb.str(r, "started"), InboxDb.str(r, "created"), null)));
        }
        forge.liveItem().ifPresent(items::add);
        int queued = (int) recent.stream().filter(t -> "queued".equals(t.get("status"))).count();
        return new Live(items, queued);
    }

    private Optional<Map.Entry<Instant, String>> nextJob(Map<String, Job> jobFiles) {
        Instant now = clock.instant();
        ZoneId tz = settings.tz();
        Map.Entry<Instant, String> best = null;
        for (Job job : jobFiles.values()) {
            if (!job.active()) {
                continue;
            }
            Instant next = job.cron().nextAfter(now, tz).toInstant();
            if (best == null || next.isBefore(best.getKey()) || (next.equals(best.getKey()) && job.name().compareTo(best.getValue()) < 0)) {
                best = Map.entry(next, job.name());
            }
        }
        return Optional.ofNullable(best);
    }

    public String activity(Live live, Map<String, Job> jobFiles) {
        List<Map<String, Object>> items = live.items();
        String nowPart;
        if (items.isEmpty()) {
            nowPart = "Niets bezig.";
        } else if (items.size() == 1 && items.get(0).get("key").toString().startsWith("f")) {
            Map<String, Object> it = items.get(0);
            String where = Text.truthy(it.get("repo")) ? " aan " + it.get("repo") : "";
            nowPart = "Forge bouwt" + where + ": " + it.get("what") + ".";
        } else if (items.size() == 1) {
            Map<String, Object> it = items.get(0);
            String who = WHO.getOrDefault(it.get("key").toString().substring(0, 1), "Claude");
            nowPart = who + " werkt aan " + Text.lowerFirst(it.get("title").toString()) + ".";
        } else {
            nowPart = "Bezig: " + Text.joinNl(items.stream().map(i -> Text.lowerFirst(i.get("title").toString())).toList()) + ".";
        }
        if (live.queued() > 0) {
            nowPart += " " + live.queued() + " in de rij.";
        }
        Optional<Map.Entry<Instant, String>> next = nextJob(jobFiles);
        return nowPart + next.map(n -> " Volgende job: " + n.getValue() + ", "
                + Text.dayTime(n.getKey(), settings.tz(), clock.instant()) + ".").orElse("");
    }

    /** The body of {@code GET /api/live}. */
    public Map<String, Object> liveBody() {
        Map<String, Job> jobFiles = jobs.load();
        Live live = live(jobFiles);
        return map("items", live.items(), "queued", live.queued(), "activity", activity(live, jobFiles));
    }

    // --- health -----------------------------------------------------------------------------------------------------

    private Object readJson(Path file) {
        return JsonFiles.readJson(json, file);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : new LinkedHashMap<>();
    }

    private record Health(Map<String, Object> checks, String last, List<Map<String, Object>> deploys,
                          Map<String, Object> backup, List<Map<String, Object>> changes) {}

    private Health health() {
        Map<String, Object> checks = asMap(readJson(settings.monitorDir().resolve("checks.json")));
        Object last = asMap(readJson(settings.monitorDir().resolve("last.json"))).get("at");
        List<Map<String, Object>> deploys = new ArrayList<>();
        Path state = settings.deployState();
        if (Files.isDirectory(state)) {
            try (Stream<Path> s = Files.list(state)) {
                for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                    String file = f.getFileName().toString();
                    String stem = file.substring(0, file.length() - 5);
                    if (stem.startsWith("_")) {
                        continue;
                    }
                    Map<String, Object> d = new LinkedHashMap<>(asMap(readJson(f)));
                    d.put("name", stem);
                    deploys.add(d);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot list " + state, e);
            }
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        try {
            List<String> lines = Files.readString(settings.changesLog()).lines().toList();
            for (String line : lines.subList(Math.max(0, lines.size() - 5), lines.size())) {
                changes.add(asMap(json.readValue(line, Object.class)));
            }
            java.util.Collections.reverse(changes);
        } catch (IOException | JacksonException e) {
            changes.clear();
        }
        Map<String, Object> backup = asMap(readJson(settings.backupStatus()));
        return new Health(checks, last == null ? null : last.toString(), deploys, backup, changes);
    }

    private String[] statusSentence(Health h) {
        List<String> fail = h.checks().entrySet().stream().filter(e -> "FAIL".equals(asMap(e.getValue()).get("status")))
                .map(Map.Entry::getKey).sorted().toList();
        List<String> warn = h.checks().entrySet().stream().filter(e -> "WARN".equals(asMap(e.getValue()).get("status")))
                .map(Map.Entry::getKey).sorted().toList();
        if (h.checks().isEmpty()) {
            return new String[] {"Er is nog geen gezondheidscheck gedraaid.", "attention"};
        }
        if (!fail.isEmpty()) {
            return new String[] {fail.size() == 1 ? "Eén check faalt: " + fail.get(0) + "."
                    : fail.size() + " checks falen: " + Text.joinNl(fail) + ".", "bad"};
        }
        Instant now = clock.instant();
        if (h.last() != null && !h.last().isEmpty() && Duration.between(Text.ts(h.last()), now).compareTo(STALE_CHECK) > 0) {
            return new String[] {"De bewaking heeft sinds " + Text.dayTime(Text.ts(h.last()), settings.tz(), now)
                    + " niet gecontroleerd.", "attention"};
        }
        if (!warn.isEmpty()) {
            return new String[] {warn.size() == 1 ? "Eén check vraagt aandacht: " + warn.get(0) + "."
                    : warn.size() + " checks vragen aandacht: " + Text.joinNl(warn) + ".", "attention"};
        }
        return new String[] {"Alles in orde.", "ok"};
    }

    private static String changeText(Map<String, Object> c) {
        String what;
        Object kind = c.get("kind");
        if ("instant".equals(kind) || "refused".equals(kind)) {
            Object args = c.get("args");
            String joined = args instanceof List<?> l ? String.join(" ", l.stream().map(Text::pyStr).toList()) : "";
            what = Text.pyStr(c.get("action")) + " " + joined;
        } else {
            Object change = c.containsKey("change") ? c.get("change") : "machine.conf";
            Object summary = Text.truthy(c.get("summary")) ? c.get("summary") : c.containsKey("result") ? c.get("result") : "";
            what = Text.pyStr(change) + ": " + Text.pyStr(summary);
        }
        return Text.cut(what.strip(), 90);
    }

    // --- timeline ---------------------------------------------------------------------------------------------------

    static double tlX(Instant dt, Instant now) {
        double d = ChronoUnit.MICROS.between(now, dt) / 1e6;
        if (d <= 0) {
            return Math.max(0.0, TL_NOW * (1 + d / (TL_PAST.toSeconds() * 1.0)));
        }
        return Math.min(100.0, TL_NOW + (100 - TL_NOW) * d / (TL_FUTURE.toSeconds() * 1.0));
    }

    private RunView runView(Map<String, Object> r, Map<Long, Map<String, Object>> taskCache) {
        Long taskId = InboxDb.num(r, "task_id");
        if (taskId != null && taskId != 0) {
            Map<String, Object> t = taskCache.computeIfAbsent(taskId, id -> {
                Map<String, Object> row = db.one("SELECT * FROM tasks WHERE id=?", id);
                return row == null ? Map.of() : row;
            });
            if (!t.isEmpty()) {
                String status = InboxDb.str(t, "status");
                String summary = first(InboxDb.str(t, "answer"), first(InboxDb.str(t, "summary"),
                        InboxDb.str(t, "question"), InboxDb.str(t, "note")), "");
                if (summary == null) {
                    summary = "";
                }
                return new RunView(TASK_TO_RUN.getOrDefault(status, status), summary, "/task/" + t.get("id"));
            }
        }
        String summary = first(InboxDb.str(r, "summary"), InboxDb.str(r, "note"), "");
        return new RunView(InboxDb.str(r, "status"), summary, "/run/" + r.get("id"));
    }

    private Map<String, Object> timeline(Map<String, Job> jobFiles, Map<Long, Map<String, Object>> taskCache) {
        ZoneId tz = settings.tz();
        Instant now = clock.instant();
        List<Map<String, Object>> runs = db.query("SELECT * FROM job_runs WHERE COALESCE(started, created) >= ? ORDER BY id",
                Text.isoUtc(now.minus(TL_PAST)));
        TreeSet<String> names = new TreeSet<>(jobFiles.keySet());
        runs.forEach(r -> names.add(InboxDb.str(r, "job")));
        List<Map<String, Object>> lanes = new ArrayList<>();
        for (String name : names) {
            Job job = jobFiles.get(name);
            List<Map<String, Object>> marks = new ArrayList<>();
            for (Map<String, Object> r : runs) {
                if (!name.equals(InboxDb.str(r, "job"))) {
                    continue;
                }
                RunView v = runView(r, taskCache);
                Instant at = Text.ts(first(InboxDb.str(r, "started"), InboxDb.str(r, "created"), null));
                marks.add(map("x", tlX(at, now), "status", v.status(), "link", v.link(),
                        "label", Text.dayTime(at, tz, now) + ": " + RUN_NL.getOrDefault(v.status(), v.status())
                                + (!v.summary().isEmpty() ? ". " + Text.firstLine(v.summary()) : "")));
            }
            if (job != null && job.active()) {
                Instant t = now;
                for (int i = 0; i < 200; i++) {
                    t = job.cron().nextAfter(t, tz).toInstant();
                    if (Duration.between(now, t).compareTo(TL_FUTURE) > 0) {
                        break;
                    }
                    marks.add(map("x", tlX(t, now), "status", "plan", "link", "/job/" + name,
                            "label", "gepland " + Text.dayTime(t, tz, now)));
                }
            }
            String state = job == null ? "deleted" : !job.ok() ? "error" : job.paused() ? "paused" : "active";
            boolean shown = state.equals("active") || state.equals("paused");
            lanes.add(map("name", name, "state", state, "model", shown ? job.model() : null,
                    "when", shown ? (!job.when().isEmpty() ? job.when() : job.schedule()) : null, "marks", marks));
        }
        List<Map<String, Object>> ticks = new ArrayList<>();
        ZonedDateTime localNow = now.atZone(tz);
        LocalDate today = localNow.toLocalDate();
        for (int i = -7; i < 2; i++) { // midnights: lines and weekdays
            ZonedDateTime m = today.plusDays(i).atStartOfDay(tz);
            double x = tlX(m.toInstant(), now);
            if (0 < x && x < 100) {
                ticks.add(map("x", x, "label", Text.dayName(m.getDayOfWeek()), "day", true));
            }
        }
        for (int h = 1; h <= 24; h++) { // the next 24 hours: 6u, 12u, 18u
            LocalDateTime wall = localNow.toLocalDateTime().plusHours(h).truncatedTo(ChronoUnit.HOURS);
            if (wall.getHour() == 6 || wall.getHour() == 12 || wall.getHour() == 18) {
                ticks.add(map("x", tlX(wall.atZone(tz).toInstant(), now), "label", wall.getHour() + "u", "day", false));
            }
        }
        ticks.removeIf(t -> Math.abs((Double) t.get("x") - TL_NOW) <= 6); // not across "nu", also on the phone
        return map("lanes", lanes, "ticks", ticks, "now_x", TL_NOW);
    }

    // --- usage ------------------------------------------------------------------------------------------------------

    private static List<Integer> perDay(List<String> isos, List<LocalDate> days, ZoneId tz) {
        Map<LocalDate, Integer> counts = new HashMap<>();
        days.forEach(d -> counts.put(d, 0));
        for (String iso : isos) {
            LocalDate d = Text.ts(iso).atZone(tz).toLocalDate();
            counts.computeIfPresent(d, (k, v) -> v + 1);
        }
        return days.stream().map(counts::get).toList();
    }

    private List<Integer> llmPerDay(List<LocalDate> days, ZoneId tz) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(settings.llmUrl() + "/usage/hourly?days=8"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            Object hours = asMap(json.readValue(response.body(), Object.class)).get("hours");
            if (!(hours instanceof List<?> list)) {
                return null;
            }
            Map<LocalDate, Integer> counts = new HashMap<>();
            days.forEach(d -> counts.put(d, 0));
            for (Object h : list) {
                Map<String, Object> hour = asMap(h);
                LocalDate d = OffsetDateTime.parse(hour.get("hour") + ":00:00+00:00").toInstant().atZone(tz).toLocalDate();
                int count = ((Number) hour.get("count")).intValue();
                counts.computeIfPresent(d, (k, v) -> v + count);
            }
            return days.stream().map(counts::get).toList();
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private Map<String, Object> usage(Map<String, Job> jobFiles, Map<Long, Map<String, Object>> taskCache) {
        ZoneId tz = settings.tz();
        LocalDate today = clock.instant().atZone(tz).toLocalDate();
        List<LocalDate> days = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            days.add(today.minusDays(i));
        }
        String since = Text.isoUtc(days.get(0).atStartOfDay(tz).toInstant());
        List<Integer> llm = llmPerDay(days, tz);
        List<String> claudeJobs = db.query("SELECT dispatched FROM job_runs WHERE task_id IS NOT NULL AND dispatched >= ?", since)
                .stream().map(r -> InboxDb.str(r, "dispatched")).toList();
        List<String> inbox = db.query("SELECT created FROM tasks WHERE job IS NULL AND created >= ?", since)
                .stream().map(r -> InboxDb.str(r, "created")).toList();
        List<Map<String, Object>> runs = db.query("SELECT * FROM job_runs WHERE COALESCE(started, created) >= ?", since);
        Map<String, Long> saved = new LinkedHashMap<>();
        for (Map<String, Object> r : runs) {
            Long mins = InboxDb.num(r, "minutes");
            if (mins == null) {
                Job job = jobFiles.get(InboxDb.str(r, "job"));
                mins = job != null ? job.minutes() : null;
            }
            String status = runView(r, taskCache).status();
            if (mins != null && mins != 0 && (status.equals("ok") || status.equals("pr"))) {
                String kind = InboxDb.str(r, "kind");
                saved.merge(Jobs.KINDS.getOrDefault(kind, String.valueOf(kind)), mins, Long::sum);
            }
        }
        return map("days", days.stream().map(LocalDate::toString).toList(), "llm", llm,
                "claude_jobs", perDay(claudeJobs, days, tz), "inbox", perDay(inbox, days, tz),
                "cap", settings.confInt("JOB_CLAUDE_CAP", 10), "saved_minutes", saved);
    }

    // --- switches ---------------------------------------------------------------------------------------------------

    private boolean switchOn(String name) {
        try {
            return conversation.switchOn(name);  // the switches live in the conversation store since M4 (PostgreSQL)
        } catch (ConversationDb.Unavailable e) {
            return true;  // as the Python inbox does: a switch counts as on unless it was turned off
        }
    }

    private List<String> clientMay(String client) {
        String text = settings.clientsJson();
        if (text == null) {
            return List.of();
        }
        try {
            Object clients = asMap(json.readValue(text, Object.class)).get("clients");
            Object entry = asMap(clients).get(client);
            if (!(entry instanceof Map<?, ?> e) || !(e.get("permissions") instanceof List<?> perms)) {
                return List.of();
            }
            return perms.stream().filter(p -> p instanceof String s && PERMISSIONS.contains(s)).map(Object::toString)
                    .distinct().sorted().toList();
        } catch (JacksonException e) {
            return List.of();
        }
    }

    // --- the overview -----------------------------------------------------------------------------------------------

    /** The body of {@code GET /api/overview}. */
    public Map<String, Object> overview() {
        Map<String, Job> jobFiles = jobs.load();
        Map<Long, Map<String, Object>> taskCache = new HashMap<>();
        Health h = health();
        String[] sentence = statusSentence(h);
        Live live = live(jobFiles);
        Map<String, Object> timeline = timeline(jobFiles, taskCache);
        Map<String, Object> usage = usage(jobFiles, taskCache);
        ZonedDateTime nowLocal = clock.instant().atZone(settings.tz());

        Map<String, Object> checks = new LinkedHashMap<>();
        h.checks().forEach((n, c) -> {
            Map<String, Object> cm = asMap(c);
            checks.put(n, map("status", cm.containsKey("status") ? cm.get("status") : "?",
                    "text", cm.containsKey("text") ? cm.get("text") : "", "since", cm.get("since")));
        });
        List<Map<String, Object>> deploys = new ArrayList<>();
        for (Map<String, Object> d : h.deploys()) {
            Map<String, Object> last = Text.truthy(d.get("last_deploy")) ? asMap(d.get("last_deploy")) : null;
            deploys.add(map("name", d.get("name"), "ok", last == null ? null : last.get("ok"),
                    "at", last == null ? null : last.get("at"), "sha", d.get("deployed_sha"),
                    "sync_error", d.get("sync_error")));
        }
        Map<String, Object> b = h.backup();
        Object backup = b.isEmpty() ? null : map("ok", Text.truthy(b.get("ok")), "at", b.get("at"),
                "free_bytes", b.containsKey("free_bytes") ? b.get("free_bytes") : 0, "free_pct", b.get("free_pct"));
        List<Map<String, Object>> changes = new ArrayList<>();
        for (Map<String, Object> c : h.changes()) {
            Object reason = Text.truthy(c.get("reason")) ? c.get("reason") : "";
            changes.add(map("ok", Text.truthy(c.get("ok")), "text", changeText(c), "at", c.get("at"),
                    "user", c.containsKey("user") ? c.get("user") : "", "reason", Text.cut(Text.pyStr(reason), 80)));
        }
        List<Map<String, Object>> services = new ArrayList<>();
        settings.services().forEach(s -> services.add(map("name", s.name(), "address", s.address(), "note", s.note())));
        String forgeLink = settings.services().stream().filter(s -> s.name().equals("Forge")).map(Settings.Service::address)
                .findFirst().orElse("");
        boolean discord = switchOn("discord");
        return map("status", map("sentence", sentence[0], "class", sentence[1], "at", Text.iso(nowLocal),
                        "timezone", settings.tz().getId(), "activity", activity(live, jobFiles)),
                "live", map("items", live.items(), "queued", live.queued()),
                "timeline", timeline,
                "changes", changes,
                "services", services,
                "forge_link", forgeLink,
                "switches", map("discord", discord, "discord_approvals", switchOn("discord_approvals"),
                        "discord_on", discord, "kitt", switchOn("kitt"), "kitt_may", clientMay("kitt")),
                "usage", usage,
                "health", map("checks", checks, "last", h.last(), "deploys", deploys, "backup", backup));
    }
}
