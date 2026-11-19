package dev.homelab.inbox.frontdoor;

import jakarta.servlet.http.HttpServletResponse;

/** The headers {@code Handler.send} puts on every answer in {@code server.py}; the policy string is byte for byte Python's. */
public final class SecurityHeaders {

    public static final String CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'self' 'unsafe-inline'; "
            + "script-src 'self'; font-src 'self'; connect-src 'self'; img-src 'self'; form-action 'self'; "
            + "base-uri 'none'; frame-ancestors 'none'";

    private SecurityHeaders() {}

    public static void apply(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Security-Policy", CONTENT_SECURITY_POLICY);
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "same-origin");
    }
}
