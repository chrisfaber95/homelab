package dev.homelab.inbox.door;

import dev.homelab.inbox.door.Ports.Brief;
import dev.homelab.inbox.door.Ports.Briefs;
import dev.homelab.inbox.door.Ports.JobInfo;
import dev.homelab.inbox.door.Ports.JobsPort;
import dev.homelab.inbox.door.Ports.RunInfo;
import dev.homelab.inbox.door.Ports.Search;
import dev.homelab.inbox.door.Ports.Unavailable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The shadow implementations of the ports the door needs from other tasks (briefs K4, jobs K6, the private search):
 * they start nothing, merge nothing and call nothing. Briefs and jobs are empty and a build, a job run or a research
 * question is refused; every request is remembered in {@link #calls()} for a test or a person looking.
 */
public class ShadowPorts implements Briefs, JobsPort, Search {

    private final List<String> calls = new CopyOnWriteArrayList<>();

    public List<String> calls() {
        return List.copyOf(calls);
    }

    @Override
    public Optional<Brief> byId(String id) {
        calls.add("brief " + id);
        return Optional.empty();
    }

    @Override
    public List<Brief> all() {
        return List.of();
    }

    @Override
    public boolean liveBuild(String id) {
        return false;
    }

    @Override
    public long startBuild(String id, boolean unattended, String who, String source) {
        calls.add("startBuild " + id);
        throw new IllegalStateException("the Java core is in shadow: it starts no build");
    }

    @Override
    public Map<String, JobInfo> jobs() {
        return Map.of();
    }

    @Override
    public List<RunInfo> runs(String name, int limit) {
        return List.of();
    }

    @Override
    public Optional<RunInfo> run(long id) {
        return Optional.empty();
    }

    @Override
    public long dispatch(String name) {
        calls.add("dispatch " + name);
        throw new IllegalStateException("the Java core is in shadow: it starts no job");
    }

    @Override
    public void pause(String name, boolean paused, String message) {
        calls.add((paused ? "pause " : "resume ") + name);
        throw new Unavailable("the Java core is in shadow: it changes no job file");
    }

    @Override
    public Result research(String question, String mode, String source) {
        calls.add("research " + mode);
        return Result.error(503, "the Java core is in shadow: it starts no research run");
    }

    @Override
    public Result ask(String question) {
        calls.add("ask");
        return Result.error(502, "the private search is not wired in the shadow door");
    }
}
