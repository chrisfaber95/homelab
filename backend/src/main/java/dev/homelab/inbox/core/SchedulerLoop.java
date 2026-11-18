package dev.homelab.inbox.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The thread that calls the scheduler every 3 seconds and sweeps old working copies hourly, after one recovery at start.
 * It exists only with {@code core.scheduler.enabled=true} (default off). It refuses to start on the shadow effects:
 * a loop that marks tasks running and then runs nothing would strand them, and until the real effects exist (K2 and
 * later) switching the scheduler on is a configuration error, not a way to run tasks.
 */
@Component
@ConditionalOnProperty(name = "core.scheduler.enabled", havingValue = "true")
class SchedulerLoop {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLoop.class);
    private final Scheduler scheduler;
    private final Recovery recovery;
    private final TaskActions actions;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private long lastSweep;

    SchedulerLoop(Scheduler scheduler, Recovery recovery, TaskActions actions, Effects.RoundRunner runner) {
        if (runner instanceof ShadowEffects) {
            throw new IllegalStateException("core.scheduler.enabled=true needs a real RoundRunner; only the shadow "
                    + "one exists (the real effects come with H-026 K2 and later)");
        }
        this.scheduler = scheduler;
        this.recovery = recovery;
        this.actions = actions;
    }

    @PostConstruct
    void start() {
        timer.execute(recovery::recover);
        timer.scheduleWithFixedDelay(this::pass, 3, 3, TimeUnit.SECONDS);
    }

    private void pass() {
        try {
            if (System.nanoTime() - lastSweep > TimeUnit.HOURS.toNanos(1) || lastSweep == 0) {
                lastSweep = System.nanoTime();
                actions.sweepCapped();
            }
            scheduler.tick();
        } catch (RuntimeException e) {
            log.error("queue: {}", e.toString());
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }
}
