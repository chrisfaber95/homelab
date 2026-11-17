package dev.homelab.inbox.db;

import dev.homelab.inbox.memory.MemoryRepo;
import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.MemoryReader;
import dev.homelab.inbox.read.Settings;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code java -jar inbox-backend.jar import-memory [--if-not-imported] [--replace]} and {@code export-memory}
 * (H-024 M5): the fresh import of the memory store into the Java backend, and its rollback. The store is the clone
 * {@code memory-repo} of the {@code memory} branch of DATA_REPO. Import = bring the clone exactly to GitHub (what the Python
 * inbox would load), count the entries, and leave the marker that makes Java its only writer. Export = check that nothing
 * written by Java is missing on GitHub, bring the clone to GitHub and remove the marker, so the old Python code can own it again.
 * Run as inbox-runner with the Python inbox stopped (scripts/memory-import.sh does that). No Spring context.
 * Exit code 0 = done (or already imported with {@code --if-not-imported}), 1 = failed, 2 = refused or bad usage.
 */
public final class MemoryCommand {

    public static final String IMPORT = "import-memory";
    public static final String EXPORT = "export-memory";

    private static final String USAGE = """
            usage: import-memory [--if-not-imported] [--replace] [--config-dir <dir>]
                   export-memory [--config-dir <dir>]
            Reads the same settings as the service: INBOX_CONFIG_DIR (or --config-dir), INBOX_RUNNER_HOME, REMOTE_HOMELAB_DATA.
            --if-not-imported  exit 0 and do nothing when the store is already Java's
            --replace          import again although the marker is there (resets the clone to GitHub)
            """;

    private MemoryCommand() {
    }

    public static int run(String command, String[] args, PrintStream out, PrintStream err) {
        return run(command, args, out, err, System.getenv());
    }

    /** {@code env} is the process environment; the tests pass their own. */
    public static int run(String command, String[] args, PrintStream out, PrintStream err, java.util.Map<String, String> env) {
        boolean ifNot = false;
        boolean replace = false;
        String configDir = env.get("INBOX_CONFIG_DIR");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--if-not-imported" -> ifNot = true;
                case "--replace" -> replace = true;
                case "--config-dir" -> {
                    if (i + 1 >= args.length) {
                        err.print(USAGE);
                        return 2;
                    }
                    configDir = args[++i];
                }
                default -> {
                    err.print("unknown option " + args[i] + "\n" + USAGE);
                    return 2;
                }
            }
        }
        boolean isImport = IMPORT.equals(command);
        if ((ifNot && replace) || (!isImport && (ifNot || replace))) {
            err.print(USAGE);
            return 2;
        }
        Settings settings = new Settings(Path.of(configDir == null ? "/usr/local/lib/homelab/config" : configDir), env);
        MemoryRepo repo = new MemoryRepo(settings);
        MemoryReader reader = new MemoryReader(settings, new Jobs(settings, tools.jackson.databind.json.JsonMapper.builder().build()));
        try {
            if (isImport) {
                if (repo.owned()) {
                    if (ifNot) {
                        out.println("already imported: " + repo.marker());
                        return 0;
                    }
                    if (!replace) {
                        err.println("REFUSED: the memory store is already Java's (" + repo.marker() + "); --replace imports again, "
                                + "only before the switch");
                        return 2;
                    }
                }
                int before = count(reader);
                repo.sync();
                int after = count(reader);
                Files.writeString(repo.marker(), "imported " + java.time.Instant.now() + " entries=" + after + "\n");
                out.println("import ok: memory entries=" + after + " (before the sync " + before + "), branch=" + repo.branch()
                        + ", Java is the only writer now");
                return 0;
            }
            if (!repo.owned()) {
                err.println("REFUSED: the memory store is not Java's (no " + repo.marker() + "); nothing to export");
                return 2;
            }
            // Everything Java committed must be on GitHub: the sync that follows resets the clone to it.
            String ahead = unpushed(repo);
            if (!ahead.isEmpty()) {
                err.println("EXPORT REFUSED: the clone has commits GitHub does not have, a sync would lose them:\n" + ahead);
                return 1;
            }
            repo.sync();
            Files.deleteIfExists(repo.marker());
            out.println("export ok: memory entries=" + count(reader) + " (equal to GitHub), the marker is removed; "
                    + "the Python code owns the store again");
            return 0;
        } catch (Exception e) {
            err.println((isImport ? "IMPORT" : "EXPORT") + " FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int count(MemoryReader reader) {
        return reader.countEntries();
    }

    private static String unpushed(MemoryRepo repo) {
        return repo.unpushed();
    }
}
