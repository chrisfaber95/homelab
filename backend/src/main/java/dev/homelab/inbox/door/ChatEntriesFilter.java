package dev.homelab.inbox.door;

import dev.homelab.inbox.frontdoor.FrontDoorFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The chat door's other two routes, {@code /api/chat/entries} and {@code /api/chat/state}, over HTTP (H-028 X1b): the Python
 * {@code chat_door} answered them for the Discord bot, and the bot keeps calling them on {@code 127.0.0.1:8105}. With the
 * Python facade gone the client can no longer arrive as {@code X-Inbox-Client}, so this filter is the facade here: loopback
 * only (the peer address, never a header), the client's bearer token from the token folder ({@link ChatDoor#authenticate}), and
 * then the client's name is put in {@code X-Inbox-Client} for {@code ConversationController}, which does the permissions, the
 * switches, scrubbing and the refusal log. Whatever {@code X-Inbox-Client} the caller sent is dropped first, so nobody can name
 * a client of their own. The owner and CSRF checks do not apply (a chat client has no browser session); the front door lets the
 * request through because of the attribute set here, which only this filter can set. Only exists with the front door on: without
 * it the Python facade still forwards with its secret.
 */
@Component
@ConditionalOnProperty(name = "inbox.front-door.enabled", havingValue = "true")
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class ChatEntriesFilter extends OncePerRequestFilter {

    static final String CLIENT_HEADER = "X-Inbox-Client";

    private final ChatDoor door;
    private final JsonMapper json;

    public ChatEntriesFilter(ChatDoor door, JsonMapper json) {
        this.door = door;
        this.json = json;
    }

    /** The path as the router sees it: without {@code ;params}, doubled or trailing slashes. */
    static boolean entriesPath(String uri) {
        String path = uri.replaceAll(";[^/]*", "").replaceAll("/{2,}", "/").replaceAll("(?<=.)/$", "");
        return path.equals("/api/chat/entries") || path.equals("/api/chat/state");
    }

    private static boolean loopback(String ip) {
        String plain = ip.startsWith("::ffff:") ? ip.substring(7) : ip;
        return plain.equals("127.0.0.1") || plain.equals("::1");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!entriesPath(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        if (!loopback(request.getRemoteAddr())) {
            refuse(response, 403, "loopback only");
            return;
        }
        String client = door.authenticate(request.getHeader("Authorization"));
        if (client == null) {
            refuse(response, 401, "unknown client or token");
            return;
        }
        request.setAttribute(FrontDoorFilter.DOOR_PASSED, Boolean.TRUE);
        chain.doFilter(new AsClient(request, client), response);
    }

    private void refuse(HttpServletResponse response, int status, String error) throws IOException {
        byte[] data = json.writeValueAsBytes(java.util.Map.of("error", error));
        response.setStatus(status);
        response.setHeader("Content-Type", "application/json; charset=utf-8");
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }

    /** The request with exactly one {@code X-Inbox-Client}: the authenticated client's, never the caller's. */
    private static final class AsClient extends HttpServletRequestWrapper {
        private final String client;

        AsClient(HttpServletRequest request, String client) {
            super(request);
            this.client = client;
        }

        @Override
        public String getHeader(String name) {
            return CLIENT_HEADER.equalsIgnoreCase(name) ? client : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return CLIENT_HEADER.equalsIgnoreCase(name) ? Collections.enumeration(Set.of(client)) : super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            Set<String> names = new LinkedHashSet<>(Collections.list(super.getHeaderNames()));
            names.removeIf(CLIENT_HEADER::equalsIgnoreCase);
            names.add(CLIENT_HEADER);
            return Collections.enumeration(names);
        }
    }
}
