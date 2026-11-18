package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The task store over the {@code tasks} table of a throwaway PostgreSQL (H-026 K1). Ports the store behaviour that the
 * Python tests rely on: {@code storage.new_task}/{@code get}/{@code update}/{@code tasks} (see docs/inbox-backend.md,
 * "Core in shadow (K1)" for the file and case of each).
 */
class TaskStoreTest {

    private ConnectionSource db;
    private CoreFixture.MutableClock clock;
    private TaskStore store;

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        clock = new CoreFixture.MutableClock("2026-11-18T10:00:00Z");
        store = new TaskStore(db, clock);
    }

    /** test_inbox_chat.StartTask.test_task_is_queued_like_a_page_task_and_marked_discord */
    @Test
    void aNewTaskIsQueuedAndRemembersWhereItCameFrom() {
        long chat = store.newTask("homelab", "fix the thing", "discord");
        Task t = store.get(chat).orElseThrow();
        assertThat(t.repo()).isEqualTo("homelab");
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.str("source")).isEqualTo("discord");
        assertThat(t.num("unattended")).isZero();
        assertThat(t.str("created")).isEqualTo("2026-11-18T10:00:00+00:00");
        assertThat(t.str("session")).hasSize(36);
        long page = store.newTask("homelab", "from the page", null);
        assertThat(store.get(page).orElseThrow().has("source")).isFalse();
        assertThat(page).isGreaterThan(chat);
    }

    /** storage.new_task (text[:8000]); no Python test */
    @Test
    void theTextIsCutAt8000Characters() {
        long id = store.newTask("homelab", "x".repeat(9000), null);
        assertThat(store.get(id).orElseThrow().str("text")).hasSize(8000);
    }

    /** test_inbox_tasks_api.test_unknown_task_is_a_json_404 (the store half: nothing found) */
    @Test
    void anUnknownTaskIsEmpty() {
        assertThat(store.get(99999999L)).isEmpty();
    }

    /** test_inbox_tasks_api.test_list_has_the_form_repos_queue_and_history (history newest first) and storage.tasks(limit) */
    @Test
    void tasksComeNewestFirstAndAreLimited() {
        long a = store.newTask("homelab", "a", null);
        long b = store.newTask("homelab", "b", null);
        long c = store.newTask("homelab", "c", null);
        assertThat(store.tasks(200)).extracting(Task::id).containsExactly(c, b, a);
        assertThat(store.tasks(2)).extracting(Task::id).containsExactly(c, b);
    }

    /** test_inbox_chat.Lists.test_state_lists_running_waiting_and_queued (the rows a state list is built from) */
    @Test
    void updateChangesOnlyTheGivenFieldsOfThatTask() {
        long a = store.newTask("homelab", "one", null);
        long b = store.newTask("homelab", "two", null);
        store.update(a, TaskStore.fields("status", "running", "started", store.now(), "turns", 3));
        Task ta = store.get(a).orElseThrow();
        assertThat(List.of(ta.status(), ta.str("started"))).containsExactly("running", "2026-11-18T10:00:00+00:00");
        assertThat(ta.num("turns")).isEqualTo(3);
        assertThat(store.get(b).orElseThrow().status()).isEqualTo("queued");
    }

    /** test_inbox_followups.test_retry_a_refused_task (summary=None) and test_inbox_chat.Answer (answer=None) */
    @Test
    void updateCanSetAColumnToNull() {
        long id = store.newTask("homelab", "q", null);
        store.update(id, TaskStore.fields("answer", "yes", "summary", "s"));
        store.update(id, TaskStore.fields("answer", null));
        Task t = store.get(id).orElseThrow();
        assertThat(t.has("answer")).isFalse();
        assertThat(t.str("summary")).isEqualTo("s");
    }

    /** storage.update; no Python test: a misspelled column is an error here, not an SQL error */
    @Test
    void updateRefusesAColumnThatIsNotOnTheTable() {
        long id = store.newTask("homelab", "q", null);
        assertThatThrownBy(() -> store.update(id, TaskStore.fields("statuz", "x")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("statuz");
        assertThatThrownBy(() -> store.update(id, TaskStore.fields("id; DROP TABLE tasks", "x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** storage.now(): UTC, whole seconds, +00:00 (also what test_inbox_followups compares with) */
    @Test
    void nowIsIsoSecondsWithAnOffset() {
        clock.advance(java.time.Duration.ofMillis(1234));
        assertThat(store.now()).isEqualTo("2026-11-18T10:00:01+00:00");
        assertThat(new TaskStore(db, Clock.systemUTC()).now()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\+00:00");
    }
}
