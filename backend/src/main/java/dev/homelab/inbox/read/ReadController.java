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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The read routes moved from the Python inbox (H-024 M1 and M2): {@code GET /api/overview}, {@code GET /api/live},
 * {@code GET /api/log/{key}}, {@code GET /api/skills}, {@code GET /api/memory} and {@code GET /api/memory/e/{id}}. Same paths, status codes, content types and error bodies as {@code server.py} and
 * {@code backend/openapi.yaml} say. Reached only through the Python facade (it did the tailnet owner check), which is
 * why the facade secret is required (FacadeSecretFilter).
 */
@RestController
public class ReadController {

    private static final String JSON_UTF8 = "application/json; charset=utf-8";
    private static final String JSON_PLAIN = "application/json";
    private static final String MEMORY_ENTRY_PREFIX = "/api/memory/e/";
    private static final Pattern TASK_OR_RUN = Pattern.compile("([tr])([0-9]+)");

    private final OverviewService overview;
    private final LogReader logs;
    private final Forge forge;
    private final SkillsService skills;
    private final MemoryReader memory;
    private final JsonMapper json;

    public ReadController(OverviewService overview, LogReader logs, Forge forge, SkillsService skills,
                          MemoryReader memory, JsonMapper json) {
        this.overview = overview;
        this.logs = logs;
        this.forge = forge;
        this.skills = skills;
        this.memory = memory;
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

    @GetMapping("/api/skills")
    public ResponseEntity<String> skills() {
        return reply(200, JSON_UTF8, skills.data());
    }

    /** The memory list; {@code q} is cut to 200 characters, a blank one counts as missing (parse_qs). */
    @GetMapping("/api/memory")
    public ResponseEntity<String> memory(HttpServletRequest request) {
        String q = request.getParameter("q");
        return reply(200, JSON_UTF8, memory.view(q == null ? "" : Text.cut(q, 200)));
    }

    /** One entry; the id has slashes, so it is the rest of the raw path (Python matches {@code url.path} undecoded). */
    @GetMapping("/api/memory/e/**")
    public ResponseEntity<String> memoryEntry(HttpServletRequest request) {
        String id = request.getRequestURI().substring(MEMORY_ENTRY_PREFIX.length());
        if (id.isEmpty()) {
            return error(404, "Not found.");
        }
        return memory.entry(id).map(e -> reply(200, JSON_UTF8, e)).orElseGet(() -> error(404, "No such memory entry."));
    }

    /**
     * Anything else under /api/, in the Python inbox's words (the parity harness reads a 404 as "not moved yet"): a GET or
     * POST is "not found" (the Python POST handlers of /api/* are other paths), PUT, PATCH and DELETE are 405 (the facade
     * has done the CSRF check for them, as Python does before it answers).
     */
    @RequestMapping("/api/**")
    public ResponseEntity<String> other(HttpServletRequest request) {
        String method = request.getMethod();
        return method.equals("GET") || method.equals("POST") ? error(404, "Not found.")
                : error(405, method + " is not supported");
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
