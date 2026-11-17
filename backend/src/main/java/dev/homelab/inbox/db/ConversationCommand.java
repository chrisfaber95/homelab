package dev.homelab.inbox.db;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code java -jar inbox-backend.jar import-conversation --sqlite <inbox.db> [--if-not-imported] [--replace]} and
 * {@code export-conversation --sqlite <inbox.db>} (H-024 M4): the fresh import into, and the rollback export out of,
 * the conversation store. Run by hand or by scripts/27-backend.sh as inbox-runner; no Spring context, no web server.
 * Exit code 0 = done (or, with {@code --if-not-imported}, already imported), 1 = failed (rolled back), 2 = refused or bad
 * usage (nothing written).
 */
public final class ConversationCommand {

    public static final String IMPORT = "import-conversation";
    public static final String EXPORT = "export-conversation";

    private static final String USAGE = """
            usage: import-conversation --sqlite <inbox.db> [--if-not-imported] [--replace] [connection options]
                   export-conversation --sqlite <inbox.db> [connection options]
            connection options: [--socket-dir /var/run/postgresql] [--port 5432] [--database inbox] [--user inbox_app]
                                [--url <jdbc url>]   (replaces socket-dir, port and database)
            --if-not-imported  exit 0 and do nothing when the store is already Java's (used by scripts/27-backend.sh)
            --replace          overwrite Java's data with SQLite's: only to redo the import before the switch
            """;

    private ConversationCommand() {
    }

    public static int run(String command, String[] args, PrintStream out, PrintStream err) {
        String sqlite = null;
        String socketDir = "/var/run/postgresql";
        String port = "5432";
        String database = "inbox";
        String user = "inbox_app";
        String url = null;
        boolean ifNot = false;
        boolean replace = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--if-not-imported" -> ifNot = true;
                case "--replace" -> replace = true;
                case "--sqlite", "--socket-dir", "--port", "--database", "--user", "--url" -> {
                    if (i + 1 >= args.length) {
                        err.print("missing value for " + args[i] + "\n" + USAGE);
                        return 2;
                    }
                    String v = args[++i];
                    switch (args[i - 1]) {
                        case "--sqlite" -> sqlite = v;
                        case "--socket-dir" -> socketDir = v;
                        case "--port" -> port = v;
                        case "--database" -> database = v;
                        case "--user" -> user = v;
                        default -> url = v;
                    }
                }
                default -> {
                    err.print("unknown option " + args[i] + "\n" + USAGE);
                    return 2;
                }
            }
        }
        boolean isImport = IMPORT.equals(command);
        if (sqlite == null || (ifNot && replace) || (!isImport && (ifNot || replace))) {
            err.print(USAGE);
            return 2;
        }
        if (isImport && !Files.isReadable(Path.of(sqlite))) {
            err.println("cannot read " + sqlite);
            return 2;
        }
        if (!isImport && !Files.isWritable(Path.of(sqlite))) {
            err.println("cannot write " + sqlite + " (run as inbox-runner, with the Python inbox stopped)");
            return 2;
        }
        if (url == null) {
            url = "jdbc:postgresql://localhost/" + database + "?socketFactory="
                    + "org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg="
                    + Path.of(socketDir, ".s.PGSQL." + port);
        }
        try {
            var counts = isImport ? ConversationImporter.importFrom(sqlite, url, user, replace)
                    : ConversationImporter.exportTo(sqlite, url, user);
            out.println((isImport ? "import" : "export") + " ok: conversation=" + counts.conversation()
                    + " conversation_refusals=" + counts.refusals() + " switches=" + counts.switches() + " (counts equal)");
            return 0;
        } catch (ConversationImporter.AlreadyImported e) {
            if (ifNot) {
                out.println("already imported: " + e.getMessage());
                return 0;
            }
            err.println("REFUSED: " + e.getMessage() + " (--replace overwrites Java's data; only before the switch)");
            return 2;
        } catch (Exception e) {
            err.println((isImport ? "IMPORT" : "EXPORT") + " FAILED, rolled back: " + e.getMessage());
            return 1;
        }
    }
}
