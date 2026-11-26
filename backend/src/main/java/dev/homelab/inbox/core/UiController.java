package dev.homelab.inbox.core;

import dev.homelab.inbox.read.MemoryReader;
import dev.homelab.inbox.read.Settings;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The built UI (H-028 W5a), as {@code ported_index}, {@code dist_asset} and the {@code /static/} route of
 * {@code server.py}: {@code /}, {@code /memory}, {@code /memory/e/{id}}, {@code /search}, {@code /skills} and
 * {@code /ssh} answer {@code frontend/dist/index.html} (503 while the build is missing); {@code /app/*} are the built
 * assets and {@code /static/*} the stylesheet and fonts of {@code web/}. The other pages (tasks, jobs, drafts, runs,
 * research, briefs) are in their own controllers and use the same {@link TaskController#index()}.
 */
@RestController
public class UiController {

    private static final Pattern STATIC = Pattern.compile("(?:fonts/)?[A-Za-z0-9-]+\\.(css|js|woff2)");
    private static final Map<String, String> TYPES = Map.ofEntries(Map.entry("html", "text/html"),
            Map.entry("css", "text/css"), Map.entry("js", "text/javascript"), Map.entry("mjs", "text/javascript"),
            Map.entry("json", "application/json"), Map.entry("map", "application/json"), Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"), Map.entry("ico", "image/vnd.microsoft.icon"), Map.entry("txt", "text/plain"),
            Map.entry("woff2", "font/woff2"), Map.entry("woff", "font/woff"));

    private final TaskController tasks;
    private final Settings conf;
    private final MemoryReader memory;

    public UiController(TaskController tasks, Settings conf, MemoryReader memory) {
        this.tasks = tasks;
        this.conf = conf;
        this.memory = memory;
    }

    @GetMapping({"/", "/memory", "/search", "/skills", "/ssh"})
    public ResponseEntity<String> page() {
        return tasks.index();
    }

    /** A memory entry's page: only for an entry that exists (the id has slashes: the rest of the raw path). */
    @GetMapping("/memory/e/**")
    public ResponseEntity<String> memoryPage(HttpServletRequest request) {
        String id = request.getRequestURI().substring("/memory/e/".length());
        return !id.isEmpty() && memory.entry(id).isPresent() ? tasks.index() : TaskController.notFoundPage();
    }

    /** A built asset under {@code /app/}; never leaves {@code frontend/dist}. */
    @GetMapping("/app/**")
    public ResponseEntity<byte[]> asset(HttpServletRequest request) {
        String rest = URLDecoder.decode(request.getRequestURI().substring("/app/".length()), StandardCharsets.UTF_8);
        return file(conf.frontendDist(), rest, "private, max-age=3600");
    }

    /** The stylesheet, script and fonts of {@code web/}, only the plain names Python allows. */
    @GetMapping({"/static/{name:[A-Za-z0-9-]+\\.(?:css|js|woff2)}", "/static/fonts/{name:[A-Za-z0-9-]+\\.(?:css|js|woff2)}"})
    public ResponseEntity<byte[]> stat(HttpServletRequest request) {
        String rest = request.getRequestURI().substring("/static/".length());
        return STATIC.matcher(rest).matches() ? file(conf.webDir(), rest, "private, max-age=86400") : missing();
    }

    private static ResponseEntity<byte[]> file(Path dir, String rest, String cache) {
        try {
            Path root = dir.toRealPath();
            Path f = root.resolve(rest).normalize();
            if (rest.isEmpty() || !f.startsWith(root) || !Files.isRegularFile(f) || !f.toRealPath().startsWith(root)) {
                return missing();
            }
            String name = f.getFileName().toString();
            String type = TYPES.getOrDefault(name.substring(name.lastIndexOf('.') + 1), "application/octet-stream");
            if (type.startsWith("text/") || type.endsWith("javascript")) {
                type += "; charset=utf-8";
            }
            return ResponseEntity.ok().header("Content-Type", type).header("Cache-Control", cache).body(Files.readAllBytes(f));
        } catch (IOException e) {
            return missing();
        }
    }

    private static ResponseEntity<byte[]> missing() {
        ResponseEntity<String> page = TaskController.notFoundPage();
        return ResponseEntity.status(404).header("Content-Type", "text/html; charset=utf-8").header("Cache-Control", "no-store")
                .body(page.getBody().getBytes(StandardCharsets.UTF_8));
    }
}
