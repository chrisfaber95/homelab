package dev.homelab.inbox;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The Python server forwards requests here (H-023, B6) with the shared secret in {@code X-Inbox-Facade}. Every route
 * except {@code GET /api/backend/health} answers 403 without the right secret. With no secret configured, nothing
 * passes (fail closed). The secret comes from {@code BACKEND_SECRET} or from the file {@code BACKEND_SECRET_FILE}
 * (read on each request, so a rotated file applies at once); it is never in the repo. With the front door on
 * ({@code inbox.front-door.enabled}) every request passes: the front door (or the chat door) has its own checks and
 * no Python facade sends the secret any more (staging, H-028 S1).
 */
@Component
public class FacadeSecretFilter extends OncePerRequestFilter {

    static final String HEADER = "X-Inbox-Facade";
    static final String HEALTH = "/api/backend/health";

    private final String secret;
    private final String secretFile;
    private final boolean frontDoor;

    public FacadeSecretFilter(@Value("${backend.secret:}") String secret,
                              @Value("${backend.secret-file:}") String secretFile,
                              @Value("${inbox.front-door.enabled:false}") boolean frontDoor) {
        this.frontDoor = frontDoor;
        this.secret = secret.strip();
        this.secretFile = secretFile.strip();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("GET".equals(request.getMethod()) && HEALTH.equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        if (frontDoor) {
            // Java faces the clients itself (staging and cut-over, H-028): no Python facade sends the secret any more.
            // FrontDoorFilter runs first and has already refused what is not the owner's device (or, for the chat door,
            // left the loopback and bearer-token checks to the door), so a browser has no header to send.
            chain.doFilter(request, response);
            return;
        }
        String expected = expectedSecret();
        String sent = request.getHeader(HEADER);
        if (expected.isEmpty() || sent == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), sent.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"missing or wrong " + HEADER + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private String expectedSecret() {
        if (!secret.isEmpty()) {
            return secret;
        }
        if (secretFile.isEmpty()) {
            return "";
        }
        try {
            return Files.readString(Path.of(secretFile)).strip();
        } catch (IOException e) {
            logger.warn("cannot read the facade secret file; refusing every non-health request");
            return "";
        }
    }
}
