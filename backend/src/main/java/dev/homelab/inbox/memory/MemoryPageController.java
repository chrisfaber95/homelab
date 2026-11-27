package dev.homelab.inbox.memory;

import dev.homelab.inbox.frontdoor.FrontDoorFilter;
import dev.homelab.inbox.memory.MemoryWriter.Author;
import dev.homelab.inbox.memory.MemoryWriter.Refused;
import dev.homelab.inbox.read.Settings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Geheugen page's forms (H-028 X1b): {@code POST /memory/add} and {@code POST /memory/e/<id>/(save|delete)}, which
 * {@code server.py} {@code post_memory} passed on to {@code /internal/memory/*} after the owner and CSRF checks. Here the front
 * door has made those checks and the form goes straight to {@link MemoryWriter}, as the owner of the page: the same
 * {@code who} text, the same author ({@code <OWNER_GITHUB> (via de pagina)}), the same answers: 303 to the page the writer
 * names, or {@code {"error": text}} with the writer's status (404 for another path). Only with the front door on; before
 * that the Python server serves these routes.
 */
@RestController
@ConditionalOnProperty(name = "inbox.front-door.enabled", havingValue = "true")
public class MemoryPageController {

    private static final Logger LOG = LoggerFactory.getLogger(MemoryPageController.class);
    private static final Pattern EDIT = Pattern.compile("/memory/e/(.+)/(save|delete)");

    private final MemoryWriter writer;
    private final Settings settings;
    private final JsonMapper json;

    public MemoryPageController(MemoryWriter writer, Settings settings, JsonMapper json) {
        this.writer = writer;
        this.settings = settings;
        this.json = json;
    }

    @PostMapping({"/memory/add", "/memory/e/**"})
    public void write(HttpServletRequest in, HttpServletResponse out) throws IOException {
        String path = in.getRequestURI();
        Matcher m = EDIT.matcher(path);
        boolean edit = m.matches();
        if (!path.equals("/memory/add") && !edit) {
            reply(out, 404, Map.of("error", "Niet gevonden."));
            return;
        }
        Map<String, String> form = new LinkedHashMap<>();
        in.getParameterMap().forEach((k, v) -> form.put(k, v.length == 0 ? "" : v[0]));
        Object who = in.getAttribute(FrontDoorFilter.WHO);
        String text = "door jou op de pagina vanaf " + (who == null ? "" : who);
        Author author = new Author(settings.conf("OWNER_GITHUB", "chrisfaber95") + " (via de pagina)", "homelab-inbox@homelab");
        try {
            String location = !edit ? writer.add(form, text, author)
                    : m.group(2).equals("save") ? writer.edit(m.group(1), form, text, author)
                    : writer.delete(m.group(1), text, author);
            out.setStatus(303);
            out.setHeader("Location", location);
            out.setContentLength(0);
        } catch (Refused e) {
            reply(out, e.status(), Map.of("error", String.valueOf(e.getMessage())));
        } catch (RuntimeException e) {
            LOG.warn("memory page write failed", e);
            reply(out, 400, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    private void reply(HttpServletResponse out, int status, Map<String, String> body) throws IOException {
        byte[] data = json.writeValueAsBytes(body);
        out.setStatus(status);
        out.setHeader("Content-Type", "application/json; charset=utf-8");
        out.setContentLength(data.length);
        out.getOutputStream().write(data);
    }
}
