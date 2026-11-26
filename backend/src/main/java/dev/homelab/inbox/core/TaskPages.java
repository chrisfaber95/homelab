package dev.homelab.inbox.core;

import dev.homelab.inbox.read.Jobs;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import tools.jackson.databind.json.JsonMapper;

/**
 * The data of the task pages ({@code overview.tasks_view}, {@code task_view}, {@code task_row_view}; H-026 final coverage
 * check), as the maps the JSON API shows: the queue and history, and one task with every derived fact, the forms that
 * apply ({@code actions}) and the log so far. Pure: it reads through {@link TaskStore}, {@link FollowupChain} and
 * {@link Briefs}. No route uses it yet (the route stays in Python until H-027).
 */
public class TaskPages {

    private static final Set<String> RETRYABLE = Set.of("failed", "cancelled", "capped", "refused");
    private static final Set<String> ACCEPTABLE = Set.of("failed", "cancelled", "capped", "refused", "closed");
    private static final int LOG_LINES = 2000;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One chunk of a live log: the lines from {@code start} and where to poll on. */
    public record LogChunk(List<Object> lines, int next) {}

    /** {@code helpers.read_log}: at most {@code limit} lines of the log {@code key} from line {@code start}. */
    public interface Logs {
        LogChunk read(String key, int start, int limit);
    }

    private final TaskStore store;
    private final FollowupChain chain;
    private final Briefs briefs;
    private final Logs logs;
    private final Function<String, String> jobKind;
    private final CoreSettings settings;
    private final String timezone;
    private final Function<Task, String> modelName;

    /**
     * @param jobKind the kind of a job by name, or null when the job file is gone ({@code JOBS.get(name)["kind"]})
     * @param timezone the zone the page shows times in
     */
    public TaskPages(TaskStore store, FollowupChain chain, Briefs briefs, Logs logs, Function<String, String> jobKind,
                     CoreSettings settings, String timezone) {
        this.store = store;
        this.chain = chain;
        this.briefs = briefs;
        this.logs = logs;
        this.jobKind = jobKind;
        this.settings = settings;
        this.timezone = timezone;
        this.modelName = t -> t.has("model") && !t.str("model").isEmpty()
                ? "Claude (" + t.str("model").replaceFirst("^claude-", "") + ")" : "Claude";
    }

    private static boolean filled(Task t, String column) {
        String v = t.str(column);
        return v != null && !v.isEmpty() && !(t.row().get(column) instanceof Number n && n.longValue() == 0);
    }

    static String label(Task t) {
        return filled(t, "job") ? "Job " + t.str("job") : "Inbox " + t.id();
    }

    private String what(Task t) {
        if (filled(t, "job")) {
            String kind = jobKind.apply(t.str("job"));
            return kind == null ? "job" : Jobs.KINDS.getOrDefault(kind, "job");
        }
        String text = t.str("text") == null ? "" : t.str("text").strip();
        if (text.isEmpty()) {
            return "taak";
        }
        String first = text.lines().findFirst().orElse("");
        return first.length() > 70 ? first.substring(0, 70) : first;
    }

    /** One row of the task lists ({@code overview.task_row_view}), the times as stored. */
    public Map<String, Object> row(Task t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("label", label(t));
        m.put("status", t.status());
        m.put("status_text", TaskActions.STATUS_NL.get(t.status()));
        m.put("what", what(t));
        m.put("repo", t.repo());
        m.put("source", t.str("source"));
        m.put("created", t.str("created"));
        m.put("ended", t.str("ended"));
        m.put("pr_url", t.str("pr_url"));
        m.put("pr_number", t.numOrNull("pr_number"));
        return m;
    }

    private static boolean active(String status) {
        return Scheduler.ACTIVE.contains(status);
    }

    /** The repos for the new-task form, the queue (oldest first) and the history (newest first). */
    public Map<String, Object> list(List<String> repos) {
        List<Task> all = store.tasks(200);
        List<Task> queue = new ArrayList<>(all.stream().filter(t -> active(t.status()) || t.status().equals("waiting")).toList());
        java.util.Collections.reverse(queue);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("repos", repos.stream().sorted().toList());
        m.put("auto", CoreSettings.AUTO);
        m.put("queue", queue.stream().map(this::row).toList());
        m.put("history", all.stream().filter(t -> !queue.contains(t)).map(this::row).toList());
        m.put("refresh", !queue.isEmpty());
        m.put("timezone", timezone);
        return m;
    }

    /** One task with everything its page shows, or empty for an unknown id. */
    public Optional<Map<String, Object>> detail(long id) {
        return store.get(id).map(this::view);
    }

    private Map<String, Object> view(Task t) {
        boolean isBuild = t.repo().equals(CoreSettings.BRIEF);
        Optional<Task> pre = RETRYABLE.contains(t.status()) && !t.repo().equals(CoreSettings.AUTO) && !isBuild
                ? chain.blockedBy(t) : Optional.empty();
        String key = "t" + t.id();
        LogChunk chunk = logs.read(key, 0, LOG_LINES);
        boolean more = !logs.read(key, chunk.next(), 1).lines().isEmpty();
        boolean live = active(t.status()) || t.status().equals("waiting");
        boolean accept = t.num("accepted") == 0 && ACCEPTABLE.contains(t.status()) && !chain.blockedFollowups(t, false).isEmpty();
        Map<String, Object> actions = new LinkedHashMap<>();
        actions.put("answer", (t.status().equals("waiting") && filled(t, "question"))
                || (t.status().equals("answered") && !filled(t, "job") && filled(t, "answer")));
        actions.put("cancel", live || (t.status().equals("routed") && !filled(t, "ended")));
        actions.put("retry", RETRYABLE.contains(t.status()) && !isBuild && !t.repo().equals(CoreSettings.AUTO) && pre.isEmpty());
        actions.put("accept", accept);
        actions.put("attended", isBuild && t.num("unattended") != 0);
        Map<String, Object> m = new LinkedHashMap<>(row(t));
        m.put("text", t.str("text"));
        m.put("question", t.str("question"));
        m.put("answer", t.str("answer"));
        m.put("parent", t.longOrNull("parent"));
        m.put("reason", t.str("reason"));
        m.put("after_task", t.longOrNull("after_task"));
        m.put("brief", t.str("brief"));
        m.put("handoff", t.str("handoff"));
        m.put("criteria", criteria(t));
        m.put("review", t.str("review"));
        m.put("reviews", t.numOrNull("reviews"));
        m.put("unattended", t.num("unattended") != 0);
        m.put("without_me", BriefPages.WITHOUT_ME);
        m.put("is_build", isBuild);
        m.put("build_left_nothing", isBuild && filled(t, "brief") && briefs.buildLeftNothing(t));
        m.put("children", store.children(t.id()).stream().map(this::row).toList());
        m.put("summary", t.str("summary"));
        m.put("summary_title", t.status().equals("refused") ? "Reden" : "Samenvatting");
        m.put("note", t.str("note"));
        m.put("turns", t.numOrNull("turns"));
        m.put("rounds", t.numOrNull("rounds"));
        m.put("model", modelName.apply(t));
        m.put("max_turns", settings.maxTurns());
        m.put("max_minutes", settings.maxMinutes());
        m.put("accepted", t.num("accepted") != 0);
        m.put("blocked_by", pre.map(Task::id).orElse(null));
        m.put("retry_label", t.status().equals("capped") ? "Continue" : "Retry");
        m.put("retry_hint", t.status().equals("refused")
                ? "Why it is fine to do after all, or what to do differently (optional)" : "Note for Claude (optional)");
        m.put("accept_pr_unmerged", t.num("pr_number") != 0 && accept);
        m.put("actions", actions);
        m.put("refresh", t.status().equals("queued"));
        m.put("timezone", timezone);
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("lines", chunk.lines());
        log.put("next", chunk.next());
        log.put("done", !active(t.status()) && !more);
        m.put("log", log);
        return m;
    }

    private static List<Object> criteria(Task t) {
        if (!filled(t, "criteria")) {
            return List.of();
        }
        return JSON.readValue(t.str("criteria"), new tools.jackson.core.type.TypeReference<List<Object>>() {});
    }
}
