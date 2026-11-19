package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.AfterEffects;
import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.Effects.Notifier;
import dev.homelab.inbox.core.Effects.PullRequests;
import dev.homelab.inbox.core.Effects.Pushes;
import dev.homelab.inbox.core.Effects.Repos;
import dev.homelab.inbox.core.Effects.Research;
import dev.homelab.inbox.core.Effects.ResearchRun;
import dev.homelab.inbox.core.Effects.WorkingCopies;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What happens when the round of a task ends ({@code queue.finish_round} and {@code finish_research}, H-026 K2): the
 * result of Claude becomes an answer, a question, a refusal, a capped task, a failure or a PR, with the notification and
 * conversation note that go with it. Everything outside the task store goes through {@link Effects}, so with the shadow
 * effects nothing is pushed, opened, merged or notified. The Dutch status notes and titles are those of the Python inbox:
 * the page shows them.
 */
public class RoundFinisher {

    private static final Logger log = LoggerFactory.getLogger(RoundFinisher.class);
    private static final Pattern SKILL = Pattern.compile(
            "^SKILL-VOORSTEL:\\s*([a-z0-9][a-z0-9-]{0,40})\\s*\\n\\s*beschrijving:\\s*(.+?)\\n(.+)$",
            Pattern.DOTALL | Pattern.MULTILINE);
    private static final Set<String> SKILL_KINDS = Set.of("KLAAR", "ANTWOORD");

    private final TaskStore store;
    private final WorkingCopies copies;
    private final Notifier notifier;
    private final ConversationNotes notes;
    private final Pushes pushes;
    private final PullRequests pullRequests;
    private final Research research;
    private final AfterEffects after;
    private final Repos repos;
    private final Executor background;
    private final Clock clock;
    private final String pageBase;

    public RoundFinisher(TaskStore store, WorkingCopies copies, Notifier notifier, ConversationNotes notes,
                         Pushes pushes, PullRequests pullRequests, Research research, AfterEffects after, Repos repos,
                         Executor background, Clock clock, String pageBase) {
        this.store = store;
        this.copies = copies;
        this.notifier = notifier;
        this.notes = notes;
        this.pushes = pushes;
        this.pullRequests = pullRequests;
        this.research = research;
        this.after = after;
        this.repos = repos;
        this.background = background;
        this.clock = clock;
        this.pageBase = pageBase;
    }

    /** A skill proposal split off the answer: the text without it, and (name, description, instructions) or null. */
    record SkillSplit(String text, List<String> proposal) {}

    static SkillSplit splitSkill(String text) {
        int i = text.indexOf("SKILL-VOORSTEL:");
        if (i < 0) {
            return new SkillSplit(text, null);
        }
        Matcher m = SKILL.matcher(text.substring(i).strip());
        return new SkillSplit(text.substring(0, i).strip(),
                m.lookingAt() ? List.of(m.group(1), m.group(2), m.group(3)) : null);
    }

    static String firstLine(String text) {
        String s = text == null ? "" : text.strip();
        if (s.isEmpty()) {
            return "taak";
        }
        String line = s.lines().findFirst().orElse("");
        return line.length() > 70 ? line.substring(0, 70) : line;
    }

    private String pageUrl(long id) {
        return pageBase + "/task/" + id;
    }

    /** The task as shown in its PR: for a brief task without the brief itself (that repo is private). */
    static String prText(Task t) {
        String text = t.str("text");
        if (t.has("brief")) {
            int i = text.indexOf("\n\nThis task is part of brief");
            return (i < 0 ? text : text.substring(0, i)) + "\n\n(Part of brief " + t.str("brief") + ".)";
        }
        return text;
    }

    private String titleOf(Task t) {
        if (t.has("job")) {
            String instruction = research.jobInstruction(t.str("job"));
            return "job " + t.str("job") + ": " + firstLine(instruction == null ? "" : instruction);
        }
        return "inbox " + t.id() + ": " + firstLine(t.str("text"));
    }

    private static String cut(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static String orElse(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    /**
     * @param cache the path of the service's copy of the repo
     * @param text what Claude answered, without its memory blocks
     * @param permissionDenials the number of actions auto mode refused
     * @param capped the limit the round stopped at ("80 beurten", "geannuleerd", ...), or null
     */
    public void finish(long taskId, String cache, String text, int permissionDenials, String capped) {
        Task t = store.get(taskId).orElseThrow();
        SkillSplit split = splitSkill(text);
        text = split.text();
        String kindText = text.strip();
        int colon = kindText.indexOf(':');
        String kind = (colon < 0 ? kindText : kindText.substring(0, colon)).strip().toUpperCase(Locale.ROOT);
        String body = colon < 0 ? "" : kindText.substring(colon + 1).strip();
        if (split.proposal() != null && SKILL_KINDS.contains(kind) && capped == null) {
            List<String> p = split.proposal();
            background.execute(() -> after.proposeSkill(t, p.get(0), p.get(1), p.get(2)));
        }
        String note = permissionDenials > 0 ? permissionDenials + " actie(s) geweigerd door auto mode" : null;
        String label = TaskActions.label(t);
        String url = pageUrl(taskId);
        try {
            if ("geannuleerd".equals(capped)) {
                store.update(taskId, TaskStore.fields("status", "cancelled", "ended", store.now(), "note", note));
                if (!t.has("pr_number")) {
                    copies.cleanup(taskId);
                }
                return;
            }
            ResearchRun run = research.runOf(t).orElse(null);
            if (run != null && capped == null) {
                finishResearch(t, run, kind, body, text, note);
                return;
            }
            if (kind.equals("ANTWOORD") && capped == null) {
                // A question makes no commit, branch or PR; commits that exist stay local and are thrown away.
                store.update(taskId, TaskStore.fields("status", "answered", "answer", body, "ended", store.now(),
                        "note", note));
                if (!t.has("job")) {
                    notes.note("claude", "inbox", "answer", body, url, "task:" + taskId);
                }
                copies.cleanup(taskId);
                if (t.has("job")) {
                    research.deliver(t, body, url); // only when the file asks for it
                    return; // a job reports only what the user needs; the result is on the page
                }
                notifier.notify("Inbox " + taskId + ": antwoord", body, url, false);
                return;
            }
            if (kind.equals("VRAAG") && capped == null) {
                store.update(taskId, TaskStore.fields("status", "waiting", "question", body, "note", note));
                notes.note("claude", "inbox", "question", body, url, "task:" + taskId);
                notifier.notify(label + ": vraag", body, url, false);
                return;
            }
            if (kind.equals("GEWEIGERD") && capped == null) {
                store.update(taskId, TaskStore.fields("status", "refused", "summary", body, "ended", store.now(),
                        "note", note));
                copies.cleanup(taskId);
                notifier.notify(label + ": geweigerd", body, url);
                return;
            }
            if (run != null) { // a research run that hit its limit: nothing is pushed, whatever the working copy holds
                copies.cleanup(taskId);
                store.update(taskId, TaskStore.fields("status", "capped", "ended", store.now(),
                        "note", "gestopt bij " + capped + (note != null ? "; " + note : "")));
                notifier.notify(label + ": gestopt bij " + capped,
                        run.mode() + "-onderzoek niet afgerond", url);
                return;
            }
            List<String> commits = pushes.push(taskId, cache);
            if (capped != null) {
                // The working copy stays: Continue on the page carries on in it, uncommitted work included.
                // sweepCapped removes it when nobody continued the task for CAPPED_KEEP_DAYS.
                store.update(taskId, TaskStore.fields("status", "capped", "ended", store.now(),
                        "note", "gestopt bij " + capped + (commits.isEmpty() ? "" : "; branch inbox/" + taskId + " gepusht")
                                + (note != null ? "; " + note : "")));
                notifier.notify(label + ": gestopt bij " + capped,
                        commits.isEmpty() ? "geen commits" : commits.size() + " commit(s) op inbox/" + taskId, url);
                return;
            }
            if (commits.isEmpty()) {
                if (!t.has("pr_number")) {
                    copies.cleanup(taskId);
                }
                store.update(taskId, TaskStore.fields("status", "failed", "ended", store.now(),
                        "summary", orElse(body, cut(text, 500)),
                        "note", "Claude gaf geen KLAAR/ANTWOORD/VRAAG en maakte geen commits"
                                + (note != null ? "; " + note : "")));
                notifier.notify(label + " mislukt", orElse(body, text), url);
                return;
            }
            String slug = repos.list().get(t.repo());
            String summary = orElse(body, cut(text, 500));
            if (t.has("pr_number")) {
                store.update(taskId, TaskStore.fields("status", "pr_open", "summary", summary, "ended", store.now(),
                        "note", note, "review", null));
                if (t.has("brief")) { // H-001: the reviewer notifies (or sends it back once) instead
                    background.execute(() -> after.reviewPr(taskId, cache));
                    return;
                }
                notifier.notify(label + ": PR #" + t.num("pr_number") + " bijgewerkt", summary, t.str("pr_url"));
                return;
            }
            String prBody = summary + "\n\n**Taak** (via de homelab-inbox, #" + taskId + "):\n> "
                    + prText(t).replace("\n", "\n> ") + "\n\nCommits:\n"
                    + commits.stream().map(c -> "- " + c).collect(Collectors.joining("\n"))
                    + "\n\nReview-commentaar van jou stuurt de taak terug naar de inbox."
                    + "\n\n<!-- homelab-inbox task=" + taskId + " -->";
            PullRequests.Opened pr = pullRequests.open(slug, cut(titleOf(t), 100), "inbox/" + taskId, "main", prBody);
            store.update(taskId, TaskStore.fields("status", "pr_open", "summary", summary, "pr_number", pr.number(),
                    "pr_url", pr.url(), "ended", store.now(), "last_comment_at", store.now(), "note", note,
                    "review", null));
            if (t.has("brief")) {
                background.execute(() -> after.reviewPr(taskId, cache));
                return;
            }
            notifier.notify(label + ": PR #" + pr.number(), summary, pr.url());
        } catch (RuntimeException e) {
            log.warn("task {} finish: {}", taskId, e.toString());
            store.update(taskId, TaskStore.fields("status", "failed", "ended", store.now(),
                    "note", cut("afronden mislukt: " + e.getMessage(), 500)));
            notifier.notify(label + " mislukt", String.valueOf(e.getMessage()), url);
        }
    }

    /**
     * The end of a research run (H-017): the report becomes the answer, with its mode and time taken on top, and goes
     * where the question came from. Never a commit, branch or PR: nothing is pushed and the working copy is thrown away.
     */
    void finishResearch(Task t, ResearchRun run, String kind, String body, String text, String note) {
        long taskId = t.id();
        copies.cleanup(taskId);
        String url = pageUrl(taskId);
        if (!kind.equals("ANTWOORD")) {
            String shown = orElse(body, text);
            store.update(taskId, TaskStore.fields("status", "failed", "ended", store.now(), "summary", cut(shown, 500),
                    "note", "the research run gave no report (ANTWOORD:)" + (note != null ? "; " + note : "")));
            notifier.notify(TaskActions.label(t) + " mislukt", orElse(cut(shown, 500), "no report"), url);
            return;
        }
        OffsetDateTime started = OffsetDateTime.parse(t.has("started") ? t.str("started") : run.created());
        double seconds = Duration.between(started.toInstant(), clock.instant()).toMillis() / 1000.0;
        String report = Reports.header(run.mode(), seconds) + "\n\n" + body;
        List<String> problems = Reports.problems(body);
        if (!problems.isEmpty()) {
            List<String> parts = new ArrayList<>();
            if (note != null) {
                parts.add(note);
            }
            parts.add("report check: " + String.join(", ", problems));
            note = String.join("; ", parts);
        }
        store.update(taskId, TaskStore.fields("status", "answered", "answer", report, "ended", store.now(), "note", note));
        research.deliver(t, report, url);
    }
}
