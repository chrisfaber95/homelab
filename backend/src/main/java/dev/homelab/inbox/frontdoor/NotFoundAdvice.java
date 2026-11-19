package dev.homelab.inbox.frontdoor;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.databind.json.JsonMapper;

/** A path nobody serves ends as {@code refuse(404)} in {@code server.py}: JSON for /api/*, the little HTML page elsewhere. */
@RestControllerAdvice
@FrontDoorEnabled
class NotFoundAdvice {

    private final JsonMapper json;

    NotFoundAdvice(JsonMapper json) {
        this.json = json;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<String> notFound(HttpServletRequest request) {
        if (request.getRequestURI().startsWith("/api/")) {
            return ResponseEntity.status(404).header("Content-Type", FrontDoorFilter.JSON_UTF8)
                    .header("Cache-Control", "no-store").body(json.writeValueAsString(Map.of("error", "Not found.")));
        }
        return ResponseEntity.status(404).header("Content-Type", "text/html; charset=utf-8")
                .header("Cache-Control", "no-store").body(ErrorPage.html("niet gevonden", "<p>Niet gevonden.</p>"));
    }
}
