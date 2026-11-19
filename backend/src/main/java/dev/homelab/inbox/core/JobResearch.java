package dev.homelab.inbox.core;

import dev.homelab.inbox.core.Effects.Research;
import dev.homelab.inbox.core.Effects.ResearchRun;
import dev.homelab.inbox.read.Jobs.Job;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What {@link RoundFinisher} (K2) asks about jobs and research, answered from the job tables ({@code jobs.research_run},
 * {@code job_of_task}, {@code deliver_result}). Not a bean: the shadow wiring keeps {@link ShadowEffects} as the one
 * {@link Research}; this replaces it at the cut-over (H-027).
 */
public class JobResearch implements Research {

    private final JobEngine engine;
    private final Supplier<Map<String, Job>> jobs;

    public JobResearch(JobEngine engine, Supplier<Map<String, Job>> jobs) {
        this.engine = engine;
        this.jobs = jobs;
    }

    @Override
    public Optional<ResearchRun> runOf(Task task) {
        return engine.researchRun(task).map(r -> new ResearchRun(((Number) r.get("id")).longValue(), (String) r.get("mode"),
                (String) r.get("created")));
    }

    @Override
    public void deliver(Task task, String text, String link) {
        engine.deliverResult(engine.jobOfTask(task).orElse(null), task.longOrNull("run_id"), text, link);
    }

    @Override
    public String jobInstruction(String job) {
        Job j = jobs.get().get(job);
        return j == null || !j.ok() ? null : j.instruction();
    }
}
