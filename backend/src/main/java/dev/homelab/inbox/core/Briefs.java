package dev.homelab.inbox.core;

import dev.homelab.inbox.core.BriefParsing.Brief;
import dev.homelab.inbox.core.Effects.BriefGitHub;
import dev.homelab.inbox.core.Effects.NewRepos;
import dev.homelab.inbox.core.Effects.ProductFile;
import dev.homelab.inbox.core.Effects.ProductRepo;
import dev.homelab.inbox.core.Effects.Repos;
import dev.homelab.inbox.core.Effects.SetupPr;
import dev.homelab.inbox.core.Effects.TaskLog;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The briefs of the product repo and their builds ({@code briefs.load_briefs}, {@code builds}, {@code live_build},
 * {@code set_brief_status}, {@code start_build}, {@code merge_setup_pr}; H-026 K4). Reads and writes go through
 * {@link ProductRepo} and {@link BriefGitHub}; with the shadow effects the product repo is empty and nothing is committed
 * or merged.
 */
public class Briefs {

    /** The settings the briefs need (names as in {@code config/inbox.conf}). */
    public record Settings(String productRepo, String ownerGithub, int planMaxTasks, int maxTurns, int maxMinutes,
                           int reviewMaxDiff, int reviewReworkRounds) {
        public static Settings defaults() {
            return new Settings("chrisfaber95/product", "chrisfaber95", 6, 80, 30, 150000, 1);
        }
    }

    /** {@code config.ACCEPTABLE}: statuses of a task that ended badly (the user may accept them). */
    static final Set<String> ACCEPTABLE = Set.of("failed", "cancelled", "capped", "refused", "closed");
    private static final Duration CACHE_AGE = Duration.ofSeconds(60);

    private final TaskStore store;
    private final ProductRepo product;
    private final BriefGitHub github;
    private final Repos repos;
    private final NewRepos newRepos;
    private final TaskLog taskLog;
    private final Settings settings;
    private final Clock clock;

    private Instant loadedAt;
    private List<Brief> cached = List.of();

    public Briefs(TaskStore store, ProductRepo product, BriefGitHub github, Repos repos, NewRepos newRepos,
                  TaskLog taskLog, Settings settings, Clock clock) {
        this.store = store;
        this.product = product;
        this.github = github;
        this.repos = repos;
        this.newRepos = newRepos;
        this.taskLog = taskLog;
        this.settings = settings;
        this.clock = clock;
    }

    ProductRepo product() {
        return product;
    }

    public Settings settings() {
        return settings;
    }

    /** The briefs on main of the product repo; cached for {@code maxAge} (60 s by default, 0 reads again). */
    public synchronized List<Brief> load(Duration maxAge) {
        if (loadedAt != null && Duration.between(loadedAt, clock.instant()).compareTo(maxAge) < 0) {
            return cached;
        }
        List<Brief> items = new java.util.ArrayList<>();
        for (ProductFile f : product.briefFiles()) {
            Brief b = BriefParsing.parseBrief(f.path(), f.text());
            if (b != null) {
                items.add(b);
            }
        }
        cached = List.copyOf(items);
        loadedAt = clock.instant();
        return cached;
    }

    public List<Brief> load() {
        return load(CACHE_AGE);
    }

    public Optional<Brief> byId(String id, Duration maxAge) {
        return load(maxAge).stream().filter(b -> b.id().equals(id)).findFirst();
    }

    synchronized void forget() {
        loadedAt = null;
    }

    /** {@code build_left_nothing}: cancelled or failed, or planned and every task ended badly. */
    public boolean buildLeftNothing(Task b) {
        if (Set.of("cancelled", "failed").contains(b.status())) {
            return true;
        }
        if (b.status().equals("routed") && b.has("ended")) {
            List<Task> kids = store.children(b.id());
            return !kids.isEmpty() && kids.stream().allMatch(k -> ACCEPTABLE.contains(k.status()));
        }
        return false;
    }

    /** The newest build of the brief that did not end with nothing left. */
    public Optional<Task> liveBuild(String briefId) {
        return store.builds(briefId).stream().filter(b -> !buildLeftNothing(b)).findFirst();
    }

    /** Approve or park a draft: commit the new status to main of the product repo. */
    public synchronized void setStatus(String briefId, String status, String why) {
        Brief b = byId(briefId, Duration.ZERO).orElse(null);
        if (b == null || !b.status().equals("draft")) {
            throw new IllegalArgumentException(briefId + " is not a draft on main of the product repo");
        }
        product.commitToMain(b.path(), BriefParsing.withStatus(b.text(), status), briefId + ": " + status + " (" + why + ")");
        forget();
    }

    /** Approving a brief for a new project adds its repo to the inbox's list. */
    void addNewRepo(Brief b) {
        String[] fresh = BriefParsing.newRepo(b, settings.ownerGithub());
        if (fresh != null && !repos.list().containsKey(fresh[0])) {
            newRepos.add(fresh[0], fresh[1]);
            taskLog.line("inbox", "info", b.id() + ": new repo " + fresh[1] + " added to the inbox's list");
        }
    }

    /**
     * Merge the designer's setup PR of this brief (branch {@code setup/<id>}); one from another repo or one that changes
     * more than CLAUDE.md and the agents and skills stops the build.
     */
    void mergeSetupPr(String briefId, String slug) {
        for (SetupPr pr : github.setupPrs(slug, briefId)) {
            if (!slug.equals(pr.headRepo())) {
                throw new IllegalStateException("setup PR #" + pr.number() + " in " + slug + " comes from another repo");
            }
            List<String> bad = pr.files().stream().filter(f -> !BriefParsing.SETUP_PATHS.matcher(f).matches()).toList();
            if (!bad.isEmpty() || pr.files().size() >= 100) {
                throw new IllegalStateException("setup PR #" + pr.number() + " in " + slug + " changes more than CLAUDE.md "
                        + "and .claude/ agents and skills: "
                        + (bad.isEmpty() ? "too many files" : String.join(", ", bad.subList(0, Math.min(5, bad.size())))));
            }
            github.mergeSetupPr(slug, pr.number(), briefId + ": build kit (merged when the build started)");
            taskLog.line("inbox", "info", briefId + ": setup PR #" + pr.number() + " in " + slug + " merged");
        }
    }

    /** Build an approved brief; a draft is approved first. {@code source} is the chat client that approved it. */
    public long startBuild(String briefId, boolean unattended, String who, String source) {
        Brief b = byId(briefId, Duration.ZERO).orElse(null);
        if (b != null && b.status().equals("draft")) {
            if (liveBuild(briefId).isPresent()) {
                throw new IllegalArgumentException(briefId + " is already being built");
            }
            setStatus(briefId, "approved", (source != null ? "approved from " + source + " by " + who
                    : "approved on the homelab page by " + who) + (unattended ? ", build without me" : ""));
            b = byId(briefId, Duration.ZERO).orElse(null);
        }
        if (b == null || !b.status().equals("approved")) {
            throw new IllegalArgumentException(briefId + " is not an approved brief on main of the product repo");
        }
        if (liveBuild(briefId).isPresent()) {
            throw new IllegalArgumentException(briefId + " is already being built");
        }
        addNewRepo(b);
        return store.newBuild(briefId, "Build " + briefId + ": " + b.title(), unattended, source);
    }

    /** {@code helpers.merges_itself}: a brief task of a build without me, on a product repo, whose review passed. */
    public boolean mergesItself(Task t) {
        if (t == null || !t.has("brief") || !t.has("parent") || t.repo().equals("homelab")
                || !repos.list().containsKey(t.repo())) {
            return false;
        }
        Optional<Task> parent = store.get(t.longOrNull("parent"));
        Integer ok = t.numOrNull("review_ok");
        return parent.isPresent() && parent.get().num("unattended") != 0 && ok != null && (ok == 1 || ok == 2);
    }
}
