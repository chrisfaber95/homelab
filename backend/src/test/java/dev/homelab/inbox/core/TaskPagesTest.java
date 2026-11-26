package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Ports the data tests of {@code tests/test_inbox_tasks_api.py}; the 404 body, the content type and the box refusal are the route's. */
class TaskPagesTest {

    private ConnectionSource db;
    private TaskStore store;
    private TaskPages pages;
    private final Map<String, List<Object>> logs = new java.util.HashMap<>();
    private long next = 100;

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        CoreFixture.MutableClock clock = new CoreFixture.MutableClock("2026-11-18T10:00:00Z");
        store = new TaskStore(db, clock);
        CoreFixture.Fakes fakes = new CoreFixture.Fakes();
        CoreSettings settings = CoreSettings.shadowDefaults();
        FollowupChain chain = new FollowupChain(store, settings, fakes, fakes, fakes, fakes, "http://h");
        ShadowEffects shadow = new ShadowEffects();
        Briefs briefs = new Briefs(store, shadow, shadow, shadow, shadow, shadow, Briefs.Settings.defaults(), clock);
        pages = new TaskPages(store, chain, briefs, (key, start, limit) -> {
            List<Object> all = logs.getOrDefault(key, List.of());
            int to = Math.min(all.size(), start + limit);
            return new TaskPages.LogChunk(all.subList(Math.min(start, all.size()), to), all.isEmpty() ? 0 : to);
        }, name -> null, settings, "Europe/Amsterdam");
    }

    private long task(String status, String... cols) throws Exception {
        long id = next++;
        CoreFixture.insert(db, id, "homelab", status, cols);
        store.update(id, TaskStore.fields("text", "Doe iets nuttigs\nmet meer regels"));
        return id;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(long id) {
        return pages.detail(id).orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> actions(Map<String, Object> d) {
        return (Map<String, Object>) d.get("actions");
    }

    /** test_list_has_the_form_repos_queue_and_history */
    @Test
    @SuppressWarnings("unchecked")
    void theListHasTheFormReposQueueAndHistory() throws Exception {
        long done = task("merged", "ended", "2026-11-18T09:00:00+00:00", "pr_url", "https://example.test/pr/7", "pr_number", "7");
        long queued = task("queued");
        long waiting = task("waiting");
        Map<String, Object> body = pages.list(List.of("homelab", "alpha"));
        assertThat(body.get("auto")).isEqualTo("auto");
        assertThat(body.get("repos")).isEqualTo(List.of("alpha", "homelab"));
        List<Map<String, Object>> queue = (List<Map<String, Object>>) body.get("queue");
        assertThat(queue).extracting(r -> r.get("id")).containsExactly(queued, waiting); // oldest first
        List<Map<String, Object>> history = (List<Map<String, Object>>) body.get("history");
        assertThat(history).extracting(r -> r.get("id")).containsExactly(done);
        Map<String, Object> row = history.get(0);
        assertThat(row.get("pr_number")).isEqualTo(7);
        assertThat(row.get("status_text")).isEqualTo("gemerged");
        assertThat(row.get("what")).isEqualTo("Doe iets nuttigs");
        assertThat(row.get("label")).isEqualTo("Inbox " + done);
        assertThat(body.get("refresh")).isEqualTo(true);
        task("failed");
        List<Map<String, Object>> again = (List<Map<String, Object>>) pages.list(List.of()).get("history");
        assertThat(again).extracting(r -> (Long) r.get("id")).isSortedAccordingTo(java.util.Comparator.reverseOrder()); // newest first
    }

    /** test_task_detail_carries_what_the_page_shows */
    @Test
    void theDetailCarriesWhatThePageShows() throws Exception {
        long id = task("waiting", "question", "Welke kant op?", "turns", "3", "rounds", "1");
        Map<String, Object> d = detail(id);
        assertThat(d.get("text")).isEqualTo("Doe iets nuttigs\nmet meer regels");
        assertThat(d.get("question")).isEqualTo("Welke kant op?");
        assertThat(d.get("turns")).isEqualTo(3);
        assertThat(d.get("rounds")).isEqualTo(1);
        assertThat(d.get("model")).isEqualTo("Claude");
        assertThat(d.get("max_turns")).isEqualTo(80);
        assertThat(d.get("max_minutes")).isEqualTo(30);
        assertThat(actions(d)).isEqualTo(Map.of("answer", true, "cancel", true, "retry", false, "accept", false, "attended", false));
        assertThat(d.get("criteria")).isEqualTo(List.of());
        assertThat(d.get("refresh")).isEqualTo(false);
        assertThat(d.get("timezone")).isEqualTo("Europe/Amsterdam").isEqualTo(pages.list(List.of()).get("timezone"));
        store.update(id, TaskStore.fields("model", "claude-opus-5-5"));
        assertThat(detail(id).get("model")).isEqualTo("Claude (opus-5-5)");
    }

    /** test_actions_follow_the_status */
    @Test
    void theActionsFollowTheStatus() throws Exception {
        Map<String, Object> failed = detail(task("failed"));
        assertThat(actions(failed).get("retry")).isEqualTo(true);
        assertThat(failed.get("retry_label")).isEqualTo("Retry");
        assertThat(failed.get("retry_hint")).isEqualTo("Note for Claude (optional)");
        assertThat(actions(failed).get("cancel")).isEqualTo(false);
        assertThat(detail(task("capped")).get("retry_label")).isEqualTo("Continue");
        Map<String, Object> refused = detail(task("refused", "summary", "nee"));
        assertThat(refused.get("summary_title")).isEqualTo("Reden");
        assertThat(refused.get("summary")).isEqualTo("nee");
        assertThat((String) refused.get("retry_hint")).startsWith("Why it is fine");
        Map<String, Object> queued = detail(task("queued"));
        assertThat(queued.get("refresh")).isEqualTo(true);
        assertThat(actions(queued).get("cancel")).isEqualTo(true);
        assertThat(actions(queued).get("retry")).isEqualTo(false);
    }

    /** the follow-up rules of the actions: a blocked retry and the mark as fine (test_inbox_followups) */
    @Test
    void aFollowupBehindARefusedTaskCannotRetryAndTheRefusedOneCanBeMarkedAsFine() throws Exception {
        long parent = next++;
        CoreFixture.insert(db, parent, "brief", "routed");
        long pre = task("refused", "parent", "" + parent);
        long follow = task("cancelled", "parent", "" + parent, "after_task", "" + pre, "note",
                "niet gestart: de voorgaande taak #" + pre + " was geweigerd");
        assertThat(detail(follow).get("blocked_by")).isEqualTo(pre);
        assertThat(actions(detail(follow)).get("retry")).isEqualTo(false);
        assertThat(actions(detail(pre)).get("accept")).isEqualTo(true);
        store.update(pre, TaskStore.fields("accepted", 1));
        assertThat(actions(detail(pre)).get("accept")).isEqualTo(false);
        assertThat(detail(follow).get("blocked_by")).isNull();
        assertThat(actions(detail(follow)).get("retry")).isEqualTo(true);
    }

    /** test_a_brief_build_and_its_children */
    @Test
    @SuppressWarnings("unchecked")
    void aBriefBuildAndItsChildren() throws Exception {
        long build = next++;
        CoreFixture.insert(db, build, "brief", "routed", "brief", "H-999", "unattended", "1");
        long kid = task("queued", "parent", "" + build, "reason", "step 1", "criteria", "[\"it works\"]");
        Map<String, Object> d = detail(build);
        assertThat(d.get("is_build")).isEqualTo(true);
        assertThat(d.get("unattended")).isEqualTo(true);
        assertThat(actions(d).get("attended")).isEqualTo(true);
        assertThat(actions(d).get("retry")).isEqualTo(false);
        assertThat(((List<Map<String, Object>>) d.get("children"))).extracting(k -> k.get("id")).containsExactly(kid);
        assertThat(d.get("without_me")).isEqualTo(BriefPages.WITHOUT_ME);
        Map<String, Object> k = detail(kid);
        assertThat(k.get("parent")).isEqualTo(build);
        assertThat(k.get("reason")).isEqualTo("step 1");
        assertThat(k.get("criteria")).isEqualTo(List.of("it works"));
    }

    /** test_the_log_so_far_and_where_to_poll_on, test_no_log_is_an_empty_chunk */
    @Test
    @SuppressWarnings("unchecked")
    void theLogSoFarAndWhereToPollOn() throws Exception {
        long id = task("running");
        logs.put("t" + id, new ArrayList<>(List.of(Map.of("kind", "info", "text", "begin"), Map.of("kind", "claude", "text", "bezig"))));
        Map<String, Object> log = (Map<String, Object>) detail(id).get("log");
        assertThat((List<Object>) log.get("lines")).hasSize(2);
        assertThat(log.get("next")).isEqualTo(2);
        assertThat(log.get("done")).isEqualTo(false);
        store.update(id, TaskStore.fields("status", "merged"));
        logs.get("t" + id).add(Map.of("kind", "result", "text", "klaar"));
        // the page asked for 2000 lines, so the third line is in; a finished task with lines left is not done
        assertThat(((Map<String, Object>) detail(id).get("log")).get("done")).isEqualTo(true);
        long none = task("failed");
        assertThat(detail(none).get("log")).isEqualTo(Map.of("lines", List.of(), "next", 0, "done", true));
    }

    /** a task past 2000 log lines is not done yet: the poll fetches the rest */
    @Test
    @SuppressWarnings("unchecked")
    void aFinishedTaskWithMoreThan2000LinesIsNotDoneYet() throws Exception {
        long id = task("merged");
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 2001; i++) {
            many.add(Map.of("kind", "info", "text", "l" + i));
        }
        logs.put("t" + id, many);
        Map<String, Object> log = (Map<String, Object>) detail(id).get("log");
        assertThat(log.get("next")).isEqualTo(2000);
        assertThat(log.get("done")).isEqualTo(false);
    }

    /** test_unknown_task_is_a_json_404 (the data half: no such task) */
    @Test
    void anUnknownTaskHasNoDetail() {
        assertThat(pages.detail(99999999)).isEmpty();
    }

    /** the job kind names a job's task row; a job without its file is just "job" */
    @Test
    void aJobTaskWithoutItsFileIsJustJob() throws Exception {
        long id = task("queued", "job", "daily");
        assertThat(pages.row(store.get(id).orElseThrow()).get("what")).isEqualTo("job");
        assertThat(pages.row(store.get(id).orElseThrow()).get("label")).isEqualTo("Job daily");
    }
}
