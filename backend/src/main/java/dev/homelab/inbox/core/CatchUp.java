package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.Result;
import dev.homelab.inbox.core.Effects.TaskCommands;
import dev.homelab.inbox.core.Effects.TaskLog;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Merge main into the branch of a task that is about to continue without a PR ({@code queue.catch_up_main}). A task
 * with a PR is covered by GitHub polling (K5), which reports the conflict and merges main in with its own round limit.
 * Without a PR nothing noticed, so a retried or continued task would carry on from the main it started with. Returns the
 * text added to the prompt (empty when nothing was done).
 */
public class CatchUp {

    static final String MERGE_MAIN_BY_HAND = "    git -c safe.directory='*' fetch --upload-pack=\"git -c "
            + "safe.directory='*' upload-pack\" origin main\n    git merge origin/main";

    private final TaskCommands commands;
    private final TaskLog taskLog;

    public CatchUp(TaskCommands commands, TaskLog taskLog) {
        this.commands = commands;
        this.taskLog = taskLog;
    }

    public String catchUp(Task t) {
        if (t.has("pr_number")) {
            return "";
        }
        long id = t.id();
        if (commands.behind(id).code() != 0) {
            return ""; // 1: the branch already has main. 2: no working copy, so prepare made a fresh one from main.
        }
        Result sync = commands.sync(id);
        if (sync.code() == 0) {
            taskLog.line("t" + id, "info", "merged main into inbox/" + id + " before this round");
            return "\n\nMain moved on since your last round and has been merged into your branch, so work of other "
                    + "tasks is in your working copy now. Build on it instead of doing it again.";
        }
        if (sync.code() == 4) {
            List<String> files = Arrays.stream(sync.out().split("\n")).filter(f -> !f.isBlank()).toList();
            taskLog.line("t" + id, "info", "main conflicts with inbox/" + id + " in " + files.size() + " file(s)");
            return "\n\nMain moved on since your last round, and `git merge origin/main` is in progress in your working "
                    + "copy with conflicts in:\n\n" + files.stream().map(f -> "- " + f).collect(Collectors.joining("\n"))
                    + "\n\nResolve them so that both your work and the work on main keep working, commit the merge on "
                    + "the same branch, and then do what is asked above.";
        }
        String out = sync.out().strip();
        String why = out.isEmpty() ? "exit " + sync.code() : out.substring(Math.max(0, out.length() - 200));
        taskLog.line("t" + id, "error", "could not merge main into inbox/" + id + ": " + why);
        return "\n\nMain moved on since your last round and merging it in here failed (" + why + "), so your working "
                + "copy is older than main. Your origin is the service's copy and git refuses a repository of another "
                + "account, so a plain git fetch does not work. Merge main yourself with:\n\n" + MERGE_MAIN_BY_HAND;
    }
}
