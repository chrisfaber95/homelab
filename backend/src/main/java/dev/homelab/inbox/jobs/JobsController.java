package dev.homelab.inbox.jobs;

import dev.homelab.inbox.gitstore.BranchRepo;
import dev.homelab.inbox.jobs.JobsWriter.Refused;
import dev.homelab.inbox.proxy.Upstream;
import dev.homelab.inbox.proxy.Upstream.Answer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The job-file write routes (H-024 M6). They are {@code /internal/jobs/*}: reached only from the Python inbox, behind the
 * facade secret. The public routes ({@code POST /draft/<id>/confirm}, {@code /job/<name>/save|pause|resume|delete}, kitt's
 * pause and resume) keep their paths, forms, CSRF check, drafts and answers in Python, which checks the owner and the
 * draft first and then sends the file here. Body: {@code {name, text, message}} for save (a null text deletes),
 * {@code {name, message}} for pause and resume and delete; answers {@code {"saved": bool}} or {@code {"error": text}}.
 */
@RestController
public class JobsController {

    private static final int MAX_BODY = 256 * 1024;
    private static final Logger LOG = LoggerFactory.getLogger(JobsController.class);

    private final JobsWriter writer;
    private final JobsRepo repo;
    private final JsonMapper json;

    public JobsController(JobsWriter writer, JobsRepo repo, JsonMapper json) {
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
            LOG.warn("job write failed", e);
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

    private Answer saved(boolean changed) {
        ObjectNode out = json.createObjectNode();
        out.put("saved", changed);
        return Upstream.respond(200, out);
    }

    @PostMapping("/internal/jobs/sync")
    public void sync(HttpServletRequest in, HttpServletResponse out) throws IOException {
        Upstream.write(out, guarded(() -> {
            if (!repo.owned()) {
                throw new BranchRepo.Failed("the job files are not imported into the Java backend yet (docs/inbox-backend.md, M6)");
            }
            repo.sync();
            ObjectNode ok = json.createObjectNode();
            ok.put("ok", true);
            ok.put("owned", true);
            return Upstream.respond(200, ok);
        }));
    }

    @PostMapping("/internal/jobs/save")
    public void save(HttpServletRequest in, HttpServletResponse out) throws IOException {
        ObjectNode b = body(in);
        Upstream.write(out, guarded(() -> {
            JsonNode text = b.get("text");
            return saved(writer.save(str(b, "name"), text == null || text.isNull() ? null : text.asString(""), str(b, "message")));
        }));
    }

    @PostMapping("/internal/jobs/delete")
    public void delete(HttpServletRequest in, HttpServletResponse out) throws IOException {
        ObjectNode b = body(in);
        Upstream.write(out, guarded(() -> saved(writer.save(str(b, "name"), null, str(b, "message")))));
    }

    @PostMapping("/internal/jobs/pause")
    public void pause(HttpServletRequest in, HttpServletResponse out) throws IOException {
        ObjectNode b = body(in);
        Upstream.write(out, guarded(() -> saved(writer.pause(str(b, "name"), true, str(b, "message")))));
    }

    @PostMapping("/internal/jobs/resume")
    public void resume(HttpServletRequest in, HttpServletResponse out) throws IOException {
        ObjectNode b = body(in);
        Upstream.write(out, guarded(() -> saved(writer.pause(str(b, "name"), false, str(b, "message")))));
    }
}
