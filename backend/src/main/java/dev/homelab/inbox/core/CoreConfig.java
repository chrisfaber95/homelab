package dev.homelab.inbox.core;

import dev.homelab.inbox.conversation.ConversationDb;
import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.Effects.Followups;
import dev.homelab.inbox.core.Effects.RoundRunner;
import dev.homelab.inbox.core.Effects.TaskLog;
import dev.homelab.inbox.core.Effects.WorkingCopies;
import dev.homelab.inbox.read.Settings;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the inbox core (H-026 K1) in shadow: the effects are {@link ShadowEffects} unless another bean replaces them,
 * and the scheduler loop ({@link SchedulerLoop}) exists only when {@code core.scheduler.enabled=true}. Nothing here
 * opens a database connection at start.
 */
@Configuration
class CoreConfig {

    @Bean
    CoreSettings coreSettings(Settings conf, @Value("${core.scheduler.enabled:false}") boolean enabled) {
        return new CoreSettings(enabled, conf.confInt("MAX_RUNNING", 4), conf.confInt("MAX_PER_REPO", 3),
                conf.confInt("MAX_TURNS", 80), conf.confInt("MAX_MINUTES", 30), conf.confInt("CAPPED_KEEP_DAYS", 7), 2);
    }

    /**
     * The one shadow implementation of every effect interface. A later task that adds a real implementation of an
     * interface replaces this bean (and must then provide all of them), so shadow and real are never mixed by accident.
     */
    @Bean
    @ConditionalOnMissingBean(RoundRunner.class)
    ShadowEffects shadowEffects() {
        return new ShadowEffects();
    }

    @Bean
    TaskStore taskStore(ConversationDb db, Clock clock) {
        return new TaskStore(db::open, clock);
    }

    @Bean
    Cancellations cancellations() {
        return new Cancellations();
    }

    @Bean(destroyMethod = "shutdown")
    java.util.concurrent.ExecutorService coreExecutor() {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "core-round");
            t.setDaemon(true);
            return t;
        });
    }

    @Bean
    Scheduler scheduler(TaskStore store, CoreSettings settings, RoundRunner runner, TaskLog log,
                        java.util.concurrent.ExecutorService coreExecutor) {
        return new Scheduler(store, settings, runner, log, (Executor) coreExecutor);
    }

    @Bean
    Recovery recovery(TaskStore store, CoreSettings settings, RoundRunner runner, TaskLog log) {
        return new Recovery(store, settings, runner, log);
    }

    @Bean
    TaskActions taskActions(TaskStore store, CoreSettings settings, Cancellations cancellations, RoundRunner runner,
                            WorkingCopies copies, TaskLog log, ConversationNotes notes, Followups followups,
                            java.util.concurrent.ExecutorService coreExecutor, Clock clock, Settings conf) {
        String base = "http://" + conf.conf("TAILNET_NAME", "homelab") + ":" + conf.conf("INBOX_PORT", "8105");
        return new TaskActions(store, settings, cancellations, runner, copies, log, notes, followups, coreExecutor,
                clock, base);
    }
}
