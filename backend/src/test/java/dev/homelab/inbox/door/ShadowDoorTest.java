package dev.homelab.inbox.door;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.homelab.inbox.InboxBackendApplication;
import dev.homelab.inbox.core.CoreSettings;
import dev.homelab.inbox.core.Effects;
import dev.homelab.inbox.core.Scheduler;
import dev.homelab.inbox.core.ShadowEffects;
import dev.homelab.inbox.door.Ports.Briefs;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Acceptance: the Java door runs against a copy of the data with the scheduler off and never starts a real task, merge
 * or notification, and the exposure of the box is what it was: no route, no listener, no gate on the tailnet address.
 */
class ShadowDoorTest {

    DoorFixture f;

    @BeforeEach
    void setUp() throws Exception {
        f = new DoorFixture();
        f.reset(ChatActionsTest.CLIENTS);
    }

    @Test
    void theApplicationWiresTheDoorInShadowAndOpensNoListener() throws Exception {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(InboxBackendApplication.class)
                .web(WebApplicationType.NONE).properties("backend.secret=x").run()) {
            assertThat(ctx.getBean(ChatActions.class)).isNotNull();
            assertThat(ctx.getBean(KittDoor.class)).isNotNull();
            assertThat(ctx.getBean(ChatDoor.class)).isNotNull();
            assertThat(ctx.getBean(Briefs.class)).isInstanceOf(ShadowPorts.class);
            assertThat(ctx.getBean(Effects.Notifier.class)).isInstanceOf(ShadowEffects.class);
            assertThat(ctx.getBean(Effects.ConversationNotes.class)).isInstanceOf(ShadowEffects.class);
            KittGate gate = ctx.getBean(KittGate.class);
            assertThat(gate.isListening()).isFalse();
            assertThat(ctx.getBeanNamesForType(DoorHttp.class)).isEmpty();
            // even asked to, the shadow gate does not take the tailnet address of the Python gate
            assertThat(gate.listen("100.85.70.34", 8121)).isEmpty();
            assertThat(gate.isListening()).isFalse();
            assertThat(ctx.getBean(DoorSettings.class).gatePort()).isEqualTo(8121);
            assertThat(ctx.getBean(DoorSettings.class).kittAccount()).isEqualTo("homelab-app-kitt");
        }
    }

    @Test
    void withTheShadowPortsNothingIsBuiltRunOrAsked() throws Exception {
        ShadowPorts ports = new ShadowPorts();
        ChatActions chat = new ChatActions(f.tasks, f.taskActions, f.store, f.clients, f.notes, f.notified, ports, ports,
                () -> Set.of("homelab"), f.db, f.clock, f.door);
        assertThat(chat.approvalRequest("discord", DoorFixture.BID, "build").status()).isEqualTo(409);
        assertThat(chat.runJob("discord", "nightly").status()).isEqualTo(404);
        assertThat(chat.drafts("discord").body().get("briefs")).isEqualTo(List.of());
        assertThrows(IllegalStateException.class, () -> ports.startBuild(DoorFixture.BID, true, "Chris", "discord"));
        assertThrows(IllegalStateException.class, () -> ports.dispatch("nightly"));
        assertThrows(Ports.Unavailable.class, () -> ports.pause("nightly", true, "m"));
        assertThat(ports.research("q", "quick", "discord").status()).isEqualTo(503);
        assertThat(ports.ask("q").status()).isEqualTo(502);
        assertThat(f.count("SELECT COUNT(*) FROM tasks")).isZero();
    }

    @Test
    void aWholeDayOfActionsStartsNoRoundMergeOrNotification() throws Exception {
        f.setClients("{\"clients\": {\"discord\": {\"permissions\": [\"read\", \"start_task\", \"approve\", \"run_job\"]},"
                + " \"kitt\": {\"permissions\": [\"read\", \"start_task\", \"approve\"]}}}");
        f.jobs.jobs.put("demo", new Ports.JobInfo("demo", null, "script", "", "daily", "local", "page", false, null));
        String token = (String) f.chat.approvalRequest("discord", DoorFixture.BID, "without-me").get("confirm");
        assertThat(f.chat.approvalConfirm("discord", token).status()).isEqualTo(200);
        assertThat(f.chat.startTask("discord", "homelab", "fix it").status()).isEqualTo(200);
        assertThat(f.kitt.route("POST", "/api/conversation", Map.of(),
                DoorFixture.JSON.readTree("{\"kind\":\"task\",\"text\":\"from kitt\"}")).status()).isEqualTo(200);
        assertThat(f.chat.runJob("discord", "demo").status()).isEqualTo(200);
        assertThat(f.count("SELECT COUNT(*) FROM tasks WHERE status='queued'")).isEqualTo(3);
        Scheduler scheduler = new Scheduler(f.tasks, CoreSettings.shadowDefaults(), f.shadow, f.shadow, Runnable::run);
        assertThat(scheduler.tick()).isEmpty();
        assertThat(f.count("SELECT COUNT(*) FROM tasks WHERE status='queued'")).isEqualTo(3);
        assertThat(f.shadow.calls()).noneMatch(c -> c.startsWith("runRound") || c.startsWith("merge")
                || c.startsWith("stop") || c.startsWith("notify"));
        assertThat(f.notified.calls()).hasSize(1).allMatch(c -> c.startsWith("notify "));
    }
}
