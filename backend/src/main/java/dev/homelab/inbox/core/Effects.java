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
}
