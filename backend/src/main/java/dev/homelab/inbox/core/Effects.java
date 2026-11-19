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

    /** A GitHub answer that was not 2xx ({@code urllib.error.HTTPError}): the status, the URL and the body. */
    public static class GitHubHttpException extends RuntimeException {
        private final int code;
        private final String url;
        private final String body;

        public GitHubHttpException(int code, String url, String body) {
            super("HTTP " + code + " " + url);
            this.code = code;
            this.url = url;
            this.body = body == null ? "" : body;
        }

        public int code() {
            return code;
        }

        public String url() {
            return url;
        }

        public String body() {
            return body;
        }
    }

    /**
     * Reads from GitHub ({@code gh("GET", ...)}): read-only by construction, there is no other method. {@code path} is
     * the API path with its query ({@code /repos/o/r/pulls/3}). Returns the JSON answer (null or a missing node when
     * there is none); throws {@link GitHubHttpException} on a non-2xx answer.
     */
    public interface GitHubReads {
        tools.jackson.databind.JsonNode get(String path);
    }

    /** Writes to GitHub: the only way the core can merge. */
    public interface GitHubWrites {
        /**
         * Merge a PR with a merge commit ({@code gh("PUT", .../merge)}). {@code sha} (may be null) is the head the
         * merge is allowed for. Throws {@link GitHubHttpException} when GitHub refuses.
         */
        void merge(String repoSlug, int prNumber, String sha, String commitTitle);
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

        /** The follow-ups cancelled because this task did not end well; {@code automatic}: only those a merge may restart. */
        java.util.List<Task> blocked(Task task, boolean automatic);

        /** {@code revive_followups(t, automatic=True)}: back in the queue after a merge. Returns how many. */
        int reviveAfterMerge(Task task);
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

        /** {@code remember_task}: the history of a finished task (K5: after its PR was merged or closed). */
        default void remember(long taskId) {}

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
}
