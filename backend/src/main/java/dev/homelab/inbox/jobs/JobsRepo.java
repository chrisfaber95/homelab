package dev.homelab.inbox.jobs;

import dev.homelab.inbox.gitstore.BranchRepo;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * The clone {@code jobs-repo} of the {@code jobs} branch of DATA_REPO (H-024 M6): one {@code jobs/<name>.md} per job. The
 * git work is in {@link BranchRepo}; this adds the README and the one-commit save of {@code jobs.save_job}. The marker is
 * {@code .git/inbox-jobs-owner}. Commits are made as the clone's configured user ({@code homelab-inbox}), like the Python code did.
 */
@Component
public class JobsRepo extends BranchRepo {

    public static final String MARKER = "inbox-jobs-owner";
    private static final String README = "# jobs\n\nDe geplande jobs van de homelab (F-022), één bestand per job in `jobs/`. "
            + "Uitleg: `docs/jobs.md` op `main`.\nDeze branch wordt nooit gemerged; de homelab-inbox "
            + "leest hem en slaat hier op wat je op de pagina bevestigt.\n";

    public JobsRepo(Settings settings) {
        super(settings);
    }

    /** The root of the clone; the job files are in its {@code jobs/} folder ({@code Settings.jobsDir}). */
    @Override
    public Path dir() {
        return settings.jobsDir().getParent();
    }

    @Override
    public String branch() {
        return settings.jobsBranch();
    }

    @Override
    protected String markerName() {
        return MARKER;
    }

    /**
     * {@code jobs.save_job}: one commit on the jobs branch. {@code edit} gets the current text of {@code jobs/<name>.md} (null
     * when there is none), after the clone was brought to GitHub, and returns the new text (written stripped, with one final
     * newline) or null to remove the file; it may throw to refuse. False when nothing changed.
     */
    public boolean update(String name, UnaryOperator<String> edit, String message) {
        lock().lock();
        try {
            if (!owned()) {
                throw new Failed("the job files are not imported into the Java backend yet (docs/inbox-backend.md, M6)");
            }
            sync();
            Path d = dir();
            try {
                Path f = d.resolve("jobs").resolve(name + ".md");
                String text = edit.apply(Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null);
                if (text == null) {
                    Files.deleteIfExists(f);
                } else {
                    Files.createDirectories(f.getParent());
                    Files.writeString(f, text.strip() + "\n", StandardCharsets.UTF_8);
                }
                Path readme = d.resolve("README.md");
                if (!Files.exists(readme)) {
                    Files.writeString(readme, README, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new Failed("cannot write the jobs clone: " + e.getMessage());
            }
            git("add", "-A");
            if (git("status", "--porcelain").isBlank()) {
                return false;
            }
            git("commit", "--quiet", "-m", message);
            // Only to the jobs branch: the destination is fixed here.
            git("push", "--quiet", "origin", "HEAD:refs/heads/" + branch());
            return true;
        } finally {
            lock().unlock();
        }
    }
}
