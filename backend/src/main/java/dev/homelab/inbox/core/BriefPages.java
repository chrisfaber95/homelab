package dev.homelab.inbox.core;

import dev.homelab.inbox.core.BriefParsing.Brief;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The data of the Briefs page ({@code overview.briefs_data}, {@code brief_data}, {@code brief_view}, {@code build_view};
 * H-026 K4), as the maps the JSON API shows. Pure: it reads the briefs and builds through {@link Briefs} and
 * {@link TaskStore}. No route uses it yet (the route stays in Python until H-027).
 */
public class BriefPages {

    /** The text shown for a build that is still going or planned: {@code overview.WITHOUT_ME}. */
    public static final String WITHOUT_ME = "The inbox merges each task PR itself once the brief reviewer finds every "
            + "criterion met and CI is green, and then the handoff. A PR on homelab, a missed criterion after the rework "
            + "round, red CI or criteria only you can check still wait for you.";

    private final Briefs briefs;
    private final TaskStore store;
    private final String timezone;
    private final Function<String, String> statusLabel;

    /** {@code statusLabel} maps a task status to its page label ({@code STATUS_NL}). */
    public BriefPages(Briefs briefs, TaskStore store, String timezone, Function<String, String> statusLabel) {
        this.briefs = briefs;
        this.store = store;
        this.timezone = timezone;
        this.statusLabel = statusLabel;
    }

    Map<String, Object> view(Brief b, boolean withText) {
        Task build = briefs.liveBuild(b.id()).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", b.id());
        out.put("title", b.title());
        out.put("status", b.status());
        out.put("priority", b.priority());
        out.put("repos", b.repos());
        out.put("project", b.project());
        out.put("path", b.path());
        out.put("build", build == null ? null : Map.of("id", build.id(), "url", "/task/" + build.id()));
        List<String> actions = new ArrayList<>();
        if (build == null) {
            actions.addAll(List.of("build", "build-without-me"));
            if (b.status().equals("draft")) {
                actions.add("park");
            }
        }
        out.put("actions", actions);
        if (withText) {
            out.put("text", BriefParsing.splitFrontmatter(b.text()).body().strip());
        }
        return out;
    }

    private Map<String, Object> buildView(Task t) {
        String text = t.str("text").strip();
        String what = text.isEmpty() ? "taak" : BriefParsing.cut(text.lines().findFirst().orElse(""), 70);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("label", TaskActions.label(t));
        m.put("url", "/task/" + t.id());
        m.put("status", t.status());
        m.put("status_label", statusLabel.apply(t.status()));
        m.put("what", what);
        m.put("repo", t.repo());
        m.put("source", t.str("source"));
        m.put("created", t.str("created"));
        m.put("ended", t.str("ended"));
        m.put("pr_number", t.numOrNull("pr_number"));
        m.put("pr_url", t.str("pr_url"));
        return m;
    }

    /** What the Briefs page shows: drafts, approved, needs-design, the builds so far and the counts per status. */
    public Map<String, Object> briefsData() {
        List<Brief> items;
        String error = null;
        try {
            items = briefs.load();
        } catch (RuntimeException e) {
            items = List.of();
            error = "Can't read " + briefs.settings().productRepo() + ": " + e.getMessage();
        }
        Map<String, Integer> counts = new TreeMap<>();
        items.forEach(b -> counts.merge(b.status(), 1, Integer::sum));
        List<Task> done = store.builds(null);
        List<Brief> all = items;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", error);
        out.put("timezone", timezone);
        out.put("without_me", WITHOUT_ME);
        out.put("drafts", all.stream().filter(b -> b.status().equals("draft")).map(b -> view(b, false)).toList());
        out.put("approved", all.stream().filter(b -> b.status().equals("approved")).map(b -> view(b, false)).toList());
        out.put("needs_design", all.stream().filter(b -> b.status().equals("needs-design")).map(b -> view(b, false)).toList());
        out.put("builds", done.stream().map(this::buildView).toList());
        out.put("counts", counts.entrySet().stream().map(e -> Map.<String, Object>of("status", e.getKey(), "count", e.getValue())).toList());
        out.put("refresh", done.stream().anyMatch(t -> Scheduler.ACTIVE.contains(t.status())));
        return out;
    }

    /** What the brief page shows: one brief with its text (front matter removed). */
    public Map<String, Object> briefData(Brief b) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", null);
        out.put("timezone", timezone);
        out.put("brief", view(b, true));
        return out;
    }
}
