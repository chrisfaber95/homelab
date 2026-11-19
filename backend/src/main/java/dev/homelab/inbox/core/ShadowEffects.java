package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.ResearchRun;
import dev.homelab.inbox.core.Effects.Result;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The shadow-mode implementation of every {@link Effects} interface: nothing leaves the process. Each call is logged
 * and kept in {@link #calls()} (so a test, or a person looking at the log, can see what the core would have done).
 * It never starts a process, never writes to a file, GitHub, ntfy or the conversation.
 */
public class ShadowEffects implements Effects.RoundRunner, Effects.WorkingCopies, Effects.Notifier,
        Effects.GitHubWrites, Effects.TaskLog, Effects.ConversationNotes, Effects.Followups, Effects.TaskCommands,
        Effects.Pushes, Effects.PullRequests, Effects.Research, Effects.AfterEffects, Effects.MemorySteps,
        Effects.Repos, Effects.Sessions, Effects.JobIo {

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
    public void notify(String title, String body, String url, boolean store) {
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
    public Result prepare(long taskId, String repo) {
        record("prepare " + taskId + " " + repo);
        return new Result(0, "");
    }

    @Override
    public Result behind(long taskId) {
        record("behind " + taskId);
        return new Result(1, "");
    }

    @Override
    public Result sync(long taskId) {
        record("sync " + taskId);
        return new Result(0, "");
    }

    @Override
    public List<String> push(long taskId, String cache) {
        record("push " + taskId);
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

    @Override
    public Opened open(String repoSlug, String title, String head, String base, String body) {
        record("open pr " + repoSlug + " " + head);
        return new Opened(0, "");
    }

    @Override
    public Optional<ResearchRun> runOf(Task task) {
        return Optional.empty();
    }

    @Override
    public void deliver(Task task, String text, String link) {
        record("deliver " + task.id());
    }

    @Override
    public String jobInstruction(String job) {
        return null;
    }

    @Override
    public void reviewPr(long taskId, String cache) {
        record("reviewPr " + taskId);
    }

    @Override
    public void proposeSkill(Task task, String name, String description, String body) {
        record("proposeSkill " + task.id() + " " + name);
    }

    @Override
    public void load() {
        record("memory load");
    }

    @Override
    public MemorySplit split(String text) {
        return new MemorySplit(text, List.of());
    }

    @Override
    public void afterRound(long taskId, List<String> blocks) {
        record("afterRound " + taskId);
    }

    @Override
    public Map<String, String> list() {
        return Map.of();
    }

    @Override
    public String refresh(String repo, String slug) {
        record("refresh " + repo);
        return "/dev/null";
    }

    @Override
    public void writePrompt(Task task, String prompt) {
        record("writePrompt " + task.id());
    }

    @Override
    public Outcome stream(long taskId, String mode) {
        record("stream " + taskId + " " + mode);
        return new Outcome("", 0, null);
    }

    @Override
    public void route(long taskId) {
        record("route " + taskId);
    }

    @Override
    public void plan(long taskId) {
        record("plan " + taskId);
    }
}
