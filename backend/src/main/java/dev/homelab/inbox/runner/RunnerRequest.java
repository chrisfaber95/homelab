package dev.homelab.inbox.runner;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * What to start: the runner as an argument array (never a shell string), where it ends, and how to stop it. The prompt
 * belongs in {@code share/<id>/prompt.txt} and the token is read by {@code homelab-inbox-task}, so neither is a field here;
 * {@code stdin} and {@code env} are for what the contract (docs/architecture/inbox-split.md, runner/SCHEMA.md) allows.
 *
 * @param command   the program and its arguments, e.g. {@code python3 -I /usr/local/lib/homelab/runner/round.py 42 <uuid> new}
 * @param killCommand the contract's {@code kill} action as an argument array, e.g.
 *                  {@code sudo -n -u inbox /usr/local/lib/homelab/bin/homelab-inbox-task kill 42}; empty for none
 * @param deadline  time after which the run is stopped
 * @param killGrace how long the runner gets to exit after the kill action before its process group is killed
 * @param env       extra environment variables (not arguments, so not visible in {@code ps})
 * @param stdin     bytes written to the runner's stdin, then closed (may be empty)
 */
public record RunnerRequest(
        List<String> command,
        List<String> killCommand,
        Duration deadline,
        Duration killGrace,
        Map<String, String> env,
        byte[] stdin) {

    public RunnerRequest {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("command is empty");
        if (deadline == null || deadline.isNegative() || deadline.isZero()) throw new IllegalArgumentException("deadline must be positive");
        command = List.copyOf(command);
        killCommand = killCommand == null ? List.of() : List.copyOf(killCommand);
        killGrace = killGrace == null ? Duration.ofSeconds(10) : killGrace;
        env = env == null ? Map.of() : Map.copyOf(env);
        stdin = stdin == null ? new byte[0] : stdin;
    }

    public static RunnerRequest of(List<String> command, List<String> killCommand, Duration deadline) {
        return new RunnerRequest(command, killCommand, deadline, null, null, null);
    }
}
