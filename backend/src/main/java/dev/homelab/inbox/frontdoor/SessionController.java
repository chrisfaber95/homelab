package dev.homelab.inbox.frontdoor;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/** {@code GET /api/session}: the CSRF token for the page's scripts. The filter has already checked the owner. */
@RestController
@FrontDoorEnabled
public class SessionController {

    private final Csrf csrf;
    private final JsonMapper json;

    public SessionController(Csrf csrf, JsonMapper json) {
        this.csrf = csrf;
        this.json = json;
    }

    @GetMapping("/api/session")
    public ResponseEntity<String> session() {
        return ResponseEntity.ok().header("Content-Type", FrontDoorFilter.JSON_UTF8).header("Cache-Control", "no-store")
                .body(json.writeValueAsString(Map.of("csrf_token", csrf.token())));
    }
}
