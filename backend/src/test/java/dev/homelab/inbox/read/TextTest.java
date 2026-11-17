package dev.homelab.inbox.read;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class TextTest {

    private static final ZoneId AMS = ZoneId.of("Europe/Amsterdam");
    private static final Instant NOW = Instant.parse("2026-11-17T20:50:30Z");

    @Test
    void firstLineCutsAtSeventyAndKeepsTheFallback() {
        assertThat(Text.firstLine("  Fix de build\nmeer ")).isEqualTo("Fix de build");
        assertThat(Text.firstLine("   ")).isEqualTo("taak");
        assertThat(Text.firstLine("a".repeat(100))).hasSize(70);
        assertThat(Text.firstLine("een twee")).isEqualTo("een"); // Python's splitlines also splits here
    }

    @Test
    void dayTimeNamesTodayTomorrowYesterdayAndTheWeekday() {
        assertThat(Text.dayTime(Instant.parse("2026-11-17T23:30:00Z"), AMS, NOW)).isEqualTo("morgen 00:30");
        assertThat(Text.dayTime(Instant.parse("2026-11-17T08:00:00Z"), AMS, NOW)).isEqualTo("vandaag 09:00");
        assertThat(Text.dayTime(Instant.parse("2026-11-16T08:00:00Z"), AMS, NOW)).isEqualTo("gisteren 09:00");
        assertThat(Text.dayTime(Instant.parse("2026-11-20T06:00:00Z"), AMS, NOW)).isEqualTo("vr 07:00");
        assertThat(Text.dayTime(Instant.parse("2026-12-20T06:00:00Z"), AMS, NOW)).isEqualTo("zo 20-12 07:00");
    }

    @Test
    void joinsAndIsoFormatLikePython() {
        assertThat(Text.joinNl(List.of("a"))).isEqualTo("a");
        assertThat(Text.joinNl(List.of("a", "b", "c"))).isEqualTo("a, b en c");
        assertThat(Text.isoUtc(NOW)).isEqualTo("2026-11-17T20:50:30+00:00"); // not "Z"
        assertThat(Text.iso(NOW.atZone(AMS))).isEqualTo("2026-11-17T21:50:30+01:00");
    }
}
