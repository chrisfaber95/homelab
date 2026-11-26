package dev.homelab.inbox.frontdoor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * On the tailnet connector ({@link PublicConnector}) the loopback-only doors stay refused (H-028 W5b): the chat door
 * ({@code /api/chat/action}) and any request carrying {@code X-Homelab-Client} (the kitt door) get a 403 before they
 * reach the doors, whatever the peer address. The doors check loopback themselves too; this is the second lock, by the
 * port the request came in on. Only exists when {@code inbox.public.port} has a value.
 */
@Component
@ConditionalOnExpression("'${inbox.public.port:}'.trim() != ''")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicPortGuard extends OncePerRequestFilter {

    private final int publicPort;

    public PublicPortGuard(@Value("${inbox.public.port}") String port) {
        this.publicPort = PublicConnector.parse(port.strip());
    }

    /** The path as the router sees it: without {@code ;params}, doubled or trailing slashes. */
    static boolean chatPath(String uri) {
        String path = uri.replaceAll(";[^/]*", "").replaceAll("/{2,}", "/");
        return path.equals("/api/chat/action") || path.equals("/api/chat/action/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean door = chatPath(request.getRequestURI()) || request.getHeader("X-Homelab-Client") != null;
        if (request.getLocalPort() == publicPort && door) {
            byte[] data = "{\"error\": \"loopback only\"}".getBytes(StandardCharsets.UTF_8);
            SecurityHeaders.apply(response);
            response.setStatus(403);
            response.setHeader("Content-Type", "application/json; charset=utf-8");
            response.setContentLength(data.length);
            response.getOutputStream().write(data);
            return;
        }
        chain.doFilter(request, response);
    }
}
