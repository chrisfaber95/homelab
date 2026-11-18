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
        void notify(String title, String body, String url);

        /** As {@code helpers.notify(..., limit=n)}: the push message is cut at {@code limit} characters (the real
         * implementation overrides this; the default only cuts the text). */
        default void notify(String title, String body, String url, int limit) {
            notify(title, body.length() > limit ? body.substring(0, limit) : body, url);
        }
    }

    /** What a job run reads from the outside world (K6): the box overview, web pages, GitHub (read only), a script of
     * a repo and the local model. In shadow mode none of it is called for real. */
    public interface JobIo {
        /** Output of {@code homelab-overview}. */
        String boxOverview() throws java.io.IOException;

        /** The raw text of a web page or feed (at most 2 MB). */
        String fetchPage(String url) throws java.io.IOException;

        /** A GET on the GitHub API; the parsed JSON (a list or a map). */
        Object github(String path) throws java.io.IOException;

        /** Runs a script of a repo in a working copy; exit code 65 means it did not run. */
        ScriptResult runScript(String repo, String script, java.util.List<String> args, long runId)
                throws java.io.IOException;

        /** The local model summarises {@code text} following the job's instruction. */
        String summarize(String jobName, String instruction, String text) throws java.io.IOException;
    }

    public record ScriptResult(int code, String output) {}

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
}
