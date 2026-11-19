package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.core.Effects.GitHubHttpException;
import dev.homelab.inbox.core.Effects.Result;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GitHub polling and the auto-merge rules (H-026 K5), against a PostgreSQL with the tasks of the Python tests and a
 * scripted GitHub. The javadoc of each test names the Python test it ports; "no Python test" means it is written from
 * {@code github.py}.
 */
class GitHubPollerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SLUG = "chrisfaber95/f1-dashboard";

    /** GitHub, scripted: reads answer from the fields below, a merge is recorded (or refused). Everything else is shadow. */
    static class Hub extends CoreFixture.Fakes {
        final List<String> requests = new ArrayList<>();
        final Map<String, JsonNode> answers = new HashMap<>();
        JsonNode pr = json("{\"state\":\"open\",\"mergeable\":true,\"draft\":false,\"head\":{\"sha\":\"abc\"}}");
        JsonNode checkRuns = json("{\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"success\"}]}");
        JsonNode actionRuns; // null: the token cannot read these either
        boolean denied;
        int refuseMerge;
        int syncCode;
        String syncOut = "";
        String lastTitle;
        String lastSha;

        @Override
        public JsonNode get(String path) {
            String p = path.split("\\?")[0];
            requests.add("GET " + p);
            if (answers.containsKey(p)) {
                return answers.get(p);
            }
            if (p.matches(".*/pulls/\\d+")) {
                return pr;
            }
            if (p.endsWith("check-runs")) {
                if (denied) {
                    throw new GitHubHttpException(403, "https://api.github.com" + p, "");
                }
                return checkRuns;
            }
            if (p.endsWith("/actions/runs")) {
                if (actionRuns == null) {
                    throw new GitHubHttpException(403, "https://api.github.com" + p, "");
                }
                return actionRuns;
            }
            return p.endsWith("comments") || p.endsWith("reviews") ? json("[]") : json("{}");
        }

        @Override
        public void merge(String repoSlug, int prNumber, String sha, String commitTitle) {
            requests.add("PUT merge " + prNumber);
            if (refuseMerge != 0) {
                throw new GitHubHttpException(refuseMerge, "https://api.github.com/x", "Base branch was modified");
            }
            lastSha = sha;
            lastTitle = commitTitle;
            super.merge(repoSlug, prNumber, sha, commitTitle);
        }

        @Override
        public Result sync(long taskId) {
            super.sync(taskId);
            return new Result(syncCode, syncOut);
        }

        @Override
        public Map<String, String> list() {
            return Map.of("f1-dashboard", SLUG, "homelab", "chrisfaber95/homelab");
        }

        boolean merged() {
            return requests.stream().anyMatch(r -> r.startsWith("PUT"));
        }

        long notifications() {
            return calls().stream().filter(c -> c.startsWith("notify")).count();
        }
    }

    static JsonNode json(String s) {
        return JSON.readTree(s);
    }

    private ConnectionSource db;
    private TaskStore store;
    private Hub hub;
    private CoreFixture.MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        clock = new CoreFixture.MutableClock("2026-10-31T13:10:00Z");
        store = new TaskStore(db, clock);
        hub = new Hub();
        CoreFixture.insert(db, 41, "brief", "routed", "unattended", "1");
        CoreFixture.insert(db, 42, "f1-dashboard", "pr_open", "ended", "2026-10-31T13:07:09+00:00", "pr_number", "13",
                "pr_url", "u", "parent", "41", "brief", "H-005", "review_ok", "1", "last_comment_at",
                "2026-10-31T13:07:13+00:00");
    }

    private GitHubPoller poller(boolean schedulerOn) {
        CoreSettings s = CoreSettings.shadowDefaults().withSchedulerEnabled(schedulerOn);
        return new GitHubPoller(store, s, new GitHubPoller.GitHubSettings("chris", 2), hub, hub, hub, hub, hub, hub,
                new FollowupChain(store, hub), hub, hub, hub, hub, clock, "http://h");
    }

    private void check(long id) {
        poller(true).checkPr(store.get(id).orElseThrow(), new HashSet<>());
    }

    private Task t(long id) {
        return store.get(id).orElseThrow();
    }

    private void set(long id, Object... kv) {
        store.update(id, TaskStore.fields(kv));
    }

    // ---- auto merge (tests/test_inbox_auto_merge.py) ----

    /** Ports {@code AutoMerge.test_green_pr_is_merged}. */
    @Test
    void aGreenPrIsMerged() {
        check(42);
        assertThat(hub.merged()).isTrue();
        assertThat(t(42).status()).isEqualTo("merged");
        assertThat(hub.lastSha).isEqualTo("abc");
        assertThat(hub.lastTitle).isEqualTo("Merge PR #13: inbox 42: text 42 (build without me)");
        assertThat(hub.calls()).contains("cleanup 42", "remember 42");
    }

    /** Ports {@code AutoMerge.test_token_without_check_runs_reads_the_actions_runs}. */
    @Test
    void aTokenWithoutCheckRunsReadsTheActionsRuns() {
        hub.denied = true;
        hub.actionRuns = json("{\"workflow_runs\":[{\"status\":\"completed\",\"conclusion\":\"success\"}]}");
        check(42);
        assertThat(hub.merged()).isTrue();
        assertThat(t(42).status()).isEqualTo("merged");
    }

    /** Ports {@code AutoMerge.test_failed_actions_run_is_not_merged}. */
    @Test
    void aFailedActionsRunIsNotMerged() {
        hub.denied = true;
        hub.actionRuns = json("{\"workflow_runs\":[{\"status\":\"completed\",\"conclusion\":\"success\"},"
                + "{\"status\":\"completed\",\"conclusion\":\"failure\"}]}");
        check(42);
        assertThat(hub.merged()).isFalse();
        assertThat(t(42).num("review_ok")).isZero();
        assertThat(t(42).str("review")).contains("CI failed");
        assertThat(t(42).status()).isEqualTo("pr_open");
    }

    /** Ports {@code AutoMerge.test_unreadable_ci_is_reported_once_and_retried}. */
    @Test
    void unreadableCiIsReportedOnceAndRetried() {
        hub.denied = true;
        check(42);
        check(42);
        assertThat(hub.merged()).isFalse();
        assertThat(t(42).num("review_ok")).isEqualTo(1);
        assertThat(t(42).str("review")).contains("403");
        assertThat(hub.notifications()).isEqualTo(1);
        hub.denied = false; // the token got the permission
        check(42);
        assertThat(hub.merged()).isTrue();
        assertThat(t(42).status()).isEqualTo("merged");
    }

    // ---- follow-ups of a task whose PR was merged (tests/test_inbox_followups.py) ----

    private void followupFixture() throws Exception {
        CoreFixture.clear(db);
        CoreFixture.insert(db, 70, "brief", "routed", "ended", "2026-10-31T19:00:00+00:00");
        CoreFixture.insert(db, 74, "homelab", "refused", "ended", "2026-10-31T19:00:00+00:00", "parent", "70",
                "pr_number", "68", "pr_url", "u");
        for (long[] p : new long[][] {{75, 74}, {76, 75}}) {
            CoreFixture.insert(db, p[0], "homelab", "cancelled", "ended", "2026-10-31T19:00:00+00:00", "parent", "70",
                    "after_task", String.valueOf(p[1]), "note", "niet gestart: de voorgaande taak #74 was geweigerd");
        }
        hub.pr = json("{\"state\":\"closed\",\"merged\":true}");
    }

    private List<String> statuses(long... ids) {
        List<String> out = new ArrayList<>();
        for (long i : ids) {
            out.add(t(i).status());
        }
        return out;
    }

    /** Ports {@code Followups.test_merged_pr_requeues_followups_in_a_chain}. */
    @Test
    void aMergedPrRequeuesFollowupsInAChain() throws Exception {
        followupFixture();
        poller(true).checkBlockingPr(t(74));
        assertThat(statuses(74, 75, 76)).containsExactly("merged", "queued", "queued");
        assertThat(t(70).has("ended")).isFalse();
        assertThat(hub.calls()).contains("notify Inbox 74: PR #68 merged");
    }

    /** Ports {@code Followups.test_open_or_closed_pr_changes_nothing}. */
    @Test
    void anOpenOrClosedPrChangesNothing() throws Exception {
        followupFixture();
        for (String pr : List.of("{\"state\":\"open\"}", "{\"state\":\"closed\",\"merged\":false}")) {
            hub.pr = json(pr);
            poller(true).checkBlockingPr(t(74));
            assertThat(statuses(74, 75)).containsExactly("refused", "cancelled");
        }
    }

    /** Ports {@code Followups.test_merge_does_not_restart_a_followup_from_before_this_feature}. */
    @Test
    void aMergeDoesNotRestartAFollowupFromBeforeThisFeature() throws Exception {
        followupFixture();
        set(75, "no_auto_revive", 1);
        assertThat(new FollowupChain(store, hub).blocked(t(74), true)).isEmpty();
        poller(true).checkBlockingPr(t(74));
        assertThat(statuses(74, 75)).containsExactly("merged", "cancelled");
    }

    /** No Python test: the poll loop looks at tasks of {@code MERGE_WATCHED} with blocked follow-ups, and only those. */
    @Test
    void theLoopWatchesTheBlockingPrOnlyWhileFollowupsWait() throws Exception {
        followupFixture();
        poller(true).poll();
        assertThat(statuses(74, 75, 76)).containsExactly("merged", "queued", "queued");
        hub.requests.clear();
        poller(true).poll(); // 74 is merged now and nothing is blocked: no GitHub call at all
        assertThat(hub.requests).isEmpty();
    }

    // ---- the rules for when a PR may and may not be merged (written from github.py and helpers.merges_itself) ----

    /** No Python test. */
    @Test
    void aDraftIsNotMerged() {
        hub.pr = json("{\"state\":\"open\",\"mergeable\":true,\"draft\":true,\"head\":{\"sha\":\"abc\"}}");
        check(42);
        assertThat(hub.merged()).isFalse();
        assertThat(t(42).str("review")).contains("Not merged: the PR is a draft");
        assertThat(t(42).num("review_ok")).isZero();
        assertThat(hub.notifications()).isEqualTo(1);
    }

    /** No Python test: no CI at all merges when every criterion was confirmed by the review (1), not when one needs CI (2). */
    @Test
    void aRepoWithoutCiIsMergedUnlessACriterionNeedsCi() {
        hub.checkRuns = json("{\"check_runs\":[]}");
        set(42, "review_ok", 2);
        check(42);
        assertThat(hub.merged()).isFalse();
        assertThat(t(42).str("review")).contains("a criterion can only be confirmed by CI (🧪) and this repo has none");
        set(42, "review_ok", 1);
        check(42);
        assertThat(hub.merged()).isTrue();
    }

    /** No Python test: pending CI waits (no merge, no note); after 30 minutes without a result it counts as failed. */
    @Test
    void pendingCiWaitsAndFailsAfterThirtyMinutes() {
        hub.checkRuns = json("{\"check_runs\":[{\"status\":\"in_progress\"}]}");
        check(42);
        assertThat(hub.merged()).isFalse();
        assertThat(t(42).has("review")).isFalse();
        assertThat(hub.notifications()).isZero();
        clock.advance(java.time.Duration.ofMinutes(21)); // ended 13:07:09 + 30 min = 13:37:09; now 13:31
        check(42);
        assertThat(t(42).has("review")).isFalse();
        clock.advance(java.time.Duration.ofMinutes(7));
        check(42);
        assertThat(hub.merged()).isFalse();
        assertThat(t(42).str("review")).contains("CI failed or didn't finish in 30 minutes");
    }

    /** No Python test: a repo with workflows but no run for this commit yet counts as pending, not as "no CI". */
    @Test
    void aRepoWithWorkflowsButNoRunYetIsPending() {
        hub.checkRuns = json("{\"check_runs\":[]}");
        hub.answers.put("/repos/" + SLUG + "/actions/workflows", json("{\"total_count\":2}"));
        check(42);
        assertThat(hub.merged()).isFalse();
        assertThat(t(42).has("review")).isFalse();
    }

    /** No Python test: a failing commit status fails the PR, a pending one waits, a green one is enough without runs. */
    @Test
    void theCommitStatusCountsToo() {
        hub.checkRuns = json("{\"check_runs\":[]}");
        hub.answers.put("/repos/" + SLUG + "/commits/abc/status", json("{\"total_count\":1,\"state\":\"failure\"}"));
        check(42);
        assertThat(hub.merged()).isFalse();
        assertThat(t(42).str("review")).contains("CI failed");
        set(42, "review_ok", 1, "review", null);
        hub.answers.put("/repos/" + SLUG + "/commits/abc/status", json("{\"total_count\":1,\"state\":\"success\"}"));
        check(42);
        assertThat(hub.merged()).isTrue();
    }

    /** No Python test: neutral and skipped runs are fine; any other conclusion is not. */
    @Test
    void neutralAndSkippedRunsPassAndCancelledDoesNot() {
        hub.checkRuns = json("{\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"neutral\"},"
                + "{\"status\":\"completed\",\"conclusion\":\"skipped\"}]}");
        assertThat(poller(true).ciState(SLUG, "abc", "2026-10-31T13:07:09+00:00")).isEqualTo("ok");
        hub.checkRuns = json("{\"check_runs\":[{\"status\":\"completed\",\"conclusion\":\"cancelled\"}]}");
        assertThat(poller(true).ciState(SLUG, "abc", "2026-10-31T13:07:09+00:00")).isEqualTo("failed");
        assertThat(poller(true).ciState(SLUG, null, "2026-10-31T13:07:09+00:00")).isEqualTo("none");
    }

    /** No Python test: GitHub still computing whether the PR merges (mergeable null) is waited out. */
    @Test
    void aPrWhoseMergeabilityIsUnknownWaits() {
        hub.pr = json("{\"state\":\"open\",\"mergeable\":null,\"draft\":false,\"head\":{\"sha\":\"abc\"}}");
        check(42);
        assertThat(hub.merged()).isFalse();
        assertThat(hub.requests).noneMatch(r -> r.contains("check-runs"));
    }

    /** No Python test: {@code helpers.merges_itself}, every way it says no. */
    @Test
    void onlyABriefTaskOfABuildWithoutMeOnAProductRepoWithAPassedReviewMergesItself() throws Exception {
        GitHubPoller p = poller(true);
        assertThat(p.mergesItself(t(42))).isTrue();
        set(42, "review_ok", 2);
        assertThat(p.mergesItself(t(42))).isTrue();
        set(42, "review_ok", 0);
        assertThat(p.mergesItself(t(42))).as("review failed").isFalse();
        set(42, "review_ok", null);
        assertThat(p.mergesItself(t(42))).as("not reviewed").isFalse();
        set(42, "review_ok", 1, "brief", null);
        assertThat(p.mergesItself(t(42))).as("not a brief task").isFalse();
        set(42, "brief", "H-005", "repo", "homelab");
        assertThat(p.mergesItself(t(42))).as("homelab: always the user").isFalse();
        set(42, "repo", "unknown-repo");
        assertThat(p.mergesItself(t(42))).as("not in repos.conf").isFalse();
        set(42, "repo", "f1-dashboard");
        set(41, "unattended", 0);
        assertThat(p.mergesItself(t(42))).as("the build was not 'without me'").isFalse();
        set(41, "unattended", 1);
        set(42, "parent", null);
        assertThat(p.mergesItself(t(42))).as("no parent").isFalse();
    }

    /** No Python test: a PR that must not merge itself is never merged, whatever its CI says. */
    @Test
    void aPrThatDoesNotMergeItselfIsLeftAlone() {
        set(42, "review_ok", 0);
        check(42);
        assertThat(hub.merged()).isFalse();
        assertThat(hub.requests).noneMatch(r -> r.contains("check-runs"));
        assertThat(t(42).status()).isEqualTo("pr_open");
    }

    /** No Python test: one merge per repo per cycle, so GitHub rechecks the other PR against the new main. */
    @Test
    void oneMergePerRepoPerCycle() throws Exception {
        CoreFixture.insert(db, 43, "f1-dashboard", "pr_open", "ended", "2026-10-31T13:07:09+00:00", "pr_number", "14",
                "pr_url", "u", "parent", "41", "brief", "H-005", "review_ok", "1");
        poller(true).poll();
        assertThat(statuses(42, 43)).containsExactlyInAnyOrder("merged", "pr_open");
        poller(true).poll(); // the next cycle merges the other
        assertThat(statuses(42, 43)).containsExactly("merged", "merged");
    }

    /** No Python test: GitHub refusing the merge is reported with its answer, and the task waits for the user. */
    @Test
    void aRefusedMergeIsReported() {
        hub.refuseMerge = 405;
        check(42);
        assertThat(t(42).status()).isEqualTo("pr_open");
        assertThat(t(42).num("review_ok")).isZero();
        assertThat(t(42).str("review")).contains("Not merged: GitHub refused the merge (405: Base branch was modified)");
        assertThat(hub.calls()).contains("notify Inbox 42: PR #13 waits for you");
    }

    // ---- PR state: closed, comments, conflicts (written from github.py) ----

    /** No Python test. */
    @Test
    void aPrClosedOnGitHubEndsTheTask() {
        hub.pr = json("{\"state\":\"closed\",\"merged\":true}");
        check(42);
        assertThat(t(42).status()).isEqualTo("merged");
        assertThat(t(42).has("ended")).isTrue();
        assertThat(hub.calls()).contains("cleanup 42", "remember 42");
        set(42, "status", "pr_open");
        hub.pr = json("{\"state\":\"closed\",\"merged\":false}");
        check(42);
        assertThat(t(42).status()).isEqualTo("closed");
        assertThat(hub.merged()).isFalse();
    }

    /** No Python test: a comment of the owner after the last one sends the task back to its session. */
    @Test
    void aCommentOfTheOwnerSendsTheTaskBack() {
        hub.answers.put("/repos/" + SLUG + "/issues/13/comments", json("[{\"user\":{\"login\":\"chris\"},"
                + "\"created_at\":\"2026-10-31T14:00:00Z\",\"body\":\"  please rename it \",\"html_url\":\"http://c1\"}]"));
        hub.answers.put("/repos/" + SLUG + "/pulls/13/comments", json("[{\"user\":{\"login\":\"chris\"},"
                + "\"created_at\":\"2026-10-31T14:05:00Z\",\"body\":\"why?\",\"path\":\"a.ts\",\"line\":7,"
                + "\"html_url\":\"http://c2\"}]"));
        check(42);
        Task t = t(42);
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.str("pending_input")).startsWith("Review-commentaar op de PR:\n\n- please rename it\n\n- why? (bij a.ts:7)")
                .contains("eindig weer met KLAAR:").contains(TaskActions.LEARN_HINT);
        assertThat(t.str("last_comment_at")).isEqualTo("2026-10-31T14:05:00+00:00");
        assertThat(t.has("ended")).isFalse();
        assertThat(t.has("review_ok")).isFalse();
        assertThat(t.str("mem_link")).isEqualTo("http://c1");
        assertThat(t.str("mem_source")).isEqualTo("jouw PR-commentaar op PR #13 (inbox 42)");
        assertThat(hub.merged()).isFalse();
    }

    /** No Python test: other accounts, old comments, empty bodies and the inbox's own comments are ignored. */
    @Test
    void commentsOfOthersOldOnesAndTheInboxItselfAreIgnored() {
        hub.answers.put("/repos/" + SLUG + "/issues/13/comments", json("["
                + "{\"user\":{\"login\":\"someone\"},\"created_at\":\"2026-10-31T14:00:00Z\",\"body\":\"hi\"},"
                + "{\"user\":{\"login\":\"chris\"},\"created_at\":\"2026-10-31T13:00:00Z\",\"body\":\"old\"},"
                + "{\"user\":{\"login\":\"chris\"},\"created_at\":\"2026-10-31T14:00:00Z\",\"body\":\"  \"},"
                + "{\"user\":{\"login\":\"chris\"},\"created_at\":\"2026-10-31T14:00:00Z\",\"body\":\"x <!-- homelab-inbox -->\"}]"));
        check(42);
        assertThat(t(42).status()).isEqualTo("merged"); // nothing to process: the green PR goes on to merge
    }

    /** No Python test: a review (submitted_at) counts like a comment. */
    @Test
    void aSubmittedReviewCounts() {
        hub.answers.put("/repos/" + SLUG + "/pulls/13/reviews", json("[{\"user\":{\"login\":\"chris\"},"
                + "\"submitted_at\":\"2026-10-31T14:00:00Z\",\"body\":\"changes\"}]"));
        check(42);
        assertThat(t(42).status()).isEqualTo("queued");
    }

    private void conflictingPr() {
        hub.pr = json("{\"state\":\"open\",\"mergeable\":false,\"head\":{\"sha\":\"abc\"}}");
    }

    /** No Python test: main merges into the branch cleanly: pushed, and the CI wait starts again. */
    @Test
    void aCleanConflictIsMergedAndPushed() {
        conflictingPr();
        check(42);
        assertThat(hub.calls()).contains("refresh f1-dashboard", "sync 42", "push 42");
        assertThat(t(42).status()).isEqualTo("pr_open");
        assertThat(t(42).str("ended")).isEqualTo("2026-10-31T13:10:00+00:00");
        assertThat(t(42).str("conflict_sha")).isEqualTo("abc");
        assertThat(hub.merged()).isFalse();
    }

    /** No Python test: a head that was handled is not handled again. */
    @Test
    void theSameHeadIsHandledOnce() {
        conflictingPr();
        check(42);
        check(42);
        assertThat(hub.calls().stream().filter(c -> c.equals("sync 42"))).hasSize(1);
    }

    /** No Python test: conflicts go back to the task's own session with the files, and count as a round. */
    @Test
    void realConflictsGoBackToTheSessionWithTheFiles() {
        conflictingPr();
        hub.syncCode = 4;
        hub.syncOut = "package.json\nsrc/main.ts\n";
        check(42);
        Task t = t(42);
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.num("conflicts")).isEqualTo(1);
        assertThat(t.str("pending_input")).contains("PR #13 conflicts with main").contains("- package.json\n- src/main.ts")
                .contains("end with KLAAR: again");
        assertThat(t.has("ended")).isFalse();
        assertThat(t.has("review_ok")).isFalse();
        assertThat(hub.calls()).doesNotContain("push 42");
    }

    /** No Python test: after {@code CONFLICT_ROUNDS} rounds the user is told and the task stays as it is. */
    @Test
    void afterTheConflictRoundsTheUserIsToldAndNothingElseHappens() {
        conflictingPr();
        hub.syncCode = 4;
        set(42, "conflicts", 2);
        check(42);
        assertThat(t(42).status()).isEqualTo("pr_open");
        assertThat(hub.calls()).doesNotContain("sync 42").contains("notify Inbox 42: PR #13 conflicts with main");
    }

    /** No Python test: a failing sync is reported to the user, not retried for the same head. */
    @Test
    void aFailingSyncIsReported() {
        conflictingPr();
        hub.syncCode = 1;
        hub.syncOut = "boom";
        check(42);
        assertThat(t(42).status()).isEqualTo("pr_open");
        assertThat(hub.calls()).contains("notify Inbox 42: PR #13 conflicts with main");
    }

    // ---- shadow mode: the acceptance criteria ----

    /** The scheduler is off: no GitHub read, no merge, no notification, no change to the data, whatever GitHub says. */
    @Test
    void withTheSchedulerOffNoMergeCallIsMadeNoGitHubIsAskedAndNothingChanges() {
        Task before = t(42);
        poller(false).poll();
        assertThat(hub.requests).isEmpty();
        assertThat(hub.calls()).isEmpty();
        assertThat(t(42)).isEqualTo(before);
        assertThat(t(42).status()).isEqualTo("pr_open");
    }

    /** On the shadow effects themselves (scheduler on, nothing real): a merge is only recorded, GitHub is never asked. */
    @Test
    void theShadowEffectsNeverReachGitHub() {
        ShadowEffects shadow = new ShadowEffects();
        assertThat(shadow.get("/repos/o/r/pulls/1").isMissingNode()).isTrue();
        shadow.merge("o/r", 1, "abc", "t");
        assertThat(shadow.calls()).containsExactly("github GET /repos/o/r/pulls/1", "merge o/r#1");
        GitHubPoller p = new GitHubPoller(store, CoreSettings.shadowDefaults().withSchedulerEnabled(true),
                new GitHubPoller.GitHubSettings("chris", 2), shadow, shadow, shadow, shadow, shadow, shadow, shadow,
                hub, shadow, shadow, shadow, clock, "http://h");
        p.poll(); // the shadow repo list is the Hub's; every answer of GitHub is "nothing": nothing is merged
        assertThat(shadow.calls()).noneMatch(c -> c.startsWith("merge o/r#13") || c.startsWith("notify"));
        assertThat(t(42).status()).isEqualTo("pr_open");
        assertThat(t(42).str("review")).isNull();
    }
}
