package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.core.Effects.ResearchRun;
import dev.homelab.inbox.core.Effects.Result;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * finish_round, the round flow and catch-up of main, ported from the Python tests (H-026 K2). Every test names the
 * Python test it covers. The effects are fakes: nothing is pushed, opened, merged, notified or started.
 */
class RoundFinishTest {

    static final String REPORT = "ANTWOORD: ## Antwoord\nOne claim [1].\n\n## Bronnen\n[1] https://example.org/a\n\n"
            + "## Doorzocht\nprivate: tailscale acl; web: tailscale acl syntax";

    /** Scripted effects: what the push finds, the research run, the answers of homelab-inbox-task. */
    static class Script extends CoreFixture.Fakes {
        List<String> commits = new ArrayList<>();
        RuntimeException pushFails;
        Optional<ResearchRun> run = Optional.empty();
        final List<String> delivered = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> notifications = new ArrayList<>();
        final Map<String, Result> answers = new java.util.HashMap<>();
        final List<String> prompts = new ArrayList<>();
        Outcome outcome = new Outcome("KLAAR: done", 0, null);
        String lastBody;
        int opened = 0;

        @Override
        public List<String> push(long taskId, String cache) {
            super.push(taskId, cache);
            if (pushFails != null) {
                throw pushFails;
            }
            return commits;
        }

        @Override
        public Opened open(String slug, String title, String head, String base, String body) {
            super.open(slug, title, head, base, body);
            lastBody = body;
            opened++;
            return new Opened(77, "https://github.com/" + slug + "/pull/77");
        }

        @Override
        public Optional<ResearchRun> runOf(Task task) {
            return run;
        }

        @Override
        public void deliver(Task task, String text, String link) {
            super.deliver(task, text, link);
            delivered.add(text);
        }

        @Override
        public void note(String author, String client, String kind, String text, String link, String ref) {
            super.note(author, client, kind, text, link, ref);
            notes.add(kind + "|" + text + "|" + link + "|" + ref);
        }

        @Override
        public void notify(String title, String body, String url, boolean store) {
            super.notify(title, body, url, store);
            notifications.add(title + "|" + body + "|" + store);
        }

        @Override
        public Result behind(long taskId) {
            super.behind(taskId);
            return answers.getOrDefault("behind", new Result(0, ""));
        }

        @Override
        public Result sync(long taskId) {
            super.sync(taskId);
            return answers.getOrDefault("sync", new Result(0, ""));
        }

        @Override
        public Result prepare(long taskId, String repo) {
            super.prepare(taskId, repo);
            return answers.getOrDefault("prepare", new Result(0, ""));
        }

        @Override
        public Map<String, String> list() {
            return Map.of("homelab", "chrisfaber95/homelab", "other", "chrisfaber95/other");
        }

        @Override
        public void writePrompt(Task task, String prompt) {
            prompts.add(prompt);
        }

        @Override
        public Outcome stream(long taskId, String mode) {
            super.stream(taskId, mode);
            return outcome;
        }
    }

    private ConnectionSource db;
    private TaskStore store;
    private Script fx;
    private CoreFixture.MutableClock clock;
    private RoundFinisher finisher;
    private CatchUp catchUp;
    private RoundFlow flow;
    private final List<String> afterRounds = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        clock = new CoreFixture.MutableClock("2026-11-18T10:00:00Z");
        store = new TaskStore(db, clock);
        fx = new Script();
        finisher = new RoundFinisher(store, fx, fx, fx, fx, fx, fx, fx, fx, Runnable::run, clock, "http://homelab:8105");
        catchUp = new CatchUp(fx, fx);
        Effects.MemorySteps memory = new Effects.MemorySteps() {
            @Override
            public void load() {}

            @Override
            public MemorySplit split(String text) {
                return new MemorySplit(text, List.of("block"));
            }

            @Override
            public void afterRound(long id, List<String> blocks) {
                afterRounds.add(id + ":" + blocks);
            }
        };
        flow = new RoundFlow(store, fx, memory, fx, fx, fx, catchUp, finisher, fx, fx, "http://homelab:8105");
    }

    private void running(long id, String... columns) throws Exception {
        CoreFixture.insert(db, id, "homelab", "running", columns);
    }

    private Task task(long id) {
        return store.get(id).orElseThrow();
    }

    private void finish(long id, String text, String capped) {
        finisher.finish(id, "/cache", text, 0, capped);
    }

    // ---- finish_round: tests/test_inbox_events.py

    /** test_questions_of_a_task_are_stored_in_full (and the VRAAG branch: waiting, question, not stored twice) */
    @Test
    void aQuestionIsKeptInFullInTheConversationAndNotNotifiedTwice() throws Exception {
        running(5);
        String longText = "q ".repeat(3000);
        finish(5, "VRAAG: " + longText, null);
        assertThat(task(5).status()).isEqualTo("waiting");
        assertThat(task(5).str("question")).isEqualTo(longText.strip());
        assertThat(fx.notes).containsExactly("question|" + longText.strip() + "|http://homelab:8105/task/5|task:5");
        assertThat(fx.notifications).containsExactly("Inbox 5: vraag|" + longText.strip() + "|false");
        assertThat(fx.calls()).doesNotContain("push 5", "cleanup 5");
    }

    // ---- finish_round: tests/test_inbox_followups.py

    /** test_capped_round_keeps_its_working_copy_for_continue */
    @Test
    void aCappedRoundKeepsItsWorkingCopyAndContinueCarriesOn() throws Exception {
        running(74, "rounds", "1");
        finish(74, "", "80 beurten");
        assertThat(task(74).status()).isEqualTo("capped");
        assertThat(task(74).str("note")).isEqualTo("gestopt bij 80 beurten");
        assertThat(fx.calls()).doesNotContain("cleanup 74");
        assertThat(fx.notifications).containsExactly("Inbox 74: gestopt bij 80 beurten|geen commits|true");
        new TaskActions(store, CoreSettings.shadowDefaults(), new Cancellations(), fx, fx, fx, fx, fx, Runnable::run,
                clock, "http://homelab:8105").retry(task(74), "");
        assertThat(task(74).num("reprepare")).isZero();
        assertThat(task(74).str("pending_input")).contains("asked to continue it. Your working copy is as you left it");
    }

    @Test
    void aCappedRoundThatPushedSaysSo() throws Exception {
        running(8);
        fx.commits = List.of("abc1234 first");
        finish(8, "KLAAR: x", "30 minuten");
        assertThat(task(8).str("note")).isEqualTo("gestopt bij 30 minuten; branch inbox/8 gepusht");
        assertThat(fx.notifications).containsExactly("Inbox 8: gestopt bij 30 minuten|1 commit(s) op inbox/8|true");
    }

    // ---- finish_round: tests/test_inbox_research.py, class Finish

    private void research(long id, String created) throws Exception {
        running(id, "job", "research", "run_id", "3", "started", created);
        fx.run = Optional.of(new ResearchRun(3, "deep", "2026-11-18T09:58:00+00:00"));
    }

    /** test_report_has_mode_and_time_and_sources_and_stays_on_the_page */
    @Test
    void aResearchReportGetsModeAndTimeAndNothingIsPushed() throws Exception {
        research(9, "2026-11-18T09:58:00+00:00");
        finish(9, REPORT, null);
        Task t = task(9);
        assertThat(t.status()).isEqualTo("answered");
        assertThat(t.str("answer")).startsWith("Research report | mode: deep | time taken: 2 min 0 s\n\n");
        assertThat(t.str("answer")).contains("https://example.org/a");
        assertThat(fx.calls()).contains("cleanup 9").doesNotContain("push 9").noneMatch(c -> c.startsWith("open pr"));
        assertThat(fx.delivered).hasSize(1).first().isEqualTo(t.str("answer"));
        assertThat(fx.notes).isEmpty(); // a job writes no conversation note itself
    }

    /** test_page_question_delivers_nothing_extra / test_discord_question_gets_its_report: delivery is one call */
    @Test
    void theReportIsHandedToDeliveryOnceAndWithTheTaskLink() throws Exception {
        research(9, "2026-11-18T09:59:30+00:00");
        finish(9, REPORT, null);
        assertThat(fx.calls().stream().filter(c -> c.equals("deliver 9"))).hasSize(1);
        assertThat(task(9).str("answer")).contains("time taken: 30 s");
    }

    /** test_nothing_found_is_delivered_as_the_report */
    @Test
    void nothingFoundIsAValidReportWithoutANote() throws Exception {
        research(9, "2026-11-18T09:59:00+00:00");
        finish(9, "ANTWOORD: NIETS GEVONDEN\nNo source covers this.\n\n## Doorzocht\n- private: x\n- web: y", null);
        assertThat(task(9).str("answer")).contains("NIETS GEVONDEN").contains("## Doorzocht");
        assertThat(task(9).has("note")).isFalse();
    }

    /** test_report_without_sources_is_flagged (with the auto mode denials kept in the note) */
    @Test
    void aReportWithoutSourcesIsFlaggedInTheNote() throws Exception {
        research(9, "2026-11-18T09:59:00+00:00");
        finisher.finish(9, "/c", "ANTWOORD: ## Antwoord\nA claim.\n\n## Doorzocht\n- x", 2, null);
        assertThat(task(9).str("note")).isEqualTo("2 actie(s) geweigerd door auto mode; report check: no sources");
    }

    /** test_no_report_is_a_failure_not_a_pr */
    @Test
    void aResearchRunWithoutAReportFailsAndOpensNoPr() throws Exception {
        research(9, "2026-11-18T09:59:00+00:00");
        finish(9, "KLAAR: I changed things", null);
        assertThat(task(9).status()).isEqualTo("failed");
        assertThat(task(9).has("pr_number")).isFalse();
        assertThat(fx.notifications).hasSize(1);
        assertThat(fx.calls()).doesNotContain("push 9");
    }

    /** test_a_run_that_hits_its_limit_pushes_nothing */
    @Test
    void aResearchRunAtItsLimitPushesNothing() throws Exception {
        research(9, "2026-11-18T09:59:00+00:00");
        finish(9, "KLAAR: half", "6 minuten");
        assertThat(task(9).status()).isEqualTo("capped");
        assertThat(task(9).has("pr_number")).isFalse();
        assertThat(fx.notifications.get(0)).startsWith("Job research: gestopt bij 6 minuten|deep-onderzoek niet afgerond");
        assertThat(fx.calls()).contains("cleanup 9").doesNotContain("push 9");
    }

    // ---- finish_round: behaviour without its own Python test (the branches of queue.finish_round)

    @Test
    void anAnswerEndsTheTaskCleansUpAndNotesTheConversation() throws Exception {
        running(11);
        finish(11, "ANTWOORD: forty-two", null);
        assertThat(task(11).status()).isEqualTo("answered");
        assertThat(task(11).str("answer")).isEqualTo("forty-two");
        assertThat(fx.notes).containsExactly("answer|forty-two|http://homelab:8105/task/11|task:11");
        assertThat(fx.calls()).contains("cleanup 11").doesNotContain("push 11");
        assertThat(fx.notifications).containsExactly("Inbox 11: antwoord|forty-two|false");
    }

    @Test
    void theAnswerOfAJobIsDeliveredAndNotNotified() throws Exception {
        running(11, "job", "weekly");
        finish(11, "ANTWOORD: all quiet", null);
        assertThat(fx.delivered).containsExactly("all quiet");
        assertThat(fx.notifications).isEmpty();
        assertThat(fx.notes).isEmpty();
    }

    @Test
    void aRefusalKeepsTheReasonAndCleansUp() throws Exception {
        running(12);
        finish(12, "GEWEIGERD: F-019 forbids it", null);
        assertThat(task(12).status()).isEqualTo("refused");
        assertThat(task(12).str("summary")).isEqualTo("F-019 forbids it");
        assertThat(fx.calls()).contains("cleanup 12");
        assertThat(fx.notifications).containsExactly("Inbox 12: geweigerd|F-019 forbids it|true");
    }

    @Test
    void aCancelledRoundEndsCancelledAndKeepsAPrWorkingCopy() throws Exception {
        running(13);
        running(14, "pr_number", "3");
        finish(13, "KLAAR: x", "geannuleerd");
        finish(14, "KLAAR: x", "geannuleerd");
        assertThat(task(13).status()).isEqualTo("cancelled");
        assertThat(task(14).status()).isEqualTo("cancelled");
        assertThat(fx.calls()).contains("cleanup 13").doesNotContain("cleanup 14");
    }

    @Test
    void noCommitsAndNoKindIsAFailureWithTheWholeTextAsSummary() throws Exception {
        running(15);
        finish(15, "I am not sure what to do", null);
        assertThat(task(15).status()).isEqualTo("failed");
        assertThat(task(15).str("summary")).isEqualTo("I am not sure what to do");
        assertThat(task(15).str("note")).isEqualTo("Claude gaf geen KLAAR/ANTWOORD/VRAAG en maakte geen commits");
        assertThat(fx.calls()).contains("cleanup 15");
    }

    @Test
    void commitsOpenAPrWithTheTaskAndTheCommitsInItsBody() throws Exception {
        running(16);
        fx.commits = List.of("abc1234 one", "def5678 two");
        finish(16, "KLAAR: made it", null);
        Task t = task(16);
        assertThat(t.status()).isEqualTo("pr_open");
        assertThat(t.num("pr_number")).isEqualTo(77);
        assertThat(t.str("pr_url")).endsWith("/pull/77");
        assertThat(t.str("summary")).isEqualTo("made it");
        assertThat(fx.lastBody).contains("made it", "**Taak** (via de homelab-inbox, #16):", "- abc1234 one",
                "- def5678 two", "<!-- homelab-inbox task=16 -->");
        assertThat(fx.calls()).contains("open pr chrisfaber95/homelab inbox/16");
        assertThat(fx.notifications).containsExactly("Inbox 16: PR #77|made it|true");
        assertThat(fx.calls()).doesNotContain("merge chrisfaber95/homelab#77");
    }

    @Test
    void aTaskWithAPrGetsItsPrUpdatedNotANewOne() throws Exception {
        running(17, "pr_number", "5", "pr_url", "https://github.com/x/pull/5", "review", "old");
        fx.commits = List.of("abc1234 fix");
        finish(17, "KLAAR: fixed", null);
        assertThat(fx.opened).isZero();
        assertThat(task(17).status()).isEqualTo("pr_open");
        assertThat(task(17).has("review")).isFalse();
        assertThat(fx.notifications).containsExactly("Inbox 17: PR #5 bijgewerkt|fixed|true");
    }

    @Test
    void aBriefTaskIsReviewedInsteadOfNotified() throws Exception {
        running(18, "brief", "H-026");
        fx.commits = List.of("abc1234 one");
        finish(18, "KLAAR: done", null);
        assertThat(fx.calls()).contains("reviewPr 18");
        assertThat(fx.notifications).isEmpty();
        running(19, "brief", "H-026", "pr_number", "6", "pr_url", "u");
        finish(19, "KLAAR: done", null);
        assertThat(fx.calls()).contains("reviewPr 19");
    }

    @Test
    void aBriefTaskPrBodyHidesTheBrief() throws Exception {
        CoreFixture.insert(db, 20, "homelab", "running", "brief", "H-026");
        store.update(20, TaskStore.fields("text", "Do K2.\n\nThis task is part of brief H-026: secret"));
        fx.commits = List.of("abc1234 one");
        finish(20, "KLAAR: done", null);
        assertThat(fx.lastBody).contains("Do K2.", "(Part of brief H-026.)").doesNotContain("secret");
    }

    @Test
    void aFailingPushFailsTheTaskInsteadOfLosingIt() throws Exception {
        running(21);
        fx.pushFails = new IllegalStateException("bundle mislukt: boom");
        finish(21, "KLAAR: done", null);
        assertThat(task(21).status()).isEqualTo("failed");
        assertThat(task(21).str("note")).isEqualTo("afronden mislukt: bundle mislukt: boom");
        assertThat(fx.notifications).containsExactly("Inbox 21 mislukt|bundle mislukt: boom|true");
    }

    @Test
    void aSkillProposalIsSplitOffAndOnlyKept_forKlaarAndAnswers() throws Exception {
        running(22);
        fx.commits = List.of("abc1234 one");
        finish(22, "KLAAR: done\n\nSKILL-VOORSTEL: my-skill\nbeschrijving: does a thing\nsteps here", null);
        assertThat(fx.calls()).contains("proposeSkill 22 my-skill");
        assertThat(task(22).str("summary")).isEqualTo("done");
        running(23);
        finish(23, "VRAAG: why\n\nSKILL-VOORSTEL: other\nbeschrijving: d\nsteps", null);
        running(24);
        fx.commits = List.of("abc1234 one");
        finish(24, "KLAAR: x\n\nSKILL-VOORSTEL: capped-one\nbeschrijving: d\nsteps", "80 beurten");
        assertThat(fx.calls()).noneMatch(c -> c.startsWith("proposeSkill 23") || c.startsWith("proposeSkill 24"));
    }

    @Test
    void autoModeDenialsAreKeptInTheNote() throws Exception {
        running(25);
        finisher.finish(25, "/c", "ANTWOORD: ok", 3, null);
        assertThat(task(25).str("note")).isEqualTo("3 actie(s) geweigerd door auto mode");
    }

    // ---- conflict rounds: tests/test_inbox_catch_up.py, class CatchUpMain

    /** test_a_pr_is_left_to_the_github_poller */
    @Test
    void aPrIsLeftToTheGithubPoller() throws Exception {
        running(119, "pr_number", "7");
        assertThat(catchUp.catchUp(task(119))).isEmpty();
        assertThat(fx.calls()).noneMatch(c -> c.startsWith("behind") || c.startsWith("sync"));
    }

    /** test_a_branch_that_already_has_main_is_not_touched */
    @Test
    void aBranchThatAlreadyHasMainIsNotTouched() throws Exception {
        running(119);
        fx.answers.put("behind", new Result(1, ""));
        assertThat(catchUp.catchUp(task(119))).isEmpty();
        assertThat(fx.calls()).containsExactly("behind 119");
    }

    /** test_no_working_copy_is_not_touched */
    @Test
    void noWorkingCopyIsNotTouched() throws Exception {
        running(119);
        fx.answers.put("behind", new Result(2, "geen werkkopie"));
        assertThat(catchUp.catchUp(task(119))).isEmpty();
        assertThat(fx.calls()).containsExactly("behind 119");
    }

    /** test_a_clean_merge_is_told_to_the_task */
    @Test
    void aCleanMergeIsToldToTheTask() throws Exception {
        running(119);
        fx.answers.put("behind", new Result(0, ""));
        assertThat(catchUp.catchUp(task(119))).contains("merged into your branch");
        assertThat(fx.calls()).containsExactly("behind 119", "sync 119",
                "log t119 info merged main into inbox/119 before this round");
    }

    /** test_conflicts_name_the_files */
    @Test
    void conflictsNameTheFiles() throws Exception {
        running(119);
        fx.answers.put("behind", new Result(0, ""));
        fx.answers.put("sync", new Result(4, "package.json\nclient/src/main.ts\n"));
        String out = catchUp.catchUp(task(119));
        assertThat(out).contains("- package.json", "- client/src/main.ts", "commit the merge");
        assertThat(fx.calls()).contains("log t119 info main conflicts with inbox/119 in 2 file(s)");
    }

    /** test_a_failed_merge_hands_over_the_command */
    @Test
    void aFailedMergeHandsOverTheCommand() throws Exception {
        running(119);
        fx.answers.put("behind", new Result(0, ""));
        fx.answers.put("sync", new Result(1, "git merge origin/main failed without conflicts (uncommitted changes?)"));
        String out = catchUp.catchUp(task(119));
        assertThat(out).contains("uncommitted changes", "safe.directory");
        assertThat(fx.calls()).anyMatch(c -> c.startsWith("log t119 error could not merge main into inbox/119: "));
    }

    // ---- conflict rounds: tests/test_inbox_catch_up.py, class RoundGetsMain

    private void retried() throws Exception {
        CoreFixture.insert(db, 119, "homelab", "queued", "rounds", "1", "pending_input", "Retried by the user.");
        store.update(119, TaskStore.fields("text", "scaffold it"));
        fx.answers.put("behind", new Result(0, ""));
    }

    /** test_retried_round_merges_main_and_keeps_the_users_note */
    @Test
    void aRetriedRoundMergesMainAndKeepsTheUsersNote() throws Exception {
        retried();
        flow.run(119);
        assertThat(fx.calls()).contains("behind 119", "sync 119").noneMatch(c -> c.startsWith("prepare"));
        assertThat(fx.prompts).hasSize(1);
        assertThat(fx.prompts.get(0)).startsWith("Retried by the user.").contains("merged into your branch");
    }

    /** test_first_round_does_not_sync */
    @Test
    void theFirstRoundDoesNotSync() throws Exception {
        retried();
        store.update(119, TaskStore.fields("rounds", 0));
        flow.run(119);
        assertThat(fx.calls()).contains("prepare 119 homelab").noneMatch(c -> c.startsWith("behind") || c.startsWith("sync"));
        assertThat(fx.prompts.get(0)).contains("scaffold it").startsWith("Taak voor de repo homelab:");
    }

    // ---- run_round around it (no separate Python test; the paths finish_round depends on)

    @Test
    void aRoundCountsUpClearsItsInputAndHandsTheResultToFinish() throws Exception {
        retried();
        fx.outcome = new Effects.Sessions.Outcome("ANTWOORD: fine\nGEHEUGEN: voorkeur x", 1, null);
        flow.run(119);
        Task t = task(119);
        assertThat(t.status()).isEqualTo("answered");
        assertThat(t.num("rounds")).isEqualTo(2);
        assertThat(t.has("pending_input")).isFalse();
        assertThat(t.num("restarts")).isZero();
        assertThat(t.str("note")).isEqualTo("1 actie(s) geweigerd door auto mode");
        assertThat(afterRounds).containsExactly("119:[block]");
        assertThat(fx.calls()).contains("stream 119 resume");
    }

    @Test
    void aCappedRoundHandsNoMemoryBlocksOn() throws Exception {
        retried();
        fx.outcome = new Effects.Sessions.Outcome("", 0, "80 beurten");
        flow.run(119);
        assertThat(afterRounds).containsExactly("119:[]");
    }

    @Test
    void aFailedPrepareFailsTheTaskAndCleansUp() throws Exception {
        retried();
        store.update(119, TaskStore.fields("reprepare", 1));
        fx.answers.put("prepare", new Result(1, "no space"));
        flow.run(119);
        assertThat(task(119).status()).isEqualTo("failed");
        assertThat(task(119).str("note")).isEqualTo("werkkopie maken mislukt: no space");
        assertThat(fx.calls()).contains("cleanup 119").doesNotContain("stream 119 resume");
        assertThat(afterRounds).containsExactly("119:[]");
    }

    @Test
    void anUnknownRepoFailsAndTheRouterAndPlannerAreNotMine() throws Exception {
        CoreFixture.insert(db, 30, "gone", "queued");
        CoreFixture.insert(db, 31, "auto", "queued");
        CoreFixture.insert(db, 32, "brief", "queued");
        flow.run(30);
        flow.run(31);
        flow.run(32);
        assertThat(task(30).str("note")).isEqualTo("repo staat niet (meer) in de lijst");
        assertThat(fx.calls()).contains("route 31", "plan 32");
    }

    @Test
    void withTheShadowEffectsNothingRealHappens() throws Exception {
        // The default wiring: a plain ShadowEffects does no push, no PR, no merge, no notification of its own.
        running(40);
        ShadowEffects shadow = new ShadowEffects();
        new RoundFinisher(store, shadow, shadow, shadow, shadow, shadow, shadow, shadow, shadow, Runnable::run, clock, "")
                .finish(40, "/c", "KLAAR: x", 0, null);
        assertThat(task(40).status()).isEqualTo("failed"); // the shadow push finds no commits
        assertThat(shadow.calls()).noneMatch(c -> c.startsWith("merge") || c.startsWith("open pr"));
    }
}
