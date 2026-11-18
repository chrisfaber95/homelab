package dev.homelab.inbox.runner;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Starts the Python runners of {@code runner/} (H-025 R3) and watches them. The process contract is in
 * {@code docs/architecture/inbox-split.md} ("Backend to Python runners") and {@code runner/SCHEMA.md}:
 * <ul>
 *   <li>The child is started from an argument array with {@link ProcessBuilder}; no shell ever sees a string. It is started
 *       through {@code setsid} (also an argument, not a shell) so it leads its own process group.</li>
 *   <li>stdout is newline-delimited JSON. Lines that are not JSON objects are ignored; the last
 *       {@code {"event":"result","v":1,...}} line plus the exit code is the result. Every other event goes to the listener
 *       untouched: Java does not parse Claude's stream-json (Q7).</li>
 *   <li>On deadline or cancel the contract's {@code kill} action runs first; if the runner has not exited after the grace
 *       time, the whole process group gets SIGKILL.</li>
 * </ul>
 */
@Service
public class RunnerService {

    private static final Logger log = LoggerFactory.getLogger(RunnerService.class);
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final int STDERR_TAIL = 4000;
    private static final Duration KILL_ACTION_LIMIT = Duration.ofSeconds(15);

    private final List<String> launcher;

    public RunnerService() {
        this(List.of("setsid"));
    }

    /** {@code launcher} is put in front of every command (an argument array too); empty starts the command directly. */
    public RunnerService(List<String> launcher) {
        this.launcher = List.copyOf(launcher);
    }

    /** Start a runner; {@code onEvent} gets every JSON line except the result line, on the reader thread. */
    public RunnerRun start(RunnerRequest req, Consumer<JsonNode> onEvent) {
        List<String> argv = new ArrayList<>(launcher);
        argv.addAll(req.command());
        ProcessBuilder pb = new ProcessBuilder(argv);
        pb.environment().putAll(req.env());
        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            return new Failed(new RunnerResult(RunnerResult.Ending.START_FAILED, -1, Optional.empty(), e.getMessage()));
        }
        return new Running(req, proc, onEvent == null ? n -> { } : onEvent);
    }

    /** Start and wait. */
    public RunnerResult run(RunnerRequest req, Consumer<JsonNode> onEvent) {
        return start(req, onEvent).result().join();
    }

    private record Failed(RunnerResult done) implements RunnerRun {
        public CompletableFuture<RunnerResult> result() {
            return CompletableFuture.completedFuture(done);
        }

        public void cancel() { }
    }

    private static final class Running implements RunnerRun {
        private final RunnerRequest req;
        private final Process proc;
        private final Consumer<JsonNode> onEvent;
        private final CompletableFuture<Void> cancelled = new CompletableFuture<>();
        private final CompletableFuture<RunnerResult> result = new CompletableFuture<>();
        private volatile JsonNode resultEvent;
        private final StringBuilder stderr = new StringBuilder();

        Running(RunnerRequest req, Process proc, Consumer<JsonNode> onEvent) {
            this.req = req;
            this.proc = proc;
            this.onEvent = onEvent;
            Thread.ofVirtual().name("runner-" + proc.pid()).start(this::supervise);
        }

        public CompletableFuture<RunnerResult> result() {
            return result;
        }

        public void cancel() {
            cancelled.complete(null);
        }

        private void supervise() {
            try {
                Thread out = Thread.ofVirtual().start(() -> readEvents(proc.getInputStream()));
                Thread err = Thread.ofVirtual().start(() -> readStderr(proc.getErrorStream()));
                writeStdin();
                RunnerResult.Ending ending = RunnerResult.Ending.EXITED;
                try {
                    CompletableFuture.anyOf(proc.onExit(), cancelled).get(req.deadline().toNanos(), TimeUnit.NANOSECONDS);
                    if (proc.isAlive()) ending = RunnerResult.Ending.CANCELLED;
                } catch (TimeoutException e) {
                    ending = RunnerResult.Ending.DEADLINE;
                }
                if (proc.isAlive()) stop();
                int code = proc.waitFor();
                out.join();
                err.join();
                result.complete(new RunnerResult(ending, code, Optional.ofNullable(resultEvent), tail()));
            } catch (Exception e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                log.warn("runner supervision failed", e);
                killGroup();
                result.complete(new RunnerResult(RunnerResult.Ending.EXITED, -1, Optional.ofNullable(resultEvent), tail()));
            }
        }

        /** The kill action first, then, if the runner is still there after the grace time, the whole process group. */
        private void stop() throws InterruptedException {
            if (!req.killCommand().isEmpty()) runKillAction();
            if (!proc.isAlive() || waitExit(req.killGrace())) return;
            killGroup();
            waitExit(Duration.ofSeconds(5));
        }

        private boolean waitExit(Duration d) throws InterruptedException {
            return proc.waitFor(d.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void runKillAction() throws InterruptedException {
            try {
                Process k = new ProcessBuilder(req.killCommand()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
                if (!k.waitFor(KILL_ACTION_LIMIT.toNanos(), TimeUnit.NANOSECONDS)) k.destroyForcibly();
            } catch (IOException e) {
                log.warn("kill action could not start: {}", e.getMessage());
            }
        }

        /** The runner leads its own group (setsid), so {@code kill -KILL -- -<pid>} reaches everything it started. */
        private void killGroup() {
            List<ProcessHandle> kids = proc.descendants().toList();
            try {
                Process k = new ProcessBuilder("kill", "-KILL", "--", "-" + proc.pid())
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                k.waitFor(5, TimeUnit.SECONDS);
            } catch (IOException e) {
                log.warn("process group kill could not start: {}", e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            kids.forEach(ProcessHandle::destroyForcibly);
            proc.destroyForcibly();
        }

        private void writeStdin() {
            try (OutputStream in = proc.getOutputStream()) {
                if (req.stdin().length > 0) in.write(req.stdin());
            } catch (IOException ignored) {
                // the runner may exit without reading its stdin
            }
        }

        private void readEvents(InputStream in) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    JsonNode n = parse(line);
                    if (n == null) continue;
                    if ("result".equals(n.path("event").asText()) && n.path("v").asInt() == 1) {
                        resultEvent = n;
                    } else {
                        try {
                            onEvent.accept(n);
                        } catch (RuntimeException e) {
                            log.warn("runner event listener failed", e);
                        }
                    }
                }
            } catch (IOException ignored) {
                // the stream closes when the process is killed
            }
        }

        private static JsonNode parse(String line) {
            String s = line.strip();
            if (!s.startsWith("{")) return null;
            try {
                JsonNode n = JSON.readTree(s);
                return n != null && n.isObject() ? n : null;
            } catch (tools.jackson.core.JacksonException e) {
                return null;
            }
        }

        private void readStderr(InputStream in) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[1024];
                int n;
                while ((n = r.read(buf)) > 0) {
                    synchronized (stderr) {
                        stderr.append(buf, 0, n);
                        if (stderr.length() > 2 * STDERR_TAIL) stderr.delete(0, stderr.length() - STDERR_TAIL);
                    }
                }
            } catch (IOException ignored) {
                // see readEvents
            }
        }

        private String tail() {
            synchronized (stderr) {
                String s = stderr.toString();
                return s.length() > STDERR_TAIL ? s.substring(s.length() - STDERR_TAIL) : s;
            }
        }
    }
}
