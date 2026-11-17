package dev.homelab.inbox.read;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.regex.Pattern;

/** The small text and time helpers of {@code overview.py} and {@code helpers.py}, with the same output. */
final class Text {

    private Text() {}

    /** Python's {@code datetime.isoformat(timespec="seconds")}: an offset of zero is {@code +00:00}, not {@code Z}. */
    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    static final String[] DAYS_NL = {"ma", "di", "wo", "do", "vr", "za", "zo"};

    /** The line separators of Python's {@code str.splitlines()}. */
    private static final Pattern LINES = Pattern.compile("\r\n|[\n\r\u000b\u000c\u001c\u001d\u001e\u0085  ]");

    static String iso(ZonedDateTime t) {
        return t.truncatedTo(ChronoUnit.SECONDS).format(ISO_SECONDS);
    }

    static String isoUtc(Instant t) {
        return iso(t.atZone(ZoneOffset.UTC));
    }

    /** {@code datetime.fromisoformat}; a time without an offset counts as UTC (the box runs on UTC). */
    static Instant ts(String iso) {
        String s = iso.strip().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        }
    }

    /** {@code text[:n]} counted in code points, as Python counts. */
    static String cut(String text, int n) {
        return text.codePointCount(0, text.length()) <= n ? text : text.substring(0, text.offsetByCodePoints(0, n));
    }

    /** helpers.first_line */
    static String firstLine(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return "taak";
        }
        return cut(LINES.split(stripped, -1)[0], 70);
    }

    /** {@code s[0].lower() + s[1:]} */
    static String lowerFirst(String s) {
        int first = s.offsetByCodePoints(0, 1);
        return s.substring(0, first).toLowerCase(java.util.Locale.ROOT) + s.substring(first);
    }

    static String joinNl(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " en " + items.get(items.size() - 1);
    }

    /** 'vandaag 07:00', 'morgen 07:00', 'gisteren 22:10' or 'wo 07:00'; within a week, otherwise with the date. */
    static String dayTime(Instant instant, ZoneId tz, Instant now) {
        ZonedDateTime d = instant.atZone(tz);
        long delta = ChronoUnit.DAYS.between(now.atZone(tz).toLocalDate(), d.toLocalDate());
        String word = delta == 0 ? "vandaag" : delta == 1 ? "morgen" : delta == -1 ? "gisteren" : null;
        if (word == null) {
            word = dayName(d.getDayOfWeek()) + (Math.abs(delta) < 7 ? "" : String.format(" %02d-%02d", d.getDayOfMonth(), d.getMonthValue()));
        }
        return String.format("%s %02d:%02d", word, d.getHour(), d.getMinute());
    }

    static String dayName(DayOfWeek day) {
        return DAYS_NL[day.getValue() - 1];
    }

    /** Python truthiness of a parsed JSON value. */
    static boolean truthy(Object v) {
        return v != null && !Boolean.FALSE.equals(v) && !"".equals(v) && !(v instanceof Number n && n.doubleValue() == 0)
                && !(v instanceof List<?> l && l.isEmpty()) && !(v instanceof java.util.Map<?, ?> m && m.isEmpty());
    }

    /** Python's {@code str()} of a parsed JSON scalar. */
    static String pyStr(Object v) {
        return v == null ? "None" : v instanceof Boolean b ? (b ? "True" : "False") : v.toString();
    }
}
