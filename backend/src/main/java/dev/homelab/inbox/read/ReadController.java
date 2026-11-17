package dev.homelab.inbox.read;

import jakarta.servlet.http.HttpServletRequest;
import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read routes moved from the Python inbox (H-024 M1): {@code GET /api/overview}, {@code GET /api/live} and
 * {@code GET /api/log/{key}}. Same paths, status codes, content types and error bodies as {@code server.py} and
 * {@code backend/openapi.yaml} say. Reached only through the Python facade (it did the tailnet owner check), which is
 * why the facade secret is required (FacadeSecretFilter).
 */
@RestController
public class ReadController {

    private static final String JSON_UTF8 = "application/json; charset=utf-8";
    private static final String JSON_PLAIN = "application/json";
    private static final Pattern TASK_OR_RUN = Pattern.compile("([tr])([0-9]+)");

    private final OverviewService overview;
    private final LogReader logs;
    private final Forge forge;
    private final JsonMapper json;

    public ReadController(OverviewService overview, LogReader logs, Forge forge, JsonMapper json) {
        this.overview = overview;
        this.logs = logs;
        this.forge = forge;
        this.json = json;
    }

    private ResponseEntity<String> reply(int status, String contentType, Object body) {
        return ResponseEntity.status(status).header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-store").body(json.writeValueAsString(body));
    }

    private ResponseEntity<String> error(int status, String message) {
        return reply(status, JSON_UTF8, Map.of("error", message));
    }

    @GetMapping("/api/overview")
    public ResponseEntity<String> overview() {
        return reply(200, JSON_UTF8, overview.overview());
    }

    @GetMapping("/api/live")
    public ResponseEntity<String> live() {
        return reply(200, JSON_PLAIN, overview.liveBody());
    }

    @GetMapping("/api/log/{key}")
    public ResponseEntity<String> log(@PathVariable String key, HttpServletRequest request) {
        boolean isForge = key.equals(Forge.LIVE_KEY);
        Matcher m = TASK_OR_RUN.matcher(key);
        if (!isForge && !m.matches()) {
            return error(404, "Not found.");
        }
        // parse_qs: the first value, and a blank value counts as missing; int() takes a sign and surrounding spaces.
        String raw = request.getParameter("from");
        BigInteger start;
        try {
            start = raw == null || raw.isEmpty() ? BigInteger.ZERO : new BigInteger(raw.strip().replaceFirst("^\\+", ""));
        } catch (NumberFormatException e) {
            return error(400, "from must be a number");
        }
        long from = start.signum() < 0 ? 0 : start.min(BigInteger.valueOf(Long.MAX_VALUE / 2)).longValue();
        if (isForge) {
            Optional<Map<String, Object>> chunk = forge.log(from);
            return chunk.isPresent() ? reply(200, JSON_UTF8, chunk.get()) : error(502, Forge.UNREACHABLE);
        }
        return reply(200, JSON_PLAIN, logs.read(m.group(1).charAt(0), m.group(2), from));
    }

    /** Any other path under /api/ is "not found" in the Python inbox's words (the parity harness reads a 404 as "not moved yet"). */
    @GetMapping("/api/**")
    public ResponseEntity<String> notFound() {
        return error(404, "Not found.");
    }

    /** server.guarded: an unexpected exception on /api/* is a 500 with a JSON error, never a dropped connection. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<String> failed(Exception e) {
        logger().warn("read route failed: " + e);
        return error(500, "the request failed; see the inbox journal");
    }

    private static org.apache.commons.logging.Log logger() {
        return org.apache.commons.logging.LogFactory.getLog(ReadController.class);
    }
}
