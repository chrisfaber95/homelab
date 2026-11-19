package dev.homelab.inbox.door;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.door.DoorFixture.Reply;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Ports the {@code Door} class of {@code tests/test_inbox_chat.py}: {@code POST /api/chat/action} with a bearer token. */
class ChatDoorTest {

    DoorFixture f;
    DoorHttp http;

    @BeforeEach
    void setUp() throws Exception {
        f = new DoorFixture();
        f.reset(ChatActionsTest.CLIENTS);
        http = new DoorHttp(f.kitt, f.chatDoor, DoorFixture.JSON);
    }

    @AfterEach
    void tearDown() {
        http.close();
    }

    Reply call(String token, String body) throws Exception {
        return DoorFixture.call(http.port(), "POST", "/api/chat/action", body, Map.of("Authorization", "Bearer " + token));
    }

    @Test
    void aTaskThroughTheDoor() throws Exception {
        Reply r = call("tok-discord", "{\"action\":\"task\",\"repo\":\"auto\",\"text\":\"do it\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(f.tasks.get(r.body().path("task").asLong()).orElseThrow().str("source")).isEqualTo("discord");
        assertThat(call("tok-reader", "{\"action\":\"task\",\"repo\":\"auto\",\"text\":\"x\"}").status()).isEqualTo(403);
        assertThat(call("nope", "{\"action\":\"state\"}").status()).isEqualTo(401);
    }

    @Test
    void twoStepsThroughTheDoor() throws Exception {
        Reply r = call("tok-discord", "{\"action\":\"approve\",\"brief\":\"" + DoorFixture.BID + "\",\"mode\":\"build\"}");
        assertThat(r.status()).isEqualTo(200);
        assertThat(f.briefs.briefs.get(DoorFixture.BID).status()).isEqualTo("draft");
        Reply confirmed = call("tok-discord", "{\"action\":\"confirm\",\"confirm\":\"" + r.body().path("confirm").asString() + "\"}");
        assertThat(confirmed.status()).isEqualTo(200);
        assertThat(f.briefs.briefs.get(DoorFixture.BID).status()).isEqualTo("approved");
    }

    @Test
    void theForbiddenActionsThroughTheDoor() throws Exception {
        for (String action : List.of("ssh_key", "memory_edit", "merge_homelab")) {
            assertThat(call("tok-discord", "{\"action\":\"" + action + "\"}").status()).isEqualTo(403);
        }
        assertThat(call("tok-discord", "{\"action\":\"unknown\"}").status()).isEqualTo(400);
        assertThat(f.store.refusals(10)).extracting(r -> r.get("action")).contains("ssh_key", "memory_edit", "merge_homelab");
    }

    @Test
    void theDiscordSwitchOffBlocksTheDoor() throws Exception {
        f.store.setSwitch("discord", false);
        Reply r = call("tok-discord", "{\"action\":\"state\"}");
        assertThat(r.status()).isEqualTo(403);
        assertThat(r.body().path("discord").asBoolean()).isFalse(); // the state comes with the refusal
        assertThat(f.store.refusals(10)).isEmpty(); // a stopped client polls: not logged as a refusal
    }

    @Test
    void anUnknownTokenIsRefusedAndLogged() throws Exception {
        assertThat(call("", "{\"action\":\"state\"}").status()).isEqualTo(401);
        assertThat(call("tok-discord ", "{\"action\":\"state\"}").status()).isEqualTo(200); // surrounding space is ignored
        assertThat(f.store.refusals(10)).extracting(r -> r.get("action")).containsExactly("authenticate");
    }

    @Test
    void aTokenNeedsItsOwnClientsFileEntry() throws Exception {
        f.setClients("{\"clients\": {\"reader\": {\"permissions\": [\"read\"]}}}"); // discord is no longer a client
        assertThat(call("tok-discord", "{\"action\":\"state\"}").status()).isEqualTo(401);
        assertThat(call("tok-reader", "{\"action\":\"state\"}").status()).isEqualTo(200);
    }

    @Test
    void aBadRequestIsA400AndOnlyPostIsAllowed() throws Exception {
        assertThat(call("tok-discord", "not json").status()).isEqualTo(400);
        assertThat(call("tok-discord", "[]").status()).isEqualTo(400);
        assertThat(call("tok-discord", "{}").status()).isEqualTo(400);
        Reply get = DoorFixture.call(http.port(), "GET", "/api/chat/action", null, Map.of("Authorization", "Bearer tok-discord"));
        assertThat(get.status()).isEqualTo(405);
    }

    @Test
    void theDoorOnlyAnswersLoopbackAndOnlyItsOwnPath() {
        Req remote = new Req("POST", "/api/chat/action", Map.of(), Map.of("Authorization", "Bearer tok-discord"),
                "{\"action\":\"state\"}".getBytes(), "100.1.2.3", 5555, 8105);
        assertThat(f.chatDoor.handle(remote)).get().extracting(Result::status, Result::error)
                .containsExactly(403, "loopback only");
        Req mapped = new Req("POST", "/api/chat/action", Map.of(), Map.of("Authorization", "Bearer tok-discord"),
                "{\"action\":\"state\"}".getBytes(), "::ffff:127.0.0.1", 5555, 8105);
        assertThat(f.chatDoor.handle(mapped).orElseThrow().status()).isEqualTo(200);
        Req entries = new Req("GET", "/api/chat/entries", Map.of(), Map.of(), new byte[0], "127.0.0.1", 5555, 8105);
        assertThat(f.chatDoor.handle(entries)).isEmpty(); // the entries and state routes are the conversation controller's
    }
}
