package dev.homelab.inbox.read;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /api/log/t<id>} and {@code r<id>}: lines of {@code logs/<key>.log} (helpers.read_log, H-024 M1). */
@Component
public class LogReader {

    private static final int LIMIT = 500;

    private final Settings settings;
    private final InboxDb db;
    private final JsonMapper json;

    public LogReader(Settings settings, InboxDb db, JsonMapper json) {
        this.settings = settings;
        this.db = db;
        this.json = json;
    }

    private record Chunk(List<Object> lines, long next) {}

    private Chunk read(String key, long start, int limit) {
        List<String> all;
        try {
            all = Files.readAllLines(settings.logsDir().resolve(key + ".log"));
        } catch (NoSuchFileException e) {
            return new Chunk(List.of(), 0);
        } catch (IOException e) {
            return new Chunk(List.of(), 0);
        }
        int from = (int) Math.min(start, all.size());
        int to = (int) Math.min((long) all.size(), start + limit);
        List<Object> lines = new ArrayList<>();
        for (String line : all.subList(from, Math.max(from, to))) {
            lines.add(json.readValue(line, Object.class));
        }
        return new Chunk(lines, Math.min(all.size(), start + limit));
    }

    /** The body for a task ({@code t}) or a job run ({@code r}) log, from line {@code start}. */
    public Map<String, Object> read(char kind, String id, long start) {
        String key = kind + id;
        Chunk chunk = read(key, start, LIMIT);
        Long numeric = parseId(id);
        boolean done;
        if (kind == 't') {
            Map<String, Object> t = numeric == null ? null : db.one("SELECT status FROM tasks WHERE id=?", numeric);
            done = t == null || !(List.of("queued", "running").contains(InboxDb.str(t, "status")));
        } else {
            Map<String, Object> r = numeric == null ? null : db.one("SELECT status FROM job_runs WHERE id=?", numeric);
            done = r == null || !(List.of("queued", "running").contains(InboxDb.str(r, "status")));
        }
        // Finished, but lines are left: those first (done only when everything is in).
        boolean more = !read(key, chunk.next(), 1).lines().isEmpty();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lines", chunk.lines());
        body.put("next", chunk.next());
        body.put("done", done && !more);
        return body;
    }

    private static Long parseId(String id) {
        try {
            return Long.parseLong(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
