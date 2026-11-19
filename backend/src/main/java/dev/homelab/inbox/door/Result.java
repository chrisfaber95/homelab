package dev.homelab.inbox.door;

import java.util.LinkedHashMap;
import java.util.Map;

/** What a handler answers: an HTTP status and a JSON object (Python's {@code (status, body)} tuple). */
public record Result(int status, Map<String, Object> body) {

    /** {@code Result.of(200, "task", 5, "url", u)}: a body from key/value pairs, in order. */
    public static Result of(int status, Object... keyValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            m.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Result(status, m);
    }

    public static Result error(int status, String message) {
        return of(status, "error", message);
    }

    public Object get(String key) {
        return body.get(key);
    }

    public String error() {
        return body.get("error") == null ? null : body.get("error").toString();
    }
}
