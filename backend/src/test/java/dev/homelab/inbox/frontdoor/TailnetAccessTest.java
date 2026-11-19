package dev.homelab.inbox.frontdoor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** {@code Access} in {@code access.py}: the owner of the box on the tailnet, and nobody else. */
class TailnetAccessTest {

    static final String OWNER = "chris@example.com";
    static final String STATUS = """
            {"Self": {"UserID": 42, "TailscaleIPs": ["100.85.70.34", "fd7a::1"]},
             "User": {"42": {"LoginName": "chris@example.com"}}}""";

    static String whois(String login, String device) {
        return "{\"UserProfile\": {\"LoginName\": \"" + login + "\"}, \"Node\": {\"ComputedName\": \"" + device + "\"}}";
    }

    final List<String> asked = new ArrayList<>();
    final Map<String, String> answers = new java.util.HashMap<>();
    final AtomicLong now = new AtomicLong();
    String status = STATUS;
    boolean whoisFails;

    final Tailscale tailscale = new Tailscale() {
        @Override
        public String status() throws IOException {
            if (status == null) {
                throw new IOException("tailscale: not found");
            }
            return status;
        }

        @Override
        public String whois(String ip) throws IOException {
            asked.add(ip);
            if (whoisFails || !answers.containsKey(ip)) {
                throw new IOException("whois failed");
            }
            return answers.get(ip);
        }
    };

    TailnetAccess access;

    @BeforeEach
    void setUp() {
        access = new TailnetAccess(tailscale, JsonMapper.builder().build(), now::get, false);
        access.init();
    }

    @Test
    void initLearnsTheOwnerAndTheBoxsOwnAddresses() {
        assertThat(access.owner()).isEqualTo(OWNER);
        for (String own : new String[] {"100.85.70.34", "fd7a::1", "127.0.0.1", "::1", "::ffff:127.0.0.1"}) {
            assertThat(access.allowed(own).allowed()).as(own).isFalse();
            assertThat(access.allowed(own).who()).isEqualTo("niet vanaf de box zelf");
        }
        assertThat(asked).isEmpty();
    }

    @Test
    void theOwnersDeviceGetsInAndIsNamed() {
        answers.put("100.64.0.7", whois(OWNER, "phone"));
        assertThat(access.allowed("100.64.0.7")).isEqualTo(new TailnetAccess.Decision(true, "phone"));
        assertThat(access.allowed("::ffff:100.64.0.7").allowed()).isTrue();  // an IPv4 peer on a dual-stack socket
    }

    @Test
    void someoneElsesDeviceIsRefused() {
        answers.put("100.64.0.8", whois("friend@example.com", "laptop"));
        assertThat(access.allowed("100.64.0.8")).isEqualTo(new TailnetAccess.Decision(false, "laptop"));
    }

    @Test
    void anAddressThatTailscaleDoesNotKnowIsRefused() {  // the LAN, the internet
        assertThat(access.allowed("192.168.1.20")).isEqualTo(new TailnetAccess.Decision(false, "192.168.1.20"));
        whoisFails = true;
        answers.put("100.64.0.7", whois(OWNER, "phone"));
        assertThat(access.allowed("100.64.0.7").allowed()).isFalse();
    }

    @Test
    void withoutAKnownOwnerNobodyGetsIn() {
        status = null;
        TailnetAccess blind = new TailnetAccess(tailscale, JsonMapper.builder().build(), now::get, false);
        blind.init();
        answers.put("100.64.0.7", whois(OWNER, "phone"));
        assertThat(blind.owner()).isNull();
        assertThat(blind.allowed("100.64.0.7").allowed()).isFalse();
        assertThat(asked).isEmpty();
    }

    @Test
    void aBrokenStatusLeavesTheDoorShut() {
        status = "not json";
        TailnetAccess blind = new TailnetAccess(tailscale, JsonMapper.builder().build(), now::get, false);
        blind.init();
        assertThat(blind.allowed("100.64.0.7").allowed()).isFalse();
    }

    @Test
    void anAnswerIsKeptForFiveMinutes() {
        answers.put("100.64.0.7", whois(OWNER, "phone"));
        access.allowed("100.64.0.7");
        now.set(299_000_000_000L);
        access.allowed("100.64.0.7");
        assertThat(asked).hasSize(1);
        now.set(301_000_000_000L);
        access.allowed("100.64.0.7");
        assertThat(asked).hasSize(2);
    }

    @Test
    void aFailedLookupIsKeptToo() {  // as in Python: (None, None) is cached, so a dead tailscale is not asked every request
        whoisFails = true;
        access.allowed("100.64.0.7");
        access.allowed("100.64.0.7");
        assertThat(asked).hasSize(1);
    }

    @Test
    void allowAllIsOnlyForTests() {
        TailnetAccess open = new TailnetAccess(tailscale, JsonMapper.builder().build(), now::get, true);
        assertThat(open.allowed("192.168.1.20")).isEqualTo(new TailnetAccess.Decision(true, "test"));
        assertThat(open.allowed("127.0.0.1").allowed()).isTrue();
    }

    @Test
    void setIdentityReplacesWhatInitLearned() {
        access.setIdentity("other@example.com", Set.of("10.0.0.1"));
        answers.put("100.64.0.7", whois(OWNER, "phone"));
        assertThat(access.allowed("100.64.0.7").allowed()).isFalse();
        assertThat(access.allowed("10.0.0.1").allowed()).isFalse();
    }
}
