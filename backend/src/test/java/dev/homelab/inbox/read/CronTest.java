package dev.homelab.inbox.read;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/** Expected values come from the Python implementation (jobs.Cron, overview.tl_x) for the same inputs. */
class CronTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");
    private static final Instant AFTER = Instant.parse("2026-11-17T20:50:30Z");

    private static String next(String expr) {
        return new Cron(expr).nextAfter(AFTER, AMS).toOffsetDateTime().toString();
    }

    @Test
    void nextTimesMatchPython() {
        assertThat(next("*/30 * * * *")).isEqualTo("2026-11-17T22:00+01:00");
        assertThat(next("0 7 * * 1-5")).isEqualTo("2026-11-18T07:00+01:00");
        assertThat(next("30 9 1 * 1")).isEqualTo("2026-11-23T09:30+01:00"); // day or weekday, as cron does
        assertThat(next("0 0 29 2 *")).isEqualTo("2028-02-29T00:00+01:00");
        assertThat(next("5/10 3 * * 0,7")).isEqualTo("2026-11-22T03:05+01:00"); // "5/10" runs from 5 to the maximum
    }

    @Test
    void badSchedulesAreRefusedWithTheDutchReason() {
        assertThatThrownBy(() -> new Cron("61 7 * * *")).hasMessage("'61' valt buiten 0-59");
        assertThatThrownBy(() -> new Cron("* * *")).hasMessageContaining("5 velden");
        assertThatThrownBy(() -> new Cron("x * * * *")).hasMessage("'x' is geen geldig schemaveld");
    }

    @Test
    void timelinePositionsMatchPython() {
        Instant now = Instant.parse("2026-11-17T20:50:30Z");
        assertThat(OverviewService.tlX(now.minusSeconds(18000), now)).isEqualTo(67.91666666666667);
        assertThat(OverviewService.tlX(now.minusSeconds(604805), now)).isEqualTo(0.0);
        assertThat(OverviewService.tlX(now.plusSeconds(10800), now)).isEqualTo(73.75);
        assertThat(OverviewService.tlX(now.plusSeconds(90000), now)).isEqualTo(100.0);
        assertThat(OverviewService.tlX(now, now)).isEqualTo(70.0);
    }
}
