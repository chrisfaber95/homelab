package dev.homelab.inbox.gitstore;

import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A clone of one branch of DATA_REPO that the Java backend owns (H-024 M5 memory, M6 job files): the git work of
 * {@code helpers.sync_branch} and the one-commit-per-change save of the old Python code. From the switch on this is the only code
 * that writes the clone: one commit per change, pushed with the service's token, one writer at a time (a lock). The Python
 * inbox only reads the files.
 *
 * <p>Ownership: the clone is Java's once its import has run (which leaves a marker in {@code .git}); until then every
 * write is refused, so there is never a moment with two writers. The export removes the marker again (rollback).
 */
public abstract class BranchRepo {

    /** The write failed (git, or the store is not Java's yet); the message is what the page shows. */
    public static final class Failed extends RuntimeException {
        public Failed(String message) {
            super(message);
        }
    }

    protected final Settings settings;
    private final ReentrantLock lock = new ReentrantLock();

    protected BranchRepo(Settings settings) {
        this.settings = settings;
    }

    /** The working tree of the clone. */
    public abstract Path dir();

    public abstract String branch();

    /** The name of the owner marker file in {@code .git}. */
    protected abstract String markerName();

    public Path marker() {
        return dir().resolve(".git").resolve(markerName());
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
    protected String git(String... args) {
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
