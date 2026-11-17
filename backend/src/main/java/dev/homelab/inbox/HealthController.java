package dev.homelab.inbox;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    public record Health(String status, String service) {}

    @GetMapping("/api/backend/health")
    public Health health() {
        return new Health("ok", "homelab-inbox-backend");
    }
}
