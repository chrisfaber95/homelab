package dev.homelab.inbox.memory;

import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;

/**
 * The clone of the {@code memory} branch of DATA_REPO, with the git work of {@code helpers.sync_branch} and
 * {@code memory.save_memory} (H-024 M5). From M5 on this is the only code that writes the clone: one commit per change,
 * pushed with the service's token, one writer at a time (a lock). The Python inbox only reads the files.
 *
 * <p>Ownership: the clone is Java's once the import has run ({@code import-memory}, which leaves the marker
 * {@code .git/inbox-memory-owner}); until then every write is refused, so there is never a moment with two writers.
 * {@code export-memory} removes the marker again (rollback).
 */
@Component
public class MemoryRepo {

    public static final String MARKER = "inbox-memory-owner";
    private static final String README = "# memory\n\nHet geheugen van Claude in de homelab-inbox (F-023): `voorkeuren/`, `feiten/` en "
            + "`geschiedenis/`, één bestand per item. Uitleg: `docs/memory.md` op `main`.\nDeze branch "
            + "wordt nooit gemerged; alleen de homelab-inbox schrijft erop (voor Claude en voor jou).\n";

    /** The write failed (git, or the store is not Java's yet); the message is what the page shows. */
    public static final class Failed extends RuntimeException {
        public Failed(String message) {
            super(message);
        }
    }

    private final Settings settings;
    private final ReentrantLock lock = new ReentrantLock();

    public MemoryRepo(Settings settings) {
        this.settings = settings;
    }

    public Path dir() {
        return settings.memoryDir();
    }

    public String branch() {
        return settings.memoryBranch();
    }

    public Path marker() {
        return dir().resolve(".git").resolve(MARKER);
    }

    public boolean owned() {
        return Files.isRegularFile(marker());
    }

    public ReentrantLock lock() {
        return lock;
    }

    private String remote() {
        String fake = settings.env("REMOTE_HOMELAB_DATA");
        return fake != null && !fake.isEmpty() ? fake : "https://github.com/" + settings.dataRepo() + ".git";
    }

    /** {@code helpers.git}: git in the clone, the token through a credential helper; the error is the end of git's output. */
    String git(String... args) {
        String helper = "!f() { test \"$1\" = get && echo username=x-access-token && echo \"password=$(cat "
                + settings.ghTokenFile() + ")\"; }; f";
        java.util.List<String> cmd = new java.util.ArrayList<>(
                java.util.List.of("git", "-c", "credential.helper=", "-c", "credential.helper=" + helper, "-C", dir().toString()));
        cmd.addAll(java.util.List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        try {
            Process p = pb.start();
            byte[] err;
            byte[] out;
            // read both streams: stderr on a thread, so a full pipe cannot block git
            var errReader = new java.util.concurrent.atomic.AtomicReference<byte[]>(new byte[0]);
            Thread t = new Thread(() -> {
                try {
                    errReader.set(p.getErrorStream().readAllBytes());
                } catch (IOException ignored) {
                    // the process ended
                }
            });
            t.start();
            out = p.getInputStream().readAllBytes();
            if (!p.waitFor(600, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new Failed("git " + args[0] + ": timed out");
            }
            t.join();
            err = errReader.get();
            if (p.exitValue() != 0) {
                String text = new String(err.length > 0 ? err : out, StandardCharsets.UTF_8).strip();
                throw new Failed("git " + args[0] + ": " + (text.length() > 300 ? text.substring(text.length() - 300) : text));
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new Failed("git " + args[0] + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failed("git " + args[0] + ": interrupted");
        }
    }

    /** {@code helpers.sync_branch}: the clone equal to GitHub (empty when the branch is not there yet). */
    public void sync() {
        lock.lock();
        try {
            Path d = dir();
            try {
                Files.createDirectories(d);
            } catch (IOException e) {
                throw new Failed("cannot create " + d + ": " + e.getMessage());
            }
            if (!Files.isDirectory(d.resolve(".git"))) {
                git("init", "--quiet");
                git("remote", "add", "origin", remote());
                git("config", "user.name", "homelab-inbox");
                git("config", "user.email", "homelab-inbox@homelab");
            } else {
                git("remote", "set-url", "origin", remote());
            }
            if (!git("ls-remote", "--heads", "origin", branch()).isBlank()) {
                git("fetch", "--quiet", "origin", branch());
                git("checkout", "--quiet", "--force", "-B", branch(), "FETCH_HEAD");
                git("clean", "-fdq");
            } else {
                git("symbolic-ref", "HEAD", "refs/heads/" + branch());
                git("update-ref", "-d", "refs/heads/" + branch());
                git("read-tree", "--empty");
                git("clean", "-fdxq");
            }
        } finally {
            lock.unlock();
        }
    }

    /** {@code memory.save_memory}: one commit on the memory branch, {id: text, or null to erase}; false when nothing changed. */
    public boolean save(Map<String, String> changes, String message, String authorName, String authorEmail) {
        lock.lock();
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
            lock.unlock();
        }
    }

    private boolean hasHead() {
        try {
            git("rev-parse", "--verify", "--quiet", "HEAD");
            return true;
        } catch (Failed e) {
            return false; // no commit yet (git exits 1)
        }
    }

    /** What the clone has that GitHub does not (local commits, uncommitted files); empty when the clone equals GitHub. */
    public String unpushed() {
        lock.lock();
        try {
            if (!Files.isDirectory(dir().resolve(".git"))) {
                return "";
            }
            StringBuilder out = new StringBuilder(git("status", "--porcelain").strip());
            if (hasHead()) {
                if (git("ls-remote", "--heads", "origin", branch()).isBlank()) {
                    out.append('\n').append(git("log", "--oneline", "HEAD").strip());
                } else {
                    git("fetch", "--quiet", "origin", branch());
                    out.append('\n').append(git("log", "--oneline", "FETCH_HEAD..HEAD").strip());
                }
            }
            return out.toString().strip();
        } finally {
            lock.unlock();
        }
    }
}
