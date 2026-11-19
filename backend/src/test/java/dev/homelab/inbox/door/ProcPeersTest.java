package dev.homelab.inbox.door;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The peer check's own source: the kernel's socket table and the passwd file, read from files the test writes. */
class ProcPeersTest {

    static final String HEADER = "  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode\n";

    @TempDir
    Path dir;

    ProcPeers peers;

    @BeforeEach
    void setUp() throws Exception {
        Files.writeString(dir.resolve("passwd"), "root:x:0:0:root:/root:/bin/bash\nhomelab-app-kitt:x:61234:61234::/:/usr/sbin/nologin\n");
        // 127.0.0.1:50000 -> 127.0.0.1:8105 (0xC350 -> 0x1FA9) belongs to uid 61234; another connection to uid 1000
        Files.writeString(dir.resolve("tcp"), HEADER
                + "   0: 0100007F:C350 0100007F:1FA9 01 00000000:00000000 00:00000000 00000000 61234        0 1 1\n"
                + "   1: 0100007F:C351 0100007F:1FA9 01 00000000:00000000 00:00000000 00000000  1000        0 2 1\n"
                + "   2: 0100007F:1FA9 0100007F:C350 01 00000000:00000000 00:00000000 00000000     0        0 3 1\n");
        Files.writeString(dir.resolve("tcp6"), HEADER
                + "   0: 00000000000000000000000001000000:C352 00000000000000000000000001000000:1FA9 01 00:00 00:00 0 7777 0 4 1\n");
        peers = new ProcPeers(dir, dir.resolve("passwd"), "homelab-app-kitt");
    }

    @Test
    void theAccountIsLookedUpInPasswd() throws Exception {
        assertThat(peers.accountUid()).isEqualTo(OptionalLong.of(61234));
        assertThat(new ProcPeers(dir, dir.resolve("passwd"), "no-such").accountUid()).isEmpty();
        assertThat(new ProcPeers(dir, dir.resolve("missing"), "homelab-app-kitt").accountUid()).isEmpty();
    }

    @Test
    void theOwnerOfTheConnectionIsTheClientsSocketNotTheServers() {
        assertThat(peers.peerUid("127.0.0.1", 50000, 8105)).isEqualTo(OptionalLong.of(61234));
        assertThat(peers.peerUid("127.0.0.1", 50001, 8105)).isEqualTo(OptionalLong.of(1000));
        assertThat(peers.peerUid("::ffff:127.0.0.1", 50000, 8105)).isEqualTo(OptionalLong.of(61234));
        assertThat(peers.peerUid("::1", 50002, 8105)).isEqualTo(OptionalLong.of(7777)); // tcp6
        assertThat(peers.peerUid("127.0.0.1", 50000, 9999)).isEmpty(); // not a connection to our port
        assertThat(peers.peerUid("127.0.0.1", 40000, 8105)).isEmpty(); // not in the table
    }

    @Test
    void aRemoteAddressHasNoUid() {
        assertThat(peers.peerUid("100.1.2.3", 50000, 8105)).isEmpty();
        assertThat(peers.peerUid("192.168.1.5", 50000, 8105)).isEmpty();
        assertThat(peers.peerUid(null, 50000, 8105)).isEmpty();
        assertThat(ProcPeers.system("x").peerUid("100.1.2.3", 5555, 8105)).isEmpty();
    }

    @Test
    void anUnreadableTableIsNoUid() {
        assertThat(new ProcPeers(dir.resolve("nowhere"), dir.resolve("passwd"), "x").peerUid("127.0.0.1", 50000, 8105)).isEmpty();
    }
}
