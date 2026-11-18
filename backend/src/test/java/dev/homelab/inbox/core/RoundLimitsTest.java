package dev.homelab.inbox.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The turn and time limits of a round (the watching part of {@code rounds.stream_claude} and {@code rounds.watch}). */
class RoundLimitsTest {

    private static final Instant START = Instant.parse("2026-11-18T10:00:00Z");
    private final List<Integer> stored = new ArrayList<>();
    private int stops;

    private RoundLimits limits(int base, int max, int minutes) {
        return new RoundLimits(base, max, minutes, START, stored::add, () -> stops++);
    }

    /** test_runner_round.InboxUsesRunner.test_stream_claude_reads_the_runner_events: turns = the task's 3 + 1 event, no cap */
    @Test
    void turnsAreCountedOnTopOfTheTurnsTheTaskAlreadyHad() {
        RoundLimits l = limits(3, 80, 30);
        l.onTurn(1);
        assertThat(l.turns()).isEqualTo(4);
        assertThat(l.capped(false)).isNull();
        assertThat(stops).isZero();
    }

    /** rounds.stream_claude: update(turns) only when the total is a multiple of 5 */
    @Test
    void everyFifthTurnOfTheTotalIsStored() {
        RoundLimits l = limits(3, 80, 30);
        for (int i = 1; i <= 8; i++) {
            l.onTurn(i);
        }
        assertThat(stored).containsExactly(5, 10);
    }

    /** rounds.stream_claude: stop at MAX_TURNS turns of this round, once, reason "N beurten" */
    @Test
    void theRoundStopsOnceAtTheTurnLimit() {
        RoundLimits l = limits(10, 5, 30);
        for (int i = 1; i <= 4; i++) {
            l.onTurn(i);
        }
        assertThat(stops).isZero();
        l.onTurn(5);
        l.onTurn(6);
        assertThat(stops).isEqualTo(1);
        assertThat(l.capped(false)).isEqualTo("5 beurten");
    }

    /** rounds.watch: stop after the deadline; the reason is the minutes */
    @Test
    void theWatchdogStopsAfterTheDeadline() {
        RoundLimits l = limits(0, 80, 30);
        assertThat(l.watch(START.plusSeconds(30 * 60), false)).isFalse();
        assertThat(l.watch(START.plusSeconds(30 * 60 + 1), false)).isTrue();
        assertThat(stops).isEqualTo(1);
        assertThat(l.capped(false)).isEqualTo("30 minuten");
    }

    /** rounds.watch + stream_claude: a cancel stops it too, and wins over a time-out and over the turn limit */
    @Test
    void aCancelIsNotATimeoutAndWinsOverTheOtherReasons() {
        RoundLimits l = limits(0, 1, 30);
        l.onTurn(1);
        assertThat(l.capped(false)).isEqualTo("1 beurten");
        assertThat(l.watch(START.plusSeconds(31 * 60), true)).isTrue();
        assertThat(l.capped(true)).isEqualTo("geannuleerd");
        assertThat(l.capped(false)).isEqualTo("1 beurten"); // not marked as timed out
    }

    /** rounds.stream_claude: a time-out overrides an earlier turn-limit reason */
    @Test
    void aTimeoutOverridesTheTurnLimitReason() {
        RoundLimits l = limits(0, 1, 30);
        l.onTurn(1);
        l.watch(START.plusSeconds(31 * 60), false);
        assertThat(l.capped(false)).isEqualTo("30 minuten");
    }
}
