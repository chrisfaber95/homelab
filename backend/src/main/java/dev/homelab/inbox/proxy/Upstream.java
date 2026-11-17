package dev.homelab.inbox.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * One call to an upstream service (forge, the search API, the router) and the answer in the shape the Python inbox
 * gives (bin/homelab_inbox/search.py {@code call}, server.py {@code flat_error} and {@code send_upstream}): same status,
 * same body, errors flattened to {@code {"error": "text"}}, and the headers the Python server puts on a JSON answer.
 */
public final class Upstream {

    /** What an upstream answered: the status and the parsed JSON body. */
    public record Reply(int status, JsonNode body) {}

    static final ObjectMapper JSON = JsonMapper.builder().build();
    static final String JSON_UTF8 = "application/json; charset=utf-8";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build();

    static final String CSP = "default-src 'none'; style-src 'self' 'unsafe-inline'; script-src 'self'; font-src 'self'; "
            + "connect-src 'self'; img-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'";

    private Upstream() {}

    /** Like Python's {@code search.call}: a dead or garbled upstream becomes a 502 with a JSON error. */
    public static Reply call(String url, JsonNode body, int timeoutSeconds) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeoutSeconds));
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            }
            HttpResponse<byte[]> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            try {
                return new Reply(status, JSON.readTree(new String(response.body(), StandardCharsets.UTF_8)));
            } catch (RuntimeException notJson) {
                if (status >= 400) {
                    return new Reply(status, error("Upstream answered HTTP " + status + "."));
                }
                throw notJson;
            }
        } catch (IOException | RuntimeException e) {
            return new Reply(502, error("The search service is unreachable: " + e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(502, error("The search service is unreachable: " + e));
        }
    }

    /** {@code {"error": {"message": text}}}, the shape the upstream services use. */
    public static ObjectNode error(String message) {
        ObjectNode inner = JSON.createObjectNode();
        inner.put("message", message);
        ObjectNode outer = JSON.createObjectNode();
        outer.set("error", inner);
        return outer;
    }

    /** Python's truthiness for a JSON value (null, false, 0, "", [] and {} are false). */
    public static boolean truthy(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return false;
        }
        if (v.isBoolean()) {
            return v.booleanValue();
        }
        if (v.isNumber()) {
            return v.doubleValue() != 0;
        }
        if (v.isString()) {
            return !v.stringValue().isEmpty();
        }
        return v.size() > 0;
    }

    /** Python's {@code str()} for a JSON scalar; other values as JSON. */
    public static String text(JsonNode v) {
        if (v.isString()) {
            return v.stringValue();
        }
        if (v.isBoolean()) {
            return v.booleanValue() ? "True" : "False";
        }
        return v.toString();
    }

    /** server.py {@code flat_error}: an error of 400 or more with {@code {"error": {"message": ...}}} becomes {@code {"error": "text"}}. */
    public static JsonNode flat(int status, JsonNode body) {
        if (status < 400 || body == null || !body.isObject() || !body.path("error").isObject()) {
            return body;
        }
        JsonNode inner = body.get("error");
        ObjectNode out = JSON.createObjectNode();
        for (Iterator<Map.Entry<String, JsonNode>> it = body.properties().iterator(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getKey().equals("error")) {
                out.set(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, JsonNode> e : inner.properties()) {
            if (!e.getKey().equals("message")) {
                out.set(e.getKey(), e.getValue());
            }
        }
        JsonNode message = inner.get("message");
        out.put("error", truthy(message) ? text(message) : "HTTP " + status);
        return out;
    }

    /** What a handler returns: the status and the JSON text. Written by {@link #write}, not by a Spring converter. */
    public record Answer(int status, String json) {}

    /** server.py {@code send_upstream}: the status and the flattened body as UTF-8 JSON. */
    public static Answer respond(int status, JsonNode body) {
        return new Answer(status, JSON.writeValueAsString(flat(status, body)));
    }

    /**
     * Writes an answer with the headers of the Python server ({@code Content-Type: application/json; charset=utf-8},
     * {@code Cache-Control: no-store}, the security headers) and an exact Content-Length. Tomcat writes the content type
     * as {@code application/json;charset=utf-8} (no space): the same type to every client; the contract tests and the
     * parity harness compare the normalized form.
     */
    public static void write(HttpServletResponse response, Answer answer) throws IOException {
        byte[] data = answer.json().getBytes(StandardCharsets.UTF_8);
        response.setStatus(answer.status());
        response.setHeader("Content-Type", JSON_UTF8);
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", CSP);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }

    /** Python's {@code {"error": "text"}} (a plain string error, not wrapped). */
    public static Answer plainError(int status, String text) {
        ObjectNode node = JSON.createObjectNode();
        node.put("error", text);
        return respond(status, node);
    }
}
