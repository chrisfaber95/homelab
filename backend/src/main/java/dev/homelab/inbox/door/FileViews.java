package dev.homelab.inbox.door;

import dev.homelab.inbox.door.Ports.Chunk;
import dev.homelab.inbox.door.Ports.Health;
import dev.homelab.inbox.door.Ports.Logs;
import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The read-only views of the door over files: the task logs and the monitor's checks. They never write. */
public final class FileViews {

    private static final int LIMIT = 500;

    private FileViews() {}

    /** {@code helpers.read_log}: lines {@code start} to {@code start + 500} of {@code logs/<key>.log}. */
    public static Logs logs(Settings settings, JsonMapper json) {
        return (key, start) -> {
            List<String> all;
            try {
                all = Files.readAllLines(settings.logsDir().resolve(key + ".log"));
            } catch (NoSuchFileException e) {
                return new Chunk(List.of(), 0);
            } catch (IOException e) {
                return new Chunk(List.of(), 0);
            }
            int from = Math.min(start, all.size());
            int to = Math.min(all.size(), start + LIMIT);
            List<Map<String, Object>> lines = new ArrayList<>();
            for (String line : all.subList(from, Math.max(from, to))) {
                JsonNode n = json.readTree(line);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("at", n.path("at").asString(""));
                m.put("kind", n.path("kind").asString(""));
                m.put("text", n.path("text").asString(""));
                lines.add(m);
            }
            return new Chunk(lines, Math.min(all.size(), start + LIMIT));
        };
    }

    /** {@code overview.health()["checks"]}: {@code checks.json} of the monitor folder; empty when it cannot be read. */
    public static Health health(Settings settings, JsonMapper json) {
        return () -> {
            Path file = settings.monitorDir().resolve("checks.json");
            Map<String, Map<String, Object>> out = new LinkedHashMap<>();
            try {
                JsonNode n = json.readTree(Files.readString(file));
                for (var e : n.properties()) {
                    Map<String, Object> c = new LinkedHashMap<>();
                    c.put("status", e.getValue().path("status").isString() ? e.getValue().path("status").stringValue() : null);
                    c.put("text", e.getValue().path("text").isString() ? e.getValue().path("text").stringValue() : null);
                    out.put(e.getKey(), c);
                }
            } catch (IOException | RuntimeException e) {
                out.clear();
            }
            return out;
        };
    }
}
