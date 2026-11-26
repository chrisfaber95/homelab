package dev.homelab.inbox.door;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /api/chat/action} over HTTP (H-028 W4a): the servlet side of {@link ChatDoor}, as {@code chat_door} in
 * {@code server.py} is the HTTP side of {@code chat_action}. The checks are the door's own: loopback only (the peer
 * address of the connection, never a header), the client's bearer token from {@code config/clients.json}'s clients and
 * the token folder, the client's switches, then the action. The front door's owner and CSRF checks do not apply to this
 * path (a chat client has no browser session); see {@code FrontDoorFilter}.
 */
@RestController
class ChatDoorController {

    private static final int MAX_BODY = 64 * 1024;

    private final ChatDoor door;
    private final JsonMapper json;

    ChatDoorController(ChatDoor door, JsonMapper json) {
        this.door = door;
        this.json = json;
    }

    @RequestMapping(ChatDoor.ACTION_PATH)
    void action(HttpServletRequest in, HttpServletResponse out) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(in.getHeaderNames())) {
            headers.put(name, in.getHeader(name));
        }
        byte[] body = in.getInputStream().readNBytes(MAX_BODY);
        Req req = new Req(in.getMethod(), in.getRequestURI(), Map.of(), headers, body, in.getRemoteAddr(),
                in.getRemotePort(), in.getLocalPort());
        Result result = door.handle(req).orElseGet(() -> Result.error(403, "not a call this door answers"));
        byte[] data = json.writeValueAsBytes(result.body());
        out.setStatus(result.status());
        out.setHeader("Content-Type", "application/json; charset=utf-8");
        out.setContentLength(data.length);
        out.getOutputStream().write(data);
    }
}
