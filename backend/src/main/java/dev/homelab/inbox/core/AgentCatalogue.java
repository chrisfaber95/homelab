package dev.homelab.inbox.core;

import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.Jobs.Job;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The agent catalogue (H-017) in Java (H-026 K6): the job files in {@code agents/} of the homelab repo (the service's copy
 * of main). A broken file never hides the others; switching an agent on only makes a ready draft, nothing is saved or
 * run until the user confirms the draft.
 */
public class AgentCatalogue {

    /** One catalogue entry: {@code job} is set when the file parses (also for a {@code fill} entry), {@code error} when
     * it does not, {@code fill} when only a field the user fills in is missing. */
    public record Agent(String name, String text, boolean on, Job job, String error, String fill) {}

    private static final Pattern NAME = Pattern.compile("^name:\\s*([a-z0-9][a-z0-9-]*)\\s*$", Pattern.MULTILINE);

    private final Path dir;
    private final Jobs jobs;
    private final JobRuns runs;
    private final TaskStore tasks;

    public AgentCatalogue(Path dir, Jobs jobs, JobRuns runs, TaskStore tasks) {
        this.dir = dir;
        this.jobs = jobs;
        this.runs = runs;
        this.tasks = tasks;
    }

    /** One entry per file; {@code existing} are the names of the jobs that exist ({@code on}). */
    public List<Agent> load(Set<String> existing) {
        List<Agent> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        Set<String> repos = jobs.repoNames();
        Set<String> skills = jobs.skillNames();
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(f -> f.getFileName().toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list " + dir, e);
        }
        for (Path f : files) {
            String file = f.getFileName().toString();
            String name = file.substring(0, file.length() - 3);
            try {
                String text = Files.readString(f);
                boolean on = existing.contains(name);
                try {
                    out.add(new Agent(name, text, on, jobs.parse(name, text, repos, skills), null, null));
                } catch (Jobs.NeedsInput e) {
                    out.add(new Agent(name, text, on, e.job(), null, e.getMessage()));
                } catch (IllegalArgumentException e) {
                    out.add(new Agent(name, text, on, null, e.getMessage(), null));
                }
            } catch (IOException e) {
                out.add(new Agent(name, "", existing.contains(name), null, e.getMessage(), null));
            }
        }
        return out;
    }

    /** The job file shown when switching an agent on: the catalogue file with {@code name:} in its header. */
    static String jobText(Agent agent) {
        return !draftName(agent.text()).isEmpty() ? agent.text() : "---\nname: " + agent.name() + "\n" + agent.text().substring(4);
    }

    /** Makes a ready draft of a catalogue agent; the draft id, or empty for an unknown or broken agent. */
    public Optional<Long> switchOn(String name, Set<String> existing) {
        Optional<Agent> agent = load(existing).stream().filter(a -> a.name().equals(name)).findFirst();
        if (agent.isEmpty() || agent.get().error() != null) {
            return Optional.empty();
        }
        return Optional.of(runs.insertReadyDraft(tasks.now(), "Agent aanzetten: " + name, jobText(agent.get())));
    }

    public static String draftName(String text) {
        int close = text.indexOf("\n---");
        Matcher m = NAME.matcher(close < 0 ? text : text.substring(0, close));
        return m.find() ? m.group(1) : "";
    }

    public Map<String, Agent> byName(Set<String> existing) {
        Map<String, Agent> out = new java.util.LinkedHashMap<>();
        load(existing).forEach(a -> out.put(a.name(), a));
        return out;
    }
}
