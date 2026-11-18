package dev.homelab.inbox.core;

import dev.homelab.inbox.read.Jobs.Job;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The text and the pure rules of jobs and research runs (H-026 K6): the research prompt and limits, the report header
 * and check, "nothing to report", the job prompt of a Claude run and the repo watcher's look-back. All of it is a
 * port of {@code bin/homelab_inbox/jobs.py}; the Dutch prompt text is kept word for word because the model reads it.
 */
public final class JobText {

    private JobText() {}

    public static final String NOTHING_MARKER = "NOTHING TO REPORT";
    public static final String RESEARCH_JOB = "research";
    public static final List<String> RESEARCH_MODES = List.of("quick", "deep");
    public static final int NTFY_DELIVER_CHARS = 4000;

    private static final DateTimeFormatter ISO_SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssxxx");

    /** How the page names a job kind in a prompt ({@code JOB_KINDS}). */
    static final Map<String, String> KIND_NL = Map.of("box-report", "boxrapport", "repo-chore", "repo-klus",
            "digest", "digest", "script", "script", "repo-watch", "repo-waker", "research", "onderzoek");

    /** How a Claude run looks in the job history, derived from its task ({@code TASK_TO_RUN}). */
    static final Map<String, String> TASK_TO_RUN = Map.ofEntries(Map.entry("queued", "queued"),
            Map.entry("running", "running"), Map.entry("waiting", "question"), Map.entry("pr_open", "pr"),
            Map.entry("merged", "pr"), Map.entry("closed", "pr"), Map.entry("answered", "ok"),
            Map.entry("capped", "capped"), Map.entry("cancelled", "cancelled"), Map.entry("failed", "failed"),
            Map.entry("refused", "failed"));

    private static final String RESEARCH_PROMPT = """
            Dit is een onderzoeksrun (alleen lezen): beantwoord de vraag hieronder met een rapport. Modus: {mode}.
            {limits}

            Vraag:
            {question}

            Regels voor het rapport (skill research-reports):
            - Raadpleeg eerst de privézoekindex: `homelab-search query "<tekst>" -k 8 --json` (en zo nodig `--source`/`--project`).
              Pas daarna het web (WebSearch, WebFetch). Zonder eerst de index te hebben geprobeerd begin je niet aan het web.
            - Elke bewering heeft een bron. Web: een link. Privézoeken: bron, plaats en datum van de treffer, zoals de index ze geeft.
              Een bewering zonder bron laat je weg of je markeert hem als "niet gevonden". Gok niet en vul niets aan uit je hoofd.
            - Geen inloggen op sites en geen bronnen achter een betaalmuur.
            - Je verandert niets: geen commit, branch of PR, geen bestanden, geen machinewijzigingen. Je tools laten het ook niet toe.
            - Geen vraag terug: neem een redelijke aanname en noem die.
            - Eindig met `ANTWOORD:` en dan het rapport in Markdown, met precies deze delen:
              `## Antwoord` (de bevindingen, elke bewering met bron achter of onder zich),
              `## Bronnen` (alle bronnen: links, en bij privézoeken bron, plaats, datum),
              `## Doorzocht` (wat je doorzocht: de zoektermen in de privé-index en op het web).
            - Vond je niets relevants, schrijf dan als eerste regel na `ANTWOORD:` `NIETS GEVONDEN`, zeg dat in één zin, en vul alleen
              `## Doorzocht`. Geen gok, geen algemene kennis als antwoord.""";

    private static final String LIMITS_QUICK = "Snel: je hebt een paar minuten (hooguit {minutes} minuten en {turns} "
            + "beurten) en gebruikt een handvol bronnen (hooguit ongeveer 5). Houd het klein.";
    private static final String LIMITS_DEEP = "Diep: tot de grens van een inbox-taak ({minutes} minuten, {turns} "
            + "beurten). Lees bronnen grondig en vergelijk ze; zoek verder waar ze elkaar tegenspreken.";

    /** {@code research_prompt}: {@code limits} is {@code {minutes, turns}} from {@link JobSettings#researchLimits}. */
    public static String researchPrompt(String mode, String question, int[] limits) {
        String text = ("quick".equals(mode) ? LIMITS_QUICK : LIMITS_DEEP).replace("{minutes}", String.valueOf(limits[0]))
                .replace("{turns}", String.valueOf(limits[1]));
        return RESEARCH_PROMPT.replace("{mode}", mode).replace("{limits}", text).replace("{question}", question);
    }

    /** The lines of the job's own instruction that define "nothing to report". */
    public static String nothingStatement(Job job) {
        return job.instruction().lines().map(String::strip).filter(l -> l.contains(NOTHING_MARKER))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static final String STRIP = " .!*_`#:";

    /** {@code is_nothing}: the first line, stripped of punctuation and markup, is the marker (any case). */
    public static boolean isNothing(String text) {
        String first = (text == null ? "" : text.strip()).lines().findFirst().orElse("");
        int a = 0;
        int b = first.length();
        while (a < b && STRIP.indexOf(first.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && STRIP.indexOf(first.charAt(b - 1)) >= 0) {
            b--;
        }
        return first.substring(a, b).toUpperCase(Locale.ROOT).equals(NOTHING_MARKER);
    }

    public static String durationText(long seconds) {
        long s = Math.max(0, seconds);
        return s >= 60 ? (s / 60) + " min " + (s % 60) + " s" : s + " s";
    }

    /** The line shown above every research report: the mode and the time taken. */
    public static String reportHeader(String mode, long seconds) {
        return "Research report | mode: " + mode + " | time taken: " + durationText(seconds);
    }

    /** What is missing from a report, in words (empty = fine). */
    public static List<String> reportProblems(String body) {
        String low = (body == null ? "" : body).toLowerCase(Locale.ROOT);
        String lead = low.stripLeading();
        boolean nothing = lead.startsWith("niets gevonden") || lead.startsWith("nothing found");
        List<String> missing = new java.util.ArrayList<>();
        if (!low.contains("## doorzocht") && !low.contains("## searched")) {
            missing.add("no list of what was searched");
        }
        if (!nothing && !low.contains("## bronnen") && !low.contains("## sources")) {
            missing.add("no sources");
        }
        return missing;
    }

    /** {@code since_day}: the start (UTC, {@code ...Z}) of the previous run's day in {@code tz}; no previous run means
     * the start of yesterday; never further back than {@code maxDays}. */
    public static String sinceDay(String previous, Instant now, ZoneId tz, int maxDays) {
        Instant ref = previous != null ? parseInstant(previous) : now.minus(Duration.ofDays(1));
        ZonedDateTime start = ref.atZone(tz).toLocalDate().atStartOfDay(tz);
        ZonedDateTime limit = now.atZone(tz).minusDays(maxDays);
        if (start.isBefore(limit)) {
            start = limit;
        }
        return start.withZoneSameInstant(ZoneOffset.UTC).format(ISO_SECONDS).replace("+00:00", "Z");
    }

    static Instant parseInstant(String iso) {
        return java.time.OffsetDateTime.parse(iso).toInstant();
    }

    static String iso(Instant t) {
        return t.atOffset(ZoneOffset.UTC).format(ISO_SECONDS);
    }

    static String iso(ZonedDateTime t) {
        return t.format(ISO_SECONDS);
    }

    /** {@code job_prompt} for the kinds that need no gathered data; the caller adds the data for the others. */
    public static String jobPrompt(Job job, String gathered, int[] researchLimits) {
        String kind = KIND_NL.get(job.kind());
        String skills = job.skills().isEmpty() ? "" : "Gebruik hiervoor de skill(s): " + String.join(", ", job.skills()) + ".";
        if ("research".equals(job.kind())) {
            return researchPrompt(job.mode(), job.instruction(), researchLimits)
                    + (skills.isEmpty() ? "" : "\n\n" + skills);
        }
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        parts.add("Dit is de geplande job '" + job.name() + "' (" + kind + "), niet door de gebruiker getypt; hij kijkt "
                + "niet mee.\n\n" + job.instruction());
        switch (job.kind()) {
            case "box-report", "digest", "repo-watch" -> parts.add("Hieronder de verzamelde gegevens. Eindig met "
                    + "ANTWOORD: (je rapport of digest); commit niets.\n\n"
                    + (gathered.length() > 30000 ? gathered.substring(0, 30000) : gathered));
            case "script" -> {
                java.util.ArrayList<String> cmd = new java.util.ArrayList<>();
                cmd.add(job.script());
                cmd.addAll(job.args());
                parts.add("Draai `" + String.join(" ", cmd) + "` in deze werkkopie (als dit account, zonder root) en vat "
                        + "de uitvoer samen zoals hierboven gevraagd. Eindig met ANTWOORD:; commit niets.");
            }
            default -> parts.add("Maak de wijziging op deze branch en eindig met KLAAR:. Is er niets te doen, eindig dan "
                    + "met ANTWOORD: en waarom (dan komt er geen PR).");
        }
        if (!skills.isEmpty()) {
            parts.add(skills);
        }
        return String.join("\n\n", parts);
    }

    private static final Pattern SCRIPT_TAGS = Pattern.compile("(?is)<(script|style|nav|footer|header)\\b.*?</\\1>");
    private static final Pattern TAGS = Pattern.compile("<[^>]+>");

    /** {@code html_text}: a web page as plain text. */
    public static String htmlText(String raw) {
        String s = TAGS.matcher(SCRIPT_TAGS.matcher(raw).replaceAll(" ")).replaceAll(" ");
        return org.springframework.web.util.HtmlUtils.htmlUnescape(s).replaceAll("\\s+", " ").strip();
    }
}
