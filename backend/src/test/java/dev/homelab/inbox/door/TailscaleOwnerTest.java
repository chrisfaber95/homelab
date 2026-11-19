package dev.homelab.inbox.door;

import static org.assertj.core.api.Assertions.assertThat;

import dev.homelab.inbox.door.Ports.Verdict;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The owner check the gate relies on, against what the Python {@code Access} allows: only the owner's own devices; the
 * box itself, an unknown peer and another tailnet user are not let in; a failing status call lets nobody in; lookups are
 * remembered for five minutes. The {@code tailscale} CLI is a function here.
 */
class TailscaleOwnerTest {

    static final String STATUS = """
            {"Self": {"UserID": 7, "TailscaleIPs": ["100.85.70.34", "fd7a::1"]},
             "User": {"7": {"LoginName": "chris@example.com"}}}""";

    final List<String> calls = new ArrayList<>();
    DoorFixture.MutableClock clock;
    TailscaleOwner owner;

    private Function<List<String>, String> cli(String status) {
        return args -> {
            calls.add(String.join(" ", args));
            if (args.get(0).equals("status")) {
                if (status == null) {
                    throw new IllegalStateException("tailscale is down");
                }
                return status;
            }
            return switch (args.get(2)) {
                case "100.64.0.2" -> "{\"UserProfile\": {\"LoginName\": \"chris@example.com\"}, \"Node\": {\"ComputedName\": \"phone\"}}";
                case "100.64.0.3" -> "{\"UserProfile\": {\"LoginName\": \"someone@else.com\"}, \"Node\": {\"ComputedName\": \"laptop\"}}";
                case "100.64.0.4" -> "{\"UserProfile\": {\"LoginName\": \"chris@example.com\"}, \"Node\": {}}";
                default -> throw new IllegalStateException("not on this tailnet");
            };
        };
    }

    @BeforeEach
    void setUp() {
        clock = new DoorFixture.MutableClock("2026-11-18T10:00:00Z");
        owner = new TailscaleOwner(cli(STATUS), DoorFixture.JSON, clock);
        owner.init();
    }

    @Test
    void theOwnersDevicesPassAndOthersDoNot() {
        assertThat(owner.owner()).isEqualTo("chris@example.com");
        assertThat(owner.allowed("100.64.0.2")).isEqualTo(new Verdict(true, "phone"));
        assertThat(owner.allowed("100.64.0.3")).isEqualTo(new Verdict(false, "laptop"));
        assertThat(owner.allowed("100.64.0.4")).isEqualTo(new Verdict(true, "100.64.0.4")); // no device name: the address
        assertThat(owner.allowed("192.168.1.9").ok()).isFalse(); // the LAN: whois does not know it
        assertThat(owner.allowed("100.64.0.99").ok()).isFalse(); // unknown peer
    }

    @Test
    void theBoxItselfIsNeverLetIn() {
        for (String own : List.of("100.85.70.34", "fd7a::1", "127.0.0.1", "::1")) {
            assertThat(owner.allowed(own).ok()).as(own).isFalse();
        }
        assertThat(calls).containsExactly("status --json"); // no lookup was needed
    }

    @Test
    void withoutAnOwnerNobodyIsLetIn() {
        TailscaleOwner broken = new TailscaleOwner(cli(null), DoorFixture.JSON, clock);
        broken.init();
        assertThat(broken.owner()).isNull();
        assertThat(broken.allowed("100.64.0.2").ok()).isFalse();
        TailscaleOwner garbage = new TailscaleOwner(cli("{\"Self\": {}}"), DoorFixture.JSON, clock);
        garbage.init();
        assertThat(garbage.allowed("100.64.0.2").ok()).isFalse();
        TailscaleOwner never = new TailscaleOwner(cli(STATUS), DoorFixture.JSON, clock); // init was never called
        assertThat(never.allowed("100.64.0.2").ok()).isFalse();
    }

    @Test
    void aLookupIsRememberedForFiveMinutes() {
        owner.allowed("100.64.0.2");
        owner.allowed("100.64.0.2");
        assertThat(calls.stream().filter(c -> c.startsWith("whois")).count()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(301));
        owner.allowed("100.64.0.2");
        assertThat(calls.stream().filter(c -> c.startsWith("whois")).count()).isEqualTo(2);
    }

    @Test
    void thereIsNoAllowAllSwitch() {
        // The Python inbox has INBOX_ALLOW_ALL=1 for its tests; the Java check reads no environment at all.
        assertThat(owner.allowed("192.168.1.9").ok()).isFalse();
        assertThat(System.getenv("INBOX_ALLOW_ALL")).isNull();
    }
}
