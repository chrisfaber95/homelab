package dev.homelab.inbox.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * A throwaway PostgreSQL cluster for tests (Q5, no Testcontainers): {@code initdb} and {@code pg_ctl} from the
 * installed PostgreSQL, in a temp directory, listening on a Unix socket in that directory only (no TCP).
 * Binaries come from {@code PG_BIN}, else the newest {@code /usr/lib/postgresql/<n>/bin}.
 */
final class ThrowawayPostgres implements AutoCloseable {

    private static final int PORT = 54329; // only names the socket file (.s.PGSQL.<port>); nothing listens on TCP
    private final Path root;
    private final Path data;
    private final Path bin;

    private ThrowawayPostgres(Path root, Path bin) {
        this.root = root;
        this.data = root.resolve("data");
        this.bin = bin;
    }

    static ThrowawayPostgres start() throws Exception {
        Path bin = findBin();
        // Short path: a Unix socket path is limited to about 100 bytes.
        Path root = Files.createTempDirectory("pgtest");
        ThrowawayPostgres pg = new ThrowawayPostgres(root, bin);
        try {
            pg.run("initdb", "-D", pg.data.toString(), "-U", "postgres", "--auth=trust", "--encoding=UTF8",
                    "--locale=C.UTF-8", "-N");
            pg.run("pg_ctl", "-D", pg.data.toString(), "-w", "-t", "60", "-l", root.resolve("log").toString(),
                    "-o", "-c listen_addresses='' -c unix_socket_directories='" + root + "' -c port=" + PORT
                            + " -c fsync=off", "start");
        } catch (Exception e) {
            pg.close();
            throw e;
        }
        return pg;
    }

    /** pgjdbc URL over the Unix socket (junixsocket). */
    String jdbcUrl() {
        return "jdbc:postgresql://localhost/postgres?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg"
                + "&socketFactoryArg=" + root.resolve(".s.PGSQL." + PORT);
    }

    String user() {
        return "postgres";
    }

    @Override
    public void close() throws IOException {
        try {
            run("pg_ctl", "-D", data.toString(), "-m", "immediate", "-w", "stop");
        } catch (Exception e) {
            // Not started (or already gone): only the directory is left to remove.
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private void run(String tool, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(bin.resolve(tool).toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(120, TimeUnit.SECONDS) || p.exitValue() != 0) {
            p.destroyForcibly();
            throw new IllegalStateException(tool + " failed:\n" + out);
        }
    }

    private static Path findBin() throws IOException {
        String env = System.getenv("PG_BIN");
        if (env != null && !env.isBlank()) {
            return Path.of(env);
        }
        Path base = Path.of("/usr/lib/postgresql");
        if (Files.isDirectory(base)) {
            try (Stream<Path> versions = Files.list(base)) {
                var found = versions.map(v -> v.resolve("bin"))
                        .filter(b -> Files.isExecutable(b.resolve("pg_ctl")) && Files.isExecutable(b.resolve("initdb")))
                        .max(Comparator.comparing(b -> Integer.parseInt(
                                b.getParent().getFileName().toString().replaceAll("\\D.*", "0"))));
                if (found.isPresent()) {
                    return found.get();
                }
            }
        }
        throw new IllegalStateException("No PostgreSQL binaries (initdb, pg_ctl): install postgresql or set PG_BIN");
    }
}
