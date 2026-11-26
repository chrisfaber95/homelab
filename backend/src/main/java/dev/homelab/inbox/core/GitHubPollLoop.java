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
 * Calls {@link GitHubPoller#poll()} every {@code GITHUB_POLL_SECONDS}. Exists only with
 * {@code core.scheduler.enabled=true} (default off) and refuses to start while GitHub writes are the shadow ones.
 */
@Component
@ConditionalOnProperty(name = "core.scheduler.enabled", havingValue = "true")
class GitHubPollLoop {

    private static final Logger log = LoggerFactory.getLogger(GitHubPollLoop.class);
    private final GitHubPoller poller;
    private final long seconds;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    GitHubPollLoop(GitHubPoller poller, Effects.GitHubWrites writes, Effects.GitHubReads reads,
                   @Value("${core.github.poll-seconds:60}") long seconds) {
        if (writes.getClass() == ShadowEffects.class || reads.getClass() == ShadowEffects.class) {
            throw new IllegalStateException("core.scheduler.enabled=true needs a real RoundRunner and GitHub client (reads and "
                    + "writes): set core.effects=live");
        }
        this.poller = poller;
        this.seconds = seconds;
    }

    @PostConstruct
    void start() {
        timer.scheduleWithFixedDelay(this::pass, seconds, seconds, TimeUnit.SECONDS);
    }

    private void pass() {
        try {
            poller.poll();
        } catch (RuntimeException e) {
            log.error("github poll: {}", e.toString());
        }
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
    }
}
