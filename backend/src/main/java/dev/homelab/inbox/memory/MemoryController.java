package dev.homelab.inbox.memory;

import dev.homelab.inbox.memory.MemoryWriter.Author;
import dev.homelab.inbox.memory.MemoryWriter.Block;
import dev.homelab.inbox.memory.MemoryWriter.Refused;
import dev.homelab.inbox.proxy.Upstream;
import dev.homelab.inbox.proxy.Upstream.Answer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The memory write routes (H-024 M5). They are {@code /internal/memory/*}: reached only from the Python inbox, behind the
 * facade secret. The public routes ({@code POST /memory/add}, {@code /memory/e/<id>/save|delete}) keep their paths, forms,
 * CSRF check and answers in {@code server.py}, which checks the owner and the CSRF token first and then passes the form
 * on here; the GEHEUGEN: blocks and the history of a task come from the queue the same way.
 */
@RestController
public class MemoryController {

    private static final int MAX_BODY = 256 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(MemoryController.class);

    private final MemoryWriter writer;
    private final MemoryRepo repo;
    private final JsonMapper json;

    public MemoryController(MemoryWriter writer, MemoryRepo repo, JsonMapper json) {
        this.writer = writer;
        this.repo = repo;
        this.json = json;
    }

    private interface Call {
        Answer run();
    }

    private static Answer guarded(Call call) {
        try {
            return call.run();
        } catch (Refused e) {
            return Upstream.plainError(e.status(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return Upstream.plainError(400, "bad request: " + e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("memory write failed", e);
            return Upstream.plainError(400, String.valueOf(e.getMessage()));
        }
    }

    private ObjectNode body(HttpServletRequest in) throws IOException {
        JsonNode b;
        try {
            b = json.readTree(in.getInputStream().readNBytes(MAX_BODY));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("not JSON");
        }
        if (!(b instanceof ObjectNode o)) {
            throw new IllegalArgumentException("not an object");
        }
        return o;
    }

    private static String str(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? "" : v.asString("");
    }

    private static Map<String, String> form(JsonNode n) {
        Map<String, String> out = new LinkedHashMap<>();
        JsonNode f = n.get("form");
        if (f != null && f.isObject()) {
            f.properties().forEach(e -> out.put(e.getKey(), e.getValue().asString("")));
        }
        return out;
    }

    private static Author author(JsonNode n) {
        JsonNode a = n.get("author");
        if (a == null || !a.isArray() || a.size() != 2) {
            throw new IllegalArgumentException("author is [name, email]");
        }
        return new Author(a.get(0).asString(""), a.get(1).asString(""));
    }

    private Answer location(String page) {
        ObjectNode out = json.createObjectNode();
        out.put("location", page);
        return Upstream.respond(200, out);
    }

    @PostMapping("/internal/memory/sync")
    public void sync(HttpServletRequest in, HttpServletResponse out) throws IOException {
        Upstream.write(out, guarded(() -> {
            repo.sync();
            ObjectNode ok = json.createObjectNode();
            ok.put("ok", true);
            ok.put("owned", repo.owned());
            return Upstream.respond(200, ok);
        }));
    }

    @PostMapping("/internal/memory/add")
    public void add(HttpServletRequest in, HttpServletResponse out) throws IOException {
        ObjectNode b = body(in);
        Upstream.write(out, guarded(() -> location(writer.add(form(b), str(b, "who"), author(b)))));
    }

    @PostMapping("/internal/memory/save")
    public void save(HttpServletRequest in, HttpServletResponse out) throws IOException {
        ObjectNode b = body(in);
        Upstream.write(out, guarded(() -> location(writer.edit(str(b, "id"), form(b), str(b, "who"), author(b)))));
    }

    @PostMapping("/internal/memory/delete")
    public void delete(HttpServletRequest in, HttpServletResponse out) throws IOException {
        ObjectNode b = body(in);
        Upstream.write(out, guarded(() -> location(writer.delete(str(b, "id"), str(b, "who"), author(b)))));
    }

    @PostMapping("/internal/memory/learned")
    public void learned(HttpServletRequest in, HttpServletResponse out) throws IOException {
        ObjectNode b = body(in);
        Upstream.write(out, guarded(() -> {
            List<Block> blocks = new ArrayList<>();
            for (JsonNode x : b.path("blocks")) {
                blocks.add(new Block(x.path(0).asString(""), x.path(1).asString(""), x.path(2).asString(""), x.path(3).asString("")));
            }
            List<String> notes = writer.learned(blocks, str(b, "source"), str(b, "link"), str(b, "label_lower"));
            ObjectNode ok = json.createObjectNode();
            ok.set("notes", json.valueToTree(notes));
            return Upstream.respond(200, ok);
        }));
    }

    @PostMapping("/internal/memory/history")
    public void history(HttpServletRequest in, HttpServletResponse out) throws IOException {
        ObjectNode b = body(in);
        Upstream.write(out, guarded(() -> {
            MemoryWriter.Task t = new MemoryWriter.Task(b.path("id").asLong(0), str(b, "created"), str(b, "repo"),
                    str(b, "label"), str(b, "label_lower"), str(b, "link"), str(b, "status_nl"), str(b, "pr_url"),
                    str(b, "job"), str(b, "title"), str(b, "asked"), str(b, "result"), b.path("remembered").asBoolean(false));
            ObjectNode ok = json.createObjectNode();
            ok.put("saved", writer.history(t));
            return Upstream.respond(200, ok);
        }));
    }
}
