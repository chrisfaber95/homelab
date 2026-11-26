package dev.homelab.inbox.core;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The thread that calls the scheduler every 3 seconds and sweeps old working copies hourly, after one recovery at start.
 * It exists only with {@code core.scheduler.enabled=true} (default off). It starts only when {@code core.effects=live}
 * as well (H-028 W1): with the shadow effects a loop that marks tasks running and then runs nothing would strand them,
 * so the combination refuses to start.
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

    /** The one rule: the loop runs only with real effects and the scheduler switched on. */
    static boolean mayStart(String effects, boolean schedulerEnabled) {
        return "live".equals(effects) && schedulerEnabled;
    }

    SchedulerLoop(Scheduler scheduler, Recovery recovery, TaskActions actions, Effects.RoundRunner runner,
                  @Value("${core.effects:shadow}") String effects,
                  @Value("${core.scheduler.enabled:false}") boolean enabled) {
        if (!mayStart(effects, enabled) || runner.getClass() == ShadowEffects.class) {
            throw new IllegalStateException("core.scheduler.enabled=true needs a real RoundRunner: set core.effects=live "
                    + "(now '" + effects + "'); the scheduler starts only with both");
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
