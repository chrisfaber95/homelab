package dev.homelab.inbox.db;

import dev.homelab.inbox.jobs.JobsRepo;
import dev.homelab.inbox.read.Jobs;
import dev.homelab.inbox.read.Settings;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@code java -jar inbox-backend.jar import-jobs [--if-not-imported] [--replace]} and {@code export-jobs}
 * (H-024 M6): the fresh import of the job files into the Java backend, and its rollback. The store is the clone
 * {@code jobs-repo} of the {@code jobs} branch of DATA_REPO. Import = bring the clone exactly to GitHub (what the Python
 * inbox would load), count the files, and leave the marker that makes Java its only writer. Export = check that nothing
 * written by Java is missing on GitHub, bring the clone to GitHub and remove the marker, so the old Python code can own it again.
 * Run as inbox-runner with the Python inbox stopped (scripts/jobs-import.sh does that). No Spring context.
 * Exit code 0 = done (or already imported with {@code --if-not-imported}), 1 = failed, 2 = refused or bad usage.
 */
public final class JobsCommand {

    public static final String IMPORT = "import-jobs";
    public static final String EXPORT = "export-jobs";

    private static final String USAGE = """
            usage: import-jobs [--if-not-imported] [--replace] [--config-dir <dir>]
                   export-jobs [--config-dir <dir>]
            Reads the same settings as the service: INBOX_CONFIG_DIR (or --config-dir), INBOX_RUNNER_HOME, REMOTE_HOMELAB_DATA.
            --if-not-imported  exit 0 and do nothing when the store is already Java's
            --replace          import again although the marker is there (resets the clone to GitHub)
            """;

    private JobsCommand() {
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
        JobsRepo repo = new JobsRepo(settings);
        Jobs reader = new Jobs(settings, tools.jackson.databind.json.JsonMapper.builder().build());
        try {
            if (isImport) {
                if (repo.owned()) {
                    if (ifNot) {
                        out.println("already imported: " + repo.marker());
                        return 0;
                    }
                    if (!replace) {
                        err.println("REFUSED: the job files is already Java's (" + repo.marker() + "); --replace imports again, "
                                + "only before the switch");
                        return 2;
                    }
                }
                int before = count(reader);
                repo.sync();
                int after = count(reader);
                Files.writeString(repo.marker(), "imported " + java.time.Instant.now() + " files=" + after + "\n");
                out.println("import ok: job files=" + after + " (before the sync " + before + "), branch=" + repo.branch()
                        + ", Java is the only writer now");
                return 0;
            }
            if (!repo.owned()) {
                err.println("REFUSED: the job files is not Java's (no " + repo.marker() + "); nothing to export");
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
            out.println("export ok: job files=" + count(reader) + " (equal to GitHub), the marker is removed; "
                    + "the Python code owns the store again");
            return 0;
        } catch (Exception e) {
            err.println((isImport ? "IMPORT" : "EXPORT") + " FAILED: " + e.getMessage());
            return 1;
        }
    }

    private static int count(Jobs reader) {
        return reader.load().size();
    }

    private static String unpushed(JobsRepo repo) {
        return repo.unpushed();
    }
}
