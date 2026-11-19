package dev.homelab.inbox.door;

import dev.homelab.inbox.read.Settings;
import java.nio.file.Path;
import java.time.ZoneId;

/**
 * The settings of the door, with the names and defaults of {@code config/inbox.conf} and the Python inbox:
 * {@code CONFIRM_TTL_SECONDS}, {@code CLIENT_TASKS_PER_DAY}, {@code TAILNET_NAME} and {@code INBOX_PORT} (the page
 * address), {@code KITT_ACCOUNT}, {@code KITT_UPSTREAM}, {@code KITT_GATE_HOST} and {@code KITT_GATE_PORT}, and
 * {@code CHAT_TOKEN_DIR} (the folder with {@code chat-<client>-token}).
 */
public record DoorSettings(int confirmTtlSeconds, int defaultTasksPerDay, String pageBase, String kittAccount,
                           String kittUpstream, String gateHost, int gatePort, Path tokenDir, ZoneId tz) {

    public static DoorSettings from(Settings s) {
        String tokens = s.env("CHAT_TOKEN_DIR");
        return new DoorSettings(s.confInt("CONFIRM_TTL_SECONDS", 300), s.confInt("CLIENT_TASKS_PER_DAY", 10),
                "http://" + s.conf("TAILNET_NAME", "homelab") + ":" + s.conf("INBOX_PORT", "8105"),
                s.conf("KITT_ACCOUNT", "homelab-app-kitt"), s.conf("KITT_UPSTREAM", "http://127.0.0.1:8122"),
                s.conf("KITT_GATE_HOST", ""), s.confInt("KITT_GATE_PORT", 8121),
                Path.of(tokens == null || tokens.isEmpty() ? "/etc/homelab" : tokens), s.tz());
    }
}
