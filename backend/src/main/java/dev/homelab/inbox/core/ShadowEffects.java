package dev.homelab.inbox.core;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shadow-mode implementation of every {@link Effects} interface: nothing leaves the process. Each call is logged
 * and kept in {@link #calls()} (so a test, or a person looking at the log, can see what the core would have done).
 * It never starts a process, never writes to a file, GitHub, ntfy or the conversation.
 */
public class ShadowEffects implements Effects.RoundRunner, Effects.WorkingCopies, Effects.Notifier,
        Effects.GitHubWrites, Effects.TaskLog, Effects.ConversationNotes, Effects.Followups, Effects.JobIo {

    private static final Logger log = LoggerFactory.getLogger(ShadowEffects.class);
    private static final int KEEP = 1000;

    private final List<String> calls = new CopyOnWriteArrayList<>();

    /** What was asked, in order ("stop 7", "notify Inbox 7 failed", "merge owner/repo#3"), at most the last 1000. */
    public List<String> calls() {
        return List.copyOf(calls);
    }

    private void record(String what) {
        calls.add(what);
        if (calls.size() > KEEP) {
            calls.remove(0);
        }
        log.debug("shadow: {}", what);
    }

    @Override
    public void runRound(long taskId) {
        record("runRound " + taskId);
    }

    @Override
    public void stop(long taskId) {
        record("stop " + taskId);
    }

    @Override
    public void reviewAgain(long taskId) {
        record("reviewAgain " + taskId);
    }

    @Override
    public boolean has(long taskId) {
        record("has " + taskId);
        return false;
    }

    @Override
    public void cleanup(long taskId) {
        record("cleanup " + taskId);
    }

    @Override
    public boolean pushed(Task task) {
        record("pushed " + task.id());
        return false;
    }

    @Override
    public void notify(String title, String body, String url) {
        record("notify " + title);
    }

    @Override
    public void merge(String repoSlug, int prNumber) {
        record("merge " + repoSlug + "#" + prNumber);
    }

    @Override
    public void line(String key, String kind, String text) {
        record("log " + key + " " + kind + " " + text);
    }

    @Override
    public void note(String author, String client, String kind, String text, String link, String ref) {
        record("note " + kind + " " + ref);
    }

    @Override
    public void revive(Task task) {
        record("revive " + task.id());
    }

    @Override
    public void notify(String title, String body, String url, int limit) {
        record("notify " + title);
    }

    @Override
    public String boxOverview() throws java.io.IOException {
        record("boxOverview");
        return "";
    }

    @Override
    public String fetchPage(String url) throws java.io.IOException {
        record("fetchPage " + url);
        return "";
    }

    @Override
    public Object github(String path) throws java.io.IOException {
        record("github " + path);
        return List.of();
    }

    @Override
    public Effects.ScriptResult runScript(String repo, String script, List<String> args, long runId) {
        record("runScript " + repo + " " + script);
        return new Effects.ScriptResult(0, "");
    }

    @Override
    public String summarize(String jobName, String instruction, String text) throws java.io.IOException {
        record("summarize " + jobName);
        return "";
    }
}
