package dev.homelab.inbox.frontdoor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/**
 * The front door of the Python inbox ({@code Handler} in {@code server.py}) as a servlet filter: the owner check, the
 * CSRF check on every mutating request and the headers on every answer.
 * <ul>
 *   <li>GET: the owner check only.</li>
 *   <li>POST: the owner check, then Origin and token. {@code /api/*} takes the token only as {@code X-CSRF-Token}
 *       (from {@code GET /api/session}); the pages' forms carry it as the field {@code csrf}.</li>
 *   <li>PUT, PATCH, DELETE: on {@code /api/*} the same checks, then 405; elsewhere 501, unchecked, like Python.</li>
 *   <li>Any other method: 501.</li>
 * </ul>
 * Errors are {@code {"error": "..."}} on {@code /api/*} and a small HTML page elsewhere. Not active until H-027
 * ({@link FrontDoorEnabled}). {@code /api/chat/action} is the chat door's (loopback and bearer token, H-028 W4a) and skips the owner and CSRF
 * checks. The kitt door answers before this filter (`KittDoorFilter`, H-028 W4b); the gate on 8121 is W5.
 */
@Component
@FrontDoorEnabled
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class FrontDoorFilter extends OncePerRequestFilter {

    public static final String WHO = "inbox.who";
    static final String JSON_UTF8 = "application/json; charset=utf-8";

    private final TailnetAccess access;
    private final Csrf csrf;
    private final JsonMapper json;

    public FrontDoorFilter(TailnetAccess access, Csrf csrf, JsonMapper json) {
        this.access = access;
        this.csrf = csrf;
        this.json = json;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        SecurityHeaders.apply(response);
        String method = request.getMethod();
        if (isChatDoor(request)) { // its own checks: loopback, bearer token per client (ChatDoor)
            chain.doFilter(request, response);
            return;
        }
        boolean api = request.getRequestURI().startsWith("/api/");
        if (!method.equals("GET") && !method.equals("POST") && !(api && isOtherMutating(method))) {
            plain(response, 501, "Unsupported method ('" + method + "')");
            return;
        }
        TailnetAccess.Decision who = access.allowed(request.getRemoteAddr());
        if (!who.allowed()) {
            refuse(response, api, 403, "<p>Alleen vanaf je eigen apparaten op het tailnet.</p>",
                    "Only from your own devices on the tailnet.");
            return;
        }
        request.setAttribute(WHO, who.who());
        if (!method.equals("GET")) {
            if (badContentLength(request)) {
                refuse(response, api, 400, "<p>Slechte aanvraag.</p>", "Bad Content-Length.");
                return;
            }
            if (!csrfOk(request, response, api)) {
                return;
            }
            if (isOtherMutating(method)) {
                reply(response, 405, Map.of("error", method + " is not supported"));
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static boolean isChatDoor(HttpServletRequest request) {
        String method = request.getMethod();
        return request.getRequestURI().equals("/api/chat/action") && (method.equals("GET") || method.equals("POST"));
    }

    private static boolean isOtherMutating(String method) {
        return method.equals("PUT") || method.equals("PATCH") || method.equals("DELETE");
    }

    private static boolean badContentLength(HttpServletRequest request) {
        String length = request.getHeader("Content-Length");
        return length != null && !length.isBlank() && !length.strip().matches("[+-]?\\d+");
    }

    private boolean csrfOk(HttpServletRequest request, HttpServletResponse response, boolean api) throws IOException {
        if (!Csrf.originMatches(request.getHeader("Origin"), request.getHeader("Host"))) {
            refuse(response, api, 403, "<p>Verkeerde herkomst.</p>", "Wrong origin.");
            return false;
        }
        String sent = api ? request.getHeader("X-CSRF-Token") : request.getParameter("csrf");
        if (!csrf.matches(sent)) {
            refuse(response, api, 403, "<p>Verlopen formulier; ververs de pagina.</p>",
                    "Missing or wrong X-CSRF-Token; fetch it from GET /api/session.");
            return false;
        }
        return true;
    }

    private void refuse(HttpServletResponse response, boolean api, int status, String html, String error) throws IOException {
        if (api) {
            reply(response, status, Map.of("error", error));
        } else {
            write(response, status, "text/html; charset=utf-8", ErrorPage.html("geweigerd", html));
        }
    }

    private void reply(HttpServletResponse response, int status, Object body) throws IOException {
        write(response, status, JSON_UTF8, json.writeValueAsString(body));
    }

    private void plain(HttpServletResponse response, int status, String text) throws IOException {
        write(response, status, "text/html; charset=utf-8", ErrorPage.html("fout", "<p>" + ErrorPage.escape(text) + "</p>"));
    }

    private static void write(HttpServletResponse response, int status, String contentType, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setHeader("Content-Type", contentType);
        response.setContentLength(data.length);
        response.getOutputStream().write(data);
    }
}
