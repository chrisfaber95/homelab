package dev.homelab.inbox.door;

import dev.homelab.inbox.frontdoor.SecurityHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The kitt door over HTTP (H-028 W4b): {@code kitt_door} in {@code server.py}, which runs before the owner check on every
 * GET and POST. A request with an {@code X-Homelab-Client} header is the door's whole: {@link KittDoor} refuses it with
 * 403 unless the connection is on loopback, owned by kitt's service account ({@link ProcPeers}, from the kernel's socket
 * table, never from a header) and carries Chris's login from the gate; otherwise it answers the route, or 404. It sits in
 * front of the front door's filter and of the routes of the pages (the paths {@code /api/jobs*} and {@code /api/log*}
 * are also the page's), so it is a filter and not a controller. A request without the header, or with another method,
 * goes on to the normal checks, which refuse the box itself. Only with the front door on, as the chat door.
 */
@Component
@ConditionalOnProperty(name = "inbox.front-door.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class KittDoorFilter extends OncePerRequestFilter {

    private static final int MAX_BODY = 64 * 1024;

    private final KittDoor door;
    private final JsonMapper json;

    public KittDoorFilter(KittDoor door, JsonMapper json) {
        this.door = door;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest in, HttpServletResponse out, FilterChain chain)
            throws ServletException, IOException {
        String method = in.getMethod();
        String client = in.getHeader("X-Homelab-Client");
        if (client == null || client.isEmpty() || !(method.equals("GET") || method.equals("POST"))) {
            chain.doFilter(in, out);
            return;
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : Collections.list(in.getHeaderNames())) {
            headers.put(name, in.getHeader(name));
        }
        // the peer is the connection's own address, never a forwarded header; the body is not parsed before KittDoor
        // has identified the caller
        byte[] body = method.equals("POST") ? in.getInputStream().readNBytes(MAX_BODY) : new byte[0];
        Req req = new Req(method, in.getRequestURI(), DoorHttp.query(in.getQueryString()), headers, body,
                in.getRemoteAddr(), in.getRemotePort(), in.getLocalPort());
        Result result = door.handle(req).orElseGet(() -> Result.error(403, "not a call this door answers"));
        byte[] data = json.writeValueAsBytes(result.body());
        SecurityHeaders.apply(out);
        out.setStatus(result.status());
        out.setHeader("Content-Type", "application/json; charset=utf-8");
        out.setContentLength(data.length);
        out.getOutputStream().write(data);
    }
}
