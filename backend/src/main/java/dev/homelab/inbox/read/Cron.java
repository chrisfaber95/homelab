package dev.homelab.inbox.read;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeSet;

/** A cron schedule (minute hour day month weekday) in the inbox time zone: {@code *}, ranges, lists and steps.
 * A port of {@code jobs.Cron}, quirks included ({@code 5/10} means 5 up to the maximum, step 10). */
public final class Cron {

    private static final int[][] RANGES = {{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 7}};

    private final List<Set<Integer>> sets;
    private final boolean anyDom;
    private final boolean anyDow;

    /** Thrown for a schedule that cannot be read; the message is Dutch, like the Python one. */
    public static final class Invalid extends IllegalArgumentException {
        public Invalid(String message) {
            super(message);
        }
    }

    public Cron(String expr) {
        String[] parts = expr.strip().isEmpty() ? new String[0] : expr.strip().split("\\s+");
        if (parts.length != 5) {
            throw new Invalid("een schema heeft 5 velden: minuut uur dag maand weekdag");
        }
        List<Set<Integer>> built = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            built.add(field(parts[i], RANGES[i][0], RANGES[i][1]));
        }
        if (built.get(4).remove(7)) {
            built.get(4).add(0);
        }
        this.sets = built;
        this.anyDom = parts[2].equals("*");
        this.anyDow = parts[4].equals("*");
    }

    private static Set<Integer> field(String text, int lo, int hi) {
        Set<Integer> out = new TreeSet<>();
        for (String item : text.split(",", -1)) {
            int slash = item.indexOf('/');
            String rng = slash < 0 ? item : item.substring(0, slash);
            String stepText = slash < 0 ? "" : item.substring(slash + 1);
            int a;
            int b;
            int step;
            try {
                step = stepText.isEmpty() ? 1 : Integer.parseInt(stepText.strip());
                if (rng.equals("*")) {
                    a = lo;
                    b = hi;
                } else if (rng.contains("-")) {
                    int dash = rng.indexOf('-');
                    a = Integer.parseInt(rng.substring(0, dash).strip());
                    b = Integer.parseInt(rng.substring(dash + 1).strip());
                } else {
                    a = Integer.parseInt(rng.strip());
                    b = step != 1 ? hi : a;
                }
            } catch (NumberFormatException e) {
                throw new Invalid("'" + item + "' is geen geldig schemaveld");
            }
            if (!(lo <= a && a <= b && b <= hi) || step < 1) {
                throw new Invalid("'" + item + "' valt buiten " + lo + "-" + hi);
            }
            for (int v = a; v <= b; v += step) {
                out.add(v);
            }
        }
        return out;
    }

    private boolean dayOk(LocalDate d) {
        if (!sets.get(3).contains(d.getMonthValue())) {
            return false;
        }
        boolean dom = sets.get(2).contains(d.getDayOfMonth());
        boolean dow = sets.get(4).contains(d.getDayOfWeek().getValue() % 7);
        // As in cron: when day and weekday are both restricted, either one is enough.
        return anyDom || anyDow ? dom && dow : dom || dow;
    }

    public ZonedDateTime nextAfter(Instant after, ZoneId tz) {
        LocalDateTime start = after.atZone(tz).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDate day = start.toLocalDate();
        for (int i = 0; i < 366 * 5; i++) {
            if (dayOk(day)) {
                for (int h : sets.get(1)) {
                    for (int m : sets.get(0)) {
                        LocalDateTime moment = LocalDateTime.of(day, LocalTime.of(h, m));
                        if (!moment.isBefore(start)) {
                            return moment.atZone(tz);
                        }
                    }
                }
            }
            day = day.plusDays(1);
        }
        throw new NoSuchElementException("dit schema valt nooit");
    }
}
