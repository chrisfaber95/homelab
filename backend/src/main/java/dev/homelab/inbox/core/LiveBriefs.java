package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.BriefGitHub;
import dev.homelab.inbox.core.Effects.Diffs;
import dev.homelab.inbox.core.Effects.NewRepos;
import dev.homelab.inbox.core.Effects.ProductFile;
import dev.homelab.inbox.core.Effects.ProductRepo;
import dev.homelab.inbox.core.Effects.RepoProfiles;
import dev.homelab.inbox.core.Effects.SetupPr;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * What the briefs (H-001) do outside the process: the product repo's clone (briefs, roadmap, the commits to main and the
 * handoff branch), the GitHub calls around setup PRs and reviews, the diff of a task's branch, the repo profiles for the
 * router and the planner, and the inbox's own list of new repos.
 */
final class LiveBriefs implements ProductRepo, BriefGitHub, Diffs, RepoProfiles, NewRepos {

    private static final Logger log = LoggerFactory.getLogger(LiveBriefs.class);
    private static final List<String> IDENTITY = List.of("-c", "user.name=homelab-inbox", "-c", "user.email=homelab-inbox@homelab");

    private final ServiceGit git;
    private final Gh gh;
    private final Settings conf;

    LiveBriefs(ServiceGit git, Gh gh, Settings conf) {
        this.git = git;
        this.gh = gh;
        this.conf = conf;
    }

    private String productSlug() {
        return conf.conf("PRODUCT_REPO", "chrisfaber95/product");
    }

    private ReentrantLock lock() {
        return git.lock("product-checkout"); // the product cache: page loads, planner and handoffs share one checkout
    }

    // ---- ProductRepo ---------------------------------------------------------------------------------------------

    @Override
    public List<ProductFile> briefFiles() {
        lock().lock();
        try {
            Path d = git.refresh("product", productSlug());
            List<ProductFile> out = new ArrayList<>();
            List<Path> projects;
            try (Stream<Path> s = Files.list(d)) {
                projects = s.filter(Files::isDirectory).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            } catch (IOException e) {
                throw new IllegalStateException("product repo: " + e.getMessage(), e);
            }
            for (Path project : projects) {
                Path briefs = project.resolve("briefs");
                if (!Files.isDirectory(briefs)) {
                    continue;
                }
                try (Stream<Path> s = Files.list(briefs)) {
                    for (Path f : s.filter(p -> p.getFileName().toString().endsWith(".md")).filter(Files::isRegularFile)
                            .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                        out.add(new ProductFile(d.relativize(f).toString(),
                                new String(Files.readAllBytes(f), StandardCharsets.UTF_8)));
                    }
                } catch (IOException e) {
                    throw new IllegalStateException("product repo: " + e.getMessage(), e);
                }
            }
            return out;
        } finally {
            lock().unlock();
        }
    }

    @Override
    public String read(String path) {
        Path f = conf.runnerHome().resolve("repos").resolve("product").resolve(path);
        try {
            return Files.isRegularFile(f) ? Files.readString(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void commitToMain(String path, String text, String message) {
        lock().lock();
        try {
            Path d = git.refresh("product", productSlug());
            write(d.resolve(path), text);
            git.git(d, "add", "--", path);
            commit(d, message);
            git.git(d, "push", "--quiet", "origin", "HEAD:refs/heads/main");
        } finally {
            lock().unlock();
        }
    }

    @Override
    public void pushBranch(String branch, Map<String, String> files, String message) {
        lock().lock();
        Path d = null;
        try {
            d = git.refresh("product", productSlug());
            git.git(d, "checkout", "--quiet", "-B", branch, "origin/main");
            for (Map.Entry<String, String> f : files.entrySet()) {
                write(d.resolve(f.getKey()), f.getValue());
            }
            git.git(d, "add", "-A");
            commit(d, message);
            git.git(d, "push", "--quiet", "--force", "origin", "HEAD:refs/heads/" + branch);
        } finally {
            try {
                if (d != null) {
                    git.git(d, "checkout", "--quiet", "-B", "main", "origin/main");
                }
            } finally {
                lock().unlock();
            }
        }
    }

    private void commit(Path d, String message) {
        List<String> args = new ArrayList<>(IDENTITY);
        args.addAll(List.of("commit", "--quiet", "-m", message));
        git.git(d, args.toArray(String[]::new));
    }

    private static void write(Path f, String text) {
        try {
            Files.createDirectories(f.getParent());
            Files.writeString(f, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("schrijven mislukt: " + e.getMessage(), e);
        }
    }

    // ---- BriefGitHub ---------------------------------------------------------------------------------------------

    @Override
    public List<SetupPr> setupPrs(String slug, String briefId) {
        String owner = slug.substring(0, slug.indexOf('/'));
        List<SetupPr> out = new ArrayList<>();
        for (JsonNode pr : records(gh.get("/repos/" + slug + "/pulls?state=open&head=" + owner + ":setup/" + briefId))) {
            int number = pr.path("number").asInt();
            List<String> files = new ArrayList<>();
            for (JsonNode f : records(gh.get("/repos/" + slug + "/pulls/" + number + "/files?per_page=100"))) {
                files.add(f.path("filename").asString("?"));
            }
            out.add(new SetupPr(number, pr.path("head").path("repo").path("full_name").asString(""), files));
        }
        return out;
    }

    private static List<JsonNode> records(JsonNode value) {
        List<JsonNode> out = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(v -> {
                if (v.isObject()) {
                    out.add(v);
                }
            });
        }
        return out;
    }

    @Override
    public void mergeSetupPr(String slug, int number, String title) {
        gh.merge(slug, number, null, title);
    }

    @Override
    public void comment(String slug, int prNumber, String body) {
        gh.post("/repos/" + slug + "/issues/" + prNumber + "/comments", Map.of("body", body));
    }

    @Override
    public void mergeHandoff(String slug, int number, String title) {
        try {
            gh.merge(slug, number, null, title);
        } catch (Effects.GitHubHttpException e) {
            throw new IllegalStateException("GitHub refused the handoff merge (" + e.code() + ")", e);
        }
    }

    // ---- Diffs ---------------------------------------------------------------------------------------------------

    @Override
    public List<String> changedFiles(String cache, long taskId) {
        String out = git.git(Path.of(cache), "diff", "--name-only", span(taskId));
        return out.lines().filter(l -> !l.isEmpty()).toList();
    }

    @Override
    public String diff(String cache, long taskId, List<String> paths) {
        List<String> args = new ArrayList<>(List.of("diff", span(taskId), "--"));
        args.addAll(paths);
        return git.git(Path.of(cache), args.toArray(String[]::new));
    }

    private static String span(long taskId) {
        return "origin/main...refs/inbox/" + taskId;
    }

    // ---- RepoProfiles --------------------------------------------------------------------------------------------

    /** {@code router.repo_profiles}: per repo the start of CLAUDE.md or README.md of main; with ci also whether main has a workflow. */
    @Override
    public String profiles(Map<String, String> repos, boolean ci) {
        List<String> out = new ArrayList<>();
        new java.util.TreeMap<>(repos).forEach((name, slug) -> {
            String about = "";
            boolean hasCi = false;
            try {
                Path cache = git.refresh(name, slug);
                for (String f : List.of("CLAUDE.md", "README.md")) {
                    try {
                        String text = String.join(" ", git.git(cache, "show", "origin/main:" + f).strip().split("\\s+"));
                        about = text.length() > 500 ? text.substring(0, 500) : text;
                        break;
                    } catch (IllegalStateException e) {
                        // not there: the next file
                    }
                }
                if (ci) {
                    hasCi = !git.git(cache, "ls-tree", "--name-only", "origin/main", ".github/workflows/").isBlank();
                }
            } catch (RuntimeException e) {
                log.warn("router: profiel van {}: {}", name, e.toString());
            }
            out.add("- " + name + " (" + slug + "): " + (about.isEmpty() ? "(geen beschrijving)" : about)
                    + (ci ? (hasCi ? " [CI: yes]" : " [CI: none yet]") : ""));
        });
        return String.join("\n", out);
    }

    // ---- NewRepos ------------------------------------------------------------------------------------------------

    @Override
    public synchronized void add(String name, String slug) {
        Map<String, String> all = new LinkedHashMap<>();
        try {
            Gh.JSON.readTree(Files.readString(conf.newReposFile())).properties()
                    .forEach(e -> all.put(e.getKey(), e.getValue().asString()));
        } catch (IOException | RuntimeException e) {
            // no file yet
        }
        all.put(name, slug);
        write(conf.newReposFile(), Gh.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(all));
    }
}
