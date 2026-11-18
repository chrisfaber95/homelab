package dev.homelab.inbox.jobs;

import dev.homelab.inbox.read.Jobs;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The writes of the job files, moved from the Python inbox (H-024 M6): save (a new, changed or edited job), pause, resume and
 * delete ({@code jobs.save_job}, {@code jobs.set_paused} and what {@code server.post_jobs} and kitt did with them). The page
 * routes, drafts, run history and the scheduler stay in Python; Python checks the draft and the name it knows about and
 * sends the file and the commit message here. A file is only saved when it passes the rules of {@code jobs.parse_job} (the
 * same checks as the Java reader {@link Jobs}), and the name is a plain job name, so nothing outside {@code jobs/} is ever written.
 */
@Component
public class JobsWriter {

    /** A rule of the job store refused the call: the text is what the page shows (HTTP 400, or 404 for an unknown job). */
    public static final class Refused extends RuntimeException {
        private final int status;

        public Refused(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9-]{0,40}$");
    /** str.splitlines(): the line boundaries Python knows. */
    private static final Pattern LINES = Pattern.compile("\r\n|[\n\r\u000b\u000c\u001c-\u001e\u0085  ]");

    private final JobsRepo repo;
    private final Jobs jobs;

    public JobsWriter(JobsRepo repo, Jobs jobs) {
        this.repo = repo;
        this.jobs = jobs;
    }

    private static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new Refused(400, "de naam mag alleen kleine letters, cijfers en streepjes hebben");
        }
    }

    private static String message(String message) {
        if (message == null || message.isBlank()) {
            throw new Refused(400, "a commit message is required");
        }
        return message;
    }

    /** Save {@code text} as the job {@code name}, or delete the job when {@code text} is null; true when something changed. */
    public boolean save(String name, String text, String message) {
        checkName(name);
        message(message);
        if (text != null) {
            try {
                jobs.parse(name, text, jobs.repoNames(), jobs.skillNames());
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new Refused(400, String.valueOf(e.getMessage()));
            }
        }
        return repo.update(name, current -> text, message);
    }

    /** {@code set_paused}: the header without its {@code paused:} lines, plus {@code paused: true} when pausing. */
    static String setPaused(String text, boolean paused) {
        String afterOpen = text.substring(4);
        int close = afterOpen.indexOf("\n---");
        String head = close < 0 ? afterOpen : afterOpen.substring(0, close);
        String tail = close < 0 ? "" : afterOpen.substring(close);
        List<String> lines = new ArrayList<>(List.of(LINES.split(head, -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1); // splitlines() has no empty last line
        }
        lines.removeIf(l -> l.startsWith("paused:"));
        if (paused) {
            lines.add("paused: true");
        }
        return "---\n" + String.join("\n", lines) + tail;
    }

    /** Pause or resume the job {@code name}: the same file with {@code paused: true} added or removed. */
    public boolean pause(String name, boolean paused, String message) {
        checkName(name);
        message(message);
        return repo.update(name, current -> {
            if (current == null) {
                throw new Refused(404, "Niet gevonden.");
            }
            try {
                jobs.parse(name, current, jobs.repoNames(), jobs.skillNames());
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw new Refused(400, "the job file has an error, fix it first: " + e.getMessage());
            }
            return setPaused(current, paused);
        }, message);
    }
}
