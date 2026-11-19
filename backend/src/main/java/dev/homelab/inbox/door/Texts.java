package dev.homelab.inbox.door;

import java.util.regex.Pattern;

/** The few string helpers of {@code helpers.py} the door needs, with Python's semantics. */
final class Texts {

    /** {@code str.splitlines()}: the line boundaries Python knows. */
    private static final Pattern LINES = Pattern.compile("\r\n|[\n\r\u000b\u000c\u001c-\u001e\u0085  ]");

    private Texts() {}

    /** {@code helpers.first_line}: the first line of the text, at most 70 characters; "taak" for an empty text. */
    static String firstLine(String text) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            return "taak";
        }
        return cut(LINES.split(t, 2)[0], 70);
    }

    /** {@code s[:n]}, counted in code points like Python. */
    static String cut(String s, int n) {
        return s.codePointCount(0, s.length()) <= n ? s : s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** {@code str.capitalize()}: first character upper case, the rest lower case. */
    static String capitalize(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    /** Python's {@code str(x or "").strip()} for a value that may be null. */
    static String strip(String s) {
        return s == null ? "" : s.strip();
    }

    /** {@code str(x).isdigit()} for the ASCII digits a task id has. */
    static boolean digits(String s) {
        return s != null && !s.isEmpty() && s.chars().allMatch(c -> c >= '0' && c <= '9');
    }
}
