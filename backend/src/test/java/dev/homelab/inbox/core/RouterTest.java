package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homelab.inbox.core.Effects.RouteAnswer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The router (H-026 K3): {@code parse_route} and {@code route_task}. The Python tests of {@code tests/} only cover the
 * runner half of the router ({@code test_runner_route.py}: the process contract, which stays Python's, and
 * {@code RouterUsesRunner.run_route}); {@code parse_route} and {@code route_task} have no Python test, so these cases
 * are written from {@code router.py} and say so.
 */
class RouterTest {

    private static final Map<String, String> REPOS = Map.of("homelab", "o/homelab", "product", "o/product");

    private ConnectionSource db;
    private TaskStore store;
    private Fakes fakes;
    private Cancellations cancellations;

    /** The router's Claude answer is scripted; the shadow one fails on purpose. */
    static class Fakes extends CoreFixture.Fakes {
        Function<Long, RouteAnswer> answer = id -> new RouteAnswer("", true, "no answer scripted");
        final List<String> prompts = new ArrayList<>();
        final List<String> systems = new ArrayList<>();

        @Override
        public Map<String, String> list() {
            return REPOS;
        }

        @Override
        public RouteAnswer route(long taskId, String system, String prompt) {
            super.route(taskId, system, prompt);
            systems.add(system);
            prompts.add(prompt);
            return answer.apply(taskId);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        store = new TaskStore(db, new CoreFixture.MutableClock("2026-11-18T10:00:00Z"));
        fakes = new Fakes();
        cancellations = new Cancellations();
        // made through the store, so the id sequence moves on and the children get their own ids
        assertThat(store.newTask("auto", "fix the port and write docs", null)).isEqualTo(1);
    }

    private Router router(int max) {
        return new Router(store, fakes, fakes, fakes, fakes, fakes, fakes, cancellations, max, "http://homelab:8105");
    }

    private Task task(long id) {
        return store.get(id).orElseThrow();
    }

    private static RouteAnswer say(String json) {
        return new RouteAnswer("```route\n" + json + "\n```", false, "");
    }

    // ---- parse_route: no Python test

    @Test
    void aTasksBlockGivesTheTasksInOrderWithTheTextCutAndTheAfterKept() {
        Router.Route r = Router.parseRoute("blah ```route\n{\"tasks\":[{\"id\":\"a\",\"repo\":\"homelab\",\"text\":\" one \","
                + "\"reason\":\"box\"},{\"repo\":\"product\",\"text\":\"two\",\"after\":\"a\"},"
                + "{\"id\":3,\"repo\":\"product\",\"text\":\"three\",\"after\":\"nope\"}]}```", REPOS.keySet());
        assertThat(r.question()).isNull();
        assertThat(r.tasks()).extracting(Router.RoutedTask::key).containsExactly("a", "1", "3");
        assertThat(r.tasks()).extracting(Router.RoutedTask::text).containsExactly("one", "two", "three");
        assertThat(r.tasks()).extracting(Router.RoutedTask::after).containsExactly("", "a", "");
        assertThat(r.tasks().get(0).reason()).isEqualTo("box");
    }

    @Test
    void aVraagWinsAndIsCutAt500() {
        Router.Route r = Router.parseRoute("```route\n{\"vraag\":\"" + "x".repeat(600) + "\",\"tasks\":[]}```", REPOS.keySet());
        assertThat(r.question()).hasSize(500);
        assertThat(r.tasks()).isEmpty();
    }

    @Test
    void textAndReasonAreCut() {
        Router.Route r = Router.parseRoute("```route\n{\"tasks\":[{\"repo\":\"homelab\",\"text\":\"" + "t".repeat(9000)
                + "\",\"reason\":\"" + "r".repeat(400) + "\"}]}```", REPOS.keySet());
        assertThat(r.tasks().get(0).text()).hasSize(8000);
        assertThat(r.tasks().get(0).reason()).hasSize(300);
    }

    @Test
    void aWrongBlockIsRefusedWithThePythonMessages() {
        Map<String, String> bad = Map.ofEntries(
                Map.entry("no block at all", "de router gaf geen route-blok terug"),
                Map.entry("```route\nnot json```", "de router gaf geen geldige JSON terug"),
                Map.entry("```route\n{\"tasks\":[]}```", "de router gaf geen taken"),
                Map.entry("```route\n[1]```", "de router gaf geen taken"),
                Map.entry("```route\n{\"tasks\":[\"x\"]}```", "ongeldige taak van de router"),
                Map.entry("```route\n{\"tasks\":[{\"repo\":\"nope\",\"text\":\"x\"}]}```",
                        "de router koos een onbekende repo: nope"),
                Map.entry("```route\n{\"tasks\":[{\"repo\":\"homelab\",\"text\":\"  \"}]}```",
                        "de router gaf een taak zonder tekst"),
                Map.entry("```route\n{\"tasks\":[{\"id\":\"a\",\"repo\":\"homelab\",\"text\":\"x\"},"
                        + "{\"id\":\"a\",\"repo\":\"homelab\",\"text\":\"y\"}]}```",
                        "de router gaf twee taken met hetzelfde id"));
        bad.forEach((text, message) -> assertThatThrownBy(() -> Router.parseRoute(text, REPOS.keySet()))
                .isInstanceOf(IllegalArgumentException.class).hasMessage(message));
    }

    // ---- route_task: no Python test

    @Test
    void theRouterAsksClaudeWithTheMessageAndTheRepoListAndCreatesTheChildren() {
        fakes.answer = id -> say("{\"tasks\":[{\"id\":\"a\",\"repo\":\"homelab\",\"text\":\"open the port\",\"reason\":\"box\"},"
                + "{\"id\":\"b\",\"repo\":\"product\",\"text\":\"write docs\",\"after\":\"a\"}]}");
        router(3).routeTask(1);
        Task p = task(1);
        assertThat(p.status()).isEqualTo("routed");
        assertThat(p.num("rounds")).isEqualTo(1);
        assertThat(p.has("started")).isTrue();
        assertThat(fakes.prompts).containsExactly("Bericht van de gebruiker:\n\nfix the port and write docs");
        assertThat(fakes.systems.get(0)).contains("hooguit 3 taken").contains("{\"vraag\": \"één korte vraag\"}");
        List<Task> kids = store.children(1);
        assertThat(kids).extracting(Task::repo).containsExactly("homelab", "product");
        assertThat(kids).extracting(Task::status).containsOnly("queued");
        assertThat(kids.get(0).str("text")).isEqualTo("open the port\n\n(Onderdeel van bericht #1, doorgestuurd naar homelab: "
                + "box. Raak alleen deze repo aan.)");
        assertThat(kids.get(1).str("text")).contains("geen reden opgegeven");
        assertThat(kids.get(0).has("after_task")).isFalse();
        assertThat(kids.get(1).num("after_task")).isEqualTo((int) kids.get(0).id());
        assertThat(kids.get(0).str("session")).isNotEqualTo(kids.get(1).str("session"));
        assertThat(fakes.calls()).contains("log t1 info router gestart",
                "log t1 info doorgestuurd naar #" + kids.get(0).id() + " (homelab), #" + kids.get(1).id() + " (product)");
    }

    @Test
    void aQuestionFromTheRouterWaitsForTheUserAndIsNotNotifiedTwice() {
        fakes.answer = id -> say("{\"vraag\":\"Welke repo?\"}");
        router(3).routeTask(1);
        assertThat(task(1).status()).isEqualTo("waiting");
        assertThat(task(1).str("question")).isEqualTo("Welke repo?");
        assertThat(store.children(1)).isEmpty();
        assertThat(fakes.calls()).contains("note question task:1", "notify Inbox 1: vraag");
    }

    @Test
    void moreTasksThanTheLimitBecomeAQuestionNamingThem() {
        fakes.answer = id -> say("{\"tasks\":[{\"repo\":\"homelab\",\"text\":\"one\"},{\"repo\":\"product\",\"text\":\"two\"}]}");
        router(1).routeTask(1);
        assertThat(task(1).status()).isEqualTo("waiting");
        assertThat(task(1).str("question")).isEqualTo("Dit wordt 2 taken, maximaal 1 per bericht: homelab: one; "
                + "product: two. Welke houd ik, of splits je het zelf?");
        assertThat(store.children(1)).isEmpty();
    }

    @Test
    void aSecondRoundCarriesTheQuestionAndTheAnswerAndKeepsTheStartTime() {
        store.update(1, TaskStore.fields("rounds", 1, "started", "2026-10-31T19:00:00+00:00", "question", "Welke repo?",
                "pending_input", "Antwoord van de gebruiker: homelab", "status", "queued"));
        fakes.answer = id -> say("{\"tasks\":[{\"repo\":\"homelab\",\"text\":\"x\"}]}");
        router(3).routeTask(1);
        assertThat(fakes.prompts.get(0)).endsWith("\n\nJe stelde eerder een vraag (Welke repo?). Antwoord van de gebruiker: homelab");
        assertThat(task(1).num("rounds")).isEqualTo(2);
        assertThat(task(1).str("started")).isEqualTo("2026-10-31T19:00:00+00:00");
        assertThat(task(1).has("pending_input")).isFalse();
        assertThat(task(1).has("question")).isFalse();
    }

    @Test
    void aFailingClaudeRunFailsTheTaskAndNotifies() {
        fakes.answer = id -> new RouteAnswer("model unavailable", true, "oops");
        router(3).routeTask(1);
        assertThat(task(1).status()).isEqualTo("failed");
        assertThat(task(1).str("note")).isEqualTo("Claude: model unavailable");
        assertThat(task(1).has("ended")).isTrue();
        assertThat(fakes.calls()).contains("notify Inbox 1 mislukt");
        assertThat(store.children(1)).isEmpty();
    }

    @Test
    void aBadRouteBlockFailsTheTaskWithTheMessage() {
        fakes.answer = id -> say("{\"tasks\":[{\"repo\":\"nope\",\"text\":\"x\"}]}");
        router(3).routeTask(1);
        assertThat(task(1).status()).isEqualTo("failed");
        assertThat(task(1).str("note")).isEqualTo("de router koos een onbekende repo: nope");
        assertThat(store.children(1)).isEmpty();
    }

    @Test
    void aCancelledRoundCreatesNothing() {
        fakes.answer = id -> {
            cancellations.request(id);
            return say("{\"tasks\":[{\"repo\":\"homelab\",\"text\":\"x\"}]}");
        };
        router(3).routeTask(1);
        assertThat(task(1).status()).isEqualTo("cancelled");
        assertThat(task(1).has("ended")).isTrue();
        assertThat(store.children(1)).isEmpty();
    }

    /** H-025 R2 {@code RouterUsesRunner.test_run_route_without_a_result_line_is_an_error}: no answer is an error, not a route */
    @Test
    void theShadowClaudeNeverRoutesAnything() {
        router(3).routeTask(1);
        assertThat(task(1).status()).isEqualTo("failed");
        assertThat(task(1).str("note")).isEqualTo("Claude: no answer scripted");
        assertThat(store.children(1)).isEmpty();
    }
}
