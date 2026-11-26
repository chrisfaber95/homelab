package dev.homelab.inbox.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * The real {@link CommandRunner}: a child process. At the deadline the process gets SIGTERM (a runner forwards it to
 * Claude) and ten seconds later SIGKILL, as {@code helpers.run_runner} does. stderr goes to a temp file, not a pipe: a
 * full stderr pipe would let Claude hang ({@code rounds.stream_claude}).
 */
public class ProcessCommandRunner implements CommandRunner {

    private static final long KILL_AFTER_SECONDS = 10;

    /** Set by the Maven test run: the suite must never start a real process through this class. */
    public static final String DENY_PROPERTY = "inbox.commands.deny";

    @Override
    public Finished run(Command c) {
        if (Boolean.getBoolean(DENY_PROPERTY)) {
            throw new IllegalStateException("real commands are not allowed in tests: " + c.args().get(0));
        }
        Path err = null;
        try {
            err = Files.createTempFile("inbox-cmd", ".err");
            ProcessBuilder pb = new ProcessBuilder(c.args());
            pb.environment().putAll(c.env());
            if (c.dir() != null) {
                pb.directory(c.dir().toFile());
            }
            pb.redirectError(err.toFile());
            Process p = pb.start();
            if (c.stdin() != null) {
                try (var out = p.getOutputStream()) {
                    out.write(c.stdin().getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // the process ended before it read its input
                }
            } else {
                p.getOutputStream().close();
            }
            StringBuilder stdout = new StringBuilder();
            CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        stdout.append(line).append('\n');
                        if (c.onLine() != null) {
                            c.onLine().accept(line);
                        }
                    }
                } catch (IOException ignored) {
                    // the stream closed with the process
                }
            });
            boolean timedOut = false;
            if (!p.waitFor(c.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                timedOut = true;
                p.destroy();
                if (!p.waitFor(KILL_AFTER_SECONDS, TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                    p.waitFor();
                }
            }
            reader.join();
            return new Finished(p.exitValue(), stdout.toString(), Files.readString(err, StandardCharsets.UTF_8), timedOut);
        } catch (IOException e) {
            return new Finished(-1, "", String.valueOf(e.getMessage()), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Finished(-1, "", "interrupted", false);
        } finally {
            if (err != null) {
                try {
                    Files.deleteIfExists(err);
                } catch (IOException ignored) {
                    // a temp file
                }
            }
        }
    }
}
