package dev.homelab.inbox.memory;

import dev.homelab.inbox.gitstore.BranchRepo;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * The clone of the {@code memory} branch of DATA_REPO (H-024 M5): the git work is in {@link BranchRepo}, this adds the
 * memory's README and its one-commit save ({@code memory.save_memory}). The marker is {@code .git/inbox-memory-owner}.
 */
@Component
public class MemoryRepo extends BranchRepo {

    public static final String MARKER = "inbox-memory-owner";
    private static final String README = "# memory\n\nHet geheugen van Claude in de homelab-inbox (F-023): `voorkeuren/`, `feiten/` en "
            + "`geschiedenis/`, één bestand per item. Uitleg: `docs/memory.md` op `main`.\nDeze branch "
            + "wordt nooit gemerged; alleen de homelab-inbox schrijft erop (voor Claude en voor jou).\n";

    public MemoryRepo(Settings settings) {
        super(settings);
    }

    @Override
    public Path dir() {
        return settings.memoryDir();
    }

    @Override
    public String branch() {
        return settings.memoryBranch();
    }

    @Override
    protected String markerName() {
        return MARKER;
    }

    /** {@code memory.save_memory}: one commit on the memory branch, {id: text, or null to erase}; false when nothing changed. */
    public boolean save(Map<String, String> changes, String message, String authorName, String authorEmail) {
        lock().lock();
        try {
            if (!owned()) {
                throw new Failed("the memory store is not imported into the Java backend yet (docs/inbox-backend.md, M5)");
            }
            sync();
            Path d = dir();
            try {
                for (Map.Entry<String, String> c : changes.entrySet()) {
                    Path f = d.resolve(c.getKey() + ".md");
                    if (c.getValue() == null) {
                        Files.deleteIfExists(f);
                    } else {
                        Files.createDirectories(f.getParent());
                        Files.writeString(f, c.getValue(), StandardCharsets.UTF_8);
                    }
                }
                Path readme = d.resolve("README.md");
                if (!Files.exists(readme)) {
                    Files.writeString(readme, README, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                throw new Failed("cannot write the memory clone: " + e.getMessage());
            }
            git("add", "-A");
            if (git("status", "--porcelain").isBlank()) {
                return false;
            }
            git("-c", "user.name=" + authorName, "-c", "user.email=" + authorEmail, "commit", "--quiet", "-m", message);
            // Only to the memory branch: the destination is fixed here.
            git("push", "--quiet", "origin", "HEAD:refs/heads/" + branch());
            return true;
        } finally {
            lock().unlock();
        }
    }
}
