package dev.homelab.inbox.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The shape of a research report ({@code jobs.report_header}, {@code report_problems}, {@code duration_text}). */
final class Reports {

    private Reports() {}

    static String duration(double seconds) {
        long s = Math.max(0, (long) seconds);
        return s >= 60 ? (s / 60) + " min " + (s % 60) + " s" : s + " s";
    }

    /** The line shown with every report: the mode and the time taken. */
    static String header(String mode, double seconds) {
        return "Research report | mode: " + mode + " | time taken: " + duration(seconds);
    }

    /** What is missing from a report, in words (empty = fine): a report without sources is flagged, not passed. */
    static List<String> problems(String body) {
        String low = body == null ? "" : body.toLowerCase(Locale.ROOT);
        String lead = low.stripLeading();
        List<String> missing = new ArrayList<>();
        boolean nothing = lead.startsWith("niets gevonden") || lead.startsWith("nothing found");
        if (!low.contains("## doorzocht") && !low.contains("## searched")) {
            missing.add("no list of what was searched");
        }
        if (!nothing && !low.contains("## bronnen") && !low.contains("## sources")) {
            missing.add("no sources");
        }
        return missing;
    }
}
