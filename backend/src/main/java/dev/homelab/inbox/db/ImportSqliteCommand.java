package dev.homelab.inbox.db;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code java -jar inbox-backend.jar import-sqlite --sqlite <inbox.db>}: the one-off import of H-023 B5, run by hand
 * (no Spring context, no web server). Exit code 0 = imported and counts equal, 1 = failed or count mismatch (rolled
 * back), 2 = target not empty or bad usage (nothing written).
 */
public final class ImportSqliteCommand {

    public static final String NAME = "import-sqlite";

    private static final String USAGE = """
            usage: import-sqlite --sqlite <inbox.db> [--socket-dir /var/run/postgresql] [--port 5432]
                                 [--database inbox] [--user inbox_app]
                                 [--url <jdbc url>]   (replaces socket-dir, port and database)
            """;

    private ImportSqliteCommand() {
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        String sqlite = null;
        String socketDir = "/var/run/postgresql";
        String port = "5432";
        String database = "inbox";
        String user = "inbox_app";
        String url = null;
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                err.print("missing value for " + args[i] + "\n" + USAGE);
                return 2;
            }
            String v = args[i + 1];
            switch (args[i]) {
                case "--sqlite" -> sqlite = v;
                case "--socket-dir" -> socketDir = v;
                case "--port" -> port = v;
                case "--database" -> database = v;
                case "--user" -> user = v;
                case "--url" -> url = v;
                default -> {
                    err.print("unknown option " + args[i] + "\n" + USAGE);
                    return 2;
                }
            }
        }
        if (sqlite == null) {
            err.print(USAGE);
            return 2;
        }
        if (!Files.isReadable(Path.of(sqlite))) {
            err.println("cannot read " + sqlite);
            return 2;
        }
        if (url == null) {
            url = "jdbc:postgresql://localhost/" + database + "?socketFactory="
                    + "org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg="
                    + Path.of(socketDir, ".s.PGSQL." + port);
        }
        try {
            var counts = new SqliteImporter(sqlite, url, user, null).run();
            out.println("import ok: all counts equal");
            counts.sqlite().forEach((t, n) -> out.println(String.format("  %-22s sqlite=%d postgres=%d", t, n,
                    counts.postgres().get(t))));
            return 0;
        } catch (SqliteImporter.TargetNotEmptyException e) {
            err.println("REFUSED: " + e.getMessage());
            return 2;
        } catch (Exception e) {
            err.println("IMPORT FAILED: " + e.getMessage());
            return 1;
        }
    }
}
