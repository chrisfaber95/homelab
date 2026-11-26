package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.homelab.inbox.InboxBackendApplication;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** {@code core.effects} chooses the Effects; the scheduler loop starts only with live effects and the scheduler on. */
class EffectsSwitchTest {

    /** Every command of the application goes to this fake; nothing here starts a real process. */
    private final List<String> commands = new CopyOnWriteArrayList<>();

    private ConfigurableApplicationContext start(String... args) {
        CommandRunner fake = c -> {
            commands.add(String.join(" ", c.args()));
            return new CommandRunner.Finished(1, "", "fake", false);
        };
        return new SpringApplicationBuilder(InboxBackendApplication.class).web(WebApplicationType.NONE)
                .initializers(ctx -> ((org.springframework.context.support.GenericApplicationContext) ctx).getBeanFactory()
                        .registerSingleton("commandRunner", fake))
                .properties("backend.secret=x", "inbox.config-dir=/nonexistent").run(args);
    }

    @Test
    void theRealCommandRunnerRefusesToRunInTheSuite() {
        assertThatThrownBy(() -> new ProcessCommandRunner().run(CommandRunner.Command.of("true")))
                .hasMessageContaining("not allowed in tests");
    }

    @Test
    void mayStartOnlyForLiveWithTheSchedulerOn() {
        assertThat(SchedulerLoop.mayStart("live", true)).isTrue();
        assertThat(SchedulerLoop.mayStart("live", false)).isFalse();
        assertThat(SchedulerLoop.mayStart("shadow", true)).isFalse();
        assertThat(SchedulerLoop.mayStart("shadow", false)).isFalse();
    }

    @Test
    void shadowIsTheDefault() {
        try (ConfigurableApplicationContext ctx = start()) {
            assertThat(ctx.getBean(Effects.RoundRunner.class)).isExactlyInstanceOf(ShadowEffects.class);
            assertThat(ctx.getBeanNamesForType(SchedulerLoop.class)).isEmpty();
        }
    }

    @Test
    void shadowAndSchedulerOffHasNoLoop() {
        try (ConfigurableApplicationContext ctx = start("--core.effects=shadow", "--core.scheduler.enabled=false")) {
            assertThat(ctx.getBean(Effects.Notifier.class)).isExactlyInstanceOf(ShadowEffects.class);
            assertThat(ctx.getBeanNamesForType(SchedulerLoop.class)).isEmpty();
        }
    }

    @Test
    void liveWithTheSchedulerOffWiresLiveEffectsButNoLoop() {
        try (ConfigurableApplicationContext ctx = start("--core.effects=live", "--core.scheduler.enabled=false")) {
            assertThat(ctx.getBean(Effects.RoundRunner.class)).isExactlyInstanceOf(LiveEffects.class);
            assertThat(ctx.getBean(Effects.GitHubWrites.class)).isExactlyInstanceOf(LiveEffects.class);
            assertThat(ctx.getBean(Effects.Repos.class)).isExactlyInstanceOf(LiveEffects.class);
            assertThat(ctx.getBeanNamesForType(SchedulerLoop.class)).isEmpty();
            assertThat(commands).isEmpty();
        }
    }

    @Test
    void shadowWithTheSchedulerOnRefusesToStart() {
        assertThatThrownBy(() -> start("--core.effects=shadow", "--core.scheduler.enabled=true").close())
                .hasStackTraceContaining("needs a real RoundRunner");
    }

    @Test
    void liveWithTheSchedulerOnStartsTheLoop() {
        try (ConfigurableApplicationContext ctx = start("--core.effects=live", "--core.scheduler.enabled=true")) {
            assertThat(ctx.getBeanNamesForType(SchedulerLoop.class)).hasSize(1);
        }
    }

    @Test
    void anUnknownEffectsValueRefusesToStart() {
        assertThatThrownBy(() -> start("--core.effects=bogus").close()).hasStackTraceContaining("shadow or live");
    }
}
