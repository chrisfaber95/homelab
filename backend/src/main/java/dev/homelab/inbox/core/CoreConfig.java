package dev.homelab.inbox.core;

import dev.homelab.inbox.conversation.ConversationDb;
import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.Effects.AfterEffects;
import dev.homelab.inbox.core.Effects.MemorySteps;
import dev.homelab.inbox.core.Effects.Notifier;
import dev.homelab.inbox.core.Effects.PullRequests;
import dev.homelab.inbox.core.Effects.Pushes;
import dev.homelab.inbox.core.Effects.Repos;
import dev.homelab.inbox.core.Effects.Research;
import dev.homelab.inbox.core.Effects.Sessions;
import dev.homelab.inbox.core.Effects.TaskCommands;
import dev.homelab.inbox.core.Effects.Followups;
import dev.homelab.inbox.core.Effects.JobIo;
import dev.homelab.inbox.core.Effects.Notifier;
import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.core.Effects.RoundRunner;
import dev.homelab.inbox.core.Effects.TaskLog;
import dev.homelab.inbox.core.Effects.WorkingCopies;
import dev.homelab.inbox.read.Settings;
import java.time.Clock;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the inbox core (H-026 K1): the effects are {@link ShadowEffects} unless {@code core.effects=live} wires
 * {@link LiveEffects} (H-028 W1), and the scheduler loop ({@link SchedulerLoop}) exists only when {@code core.scheduler.enabled=true}. Nothing here
 * opens a database connection at start.
 */
@Configuration
class CoreConfig {

    /**
     * core.effects chooses the Effects wired in: shadow (default) or live; anything else is a configuration error. Checked
     * before any bean is made, so the error is this one whatever bean the container happens to create first.
     */
    @Bean
    static org.springframework.beans.factory.config.BeanFactoryPostProcessor effectsValueCheck() {
        return beanFactory -> {
            String effects = beanFactory.resolveEmbeddedValue("${core.effects:shadow}");
            if (!"shadow".equals(effects) && !"live".equals(effects)) {
                throw new IllegalStateException("core.effects must be shadow or live, not '" + effects + "'");
            }
        };
    }

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
    @ConditionalOnProperty(name = "core.effects", havingValue = "shadow", matchIfMissing = true)
    @ConditionalOnMissingBean(RoundRunner.class)
    ShadowEffects shadowEffects() {
        return new ShadowEffects();
    }

    @Bean
    @ConditionalOnMissingBean(CommandRunner.class)
    CommandRunner commandRunner() {
        return new ProcessCommandRunner();
    }

    /** The real effects: only with {@code core.effects=live}. The beans these effects call back into are looked up late. */
    @Bean
    @ConditionalOnProperty(name = "core.effects", havingValue = "live")
    LiveEffects liveEffects(CommandRunner commands, Settings conf, TaskStore store,
                            dev.homelab.inbox.conversation.ConversationStore conversation,
                            org.springframework.beans.factory.ObjectProvider<RoundFlow> flow,
                            org.springframework.beans.factory.ObjectProvider<Router> router,
                            org.springframework.beans.factory.ObjectProvider<BriefRounds> briefRounds,
                            org.springframework.beans.factory.ObjectProvider<JobEngine> engine, Jobs jobs,
                            dev.homelab.inbox.memory.MemoryRepo memoryRepo, dev.homelab.inbox.memory.MemoryWriter memoryWriter,
                            dev.homelab.inbox.read.MemoryReader memoryReader, Cancellations cancellations,
                            CoreSettings settings) {
        Effects.Research[] research = new Effects.Research[1];
        LiveEffects.Late late = new LiveEffects.Late() {
            @Override
            public RoundFlow flow() {
                return flow.getObject();
            }

            @Override
            public Router router() {
                return router.getObject();
            }

            @Override
            public BriefRounds briefRounds() {
                return briefRounds.getObject();
            }

            @Override
            public Effects.Research research() {
                if (research[0] == null) {
                    research[0] = new JobResearch(engine.getObject(), jobs::load);
                }
                return research[0];
            }
        };
        return new LiveEffects(commands, conf, store,
                (author, client, kind, text, link, ref) -> conversation.append(author, client, kind, text, link, ref), late,
                cancellations, settings, new MemoryDoor(memoryRepo, memoryWriter, memoryReader), jobs::load);
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
                            WorkingCopies copies, TaskLog log, ConversationNotes notes, FollowupChain followups,
                            java.util.concurrent.ExecutorService coreExecutor, Clock clock, Settings conf) {
        String base = "http://" + conf.conf("TAILNET_NAME", "homelab") + ":" + conf.conf("INBOX_PORT", "8105");
        return new TaskActions(store, settings, cancellations, runner, copies, log, notes, followups, coreExecutor,
                clock, base);
    }

    /** The follow-up chain and the router (K3): nothing calls them while the scheduler is off. */
    @Bean
    FollowupChain followupChain(TaskStore store, CoreSettings settings, WorkingCopies copies, TaskLog log,
                                Notifier notifier, Effects.TaskHistory history, Settings conf) {
        return new FollowupChain(store, settings, copies, log, notifier, history, pageBase(conf));
    }

    @Bean
    Router router(TaskStore store, Repos repos, Effects.RepoProfiles profiles, Effects.RouteClaude claude,
                  Notifier notifier, ConversationNotes notes, TaskLog log, Cancellations cancellations, Settings conf) {
        return new Router(store, repos, profiles, claude, notifier, notes, log, cancellations,
                conf.confInt("ROUTE_MAX_CHILDREN", 3), pageBase(conf));
    }

    @Bean
    JobSettings jobSettings(Settings conf, CoreSettings core) {
        return new JobSettings(conf.confInt("JOB_CLAUDE_CAP", 10), conf.confInt("RESEARCH_QUICK_MINUTES", 6),
                conf.confInt("RESEARCH_QUICK_TURNS", 25), core.maxMinutes(), core.maxTurns(), JobSettings.LOCAL_MAX_CHARS,
                conf.tz());
    }

    @Bean
    JobRuns jobRuns(ConversationDb db) {
        return new JobRuns(db::open);
    }

    /** Jobs and research in shadow (K6): the engine exists, nothing calls it, and its ticks do nothing while the
     * scheduler is off. */
    @Bean
    JobEngine jobEngine(JobRuns runs, TaskStore store, CoreSettings core, JobSettings settings, Jobs jobs, JobIo io,
                        Notifier notifier, TaskLog log, ConversationNotes notes, WorkingCopies copies, Clock clock,
                        java.util.concurrent.ExecutorService coreExecutor, Settings conf) {
        String base = "http://" + conf.conf("TAILNET_NAME", "homelab") + ":" + conf.conf("INBOX_PORT", "8105");
        return new JobEngine(runs, store, core, settings, jobs::load, io, notifier, log, notes, copies,
                new Cancellations(), coreExecutor, clock, base);
    }

    @Bean
    AgentCatalogue agentCatalogue(Settings conf, Jobs jobs, JobRuns runs, TaskStore store) {
        return new AgentCatalogue(conf.skillsDir().resolveSibling("agents"), jobs, runs, store);
    }

    @Bean
    JobPages jobPages(JobEngine engine, JobRuns runs, JobSettings settings, Jobs jobs, AgentCatalogue catalogue, Clock clock) {
        return new JobPages(engine, runs, settings, jobs::load, catalogue, jobs, clock);
    }

    @Bean
    CatchUp catchUp(TaskCommands commands, TaskLog log) {
        return new CatchUp(commands, log);
    }

    @Bean
    RoundFinisher roundFinisher(TaskStore store, WorkingCopies copies, Notifier notifier, ConversationNotes notes,
                                Pushes pushes, PullRequests pullRequests, Research research, AfterEffects after,
                                Repos repos, java.util.concurrent.ExecutorService coreExecutor, Clock clock,
                                Settings conf) {
        return new RoundFinisher(store, copies, notifier, notes, pushes, pullRequests, research, after, repos,
                coreExecutor, clock, pageBase(conf));
    }

    @Bean
    RoundFlow roundFlow(TaskStore store, Repos repos, MemorySteps memory, TaskCommands commands, WorkingCopies copies,
                        Sessions sessions, CatchUp catchUp, RoundFinisher finisher, TaskLog log, Notifier notifier,
                        Settings conf) {
        return new RoundFlow(store, repos, memory, commands, copies, sessions, catchUp, finisher, log, notifier,
                pageBase(conf));
    }

    @Bean
    Briefs briefs(TaskStore store, Effects.ProductRepo product, Effects.BriefGitHub github, Repos repos,
                  Effects.NewRepos newRepos, TaskLog log, Clock clock, Settings conf) {
        Briefs.Settings s = new Briefs.Settings(conf.conf("PRODUCT_REPO", "chrisfaber95/product"),
                conf.conf("OWNER_GITHUB", "chrisfaber95"), conf.confInt("PLAN_MAX_TASKS", 6),
                conf.confInt("MAX_TURNS", 80), conf.confInt("MAX_MINUTES", 30), conf.confInt("REVIEW_MAX_DIFF", 60000),
                conf.confInt("REVIEW_REWORK_ROUNDS", 1));
        return new Briefs(store, product, github, repos, newRepos, log, s, clock);
    }

    /** Planner, reviewer and handoff of the briefs: not called by anything while the scheduler is off. */
    @Bean
    BriefRounds briefRounds(TaskStore store, Briefs briefs, Repos repos, Effects.RepoProfiles profiles,
                            Effects.BriefClaude claude, Effects.Diffs diffs, Effects.BriefGitHub github,
                            PullRequests pullRequests, Notifier notifier, ConversationNotes notes, TaskLog log,
                            Cancellations cancellations, Settings conf) {
        return new BriefRounds(store, briefs, repos, profiles, claude, diffs, github, pullRequests, notifier, notes, log,
                cancellations, pageBase(conf));
    }

    @Bean
    GitHubPoller gitHubPoller(TaskStore store, CoreSettings settings, Effects.GitHubReads reads,
                              Effects.GitHubWrites writes, Notifier notifier, TaskLog log, WorkingCopies copies,
                              Effects.TaskHistory history, FollowupChain followups, Repos repos,
                              TaskCommands commands, Pushes pushes, Research research, Clock clock, Settings conf) {
        String owner = System.getenv("OWNER_LOGIN") != null ? System.getenv("OWNER_LOGIN")
                : conf.conf("OWNER_GITHUB", "chrisfaber95");
        return new GitHubPoller(store, settings, new GitHubPoller.GitHubSettings(owner, conf.confInt("CONFLICT_ROUNDS", 2)),
                reads, writes, notifier, log, copies, history, followups, repos, commands, pushes, research, clock,
                pageBase(conf));
    }

    /** The data of the task pages (H-028 W2a): read through the store, the follow-up chain and the briefs. */
    @Bean
    TaskPages taskPages(TaskStore store, FollowupChain chain, Briefs briefs, Settings conf, CoreSettings settings, Jobs jobs,
                        tools.jackson.databind.json.JsonMapper json) {
        return new TaskPages(store, chain, briefs, new TaskLogs(conf, json), name -> {
            Jobs.Job job = jobs.load().get(name);
            return job == null ? null : job.kind();
        }, settings, conf.tz().toString());
    }

    /** The data of the Briefs pages (H-028 W2c). */
    @Bean
    BriefPages briefPages(Briefs briefs, TaskStore store, Settings conf) {
        return new BriefPages(briefs, store, conf.tz().toString(), TaskActions.STATUS_NL::get);
    }

    private static String pageBase(Settings conf) {
        return "http://" + conf.conf("TAILNET_NAME", "homelab") + ":" + conf.conf("INBOX_PORT", "8105");
    }
}
