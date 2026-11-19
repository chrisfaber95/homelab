package dev.homelab.inbox.door;

import dev.homelab.inbox.door.Ports.Peers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

/**
 * Who is on the other end of a loopback connection, from the kernel's tables and not from anything the caller sends
 * ({@code kitt.peer_uid} and {@code kitt.account_uid}): the owner of the socket in {@code /proc/net/tcp} and
 * {@code tcp6}, and the user id of kitt's service account in the passwd file. A local process cannot choose its user id.
 */
public final class ProcPeers implements Peers {

    private final Path procNet;
    private final Path passwd;
    private final String account;

    public ProcPeers(Path procNet, Path passwd, String account) {
        this.procNet = procNet;
        this.passwd = passwd;
        this.account = account;
    }

    public static ProcPeers system(String account) {
        return new ProcPeers(Path.of("/proc/net"), Path.of("/etc/passwd"), account);
    }

    /** The user id of kitt's account (a systemd DynamicUser, resolved while the unit runs), or empty. */
    @Override
    public OptionalLong accountUid() {
        try {
            for (String line : Files.readAllLines(passwd)) {
                String[] f = line.split(":");
                if (f.length > 2 && f[0].equals(account)) {
                    return OptionalLong.of(Long.parseLong(f[2]));
                }
            }
        } catch (IOException | NumberFormatException e) {
            // an unreadable passwd file has no account: the door stays shut
        }
        return OptionalLong.empty();
    }

    /** The user id that owns the loopback connection from {@code ip:port} to our {@code serverPort}; empty if not found. */
    @Override
    public OptionalLong peerUid(String ip, int port, int serverPort) {
        String plain = ip == null ? "" : ip.startsWith("::ffff:") ? ip.substring(7) : ip;
        if (!plain.equals("127.0.0.1") && !plain.equals("::1")) {
            return OptionalLong.empty();
        }
        for (String table : List.of("tcp", "tcp6")) {
            List<String> rows;
            try {
                rows = Files.readAllLines(procNet.resolve(table));
            } catch (IOException e) {
                continue;
            }
            for (String row : rows.subList(Math.min(1, rows.size()), rows.size())) {
                String[] f = row.trim().split("\\s+");
                try {
                    if (f.length > 7 && portOf(f[1]) == port && portOf(f[2]) == serverPort) {
                        return OptionalLong.of(Long.parseLong(f[7]));
                    }
                } catch (NumberFormatException e) {
                    // a row we cannot read is not the one
                }
            }
        }
        return OptionalLong.empty();
    }

    private static int portOf(String address) {
        return Integer.parseInt(address.substring(address.lastIndexOf(':') + 1), 16);
    }
}
