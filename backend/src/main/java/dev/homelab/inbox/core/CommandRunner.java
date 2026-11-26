package dev.homelab.inbox.core;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The one door through which the live effects start an external process (the runner scripts, {@code
 * homelab-inbox-task}, {@code gh}, {@code git}, {@code curl} for ntfy). Tests pass a fake, so the suite never calls the
 * real Claude, {@code gh} or ntfy.
 */
public interface CommandRunner {

    /**
     * One command. {@code env} is added to the environment of the service; {@code dir} may be null; {@code onLine}
     * (may be null) gets every line of stdout while the process runs (the NDJSON of a runner).
     */
    record Command(List<String> args, Map<String, String> env, Path dir, String stdin, Duration timeout,
                   Consumer<String> onLine) {

        public static Command of(String... args) {
            return new Command(List.of(args), Map.of(), null, null, Duration.ofMinutes(10), null);
        }

        public static Command of(List<String> args) {
            return new Command(List.copyOf(args), Map.of(), null, null, Duration.ofMinutes(10), null);
        }

        public Command withEnv(Map<String, String> more) {
            return new Command(args, more, dir, stdin, timeout, onLine);
        }

        public Command withDir(Path d) {
            return new Command(args, env, d, stdin, timeout, onLine);
        }

        public Command withStdin(String s) {
            return new Command(args, env, dir, s, timeout, onLine);
        }

        public Command withTimeout(Duration t) {
            return new Command(args, env, dir, stdin, t, onLine);
        }

        public Command withOnLine(Consumer<String> c) {
            return new Command(args, env, dir, stdin, timeout, c);
        }
    }

    /** {@code code} is -1 when the process could not start; {@code timedOut}: it was terminated at the deadline. */
    record Finished(int code, String stdout, String stderr, boolean timedOut) {

        /** {@code (stdout + stderr).strip()}, what {@code helpers.task_cmd} returns. */
        public String combined() {
            return (stdout + stderr).strip();
        }
    }

    Finished run(Command command);
}
