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

    /** The beans of the core the effects call back into: none of them is used by these tests. */
    static final class FakeLate implements LiveEffects.Late {
        RoundFlow flow = org.mockito.Mockito.mock(RoundFlow.class);
        Router router = org.mockito.Mockito.mock(Router.class);
        BriefRounds briefRounds = org.mockito.Mockito.mock(BriefRounds.class);
        Effects.Research research = org.mockito.Mockito.mock(Effects.Research.class);

        @Override
        public RoundFlow flow() {
            return flow;
        }

        @Override
        public Router router() {
            return router;
        }

        @Override
        public BriefRounds briefRounds() {
            return briefRounds;
        }

        @Override
        public Effects.Research research() {
            return research;
        }
    }

    /** An in-memory memory store: what it was asked is in {@code calls}. */
    static final class FakeStore implements LiveMemory.Store {
        final List<String> calls = new ArrayList<>();
        List<java.util.Map<String, Object>> entries = new ArrayList<>();
        Path dir;

        @Override
        public void sync() {
            calls.add("sync");
        }

        @Override
        public Path file(String id) {
            return dir.resolve(id + ".md");
        }

        @Override
        public List<java.util.Map<String, Object>> entries() {
            return entries;
        }

        @Override
        public List<String> learned(List<String[]> blocks, String source, String link, String labelLower) {
            blocks.forEach(b -> calls.add("learned " + String.join("|", b) + " from " + source + " " + link));
            return List.of("1 item(s) in het geheugen bewaard");
        }

        @Override
        public boolean history(Task t, String label, String labelLower, String link, String statusNl, String title,
                               String asked, String result) {
            calls.add("history " + t.id() + " " + label + " " + statusNl + " " + title);
            return true;
        }
    }

    @TempDir
    Path tmp;
    FakeRunner runner;
    List<String> notes;
    LiveEffects fx;
    FakeLate late;
    FakeStore memory;
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
        late = new FakeLate();
        memory = new FakeStore();
        memory.dir = tmp.resolve("memory-repo");
        db = CoreFixture.db();
        CoreFixture.clear(db);
        TaskStore store = new TaskStore(db, new CoreFixture.MutableClock("2026-11-26T10:00:00Z"));
        fx = new LiveEffects(runner, new Settings(tmp.resolve("config"), env), store,
                (a, c, k, t, l, r) -> notes.add(k + " " + t), late, new Cancellations(),
                new CoreSettings(false, 4, 3, 80, 30, 7, 2), memory, Map::of);
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
        assertThat(runner.joined()).containsExactly("gh api -X GET /repos/o/r/pulls/3");
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

    // ---- the router, planner and reviewer ----------------------------------------------------------------------------

    private static final String ROUTE_JSON = "{\"event\":\"result\",\"v\":1,\"text\":\"```route\\n{}\\n```\","
            + "\"is_error\":false,\"exit_code\":0,\"raw\":{},\"stderr\":\"\"}";

    @Test
    void theRouterWritesItsShareFilesStartsRoutePyAndRemovesThem() {
        boolean[] filesThere = {false};
        runner.answer = c -> {
            filesThere[0] = Files.exists(tmp.resolve("share/r21/prompt.txt")) && Files.exists(tmp.resolve("share/r21/system.txt"));
            return new Finished(0, ROUTE_JSON + "\n", "", false);
        };
        Effects.RouteAnswer a = fx.route(21, "systeem", "prompt");
        assertThat(filesThere[0]).isTrue();
        assertThat(a.error()).isFalse();
        assertThat(a.text()).startsWith("```route");
        assertThat(runner.joined()).containsExactly("python3 -I /r/route.py 21");
        assertThat(runner.commands.get(0).env()).containsEntry("TASK_CMD", "fake-task-cmd");
        assertThat(runner.commands.get(0).timeout()).isEqualTo(java.time.Duration.ofSeconds(300));
        assertThat(tmp.resolve("share/r21")).doesNotExist();
    }

    @Test
    void aRouterThatTimesOutOrGivesNoResultIsAnError() {
        runner.answer = c -> new Finished(143, "", "", true);
        assertThat(fx.route(21, "s", "p")).isEqualTo(new Effects.RouteAnswer("", true, "de router duurde langer dan 300 s"));
        runner.answer = c -> new Finished(1, "geen json\n", "boem\n", false);
        assertThat(fx.route(21, "s", "p")).isEqualTo(new Effects.RouteAnswer("", true, "boem"));
        runner.answer = c -> new Finished(1, "", "", false);
        assertThat(fx.route(21, "s", "p").stderr()).isEqualTo("de route-runner gaf geen resultaat");
    }

    @Test
    void aJobDraftStartsDraftPyWithItsDraftIdAndReturnsTheJobFile() {
        String result = "{\"event\":\"result\",\"is_error\":false,\"text\":\"```job\\n---\\nname: x\\n---\\nDoe iets.\\n```\"}";
        boolean[] filesThere = {false};
        runner.answer = c -> {
            filesThere[0] = Files.exists(tmp.resolve("share/d7/prompt.txt")) && Files.exists(tmp.resolve("share/d7/system.txt"));
            return new Finished(0, result + "\n", "", false);
        };
        Effects.DraftAnswer a = fx.draft(7, "systeem", "prompt");
        assertThat(filesThere[0]).isTrue();
        assertThat(a).isEqualTo(new Effects.DraftAnswer("---\nname: x\n---\nDoe iets.\n", null));
        assertThat(runner.joined()).containsExactly("python3 -I /r/draft.py d7");
        assertThat(runner.commands.get(0).env()).containsEntry("TASK_CMD", "fake-task-cmd");
        assertThat(runner.commands.get(0).timeout()).isEqualTo(java.time.Duration.ofSeconds(300));
        assertThat(tmp.resolve("share/d7")).doesNotExist();
    }

    @Test
    void aJobDraftThatFailsOrTimesOutAnswersWithAnError() {
        runner.answer = c -> new Finished(143, "", "", true);
        assertThat(fx.draft(7, "s", "p").text()).isNull();
        assertThat(fx.draft(7, "s", "p").error()).contains("300");
        runner.answer = c -> new Finished(0, "{\"event\":\"result\",\"is_error\":false,\"text\":\"geen blok\"}\n", "", false);
        assertThat(fx.draft(7, "s", "p").error()).isEqualTo("Claude gaf geen job-bestand terug");
        assertThat(tmp.resolve("share/d7")).doesNotExist();
    }

    @Test
    void thePlannerRunsThePlanActionAndReadsTheFirstJsonObjectDespiteAWarningLine() {
        runner.answer = c -> new Finished(0, "{\"is_error\":false,\"result\":\"```plan\\n{}\\n```\"}\n[claude-code:unrecognized_model]\n", "", false);
        Effects.ClaudeAnswer a = fx.plan(22, "systeem", "prompt");
        assertThat(a.ok()).isTrue();
        assertThat(a.text()).startsWith("```plan");
        assertThat(runner.joined()).containsExactly("fake-task-cmd plan r22");
        assertThat(runner.commands.get(0).timeout()).isEqualTo(java.time.Duration.ofSeconds(900));
        assertThat(tmp.resolve("share/r22")).doesNotExist();
        runner.answer = c -> new Finished(1, "kapot", "", false);
        Effects.ClaudeAnswer bad = fx.plan(22, "s", "p");
        assertThat(bad.ok()).isFalse();
        assertThat(bad.detail()).isEqualTo("kapot");
    }

    @Test
    void theReviewerRunsReviewPyAndReturnsItsVerdict() {
        runner.answer = c -> new Finished(0, "{\"event\":\"log\"}\n{\"event\":\"result\",\"ok\":true,\"text\":\"```review```\","
                + "\"detail\":\"\"}\n", "", false);
        Effects.ClaudeAnswer a = fx.review(23, "systeem", "prompt");
        assertThat(a.ok()).isTrue();
        assertThat(a.text()).isEqualTo("```review```");
        assertThat(runner.joined()).containsExactly("python3 -I /r/review.py 23");
        assertThat(tmp.resolve("share/v23")).doesNotExist();
    }

    // ---- the product repo and the briefs -----------------------------------------------------------------------------

    private void haveClone(String name) throws Exception {
        Files.createDirectories(tmp.resolve("repos").resolve(name).resolve(".git"));
    }

    @Test
    void approvingABriefCommitsOneFileToMainOfTheProductRepo() throws Exception {
        haveClone("product");
        fx.commitToMain("homelab/briefs/H-1.md", "tekst", "H-1: approved (x)");
        List<String> j = runner.joined().stream().map(l -> l.substring(l.indexOf("-C ") + 3)).toList();
        assertThat(j.get(0)).endsWith("product fetch --quiet origin main");
        assertThat(j.get(1)).endsWith("product checkout --quiet -B main origin/main");
        assertThat(j.get(2)).endsWith("product add -- homelab/briefs/H-1.md");
        assertThat(j.get(3)).contains("-c user.name=homelab-inbox -c user.email=homelab-inbox@homelab commit --quiet -m H-1: approved (x)");
        assertThat(j.get(4)).endsWith("product push --quiet origin HEAD:refs/heads/main");
        assertThat(Files.readString(tmp.resolve("repos/product/homelab/briefs/H-1.md"))).isEqualTo("tekst");
    }

    @Test
    void theHandoffBranchIsForcePushedAndMainRestoredEvenWhenThePushFails() throws Exception {
        haveClone("product");
        runner.answer = c -> new Finished(String.join(" ", c.args()).contains(" push ") ? 1 : 0, "", "geweigerd", false);
        assertThatThrownBy(() -> fx.pushBranch("handoff/H-1", Map.of("a.md", "x"), "m")).hasMessageContaining("git push");
        String all = String.join("\n", runner.joined());
        assertThat(all).contains("checkout --quiet -B handoff/H-1 origin/main", "push --quiet --force origin HEAD:refs/heads/handoff/H-1");
        assertThat(runner.joined().get(runner.joined().size() - 1)).endsWith("checkout --quiet -B main origin/main");
    }

    @Test
    void setupPrsAreReadFromTheOpenPullsAndTheirFiles() {
        runner.answer = c -> {
            String j = String.join(" ", c.args());
            if (j.contains("/pulls?state=open&head=o:setup/H-1")) {
                return new Finished(0, "[{\"number\":4,\"head\":{\"repo\":{\"full_name\":\"o/r\"}}},\"x\"]", "", false);
            }
            return new Finished(0, "[{\"filename\":\"CLAUDE.md\"},{\"filename\":\".claude/agents/a.md\"}]", "", false);
        };
        List<Effects.SetupPr> prs = fx.setupPrs("o/r", "H-1");
        assertThat(prs).containsExactly(new Effects.SetupPr(4, "o/r", List.of("CLAUDE.md", ".claude/agents/a.md")));
    }

    @Test
    void aBriefReviewCommentIsPostedThroughGhApi() {
        fx.comment("o/r", 7, "tekst");
        assertThat(runner.joined()).containsExactly("gh api -X POST /repos/o/r/issues/7/comments --input -");
        assertThat(runner.commands.get(0).stdin()).isEqualTo("{\"body\":\"tekst\"}");
    }

    @Test
    void aRefusedHandoffMergeIsAnIllegalStateException() {
        runner.answer = c -> new Finished(1, "", "HTTP 405", false);
        assertThatThrownBy(() -> fx.mergeHandoff("o/r", 7, "t")).isInstanceOf(IllegalStateException.class).hasMessageContaining("405");
    }

    @Test
    void repoProfilesShowTheStartOfClaudeMdAndWhetherMainHasCi() throws Exception {
        haveClone("alpha");
        runner.answer = c -> {
            String j = String.join(" ", c.args());
            if (j.contains("show origin/main:CLAUDE.md")) {
                return new Finished(0, "# Alpha\n\n  de  repo\n", "", false);
            }
            return new Finished(0, j.contains("ls-tree") ? ".github/workflows/ci.yml\n" : "", "", false);
        };
        assertThat(fx.profiles(Map.of("alpha", "o/alpha"), true)).isEqualTo("- alpha (o/alpha): # Alpha de repo [CI: yes]");
        assertThat(fx.profiles(Map.of("alpha", "o/alpha"), false)).isEqualTo("- alpha (o/alpha): # Alpha de repo");
    }

    @Test
    void theChangedFilesAndDiffComeFromTheTaskBranchAgainstMain() {
        runner.answer = c -> new Finished(0, "a.txt\n\nb.txt\n", "", false);
        assertThat(fx.changedFiles("/c/homelab", 5)).containsExactly("a.txt", "b.txt");
        fx.diff("/c/homelab", 5, List.of("a.txt"));
        assertThat(runner.joined().get(1)).endsWith("-C /c/homelab diff origin/main...refs/inbox/5 -- a.txt");
    }

    @Test
    void aNewRepoIsAddedToTheInboxsOwnList() throws Exception {
        Files.writeString(tmp.resolve("new-repos.json"), "{\"old\":\"o/old\"}");
        fx.add("fresh", "o/fresh");
        assertThat(fx.list()).containsEntry("old", "o/old").containsEntry("fresh", "o/fresh");
    }

    // ---- jobs --------------------------------------------------------------------------------------------------------

    @Test
    void theLocalModelIsAskedThroughCurlWithTheBodyOnStdin() throws Exception {
        runner.answer = c -> new Finished(0, "{\"choices\":[{\"message\":{\"content\":\" samenvatting \"}}]}", "", false);
        assertThat(fx.summarize("daily", "vat samen", "tekst")).isEqualTo("samenvatting");
        Command call = runner.commands.get(0);
        assertThat(call.args()).contains("curl", "--fail-with-body", "X-LLM-Caller: job:daily").endsWith("http://127.0.0.1:8017/v1/chat/completions");
        assertThat(call.stdin()).contains("\"model\":\"summarize\"", "\"user\":\"job:daily\"", "vat samen", "tekst");
        runner.answer = c -> new Finished(22, "{\"error\":\"vol\"}", "", false);
        assertThatThrownBy(() -> fx.summarize("daily", "i", "t")).hasMessageStartingWith("LLM-helper: {");
    }

    @Test
    void aJobScriptRunsThroughTheTaskScriptAction() throws Exception {
        haveClone("homelab");
        runner.answer = c -> new Finished(String.join(" ", c.args()).contains("fake-task-cmd script") ? 3 : 0, "uitvoer", "", false);
        Effects.ScriptResult r = fx.runScript("homelab", "scripts/x.sh", List.of("a", "b"), 12);
        assertThat(r).isEqualTo(new Effects.ScriptResult(3, "uitvoer"));
        assertThat(runner.joined()).contains("fake-task-cmd script j12 homelab scripts/x.sh a b");
    }

    @Test
    void theBoxOverviewIsTheOutputOfHomelabOverview() throws Exception {
        runner.answer = c -> new Finished(0, "overzicht", "", false);
        assertThat(fx.boxOverview()).isEqualTo("overzicht");
        assertThat(runner.joined()).containsExactly("/usr/local/bin/homelab-overview");
        runner.answer = c -> new Finished(2, "", "stuk", false);
        assertThatThrownBy(() -> fx.boxOverview()).hasMessage("homelab-overview: stuk");
    }

    @Test
    void aPageIsFetchedThroughCurlAndGithubReadsComeBackAsPlainJava() throws Exception {
        runner.answer = c -> new Finished(0, "<html>", "", false);
        assertThat(fx.fetchPage("https://x.test/feed")).isEqualTo("<html>");
        assertThat(runner.joined().get(0)).startsWith("curl -sSL --fail -m 20 -A homelab-jobs/1 https://x.test/feed");
        runner.answer = c -> new Finished(0, "[{\"sha\":\"abc\"}]", "", false);
        assertThat(fx.github("/repos/o/r/commits")).isEqualTo(List.of(Map.of("sha", "abc")));
    }

    // ---- memory and skills -------------------------------------------------------------------------------------------

    @Test
    void geheugenBlocksAreSplitOffTheAnswerAndSavedAfterTheRound() throws Exception {
        String text = "KLAAR: ok\n\nGEHEUGEN: voorkeur algemeen kort\nHou het kort.\nGEHEUGEN: feit box poort\nPoort 1.\n";
        Effects.MemorySteps.MemorySplit split = fx.split(text);
        assertThat(split.text()).isEqualTo("KLAAR: ok");
        assertThat(split.blocks()).hasSize(2);
        CoreFixture.insert(db, 31, "homelab", "answered");
        fx.afterRound(31, split.blocks());
        assertThat(memory.calls).contains("learned voorkeur|algemeen|kort|Hou het kort. from Inbox 31 http://homelab:8105/task/31",
                "history 31 Inbox 31 beantwoord inbox 31: text 31");
        Task t = new TaskStore(db, new CoreFixture.MutableClock("2026-11-26T10:00:00Z")).get(31).orElseThrow();
        assertThat(t.str("note")).isEqualTo("1 item(s) in het geheugen bewaard");
        assertThat(t.num("remembered")).isEqualTo(1);
    }

    @Test
    void theSystemPromptHasTheMemoryPartAndTheHistoryFilesAreCopiedNextToTheTask() throws Exception {
        Files.createDirectories(memory.dir.resolve("geschiedenis/2026-11"));
        Files.writeString(memory.dir.resolve("geschiedenis/2026-11/taak-1.md"), "oud");
        memory.entries.add(Map.of("id", "geschiedenis/2026-11/taak-1", "kind", "geschiedenis", "about", "homelab", "name", "taak-1",
                "body", "# Oud", "added", "2026-11-01T10:00:00", "changed", "2026-11-01T10:00:00", "status", "gemerged", "link", "http://l"));
        memory.entries.add(Map.of("id", "voorkeuren/algemeen/kort", "kind", "voorkeuren", "about", "algemeen", "name", "kort",
                "body", "Hou het kort.", "added", "", "changed", "", "status", "", "link", ""));
        CoreFixture.insert(db, 32, "homelab", "queued");
        Task t = new TaskStore(db, new CoreFixture.MutableClock("2026-11-26T10:00:00Z")).get(32).orElseThrow();
        fx.writePrompt(t, "doe iets");
        String system = Files.readString(tmp.resolve("share/32/system.txt"));
        assertThat(system).contains("Je werkt voor de homelab-inbox", "Geheugen (F-023)", "- [algemeen/kort] Hou het kort.",
                "- 2026-11-01 gemerged: Oud (http://l)");
        assertThat(Files.readString(tmp.resolve("share/32/geschiedenis/2026-11-taak-1.md"))).isEqualTo("oud");
        assertThat(Files.readString(tmp.resolve("share/32/prompt.txt"))).isEqualTo("doe iets");
    }

    @Test
    void aSkillProposalIsPushedToItsOwnBranchAndOpenedAsAPr() throws Exception {
        haveClone("homelab");
        runner.answer = c -> new Finished(0, c.args().contains("create") ? "https://github.com/o/r/pull/9\n" : "", "", false);
        CoreFixture.insert(db, 33, "homelab", "answered");
        Task t = new TaskStore(db, new CoreFixture.MutableClock("2026-11-26T10:00:00Z")).get(33).orElseThrow();
        fx.proposeSkill(t, "mijn-skill", "doet iets", "stappen");
        String all = String.join("\n", runner.joined());
        assertThat(all).contains("checkout --quiet -b skill/mijn-skill-33",
                "push --quiet https://github.com/chrisfaber95/homelab.git HEAD:refs/heads/skill/mijn-skill-33",
                "gh pr create --repo chrisfaber95/homelab --title skill: mijn-skill --head skill/mijn-skill-33 --base main");
        assertThat(all).doesNotContain("HEAD:refs/heads/main");
        assertThat(all).contains("-c user.name=Claude (homelab-inbox) -c user.email=noreply@anthropic.com commit --quiet -m Skill mijn-skill: nieuw (voorstel uit Inbox 33)");
    }

    // ---- what the effects hand back to the core ----------------------------------------------------------------------

    @Test
    void roundsRoutesPlansAndResearchGoToTheBeansOfTheCore() {
        fx.runRound(5);
        org.mockito.Mockito.verify(late.flow).run(5);
        fx.route(5);
        org.mockito.Mockito.verify(late.router).routeTask(5);
        fx.plan(5);
        org.mockito.Mockito.verify(late.briefRounds).planTask(5);
        fx.reviewPr(5, "/c");
        org.mockito.Mockito.verify(late.briefRounds).reviewPr(5, "/c");
        assertThat(fx.jobInstruction("j")).isNull();
        org.mockito.Mockito.verify(late.research).jobInstruction("j");
        assertThat(runner.commands).isEmpty();
    }

    @Test
    void aQuickResearchRunStartsRoundPyWithResearchAndItsOwnLimit() throws Exception {
        org.mockito.Mockito.when(late.research.runOf(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(new Effects.ResearchRun(1, "quick", "c")));
        CoreFixture.insert(db, 34, "homelab", "running");
        runner.lines.add("{\"event\":\"result\",\"raw\":{\"result\":\"ANTWOORD: x\"}}");
        runner.lines.add("{\"event\":\"turn\",\"turns\":1}");
        fx.stream(34, "new");
        assertThat(runner.joined().get(0)).endsWith(" new research");
        assertThat(runner.commands.get(0).timeout()).isEqualTo(java.time.Duration.ofMinutes(6));
    }
}
