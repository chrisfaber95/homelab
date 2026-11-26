package dev.homelab.inbox.core;

import java.time.Duration;
import java.time.Instant;
import java.util.function.IntConsumer;

/**
 * The limits of one round of Claude: turns and time, and a cancel (the watching part of Python's
 * {@code rounds.stream_claude} and {@code rounds.watch}). The runner reports a running turn count per round; the
 * limit is counted from the turns the task already had. The caller starts the real process; this class only decides.
 * <ul>
 *   <li>Every 5th turn (total) is stored on the task.</li>
 *   <li>At {@code maxTurns} turns in this round the round is stopped, once; the reason is "N beurten".</li>
 *   <li>Past the deadline, or when the task is cancelled, the watchdog stops it. A cancel is not a time-out.</li>
 *   <li>The final reason: cancelled wins over time-out, which wins over the turn limit.</li>
 * </ul>
 */
public class RoundLimits {

    private final int baseTurns;
    private final int maxTurns;
    private final int minutes;
    private final Instant deadline;
    private final IntConsumer storeTurns;
    private final Runnable stop;
    private int turns;
    private String capped;
    private boolean timedOut;

    public RoundLimits(int baseTurns, int maxTurns, int minutes, Instant startedAt, IntConsumer storeTurns, Runnable stop) {
        this.baseTurns = baseTurns;
        this.turns = baseTurns;
        this.maxTurns = maxTurns;
        this.minutes = minutes;
        this.deadline = startedAt.plus(Duration.ofMinutes(minutes));
        this.storeTurns = storeTurns;
        this.stop = stop;
    }

    /** A {@code turn} event of the runner: {@code roundTurns} is the number of turns of this round so far. */
    public void onTurn(int roundTurns) {
        turns = baseTurns + roundTurns;
        if (turns % 5 == 0) {
            storeTurns.accept(turns);
        }
        if (turns - baseTurns >= maxTurns && capped == null) {
            capped = maxTurns + " beurten";
            stop.run();
        }
    }

    /** One look of the watchdog; true when it stopped the round (the watchdog then ends). */
    public boolean watch(Instant now, boolean cancelled) {
        if (now.isAfter(deadline) || cancelled) {
            timedOut = !cancelled;
            stop.run();
            return true;
        }
        return false;
    }

    public int turns() {
        return turns;
    }

    /** Why the round was stopped: "geannuleerd", "N minuten", "N beurten", or null when it ended by itself. */
    public String capped(boolean cancelled) {
        if (cancelled) {
            return "geannuleerd";
        }
        if (timedOut) {
            return minutes + " minuten";
        }
        return capped;
    }

    /**
     * The end checks of {@code rounds.stream_claude}: a round that ends without a result, or without a single turn
     * (a resume that found no session, a refused start), is a failure of the round and not an answer without KLAAR.
     *
     * @param result the text of Claude's result, or null when there was no result event
     * @param isError the result is flagged as an error
     * @param stderr the last 300 characters of what the runner wrote on stderr
     * @throws IllegalStateException with the messages of Python (the task then fails with that note)
     */
    public void checkEnded(String result, boolean isError, String stderr, boolean cancelled) {
        String capped = capped(cancelled);
        if (result == null && capped == null) {
            throw new IllegalStateException("Claude stopte zonder resultaat: " + stderr);
        }
        if (capped == null && turns == baseTurns && (isError || result == null || result.isBlank())) {
            String said = result == null ? "" : result.substring(0, Math.min(200, result.length()));
            throw new IllegalStateException(("Claude deed niets (0 beurten): " + said + " " + stderr).strip());
        }
    }
}
