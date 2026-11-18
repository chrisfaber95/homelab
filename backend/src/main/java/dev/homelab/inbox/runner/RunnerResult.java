package dev.homelab.inbox.runner;

import tools.jackson.databind.JsonNode;
import java.util.Optional;

/**
 * The outcome of one runner: how it ended, its exit code and the final {@code {"event":"result","v":1,...}} line, kept as
 * parsed JSON. Java does not look inside it (decision Q7: the runner owns the Claude-specific parsing).
 *
 * @param ending   {@link Ending#EXITED} when the runner ended by itself, otherwise why it was stopped
 * @param exitCode the exit code, or -1 when the process could not be started
 * @param result   the last result event; empty when the runner printed none (a crash)
 * @param stderrTail the last part of stderr, for the task log and error messages
 */
public record RunnerResult(Ending ending, int exitCode, Optional<JsonNode> result, String stderrTail) {

    public enum Ending { EXITED, DEADLINE, CANCELLED, START_FAILED }

    /** Finished by itself with exit 0 and a result line. */
    public boolean ok() {
        return ending == Ending.EXITED && exitCode == 0 && result.isPresent();
    }
}
