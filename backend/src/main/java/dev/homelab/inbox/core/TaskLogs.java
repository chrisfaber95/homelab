package dev.homelab.inbox.core;

import dev.homelab.inbox.read.Settings;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

/** {@code helpers.read_log}: lines of {@code logs/<key>.log} for the task pages; no file is an empty chunk. */
public class TaskLogs implements TaskPages.Logs {

    private final Settings settings;
    private final JsonMapper json;

    public TaskLogs(Settings settings, JsonMapper json) {
        this.settings = settings;
        this.json = json;
    }

    @Override
    public TaskPages.LogChunk read(String key, int start, int limit) {
        List<String> all;
        try {
            all = Files.readAllLines(settings.logsDir().resolve(key + ".log"));
        } catch (IOException e) {
            return new TaskPages.LogChunk(List.of(), 0);
        }
        int from = Math.min(start, all.size());
        int to = (int) Math.min((long) all.size(), (long) start + limit);
        List<Object> lines = new ArrayList<>();
        for (String line : all.subList(from, Math.max(from, to))) {
            lines.add(json.readValue(line, Object.class));
        }
        return new TaskPages.LogChunk(lines, to);
    }
}
