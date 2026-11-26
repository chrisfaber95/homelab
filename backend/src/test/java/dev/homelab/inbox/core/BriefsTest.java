package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homelab.inbox.core.BriefParsing.Brief;
import dev.homelab.inbox.core.Effects.ClaudeAnswer;
import dev.homelab.inbox.core.Effects.ProductFile;
import dev.homelab.inbox.core.Effects.SetupPr;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Briefs in Java (H-026 K4): the build bookkeeping, the planner, the PR review and the handoff, ported from the Python
 * tests. Every test names the Python test it covers; "new" marks behaviour of {@code briefs.py} that no Python test covers.
 * The effects are fakes: no Claude run, no GitHub call, no commit and no notification leaves the test.
 */
class BriefsTest {

    static final String BID = "H-014";
    static final String PATH = "pixel-quest/briefs/" + BID + ".md";
    static final String ROADMAP = "pixel-quest/ROADMAP.md";
    static final String BASE = "http://homelab:8105";

    static String briefFile(String status, String extra) {
        return "---\nid: " + BID + "\ntitle: First playable\nstatus: " + status + "   # draft | approved\nrepos: [homelab, other]\n"
                + extra + "---\nBody of the brief.";
    }

    /** Scripted effects: the product repo, GitHub, Claude and the notifications are all recorded, nothing is real. */
    static class Script extends CoreFixture.Fakes {
        final Map<String, String> product = new LinkedHashMap<>();
        RuntimeException productFails;
        final List<String> commits = new ArrayList<>();
        final Map<String, Map<String, String>> branches = new LinkedHashMap<>();
        List<SetupPr> setup = List.of();
        final List<String> merged = new ArrayList<>();
        final List<String> comments = new ArrayList<>();
        RuntimeException handoffMergeFails;
        ClaudeAnswer planAnswer = new ClaudeAnswer(true, "", "");
        ClaudeAnswer reviewAnswer = new ClaudeAnswer(true, "", "");
        final List<String> systems = new ArrayList<>();
        final List<String> prompts = new ArrayList<>();
        List<String> changed = List.of();
        final List<List<String>> diffGroups = new ArrayList<>();
        String diffText = "DIFF";
        final List<String> notifications = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        final List<String> newRepos = new ArrayList<>();
        final List<String> logs = new ArrayList<>();

        @Override
        public List<ProductFile> briefFiles() {
            super.briefFiles();
            if (productFails != null) {
                throw productFails;
            }
            return product.entrySet().stream().filter(e -> e.getKey().contains("/briefs/"))
                    .map(e -> new ProductFile(e.getKey(), e.getValue())).toList();
        }

        @Override
        public String read(String path) {
            return product.get(path);
        }

        @Override
        public void commitToMain(String path, String text, String message) {
            super.commitToMain(path, text, message);
            product.put(path, text);
            commits.add(message);
        }

        @Override
        public void pushBranch(String branch, Map<String, String> files, String message) {
            super.pushBranch(branch, files, message);
            branches.put(branch, files);
            commits.add(message);
        }

        @Override
        public List<SetupPr> setupPrs(String slug, String briefId) {
            super.setupPrs(slug, briefId);
            return setup.stream().filter(p -> p.headRepo().equals(slug) || p.headRepo().startsWith("evil")).toList();
        }

        @Override
        public void mergeSetupPr(String slug, int number, String title) {
            super.mergeSetupPr(slug, number, title);
            merged.add(slug + "#" + number);
        }

        @Override
        public void comment(String slug, int prNumber, String body) {
            super.comment(slug, prNumber, body);
            comments.add(body);
        }

        @Override
        public void mergeHandoff(String slug, int number, String title) {
            super.mergeHandoff(slug, number, title);
            if (handoffMergeFails != null) {
                throw handoffMergeFails;
            }
            merged.add("handoff " + slug + "#" + number);
        }

        @Override
        public ClaudeAnswer plan(long taskId, String system, String prompt) {
            super.plan(taskId, system, prompt);
            systems.add(system);
            prompts.add(prompt);
            return planAnswer;
        }

        @Override
        public ClaudeAnswer review(long taskId, String system, String prompt) {
            super.review(taskId, system, prompt);
            prompts.add(prompt);
            return reviewAnswer;
        }

        @Override
        public String profiles(Map<String, String> repos, boolean ci) {
            return repos.keySet().stream().map(r -> "- " + r + " [CI: none yet]").reduce("", (a, b) -> a + b);
        }

        @Override
        public void add(String name, String slug) {
            super.add(name, slug);
            newRepos.add(name + "=" + slug);
        }

        @Override
        public List<String> changedFiles(String cache, long taskId) {
            return changed;
        }

        @Override
        public String diff(String cache, long taskId, List<String> paths) {
            diffGroups.add(paths);
            return diffText;
        }

        @Override
        public Opened open(String slug, String title, String head, String base, String body) {
            super.open(slug, title, head, base, body);
            return new Opened(9, "https://github.com/" + slug + "/pull/9");
        }

        @Override
        public Map<String, String> list() {
            return Map.of("homelab", "chrisfaber95/homelab", "other", "chrisfaber95/other");
        }

        @Override
        public void notify(String title, String body, String url, boolean store) {
            super.notify(title, body, url, store);
            notifications.add(title + "|" + body + "|" + url + "|" + store);
        }

        @Override
        public void note(String author, String client, String kind, String text, String link, String ref) {
            super.note(author, client, kind, text, link, ref);
            notes.add(kind + "|" + text + "|" + ref);
        }

        @Override
        public void line(String key, String kind, String text) {
            super.line(key, kind, text);
            logs.add(key + " " + text);
        }
    }

    private ConnectionSource db;
    private TaskStore store;
    private Script fx;
    private Briefs briefs;
    private BriefRounds rounds;
    private Cancellations cancellations;
    private CoreFixture.MutableClock clock;

    @BeforeEach
    void setUp() throws Exception {
        db = CoreFixture.db();
        CoreFixture.clear(db);
        clock = new CoreFixture.MutableClock("2026-11-18T10:00:00Z");
        store = new TaskStore(db, clock);
        fx = new Script();
        fx.product.put(PATH, briefFile("approved", ""));
        briefs = new Briefs(store, fx, fx, fx, fx, fx, Briefs.Settings.defaults(), clock);
        cancellations = new Cancellations();
        rounds = new BriefRounds(store, briefs, fx, fx, fx, fx, fx, fx, fx, fx, fx, cancellations, BASE);
    }

    private Task task(long id) {
        return store.get(id).orElseThrow();
    }

    private long build(String status, String ended, String... more) throws Exception {
        long id = store.newBuild(BID, "Build", false, null);
        store.update(id, TaskStore.fields("status", status, "ended", ended));
        return id;
    }

    private long child(long parent, String status, String ended) throws Exception {
        long id = store.newChild("homelab", "task", parent, null, "planned", BID, "[\"c\"]");
        store.update(id, TaskStore.fields("status", status, "ended", ended));
        return id;
    }

    private static final String T = "2026-11-06T21:00:00+00:00";

    // ---- tests/test_inbox_builds.py, class Builds

    /** test_a_cancelled_build_does_not_block_a_new_one */
    @Test
    void aCancelledBuildDoesNotBlockANewOne() throws Exception {
        build("cancelled", T);
        assertThat(briefs.liveBuild(BID)).isEmpty();
        assertThat(briefs.startBuild(BID, false, "me", null)).isPositive();
    }

    /** test_a_planned_build_whose_tasks_were_all_cancelled_can_be_started_again */
    @Test
    void aPlannedBuildWhoseTasksWereAllCancelledCanBeStartedAgain() throws Exception {
        long parent = build("routed", T);
        child(parent, "cancelled", T);
        child(parent, "cancelled", T);
        assertThat(briefs.buildLeftNothing(task(parent))).isTrue();
        assertThat(briefs.liveBuild(BID)).isEmpty();
        long fresh = briefs.startBuild(BID, false, "me", null);
        assertThat(task(fresh).status()).isEqualTo("queued");
        assertThat(task(fresh).repo()).isEqualTo("brief");
    }

    /** test_a_build_with_a_merged_task_stays_live */
    @Test
    void aBuildWithAMergedTaskStaysLive() throws Exception {
        long parent = build("routed", T);
        child(parent, "merged", T);
        child(parent, "cancelled", T);
        assertThat(briefs.buildLeftNothing(task(parent))).isFalse();
        assertThat(briefs.liveBuild(BID).orElseThrow().id()).isEqualTo(parent);
        assertThatThrownBy(() -> briefs.startBuild(BID, false, "me", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(BID + " is already being built");
    }

    /** test_a_build_that_is_still_running_stays_live */
    @Test
    void aBuildThatIsStillRunningStaysLive() throws Exception {
        long parent = build("routed", null);
        child(parent, "queued", null);
        assertThat(briefs.buildLeftNothing(task(parent))).isFalse();
        assertThat(briefs.liveBuild(BID).orElseThrow().id()).isEqualTo(parent);
        assertThatThrownBy(() -> briefs.startBuild(BID, false, "me", null)).isInstanceOf(IllegalArgumentException.class);
    }

    /** test_a_build_without_tasks_yet_stays_live */
    @Test
    void aBuildWithoutTasksYetStaysLive() throws Exception {
        long parent = build("queued", null);
        assertThat(briefs.buildLeftNothing(task(parent))).isFalse();
        assertThat(briefs.liveBuild(BID).orElseThrow().id()).isEqualTo(parent);
    }

    /** test_a_cancelled_build_does_not_hide_an_earlier_one_with_merged_work */
    @Test
    void aCancelledBuildDoesNotHideAnEarlierOneWithMergedWork() throws Exception {
        long old = build("routed", T);
        child(old, "merged", T);
        long fresh = build("cancelled", T);
        assertThat(briefs.liveBuild(BID).orElseThrow().id()).isEqualTo(old);
        assertThat(store.builds(BID).stream().map(Task::id)).containsExactly(fresh, old);
    }

    /** new: every status the page treats as "ended badly" counts, a build with a merged-and-failed mix does not (ACCEPTABLE) */
    @Test
    void everyBadEndingCountsAsNothingLeft() throws Exception {
        long parent = build("routed", T);
        for (String s : List.of("failed", "capped", "refused", "closed")) {
            child(parent, s, T);
        }
        assertThat(briefs.buildLeftNothing(task(parent))).isTrue();
        child(parent, "pr_open", null);
        assertThat(briefs.buildLeftNothing(task(parent))).isFalse();
    }

    // ---- tests/test_inbox_builds.py, class TaskLimit

    private static Brief limited(Object maxTasks) {
        return new Brief(BID, "t", "approved", "", List.of(), Map.of(), maxTasks, "p", "p/briefs/x.md", "");
    }

    /** test_the_setting_applies_when_the_brief_says_nothing */
    @Test
    void theSettingAppliesWhenTheBriefSaysNothing() {
        for (Object own : new Object[] {null, "many", -1, true}) {
            assertThat(BriefParsing.taskLimit(limited(own), 6)).isEqualTo(6);
        }
    }

    /** test_the_brief_sets_its_own_limit (also through the front matter of a file) */
    @Test
    void theBriefSetsItsOwnLimit() {
        assertThat(BriefParsing.taskLimit(limited(20), 6)).isEqualTo(20);
        assertThat(BriefParsing.taskLimit(limited(0), 6)).isZero();
        Brief parsed = BriefParsing.parseBrief(PATH, briefFile("approved", "max_tasks: 0\n"));
        assertThat(BriefParsing.taskLimit(parsed, 6)).isZero();
    }

    private static String planOf(int n) {
        StringBuilder tasks = new StringBuilder();
        for (int i = 0; i < n; i++) {
            tasks.append(i == 0 ? "" : ",").append("{\"id\":\"").append(i)
                    .append("\",\"repo\":\"homelab\",\"text\":\"do it\",\"criteria\":[\"done\"]}");
        }
        return "```plan\n{\"tasks\":[" + tasks + "]}\n```";
    }

    /** test_a_plan_over_the_limit_is_refused */
    @Test
    void aPlanOverTheLimitIsRefused() {
        assertThatThrownBy(() -> BriefParsing.parsePlan(planOf(7), List.of("homelab"), 6))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("the planner gave 7 tasks, at most 6");
    }

    /** test_limit_zero_takes_any_number_of_tasks */
    @Test
    void limitZeroTakesAnyNumberOfTasks() {
        BriefParsing.Plan plan = BriefParsing.parsePlan(planOf(25), List.of("homelab"), 0);
        assertThat(plan.isQuestion()).isFalse();
        assertThat(plan.tasks()).hasSize(25);
    }

    // ---- tests/test_inbox_builds.py, class ClaudeResult
    // The two tests of helpers.claude_result (a warning line after the JSON, no JSON) stay Python: Java never parses
    // Claude's JSON (Q7), the runner does (runner/review.py, tests/test_runner_review.py; RunnerServiceTest on the Java
    // side). Here the planner and reviewer only get the runner's result as ClaudeAnswer: see the Claude failure tests below.

    // ---- tests/test_inbox_briefs_api.py (the data of the page; the routes stay in Python)

    private BriefPages pages() {
        return new BriefPages(briefs, store, "UTC", s -> "label-" + s);
    }

    private void listing() throws Exception {
        fx.product.clear();
        fx.product.put("homelab/briefs/H-001.md", "---\nid: H-001\ntitle: Title H-001\nstatus: draft\npriority: should\nrepos: [homelab]\n---\nBody of the brief.");
        fx.product.put("homelab/briefs/H-002.md", "---\nid: H-002\ntitle: Title H-002\nstatus: approved\nrepos: [homelab]\n---\nb");
        fx.product.put("homelab/briefs/H-003.md", "---\nid: H-003\ntitle: Title H-003\nstatus: approved\nrepos: [homelab]\n---\nb");
        fx.product.put("homelab/briefs/H-004.md", "---\nid: H-004\ntitle: Title H-004\nstatus: needs-design\nrepos: [homelab]\n---\nb");
        fx.product.put("homelab/briefs/notes.md", "---\nid: nope\n---\nnot a brief");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String key) {
        return (List<Map<String, Object>>) m.get(key);
    }

    /** test_list */
    @Test
    @SuppressWarnings("unchecked")
    void theListGroupsBriefsByStatusWithTheirActions() throws Exception {
        listing();
        long b = store.newBuild("H-003", "Build H-003\nmore", false, "web");
        store.update(b, TaskStore.fields("status", "routed"));
        Map<String, Object> d = pages().briefsData();
        assertThat(list(d, "drafts")).extracting(m -> m.get("id")).containsExactly("H-001");
        assertThat(list(d, "approved")).extracting(m -> m.get("id")).containsExactly("H-002", "H-003");
        assertThat(list(d, "needs_design")).extracting(m -> m.get("id")).containsExactly("H-004");
        assertThat(list(d, "drafts").get(0).get("actions")).isEqualTo(List.of("build", "build-without-me", "park"));
        assertThat(list(d, "approved").get(0).get("actions")).isEqualTo(List.of("build", "build-without-me"));
        assertThat(list(d, "approved").get(1).get("actions")).isEqualTo(List.of());
        assertThat(list(d, "approved").get(1).get("build")).isEqualTo(Map.of("id", b, "url", "/task/" + b));
        assertThat(list(d, "approved").get(0).get("build")).isNull();
        assertThat(list(d, "drafts").get(0)).doesNotContainKey("text");
        Map<String, Object> row = list(d, "builds").get(0);
        assertThat(row).containsEntry("id", b).containsEntry("label", "Inbox " + b).containsEntry("status", "routed")
                .containsEntry("status_label", "label-routed").containsEntry("what", "Build H-003");
        assertThat(list(d, "counts")).containsExactly(Map.of("status", "approved", "count", 2),
                Map.of("status", "draft", "count", 1), Map.of("status", "needs-design", "count", 1));
        assertThat(d.get("without_me")).isEqualTo(BriefPages.WITHOUT_ME);
        assertThat(d.get("error")).isNull();
        assertThat(d.get("refresh")).isEqualTo(false);
    }

    /** new: the page refreshes while a build is queued or running (overview.briefs_data "refresh") */
    @Test
    void thePageRefreshesWhileABuildIsActive() throws Exception {
        listing();
        store.newBuild("H-003", "Build H-003", false, null);
        assertThat(pages().briefsData().get("refresh")).isEqualTo(true);
    }

    /** test_unreadable_product_repo */
    @Test
    void anUnreadableProductRepoGivesAnErrorAndEmptyLists() {
        fx.productFails = new IllegalStateException("no clone");
        Map<String, Object> d = pages().briefsData();
        assertThat((String) d.get("error")).contains("no clone");
        assertThat(list(d, "drafts")).isEmpty();
        assertThat(list(d, "approved")).isEmpty();
        assertThat(list(d, "needs_design")).isEmpty();
        assertThat(list(d, "counts")).isEmpty();
    }

    /** test_one_brief and test_times_come_with_their_zone */
    @Test
    @SuppressWarnings("unchecked")
    void oneBriefComesWithItsBodyPathAndZone() throws Exception {
        listing();
        Brief b = briefs.byId("H-001", Duration.ZERO).orElseThrow();
        Map<String, Object> d = pages().briefData(b);
        Map<String, Object> view = (Map<String, Object>) d.get("brief");
        assertThat(view.get("text")).isEqualTo("Body of the brief.");
        assertThat(view.get("path")).isEqualTo("homelab/briefs/H-001.md");
        assertThat(d.get("error")).isNull();
        assertThat(d.get("timezone")).isEqualTo("UTC");
        assertThat(pages().briefsData().get("timezone")).isEqualTo("UTC");
    }

    /** test_unknown_brief (the 404 is the route's; here the lookup finds nothing) */
    @Test
    void anUnknownBriefIsNotFound() throws Exception {
        listing();
        assertThat(briefs.byId("H-999", Duration.ZERO)).isEmpty();
    }

    /** test_a_failed_build_is_400_with_the_reason (the reason is the exception text the route turns into a 400) */
    @Test
    void aBuildThatCannotStartSaysWhy() throws Exception {
        long live = build("queued", null);
        assertThat(live).isPositive();
        assertThatThrownBy(() -> briefs.startBuild(BID, true, "me", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(BID + " is already being built");
        fx.product.put(PATH, briefFile("parked", ""));
        CoreFixture.clearBuilds(db);
        assertThatThrownBy(() -> briefs.startBuild(BID, false, "me", null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(BID + " is not an approved brief on main of the product repo");
    }

    /** test_park_redirects_to_the_briefs_page (the redirect is the route's; here: parking commits the new status) */
    @Test
    void parkingADraftCommitsTheNewStatusAndKeepsTheComment() {
        fx.product.put(PATH, briefFile("draft", ""));
        briefs.setStatus(BID, "parked", "parked on the homelab page by me");
        assertThat(fx.product.get(PATH)).contains("status: parked   # draft | approved");
        assertThat(fx.commits).containsExactly(BID + ": parked (parked on the homelab page by me)");
        assertThat(briefs.byId(BID, Duration.ofSeconds(60)).orElseThrow().status()).isEqualTo("parked");
    }

    /** new: only a draft can be parked or approved from the page */
    @Test
    void onlyADraftCanChangeStatus() {
        assertThatThrownBy(() -> briefs.setStatus(BID, "parked", "x")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage(BID + " is not a draft on main of the product repo");
        assertThat(fx.commits).isEmpty();
    }

    // ---- start_build (new: no Python test)

    /** new: start_build approves a draft first, with who and how in the commit, and marks "build without me" */
    @Test
    void startingADraftApprovesItFirst() {
        fx.product.put(PATH, briefFile("draft", ""));
        long id = briefs.startBuild(BID, true, "chris", null);
        assertThat(fx.commits).containsExactly(BID + ": approved (approved on the homelab page by chris, build without me)");
        assertThat(task(id).num("unattended")).isEqualTo(1);
        assertThat(task(id).str("text")).isEqualTo("Build " + BID + ": First playable");
        fx.product.put(PATH, briefFile("draft", ""));
        briefs.forget();
        store.update(id, TaskStore.fields("status", "cancelled"));
        briefs.startBuild(BID, false, "chris", "discord");
        assertThat(fx.commits.get(1)).isEqualTo(BID + ": approved (approved from discord by chris)");
    }

    /** new: a brief for a new project adds its repo to the inbox's list, once, only under the owner's account */
    @Test
    void aNewProjectAddsItsRepo() {
        fx.product.put(PATH, briefFile("approved", "build: {new_repo: chrisfaber95/pixel-quest}\n").replace("[homelab, other]", "[pixel-quest]"));
        briefs.startBuild(BID, false, "me", null);
        assertThat(fx.newRepos).containsExactly("pixel-quest=chrisfaber95/pixel-quest");
        Brief foreign = BriefParsing.parseBrief(PATH, briefFile("approved", "build: {new_repo: someone/other}\n"));
        assertThat(BriefParsing.newRepo(foreign, "chrisfaber95")).isNull();
        Brief unlisted = BriefParsing.parseBrief(PATH, briefFile("approved", "build: {new_repo: chrisfaber95/zzz}\n"));
        assertThat(BriefParsing.newRepo(unlisted, "chrisfaber95")).isNull();
    }

    /** new: front matter, ids and titles of load_briefs */
    @Test
    void briefFilesAreReadLikeLoadBriefs() {
        Brief b = BriefParsing.parseBrief(PATH, briefFile("approved", "priority: must\n"));
        assertThat(b.id()).isEqualTo(BID);
        assertThat(b.title()).isEqualTo("First playable");
        assertThat(b.status()).isEqualTo("approved");
        assertThat(b.priority()).isEqualTo("must");
        assertThat(b.repos()).containsExactly("homelab", "other");
        assertThat(b.project()).isEqualTo("pixel-quest");
        assertThat(BriefParsing.parseBrief("p/briefs/x.md", "---\nid: nope\n---\n")).isNull();
        assertThat(BriefParsing.parseBrief("p/briefs/x.md", "no front matter")).isNull();
        assertThat(BriefParsing.parseBrief("p/briefs/untitled.md", "---\nid: H-1234\n---\n").title()).isEqualTo("untitled");
        assertThat(BriefParsing.splitFrontmatter("---\n: : [\n---\nbody").meta()).isEmpty();
        assertThat(BriefParsing.asList("a, b,,c")).containsExactly("a", "b", "c");
    }

    // ---- setup PR (new)

    private void approvedWithSetup(SetupPr pr) {
        fx.setup = List.of(pr);
    }

    /** new: merge_setup_pr merges the designer's PR when it only changes the kit */
    @Test
    void aSetupPrThatOnlyChangesTheKitIsMerged() throws Exception {
        approvedWithSetup(new SetupPr(3, "chrisfaber95/homelab", List.of("CLAUDE.md", ".claude/agents/a.md", ".claude/skills/s/SKILL.md")));
        briefs.mergeSetupPr(BID, "chrisfaber95/homelab");
        assertThat(fx.merged).containsExactly("chrisfaber95/homelab#3");
    }

    /** new: a setup PR from a fork, or touching other files, stops the build */
    @Test
    void aSetupPrFromAForkOrWithOtherFilesIsRefused() {
        approvedWithSetup(new SetupPr(3, "evil/homelab", List.of("CLAUDE.md")));
        assertThatThrownBy(() -> briefs.mergeSetupPr(BID, "chrisfaber95/homelab")).hasMessageContaining("comes from another repo");
        approvedWithSetup(new SetupPr(4, "chrisfaber95/homelab", List.of("CLAUDE.md", "scripts/x.sh", ".github/workflows/a.yml")));
        assertThatThrownBy(() -> briefs.mergeSetupPr(BID, "chrisfaber95/homelab"))
                .hasMessageContaining("scripts/x.sh, .github/workflows/a.yml");
        approvedWithSetup(new SetupPr(5, "chrisfaber95/homelab", java.util.Collections.nCopies(100, "CLAUDE.md")));
        assertThatThrownBy(() -> briefs.mergeSetupPr(BID, "chrisfaber95/homelab")).hasMessageContaining("too many files");
        assertThat(fx.merged).isEmpty();
    }

    // ---- planner (new: plan_task has no Python test; parse_plan only through TaskLimit)

    private long planTask(String... columns) throws Exception {
        long id = 7;
        String[] cols = new String[columns.length + 2];
        cols[0] = "brief";
        cols[1] = BID;
        System.arraycopy(columns, 0, cols, 2, columns.length);
        CoreFixture.insert(db, id, "brief", "queued", cols);
        return id;
    }

    private static String plan(String json) {
        return "```plan\n" + json + "\n```";
    }

    private static final String TWO_TASKS = "{\"tasks\":[{\"id\":\"a\",\"repo\":\"homelab\",\"text\":\"first\",\"criteria\":[\"c1\"]},"
            + "{\"id\":\"b\",\"repo\":\"other\",\"text\":\"second\",\"criteria\":[\"c2\",\"c3\"],\"after\":\"a\"}],"
            + "\"manual\":[\"check on the hardware\"]}";

    /** new: the planner turns a plan into queued child tasks with criteria and order, and the build becomes routed */
    @Test
    void thePlannerMakesChildTasks() throws Exception {
        long id = planTask();
        fx.setup = List.of(new SetupPr(3, "chrisfaber95/homelab", List.of("CLAUDE.md")));
        fx.planAnswer = new ClaudeAnswer(true, plan(TWO_TASKS), "");
        rounds.planTask(id);
        Task p = task(id);
        assertThat(p.status()).isEqualTo("routed");
        assertThat(p.num("rounds")).isEqualTo(1);
        assertThat(p.str("note")).isEqualTo("Needs you (no task covers it): check on the hardware");
        List<Task> kids = store.children(id);
        assertThat(kids).hasSize(2);
        assertThat(kids.get(0).repo()).isEqualTo("homelab");
        assertThat(kids.get(0).status()).isEqualTo("queued");
        assertThat(kids.get(0).has("after_task")).isFalse();
        assertThat(kids.get(1).num("after_task")).isEqualTo((int) kids.get(0).id());
        assertThat(kids.get(1).str("reason")).isEqualTo("planned from " + BID);
        assertThat(kids.get(1).str("brief")).isEqualTo(BID);
        assertThat(kids.get(1).str("criteria")).isEqualTo("[\"c2\",\"c3\"]");
        assertThat(kids.get(1).str("text")).startsWith("second\n\nAcceptance criteria for this task:\n- c2\n- c3\n\n"
                + "This task is part of brief " + BID + " (First playable), build #7. Touch only this repo.")
                .endsWith("Body of the brief.");
        assertThat(fx.merged).containsExactly("chrisfaber95/homelab#3");
        assertThat(fx.systems.get(0)).contains("at most 6 tasks").contains("- homelab [CI: none yet]")
                .contains("within\n80 turns or 30 minutes").doesNotContain("{cap}", "{repos}");
        assertThat(fx.prompts.get(0)).startsWith("The brief (" + PATH + " in the product repo):\n\n---\nid: " + BID);
        assertThat(fx.notifications).isEmpty();
        assertThat(fx.logs).contains("t7 planner started for " + BID, "t7 planned: #" + kids.get(0).id() + " (homelab), #"
                + kids.get(1).id() + " (other)");
    }

    /** new: a brief with max_tasks 0 tells the planner to make as many tasks as it needs */
    @Test
    void aBriefWithoutALimitTellsThePlannerSo() throws Exception {
        fx.product.put(PATH, briefFile("approved", "max_tasks: 0\n"));
        long id = planTask();
        fx.planAnswer = new ClaudeAnswer(true, plan(TWO_TASKS), "");
        rounds.planTask(id);
        assertThat(fx.systems.get(0)).contains("as many tasks as the brief needs, each as small as it can be");
    }

    /** new: a question from the planner waits for the user, goes to the conversation and is notified without storing twice */
    @Test
    void aQuestionFromThePlannerWaits() throws Exception {
        long id = planTask();
        fx.planAnswer = new ClaudeAnswer(true, plan("{\"question\":\"Which repo?\"}"), "");
        rounds.planTask(id);
        assertThat(task(id).status()).isEqualTo("waiting");
        assertThat(task(id).str("question")).isEqualTo("Which repo?");
        assertThat(fx.notes).containsExactly("question|Which repo?|task:7");
        assertThat(fx.notifications).containsExactly("Inbox 7: question about " + BID + "|Which repo?|" + BASE + "/task/7|false");
        assertThat(store.children(id)).isEmpty();
    }

    /** new: after the user's answer the planner gets it with its own question, and the setup PRs are not merged again */
    @Test
    void theAnswerGoesBackToThePlanner() throws Exception {
        long id = planTask("rounds", "1", "question", "Which repo?", "pending_input", "homelab");
        fx.setup = List.of(new SetupPr(3, "chrisfaber95/homelab", List.of("CLAUDE.md")));
        fx.planAnswer = new ClaudeAnswer(true, plan(TWO_TASKS), "");
        rounds.planTask(id);
        assertThat(fx.prompts.get(0)).endsWith("\n\nYou asked earlier: Which repo?\nhomelab");
        assertThat(fx.merged).isEmpty();
        assertThat(task(id).num("rounds")).isEqualTo(2);
        assertThat(task(id).has("question")).isFalse();
    }

    /** new: a bad plan, a Claude error, a brief that is no longer approved and a refused setup PR all fail the build with a notification */
    @Test
    void whatGoesWrongFailsTheBuildWithTheReason() throws Exception {
        long id = planTask();
        fx.planAnswer = new ClaudeAnswer(true, "no block", "");
        rounds.planTask(id);
        assertThat(task(id).status()).isEqualTo("failed");
        assertThat(task(id).str("note")).isEqualTo("the planner gave no plan block");
        assertThat(task(id).has("ended")).isTrue();
        assertThat(fx.notifications).containsExactly("Inbox 7 mislukt|the planner gave no plan block|" + BASE + "/task/7|true");

        fx.notifications.clear();
        long b = 8;
        CoreFixture.insert(db, b, "brief", "queued", "brief", BID);
        fx.planAnswer = new ClaudeAnswer(false, "model unknown", "boom");
        rounds.planTask(b);
        assertThat(task(b).str("note")).isEqualTo("Claude: model unknown");

        long c = 9;
        CoreFixture.insert(db, c, "brief", "queued", "brief", BID);
        fx.product.put(PATH, briefFile("parked", ""));
        briefs.forget();
        rounds.planTask(c);
        assertThat(task(c).str("note")).isEqualTo(BID + " is no longer an approved brief on main of the product repo");

        long d = 10;
        CoreFixture.insert(db, d, "brief", "queued", "brief", BID);
        fx.product.put(PATH, briefFile("approved", "").replace("[homelab, other]", "[nowhere]"));
        briefs.forget();
        rounds.planTask(d);
        assertThat(task(d).str("note")).isEqualTo("none of the brief's repos (nowhere) is in the inbox's list");

        long e = 11;
        CoreFixture.insert(db, e, "brief", "queued", "brief", BID);
        fx.product.put(PATH, briefFile("approved", ""));
        briefs.forget();
        fx.setup = List.of(new SetupPr(3, "evil/homelab", List.of("CLAUDE.md")));
        rounds.planTask(e);
        assertThat(task(e).str("note")).contains("comes from another repo");
        assertThat(fx.notifications).hasSize(4);
        assertThat(fx.calls()).noneMatch(c2 -> c2.startsWith("merge "));
    }

    /** new: a build cancelled while the planner ran ends cancelled and makes no tasks */
    @Test
    void aCancelledPlannerMakesNoTasks() throws Exception {
        long id = planTask();
        fx.planAnswer = new ClaudeAnswer(true, plan(TWO_TASKS), "");
        cancellations.request(id);
        rounds.planTask(id);
        assertThat(task(id).status()).isEqualTo("cancelled");
        assertThat(store.children(id)).isEmpty();
    }

    /** new: parse_plan rules: repo outside the brief, empty text or criteria, duplicate ids, unknown "after", limits */
    @Test
    void thePlanIsChecked() {
        List<String> repos = List.of("homelab");
        assertThatThrownBy(() -> BriefParsing.parsePlan("x", repos, 6)).hasMessage("the planner gave no plan block");
        assertThatThrownBy(() -> BriefParsing.parsePlan(plan("{oops"), repos, 6)).hasMessage("the planner gave no valid JSON");
        assertThatThrownBy(() -> BriefParsing.parsePlan(plan("{\"tasks\":[]}"), repos, 6)).hasMessage("the planner gave no tasks");
        assertThatThrownBy(() -> BriefParsing.parsePlan(plan("{\"tasks\":[{\"repo\":\"evil\",\"text\":\"x\",\"criteria\":[\"c\"]}]}"), repos, 6))
                .hasMessage("the planner chose a repo outside the brief: evil");
        assertThatThrownBy(() -> BriefParsing.parsePlan(plan("{\"tasks\":[{\"repo\":\"homelab\",\"text\":\"x\",\"criteria\":[]}]}"), repos, 6))
                .hasMessage("the planner gave a task without text or criteria");
        assertThatThrownBy(() -> BriefParsing.parsePlan(plan("{\"tasks\":[\"x\"]}"), repos, 6)).hasMessage("invalid task from the planner");
        String dup = "{\"tasks\":[{\"id\":\"a\",\"repo\":\"homelab\",\"text\":\"x\",\"criteria\":[\"c\"]},"
                + "{\"id\":\"a\",\"repo\":\"homelab\",\"text\":\"y\",\"criteria\":[\"c\"]}]}";
        assertThatThrownBy(() -> BriefParsing.parsePlan(plan(dup), repos, 6)).hasMessage("the planner gave two tasks with the same id");
        String later = "{\"tasks\":[{\"id\":\"a\",\"repo\":\"homelab\",\"text\":\"x\",\"criteria\":[\"c\"],\"after\":\"b\"},"
                + "{\"id\":\"b\",\"repo\":\"homelab\",\"text\":\"y\",\"criteria\":[\"c\"],\"after\":\"a\"}]}";
        BriefParsing.Plan p = BriefParsing.parsePlan(plan(later), repos, 6);
        assertThat(p.tasks().get(0).after()).isEmpty(); // "b" is not an earlier task
        assertThat(p.tasks().get(1).after()).isEqualTo("a");
        String cut = "{\"tasks\":[{\"repo\":\"homelab\",\"text\":\"" + "t".repeat(7000) + "\",\"criteria\":[\"" + "c".repeat(600) + "\"]}]}";
        BriefParsing.Plan big = BriefParsing.parsePlan(plan(cut), repos, 6);
        assertThat(big.tasks().get(0).text()).hasSize(6000);
        assertThat(big.tasks().get(0).criteria().get(0)).hasSize(500);
        assertThat(big.tasks().get(0).key()).isEqualTo("0");
    }

    // ---- brief review: tests/test_runner_review.py, test_review_pr_reads_the_runner_result

    private static String review(String... rows) {
        return "```review\n{\"summary\":\"all fine\",\"criteria\":[" + String.join(",", rows) + "]}\n```";
    }

    private static String row(String criterion, String verdict) {
        return "{\"criterion\":\"" + criterion + "\",\"verdict\":\"" + verdict + "\",\"note\":\"n\"}";
    }

    private long prTask(String... columns) throws Exception {
        long id = 5;
        String[] cols = new String[columns.length + 12];
        String[] base = {"brief", BID, "criteria", "[\"c\"]", "pr_number", "3", "pr_url", "https://github.com/o/r/pull/3",
                "summary", "did it"};
        System.arraycopy(base, 0, cols, 0, base.length);
        System.arraycopy(columns, 0, cols, base.length, columns.length);
        String[] used = java.util.Arrays.copyOf(cols, base.length + columns.length);
        CoreFixture.insert(db, id, "homelab", "pr_open", used);
        return id;
    }

    /** test_review_pr_reads_the_runner_result: a passing result gives review_ok 1, a failed runner gives review_ok 0 */
    @Test
    void theReviewReadsTheRunnersResult() throws Exception {
        long id = prTask();
        fx.reviewAnswer = new ClaudeAnswer(true, review(row("c", "pass")), "");
        rounds.reviewPr(id, "cache");
        assertThat(task(id).num("review_ok")).isEqualTo(1);
        assertThat(task(id).str("review")).isEqualTo("1 of 1 criteria met. all fine");
        fx.reviewAnswer = new ClaudeAnswer(false, "", "boom");
        rounds.reviewPr(id, "cache");
        assertThat(task(id).num("review_ok")).isZero();
        assertThat(task(id).str("review")).isEqualTo("brief review failed: Claude: boom");
    }

    /** new: a review is posted as a PR comment and the user is notified when the inbox does not merge it itself */
    @Test
    void aPassedReviewIsCommentedAndNotified() throws Exception {
        long id = prTask();
        fx.reviewAnswer = new ClaudeAnswer(true, review(row("c1", "pass"), row("c2", "ci")), "");
        rounds.reviewPr(id, "cache");
        assertThat(task(id).num("review_ok")).isEqualTo(2);
        assertThat(fx.comments).containsExactly("**Brief review** (" + BID + ", round 1): all fine\n\n- ✅ c1: n\n- 🧪 c2: n"
                + "\n\n<!-- homelab-inbox review -->");
        assertThat(fx.notifications).containsExactly("Inbox 5: PR #3|did it\n2 of 2 criteria met. all fine|https://github.com/o/r/pull/3|true");
        assertThat(fx.calls()).noneMatch(c -> c.startsWith("merge "));
    }

    /** new: a miss sends the task back once (queued again with what was missed), the second miss waits for the user */
    @Test
    void aMissSendsTheTaskBackOnce() throws Exception {
        long id = prTask("ended", "2026-11-18T09:00:00+00:00");
        fx.reviewAnswer = new ClaudeAnswer(true, review(row("c1", "pass"), row("c2", "miss"), row("c3", "unclear")), "");
        rounds.reviewPr(id, "cache");
        Task t = task(id);
        assertThat(t.status()).isEqualTo("queued");
        assertThat(t.num("reviews")).isEqualTo(1);
        assertThat(t.has("ended")).isFalse();
        assertThat(t.has("review_ok")).isFalse();
        assertThat(t.str("review")).isEqualTo("1 of 3 criteria met, missed: c2, can't be judged from the diff: c3. all fine");
        assertThat(t.str("pending_input")).startsWith("The brief reviewer found these acceptance criteria not met:\n\n- c2: n\n\n")
                .contains("end with KLAAR: again");
        assertThat(fx.notifications).isEmpty();
        // the rework round ends with a PR again: no second rework, the user decides
        store.update(id, TaskStore.fields("status", "pr_open"));
        rounds.reviewPr(id, "cache");
        assertThat(task(id).num("review_ok")).isZero();
        assertThat(task(id).num("reviews")).isEqualTo(1);
        assertThat(fx.notifications).hasSize(1);
        assertThat(fx.comments.get(1)).contains("round 2");
    }

    /** new: in a build without me a passing product-repo PR is left to the merger (no notification); homelab always waits */
    @Test
    void aBuildWithoutMeMergesProductRepoPrsItself() throws Exception {
        long parent = build("routed", null);
        store.update(parent, TaskStore.fields("unattended", 1));
        long id = 20;
        CoreFixture.insert(db, id, "other", "pr_open", "brief", BID, "criteria", "[\"c\"]", "pr_number", "4", "parent", "" + parent);
        fx.reviewAnswer = new ClaudeAnswer(true, review(row("c", "pass")), "");
        rounds.reviewPr(id, "cache");
        assertThat(task(id).num("review_ok")).isEqualTo(1);
        assertThat(briefs.mergesItself(task(id))).isTrue();
        assertThat(fx.notifications).isEmpty();
        long home = 21;
        CoreFixture.insert(db, home, "homelab", "pr_open", "brief", BID, "criteria", "[\"c\"]", "pr_number", "5", "parent", "" + parent);
        rounds.reviewPr(home, "cache");
        assertThat(briefs.mergesItself(task(home))).isFalse();
        assertThat(fx.notifications).hasSize(1);
        assertThat(fx.notifications.get(0)).contains("Build without me: this PR waits for you (homelab)");
        long failed = 22;
        CoreFixture.insert(db, failed, "other", "pr_open", "brief", BID, "criteria", "[\"c\"]", "pr_number", "6", "parent", "" + parent);
        fx.reviewAnswer = new ClaudeAnswer(true, review(row("c", "miss")), "");
        store.update(failed, TaskStore.fields("reviews", 1));
        rounds.reviewPr(failed, "cache");
        assertThat(fx.notifications.get(1)).contains("missed: c. all fine. Build without me: this PR waits for you")
                .doesNotContain("(homelab)");
    }

    /** new: merges_itself needs a brief task with a parent, a product repo and a passed review (review_ok 1 or 2) */
    @Test
    void mergesItselfNeedsEveryCondition() throws Exception {
        long parent = build("routed", null);
        store.update(parent, TaskStore.fields("unattended", 1));
        long plain = 30;
        CoreFixture.insert(db, plain, "other", "pr_open", "brief", BID, "parent", "" + parent, "review_ok", "0");
        assertThat(briefs.mergesItself(task(plain))).isFalse();
        store.update(plain, TaskStore.fields("review_ok", 2));
        assertThat(briefs.mergesItself(task(plain))).isTrue();
        store.update(parent, TaskStore.fields("unattended", 0));
        assertThat(briefs.mergesItself(task(plain))).isFalse();
        long loose = 31;
        CoreFixture.insert(db, loose, "other", "pr_open", "review_ok", "1");
        assertThat(briefs.mergesItself(task(loose))).isFalse();
        assertThat(briefs.mergesItself(null)).isFalse();
    }

    /** new: the reviewer gets code, then docs, without lockfiles; a long diff is cut; left-out files are named */
    @Test
    void theReviewerDiffLeavesOutLockfilesAndPutsDocsLast() throws Exception {
        long id = prTask();
        fx.changed = List.of("README.md", "src/a.ts", "package-lock.json", "web/dist/app.js", "docs/x.txt", "tests/fixtures/a.json",
                "go.sum", "src/app.min.js");
        fx.diffText = "x".repeat(100);
        fx.reviewAnswer = new ClaudeAnswer(true, review(row("c", "pass")), "");
        rounds.reviewPr(id, "cache");
        assertThat(fx.diffGroups).containsExactly(List.of("src/a.ts"), List.of("README.md", "docs/x.txt"));
        assertThat(fx.prompts.get(0)).contains("Acceptance criteria of this task:\n- c\n\nThe task as given:\n\ntext 5\n\n"
                + "The diff of PR #3:\n\n" + "x".repeat(200) + "\n\nLeft out of this diff (lockfiles and generated files): "
                + "package-lock.json, web/dist/app.js, tests/fixtures/a.json, go.sum, src/app.min.js");
        Briefs small = new Briefs(store, fx, fx, fx, fx, fx, new Briefs.Settings("o/p", "o", 6, 80, 30, 150, 1), clock);
        BriefRounds cutting = new BriefRounds(store, small, fx, fx, fx, fx, fx, fx, fx, fx, fx, cancellations, BASE);
        fx.prompts.clear();
        fx.diffText = "y".repeat(100);
        cutting.reviewPr(id, "cache");
        assertThat(fx.prompts.get(0)).contains("y".repeat(150) + "\n[diff cut off at 150 characters]");
    }

    /** new: parse_review rules: no block, no criteria, unknown verdicts become unclear, long text is cut */
    @Test
    void theReviewIsChecked() {
        assertThatThrownBy(() -> BriefParsing.parseReview("x")).hasMessage("the reviewer gave no review block");
        assertThatThrownBy(() -> BriefParsing.parseReview("```review\n{\"criteria\":[]}\n```")).hasMessage("the reviewer judged no criteria");
        BriefParsing.Review r = BriefParsing.parseReview(review("{\"criterion\":\"c\",\"verdict\":\"PASS\"}",
                "{\"criterion\":\"d\",\"verdict\":\"maybe\",\"note\":\"" + "n".repeat(400) + "\"}", "{\"criterion\":\"e\"}", "\"junk\""));
        assertThat(r.rows()).extracting(BriefParsing.ReviewRow::verdict).containsExactly("pass", "unclear", "unclear");
        assertThat(r.rows().get(1).note()).hasSize(300);
        assertThat(r.summary()).isEqualTo("all fine");
    }

    // ---- handoff (new)

    private long merged(long parent, String repo, int pr) throws Exception {
        long id = store.newChild(repo, "task", parent, null, "planned", BID, "[\"c\"]");
        store.update(id, TaskStore.fields("status", "merged", "pr_number", pr));
        return id;
    }

    /** new: the handoff pushes the brief as handed-off and the roadmap with the PRs, opens the PR and notifies */
    @Test
    void theHandoffOpensAPrInTheProductRepo() throws Exception {
        fx.product.put(ROADMAP, "# Roadmap\n- [" + BID + "] First playable.\n- [H-015] Other. Built: x#1.\n");
        long parent = build("routed", T);
        merged(parent, "homelab", 3);
        merged(parent, "other", 4);
        rounds.handoff(task(parent));
        Map<String, String> files = fx.branches.get("handoff/" + BID);
        assertThat(files.get(PATH)).contains("status: handed-off\n").doesNotContain("# draft | approved");
        assertThat(files.get(ROADMAP)).isEqualTo("# Roadmap\n- [" + BID + "] First playable. Built: homelab#3, other#4.\n"
                + "- [H-015] Other. Built: x#1.\n");
        assertThat(fx.commits).containsExactly(BID + ": handed off (build #" + parent + ": homelab#3, other#4)");
        assertThat(task(parent).str("handoff")).isEqualTo("https://github.com/chrisfaber95/product/pull/9");
        assertThat(fx.notifications).containsExactly(BID + " built|All 2 task(s) merged; handoff PR in the product repo.|"
                + "https://github.com/chrisfaber95/product/pull/9|true");
        assertThat(fx.calls()).contains("open pr chrisfaber95/product handoff/" + BID).noneMatch(c -> c.startsWith("mergeHandoff"));
    }

    /** new: a build without me merges the handoff PR too, unless it has criteria only the user can check or GitHub refuses */
    @Test
    void aBuildWithoutMeMergesTheHandoffToo() throws Exception {
        long parent = build("routed", T);
        store.update(parent, TaskStore.fields("unattended", 1));
        merged(parent, "homelab", 3);
        rounds.handoff(task(parent));
        assertThat(fx.merged).containsExactly("handoff chrisfaber95/product#9");
        assertThat(fx.notifications.get(0)).startsWith(BID + " built and handed off|All 1 task(s) merged: homelab#3. Build without me");
        fx.notifications.clear();
        fx.handoffMergeFails = new IllegalStateException("405");
        rounds.handoff(task(parent));
        assertThat(fx.notifications).containsExactly(BID + " built|All 1 task(s) merged; handoff PR in the product repo.|"
                + "https://github.com/chrisfaber95/product/pull/9|true");
        fx.notifications.clear();
        fx.handoffMergeFails = null;
        fx.merged.clear();
        store.update(parent, TaskStore.fields("note", "Needs you (no task covers it): hardware"));
        rounds.handoff(task(parent));
        assertThat(fx.merged).isEmpty();
        assertThat(fx.notifications.get(0)).contains("It has criteria only you can check (see the build).");
    }

    /** new: settle_briefs hands off a finished build once, and only when every task merged (or was answered) and one merged */
    @Test
    void settleHandsOffOnlyFinishedBuilds() throws Exception {
        long waiting = build("routed", T);
        merged(waiting, "homelab", 3);
        child(waiting, "pr_open", null);
        long onlyAnswered = build("routed", T);
        child(onlyAnswered, "answered", T);
        long noTasks = build("routed", T);
        long done = build("routed", T);
        merged(done, "homelab", 5);
        child(done, "answered", T);
        long running = build("routed", null);
        merged(running, "homelab", 6);
        rounds.settle();
        assertThat(task(done).has("handoff")).isTrue();
        for (long id : List.of(waiting, onlyAnswered, noTasks, running)) {
            assertThat(task(id).has("handoff")).as("build " + id).isFalse();
        }
        int notified = fx.notifications.size();
        rounds.settle();
        assertThat(fx.notifications).hasSize(notified);
    }

    /** new: a handoff that fails is recorded on the build and the user is told */
    @Test
    void aFailedHandoffIsRecordedAndNotified() throws Exception {
        long parent = build("routed", T);
        merged(parent, "homelab", 3);
        fx.product.remove(PATH);
        rounds.settle();
        assertThat(task(parent).str("handoff")).isEqualTo("failed: " + BID + " is no longer in the product repo");
        assertThat(fx.notifications).containsExactly("Inbox " + parent + ": handoff failed|" + BID
                + " is no longer in the product repo|" + BASE + "/task/" + parent + "|true");
    }

    // ---- shadow: the acceptance criterion

    /** With the shadow effects nothing leaves the core: no Claude, merge, comment, commit, PR or notification. */
    @Test
    void withTheShadowEffectsNothingLeavesTheCore() throws Exception {
        ShadowEffects shadow = new ShadowEffects();
        TaskStore s = new TaskStore(db, clock);
        Briefs b = new Briefs(s, shadow, shadow, shadow, shadow, shadow, Briefs.Settings.defaults(), clock);
        BriefRounds r = new BriefRounds(s, b, shadow, shadow, shadow, shadow, shadow, shadow, shadow, shadow, shadow,
                new Cancellations(), BASE);
        long build = planTask();
        CoreFixture.insert(db, 41, "homelab", "pr_open", "brief", BID, "criteria", "[\"c\"]", "pr_number", "3", "parent", "" + build);
        r.planTask(build);
        r.reviewPr(41, "cache");
        r.settle();
        assertThat(task(build).status()).isEqualTo("failed"); // the shadow product repo has no briefs
        assertThat(task(41).num("review_ok")).isZero();
        assertThat(shadow.calls()).noneMatch(c -> c.startsWith("merge") || c.startsWith("mergeSetupPr")
                || c.startsWith("mergeHandoff") || c.startsWith("comment") || c.startsWith("commitToMain")
                || c.startsWith("pushBranch") || c.startsWith("open pr") || c.startsWith("runRound")
                || c.startsWith("claude plan"));
        assertThat(shadow.calls()).contains("claude review 41");
    }
}
