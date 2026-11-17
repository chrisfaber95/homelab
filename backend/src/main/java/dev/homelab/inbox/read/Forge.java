package dev.homelab.inbox.read;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Forge's row in "Nu bezig" and forge's log, read from forge's own endpoints on the tailnet address (a port of
 * the read half of {@code forge.py}, H-024 M1). Start and Stop stay in Python (M3). As in Python, the state is kept for
 * two seconds and a forge that does not answer is not asked again for thirty.
 */
@Component
public class Forge {

    public static final String LIVE_KEY = "f1";
    public static final String UNREACHABLE = "Forge is not reachable (is homelab-app-forge running?).";
    private static final int LOG_LIMIT = 500;
    private static final double CACHE_SECONDS = 2.0;
    private static final double FAIL_BACKOFF = 30.0;
    private static final Pattern ISO = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T[\\d:+.-]+");
    private static final Pattern TOOL = Pattern.compile("[A-Z][A-Za-z_]*:.*", Pattern.DOTALL);

    private final Settings settings;
    private final JsonMapper json;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private Long lastAtNanos;
    private Map<String, Object> lastData;

    public Forge(Settings settings, JsonMapper json, Clock clock) {
        this.settings = settings;
        this.json = json;
        this.clock = clock;
    }

    /** GET a JSON object from forge: the parsed map for a 200, otherwise empty. */
    private Optional<Map<String, Object>> get(String path, int timeoutSeconds) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(settings.forgeUrl() + path))
                .timeout(Duration.ofSeconds(timeoutSeconds)).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return Optional.empty();
            }
            Object body = json.readValue(response.body(), Object.class);
            if (body instanceof Map<?, ?> m) {
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((k, v) -> out.put(String.valueOf(k), v));
                return Optional.of(out);
            }
            return Optional.empty();
        } catch (IOException | JacksonException | IllegalArgumentException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static boolean running(Map<String, Object> data) {
        if (data.get("running") instanceof Boolean b) {
            return b;
        }
        Object word = data.get("status") != null && Text.truthy(data.get("status")) ? data.get("status") : data.get("state");
        return Text.truthy(word) && "running".equals(Text.pyStr(word).toLowerCase(java.util.Locale.ROOT));
    }

    private Map<String, Object> view(int timeoutSeconds) {
        return get("/api/state", timeoutSeconds).map(data -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("running", running(data));
            v.put("step", data.get("step"));
            v.put("project", data.get("project"));
            v.put("activity", data.get("activity"));
            v.put("started", data.get("started_at"));
            v.put("paused", Text.truthy(data.get("paused")));
            v.put("pause_reason", data.get("pause_reason"));
            return v;
        }).orElse(null);
    }

    private synchronized Map<String, Object> recentView() {
        double age = lastAtNanos == null ? -1 : (System.nanoTime() - lastAtNanos) / 1e9;
        if (lastAtNanos != null && age < (lastData != null ? CACHE_SECONDS : FAIL_BACKOFF)) {
            return lastData;
        }
        lastData = view(2);
        lastAtNanos = System.nanoTime();
        return lastData;
    }

    /** Forge's row in "Nu bezig", or empty when it is not working. */
    public Optional<Map<String, Object>> liveItem() {
        Map<String, Object> data = recentView();
        if (data == null || !Boolean.TRUE.equals(data.get("running"))) {
            return Optional.empty();
        }
        Object step = Text.truthy(data.get("step")) ? data.get("step") : "bezig";
        Object what = Text.truthy(data.get("activity")) ? data.get("activity")
                : Boolean.TRUE.equals(data.get("paused")) ? "paused: " + Text.pyStr(data.get("pause_reason"))
                : "stap " + Text.pyStr(step);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("key", LIVE_KEY);
        item.put("title", "Forge");
        item.put("what", what);
        item.put("repo", Text.truthy(data.get("project")) ? data.get("project") : "eigen projecten");
        item.put("model", "Claude (eigen login)");
        item.put("url", settings.forgePageUrl() + "/");
        item.put("cancel", null);
        item.put("started", Text.truthy(data.get("started")) ? data.get("started") : Text.isoUtc(clock.instant()));
        return Optional.of(item);
    }

    /** The colour of a log line: forge's own words, a tool it used, or something that went wrong. */
    static String kindOf(String text) {
        String low = text.toLowerCase(java.util.Locale.ROOT);
        if (low.startsWith("refused") || low.startsWith("paused") || low.contains("failed") || low.contains("not built")) {
            return "error";
        }
        if (low.startsWith("claude ") || low.startsWith("out of tokens")) {
            return "claude";
        }
        return TOOL.matcher(text).matches() ? "tool" : "info";
    }

    private Map<String, Object> logLine(Object line) {
        String s = Text.pyStr(line);
        int space = s.indexOf(' ');
        String at = space < 0 ? s : s.substring(0, space);
        String text = space < 0 ? "" : s.substring(space + 1);
        if (text.isEmpty() || !ISO.matcher(at).matches()) {
            at = Text.isoUtc(clock.instant());
            text = s;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("at", at);
        out.put("kind", kindOf(text));
        out.put("text", Text.cut(text, 600));
        return out;
    }

    /** Result of {@link #log}: the chunk, or empty when forge does not answer. */
    public Optional<Map<String, Object>> log(long start) {
        long from = Math.max(0, start);
        Optional<Map<String, Object>> data = get("/api/log?from=" + from, 5);
        if (data.isEmpty() || !(data.get().get("lines") instanceof List<?> raw)) {
            return Optional.empty();
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (Object line : raw.subList(0, Math.min(raw.size(), LOG_LIMIT))) {
            lines.add(logLine(line));
        }
        Object next = data.get().get("next");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lines", lines);
        out.put("next", next instanceof Integer || next instanceof Long ? next : (Object) (from + lines.size()));
        out.put("done", !Text.truthy(data.get().get("running")));
        return Optional.of(out);
    }
}
