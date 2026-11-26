package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homelab.inbox.core.CommandRunner.Command;
import dev.homelab.inbox.core.CommandRunner.Finished;
import dev.homelab.inbox.read.Settings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** LiveEffects with a fake {@link CommandRunner} only: nothing here starts the real Claude, gh, git or ntfy. */
class LiveEffectsTest {

    /** Records every command and answers with the first matching rule, else exit 0 and no output. */
    static final class FakeRunner implements CommandRunner {
        final List<Command> commands = new ArrayList<>();
        final List<String> lines = new ArrayList<>();
        Function<Command, Finished> answer = c -> new Finished(0, "", "", false);

        @Override
        public Finished run(Command c) {
            commands.add(c);
            Finished f = answer.apply(c);
            if (c.onLine() != null) {
                lines.forEach(c.onLine());
            }
            return f;
        }

        List<String> joined() {
            return commands.stream().map(c -> String.join(" ", c.args())).toList();
        }
    }

    @TempDir
    Path tmp;
    FakeRunner runner;
    List<String> notes;
    LiveEffects fx;
    ConnectionSource db;

    @BeforeEach
    void setUp() throws Exception {
        runner = new FakeRunner();
        notes = new ArrayList<>();
        Files.writeString(tmp.resolve("gh-token"), "ghp_faketoken\n");
        Files.writeString(tmp.resolve("ntfy-token"), "ntfy-faketoken\n");
        Map<String, String> env = Map.of("INBOX_RUNNER_HOME", tmp.toString(), "GH_TOKEN_FILE", tmp.resolve("gh-token").toString(),
                "NTFY_TOKEN_FILE", tmp.resolve("ntfy-token").toString(), "TASK_CMD", "fake-task-cmd", "RUNNER_DIR", "/r",
                "INBOX_TASK_HOME", tmp.resolve("task").toString());
        db = CoreFixture.db();
        CoreFixture.clear(db);
        TaskStore store = new TaskStore(db, new CoreFixture.MutableClock("2026-11-26T10:00:00Z"));
        fx = new LiveEffects(runner, new Settings(tmp.resolve("config"), env), store,
                (a, c, k, t, l, r) -> notes.add(k + " " + t), () -> {
                    throw new AssertionError("no round flow in this test");
                }, new Cancellations(), new CoreSettings(false, 4, 3, 80, 30, 7, 2));
    }

    @Test
    void mergeUsesGhPrMergeWithTheHeadAndTheTitle() {
        fx.merge("o/r", 7, "abc123", "Merge PR #7: x (build without me)");
        assertThat(runner.joined()).containsExactly(
                "gh pr merge 7 --repo o/r --merge --subject Merge PR #7: x (build without me) --match-head-commit abc123");
        assertThat(runner.commands.get(0).env()).containsEntry("GH_TOKEN", "ghp_faketoken");
    }

    @Test
    void aRefusedMergeIsAGitHubHttpExceptionWithTheStatusGhReports() {
        runner.answer = c -> new Finished(1, "", "gh: Pull Request is not mergeable (HTTP 405)", false);
        assertThatThrownBy(() -> fx.merge("o/r", 7, null, "t")).isInstanceOfSatisfying(Effects.GitHubHttpException.class,
                e -> {
                    assertThat(e.code()).isEqualTo(405);
                    assertThat(e.body()).contains("not mergeable");
                });
        assertThat(runner.joined().get(0)).doesNotContain("--match-head-commit");
    }

    @Test
    void notifySendsOneNtfyPostAndKeepsTheFullTextInTheConversation() {
        String body = "x".repeat(500);
        fx.notify("Inbox 5 mislukt", body, "http://h/task/5");
        assertThat(notes).containsExactly("notification Inbox 5 mislukt\n" + body);
        Command c = runner.commands.get(0);
        assertThat(c.args()).startsWith("curl").contains("http://127.0.0.1:8102/");
        assertThat(String.join(" ", c.args())).doesNotContain("ntfy-faketoken");
        assertThat(c.stdin()).contains("Bearer ntfy-faketoken");
        String data = c.args().get(c.args().indexOf("--data-binary") + 1);
        assertThat(data).contains("\"title\":\"Inbox 5 mislukt\"", "\"priority\":4", "\"label\":\"Openen\"")
                .contains("x".repeat(300)).doesNotContain("x".repeat(301));
    }

    @Test
    void aFailingNtfyNeverFailsTheCaller() {
        runner.answer = c -> new Finished(7, "", "connection refused", false);
        fx.notify("t", "b", null, false);
        assertThat(notes).isEmpty();
    }

    @Test
    void taskCommandsGoThroughHomelabInboxTask() {
        runner.answer = c -> new Finished(c.args().contains("has") ? 1 : 0, "", "", false);
        assertThat(fx.has(3)).isFalse();
        fx.cleanup(3);
        fx.stop(3);
        assertThat(fx.sync(3).code()).isZero();
        assertThat(runner.joined()).containsExactly("fake-task-cmd has 3", "fake-task-cmd cleanup 3",
                "fake-task-cmd kill 3", "fake-task-cmd sync 3");
    }

    @Test
    void pushTakesTheBundleIntoTheServiceCopyAndPushesOnlyToTheTaskBranch() {
        runner.answer = c -> {
            String j = String.join(" ", c.args());
            if (j.equals("fake-task-cmd bundle 9")) {
                return new Finished(0, "abc1234 first\nnoise\n", "", false);
            }
            if (j.contains("list-heads")) {
                return new Finished(0, "deadbeef refs/heads/inbox/9\n", "", false);
            }
            return new Finished(0, "", "", false);
        };
        assertThat(fx.push(9, tmp.resolve("repos/homelab").toString())).containsExactly("abc1234 first");
        String all = String.join("\n", runner.joined());
        assertThat(all).contains("push --quiet --force origin refs/inbox/9:refs/heads/inbox/9").doesNotContain("main");
    }

    @Test
    void pushRefusesABundleWithAnotherRefAndNothingIsPushed() {
        runner.answer = c -> String.join(" ", c.args()).contains("list-heads")
                ? new Finished(0, "deadbeef refs/heads/main\n", "", false) : new Finished(0, "x y\n", "", false);
        assertThatThrownBy(() -> fx.push(9, tmp.resolve("repos/homelab").toString())).hasMessageContaining("iets anders");
        assertThat(String.join("\n", runner.joined())).doesNotContain(" push ");
    }

    @Test
    void noNewCommitsPushesNothing() {
        runner.answer = c -> new Finished(3, "", "", false);
        assertThat(fx.push(9, "/x/homelab")).isEmpty();
        assertThat(runner.commands).hasSize(1);
    }

    @Test
    void openingAPrUsesGhPrCreateAndReadsTheNumberFromTheUrl() {
        runner.answer = c -> new Finished(0, "https://github.com/o/r/pull/42\n", "", false);
        var pr = fx.open("o/r", "inbox 5: x", "inbox/5", "main", "body");
        assertThat(pr.number()).isEqualTo(42);
        assertThat(pr.url()).isEqualTo("https://github.com/o/r/pull/42");
        assertThat(runner.joined().get(0)).startsWith("gh pr create --repo o/r --title inbox 5: x --head inbox/5 --base main");
    }

    @Test
    void readsGoThroughGhApiAndParseTheJson() {
        runner.answer = c -> new Finished(0, "{\"state\":\"closed\",\"merged\":true}", "", false);
        assertThat(fx.get("o/r", 3)).contains(new Effects.PrState("closed", true));
        assertThat(runner.joined()).containsExactly("gh api /repos/o/r/pulls/3");
    }

    @Test
    void aRoundStartsRoundPyWithTheSessionAndCountsItsTurns() throws Exception {
        CoreFixture.insert(db, 11, "homelab", "running");
        new TaskStore(db, new CoreFixture.MutableClock("2026-11-26T10:00:00Z")).update(11, TaskStore.fields("session", "5b1c1d3e-0000-4000-8000-000000000000"));
        Task t = new TaskStore(db, new CoreFixture.MutableClock("2026-11-26T10:00:00Z")).get(11).orElseThrow();
        fx.writePrompt(t, "Taak voor de repo homelab:\n\nx");
        assertThat(Files.readString(tmp.resolve("share/11/system.txt"))).contains("Je werkt voor de homelab-inbox")
                .contains("repo homelab op branch inbox/11");
        runner.lines.addAll(List.of("{\"event\":\"log\",\"kind\":\"info\",\"text\":\"hoi ghp_abcdefghijklmnopqrstuvwxyz\"}",
                "{\"event\":\"turn\",\"turns\":1}", "not json",
                "{\"event\":\"result\",\"raw\":{\"result\":\"KLAAR: ok\",\"is_error\":false,\"permission_denials\":[1,2]}}"));
        var out = fx.stream(11, "new");
        assertThat(out.result()).isEqualTo("KLAAR: ok");
        assertThat(out.permissionDenials()).isEqualTo(2);
        assertThat(out.capped()).isNull();
        assertThat(runner.joined().get(0)).startsWith("python3 -I /r/round.py 11 5b1c1d3e-0000-4000-8000-000000000000 new");
        assertThat(runner.commands.get(0).env()).containsEntry("TASK_CMD", "fake-task-cmd");
        String log = Files.readString(tmp.resolve("logs/t11.log"));
        assertThat(log).contains("\"kind\":\"info\"", "hoi [verborgen]").doesNotContain("ghp_abc");
    }

    @Test
    void aRoundWithoutAResultFailsWithClaudesStderr() throws Exception {
        CoreFixture.insert(db, 12, "homelab", "running");
        runner.answer = c -> new Finished(1, "", "boem", false);
        assertThatThrownBy(() -> fx.stream(12, "new")).hasMessage("Claude stopte zonder resultaat: boem");
    }

    @Test
    void aRoundThatRunsOutOfTimeIsCappedWithTheMinutes() throws Exception {
        CoreFixture.insert(db, 13, "homelab", "running");
        runner.answer = c -> new Finished(143, "", "", true);
        assertThat(fx.stream(13, "new").capped()).isEqualTo("30 minuten");
    }
}
