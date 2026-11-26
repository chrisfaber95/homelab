package dev.homelab.inbox.door;

import dev.homelab.inbox.conversation.Clients;
import dev.homelab.inbox.conversation.ConversationDb;
import dev.homelab.inbox.conversation.ConversationStore;
import dev.homelab.inbox.core.ConnectionSource;
import dev.homelab.inbox.core.Effects.ConversationNotes;
import dev.homelab.inbox.core.Effects.Notifier;
import dev.homelab.inbox.core.TaskActions;
import dev.homelab.inbox.core.TaskStore;
import dev.homelab.inbox.door.Ports.Briefs;
import dev.homelab.inbox.door.Ports.Health;
import dev.homelab.inbox.door.Ports.JobsPort;
import dev.homelab.inbox.door.Ports.Logs;
import dev.homelab.inbox.door.Ports.OwnerCheck;
import dev.homelab.inbox.door.Ports.Peers;
import dev.homelab.inbox.door.Ports.Search;
import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.Settings;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

/**
 * Wires the chat actions, the kitt door, the chat door and the gate (H-026 K7) in shadow. Every part is an object that
 * does nothing until it is called, and nothing calls it: there is no route, no listener and no scheduled job here, so
 * the exposure of the box is what it was (the Python inbox serves the doors, the Python gate listens on 8121). The ports
 * to briefs, jobs and search are {@link ShadowPorts} unless another task's bean replaces them, and the effects are the
 * shadow effects of the core ({@code CoreConfig}): nothing is built, run, merged or sent.
 */
@Configuration
class DoorConfig {

    @Bean
    DoorSettings doorSettings(Settings conf) {
        return DoorSettings.from(conf);
    }

    @Bean
    @ConditionalOnMissingBean(Briefs.class)
    ShadowPorts shadowPorts() {
        return new ShadowPorts();
    }

    @Bean
    @ConditionalOnMissingBean(Logs.class)
    Logs doorLogs(Settings conf, JsonMapper json) {
        return FileViews.logs(conf, json);
    }

    @Bean
    @ConditionalOnMissingBean(Health.class)
    Health doorHealth(Settings conf, JsonMapper json) {
        return FileViews.health(conf, json);
    }

    @Bean
    @ConditionalOnMissingBean(OwnerCheck.class)
    TailscaleOwner tailscaleOwner(JsonMapper json, Clock clock) {
        return new TailscaleOwner(TailscaleOwner.cli(), json, clock); // runs nothing until the gate or the door asks
    }

    @Bean
    @ConditionalOnMissingBean(Peers.class)
    ProcPeers procPeers(DoorSettings door) {
        return ProcPeers.system(door.kittAccount());
    }

    @Bean
    ChatActions chatActions(TaskStore tasks, TaskActions taskActions, ConversationStore conversation, Clients clients,
                            ConversationNotes notes, Notifier notifier, Briefs briefs, JobsPort jobs, Jobs repos,
                            ConversationDb db, Clock clock, DoorSettings door) {
        return new ChatActions(tasks, taskActions, conversation, clients, notes, notifier, briefs, jobs,
                repos::repoNames, (ConnectionSource) db::open, clock, door);
    }

    @Bean
    KittDoor kittDoor(ChatActions chat, ConversationStore conversation, Clients clients, ConversationNotes notes,
                      TaskStore tasks, Briefs briefs, JobsPort jobs, Search search, Health health, Logs logs,
                      OwnerCheck owner, Peers peers, ExecutorService coreExecutor, Clock clock, DoorSettings door,
                      JsonMapper json) {
        return new KittDoor(chat, conversation, clients, notes, tasks, briefs, jobs, search, health, logs, owner, peers,
                coreExecutor, clock, door, json);
    }

    @Bean
    ChatDoor chatDoor(ChatActions chat, ConversationStore conversation, Clients clients, DoorSettings door,
                      JsonMapper json) {
        return new ChatDoor(chat, conversation, clients, door, json);
    }

    /** The gate object: it listens only when {@link KittGateStarter} calls {@code listen}, i.e. with kitt.gate.enabled. */
    @Bean
    KittGate kittGate(OwnerCheck owner, DoorSettings door, @Value("${kitt.gate.enabled:false}") boolean enabled) {
        return new KittGate(owner, door.kittUpstream(), enabled);
    }

    @Bean
    KittGateStarter kittGateStarter(KittGate gate, DoorSettings door,
                                    @Value("${kitt.gate.enabled:false}") boolean enabled) {
        return new KittGateStarter(gate, door.gateHost(), door.gatePort(), enabled, 30_000);
    }
}
