package dev.homelab.inbox.core;

/**
 * Everything the core does to the outside world, as interfaces (H-026 K1): starting and stopping a Claude session,
 * the working copies of the task account, notifications, GitHub writes, the task log and the conversation. In shadow
 * mode all of them are {@link ShadowEffects}: they do nothing and only remember what they were asked. The real
 * implementations arrive with the tasks that need them (K2 and later) and are never wired while the scheduler is off.
 */
public final class Effects {

    private Effects() {}

    /** Runs the rounds of Claude Code (a runner process, {@code runner/round.py}). */
    public interface RoundRunner {
        /** One round of a task, on the calling thread: the Java {@code run_round}. */
        void runRound(long taskId);

        /** Stop the Claude session of a task ({@code homelab-inbox-task kill}). */
        void stop(long taskId);

        /** Review the PR of a brief task again after a restart cut the review off (K4). */
        void reviewAgain(long taskId);
    }

    /** The working copies of the task account ({@code homelab-inbox-task has|cleanup}) and the pushed branches. */
    public interface WorkingCopies {
        boolean has(long taskId);

        void cleanup(long taskId);

        /** Whether an earlier round pushed commits to {@code inbox/<id>}. */
        boolean pushed(Task task);
    }

    public interface Notifier {
        /** Notify and keep it in the conversation ({@code helpers.notify}). */
        default void notify(String title, String body, String url) {
            notify(title, body, url, true);
        }

        /** {@code store=false}: the text is in the conversation already (an answer or a question of the task). */
        void notify(String title, String body, String url, boolean store);
    }

    /** Writes to GitHub: the only way the core can merge. */
    public interface GitHubWrites {
        void merge(String repoSlug, int prNumber);
    }

    /** The log of a task or run ({@code helpers.log_line}). */
    public interface TaskLog {
        void line(String key, String kind, String text);
    }

    /** What the conversation records about a task ({@code conversation.note}). */
    public interface ConversationNotes {
        void note(String author, String client, String kind, String text, String link, String ref);
    }

    /** Brings the follow-ups of a task back in the queue after a retry (K3). */
    public interface Followups {
        void revive(Task task);
    }

    /** The outcome of a command of {@code homelab-inbox-task}: exit code and output. */
    public record Result(int code, String out) {}

    /** The {@code homelab-inbox-task} commands a round needs besides {@link WorkingCopies} (K2). */
    public interface TaskCommands {
        /** Make the working copy of a task: a fresh one, or one rebuilt from the pushed branch. */
        Result prepare(long taskId, String repo);

        /** 0: main has commits the branch lacks; 1: it has them; 2: no working copy. */
        Result behind(long taskId);

        /** Merge main into the branch: 0 merged, 4 conflicts (output: the files), anything else failed. */
        Result sync(long taskId);
    }

    /** Takes the commits of a task out of its working copy and pushes them to {@code inbox/<id>} (never to main). */
    public interface Pushes {
        /** The commit lines pushed, empty when there was nothing to push. Throws when the push failed. */
        java.util.List<String> push(long taskId, String cache);
    }

    /** The PR of a task on GitHub. */
    public interface PullRequests {
        record Opened(int number, String url) {}

        Opened open(String repoSlug, String title, String head, String base, String body);
    }

    /** The {@code job_runs} row of a research run. */
    public record ResearchRun(long id, String mode, String created) {}

    /** What the core needs to know about jobs and research (K6 ports the jobs themselves). */
    public interface Research {
        java.util.Optional<ResearchRun> runOf(Task task);

        /** Deliver a successful result where the job file says (nothing for the default, page). Never throws. */
        void deliver(Task task, String text, String link);

        /** The instruction of a job file, for the title of its PR; null when the job is not known. */
        String jobInstruction(String job);
    }

    /** Things that run beside a finished round, on their own thread: a PR review of a brief, a skill proposal. */
    public interface AfterEffects {
        void reviewPr(long taskId, String cache);

        void proposeSkill(Task task, String name, String description, String body);
    }

    /** The memory steps around a round (F-023); in Java since H-024 M5. */
    public interface MemorySteps {
        void load();

        /** The text of Claude without its memory blocks, and those blocks. */
        MemorySplit split(String text);

        void afterRound(long taskId, java.util.List<String> blocks);

        record MemorySplit(String text, java.util.List<String> blocks) {}
    }

    /** The repos of {@code config/repos.conf} (name to GitHub slug) and the working copies of the service. */
    public interface Repos {
        java.util.Map<String, String> list();

        /** Update the service's copy of a repo ({@code refresh_cache}); its path. */
        String refresh(String repo, String slug);
    }

    /** The Claude part of one round: the prompt file, the session and the routing of the pseudo-repos. */
    public interface Sessions {
        void writePrompt(Task task, String prompt);

        /** One Claude run on the calling thread: {@code mode} is {@code new} or {@code resume}. */
        Outcome stream(long taskId, String mode);

        void route(long taskId);

        void plan(long taskId);

        /** The text of Claude (memory blocks not yet split off), permission denials and the limit hit, or null. */
        record Outcome(String result, int permissionDenials, String capped) {}
    }

    /** One file a {@code briefs} folder of the product repo's main, as read from the clone. */
    public record ProductFile(String path, String text) {}

    /** The product repo (briefs, roadmap): {@code briefs.refresh_cache("product")} and the commits to it (K4). */
    public interface ProductRepo {
        /** Update the clone and return the brief files of main (the {@code briefs} folders), sorted by path. */
        java.util.List<ProductFile> briefFiles();

        /** The text of a file on main, or null when it does not exist (the roadmap). */
        String read(String path);

        /** Commit one changed file straight to main of the product repo (approving or parking a draft). */
        void commitToMain(String path, String text, String message);

        /** Commit changed files on a branch cut from main and force-push it (the handoff branch); main is restored. */
        void pushBranch(String branch, java.util.Map<String, String> files, String message);
    }

    /** A setup PR of the designer ({@code setup/<brief>}) as the GitHub API shows it. */
    public record SetupPr(int number, String headRepo, java.util.List<String> files) {}

    /** The GitHub calls of the briefs that {@link GitHubWrites} and {@link PullRequests} do not cover. */
    public interface BriefGitHub {
        java.util.List<SetupPr> setupPrs(String slug, String briefId);

        void mergeSetupPr(String slug, int number, String title);

        /** Comment on a PR (the brief review). */
        void comment(String slug, int prNumber, String body);

        /** Merge the handoff PR; throws {@link IllegalStateException} when GitHub refuses. */
        void mergeHandoff(String slug, int number, String title);
    }

    /** The outcome of one Claude call without tools (planner, reviewer): ok, the text, or why not. */
    public record ClaudeAnswer(boolean ok, String text, String detail) {}

    /** The Claude calls of the briefs: the files in {@code share/} are written by the implementation, not by the core. */
    public interface BriefClaude {
        /** The planner ({@code task_cmd plan}) with its system prompt and prompt. */
        ClaudeAnswer plan(long taskId, String system, String prompt);

        /** The reviewer ({@code runner/review.py}). */
        ClaudeAnswer review(long taskId, String system, String prompt);
    }

    /** The short descriptions of repos for the planner ({@code router.repo_profiles(repos, ci=True)}). */
    public interface RepoProfiles {
        String profiles(java.util.Map<String, String> repos, boolean ci);
    }

    /** The inbox's own list of repos of new projects ({@code new-repos.json}). */
    public interface NewRepos {
        void add(String name, String slug);
    }

    /** The diff of a task's pushed branch against main in the service's copy of the repo. */
    public interface Diffs {
        java.util.List<String> changedFiles(String cache, long taskId);

        String diff(String cache, long taskId, java.util.List<String> paths);
    }
}
