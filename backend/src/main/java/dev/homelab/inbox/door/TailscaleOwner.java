package dev.homelab.inbox.door;

import dev.homelab.inbox.door.Ports.OwnerCheck;
import dev.homelab.inbox.door.Ports.Verdict;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tailnet owner check ({@code access.py Access}): only the owner's own devices on the tailnet are let in; the box
 * itself, the LAN and another tailnet user are not. The owner and the box's own addresses come from
 * {@code tailscale status --json} (read once, by {@link #init}); a peer is looked up with {@code tailscale whois --json}
 * and remembered for five minutes. The CLI is a function, so tests run without Tailscale. There is no "allow all"
 * switch here, as the Python inbox has for its tests.
 */
public final class TailscaleOwner implements OwnerCheck {

    private static final Logger LOG = LoggerFactory.getLogger(TailscaleOwner.class);
    private static final long REMEMBER_MS = 300_000;

    private record Seen(String login, String device, long until) {}

    private final Function<List<String>, String> tailscale;
    private final JsonMapper json;
    private final Clock clock;
    private final Map<String, Seen> cache = new HashMap<>();
    private volatile String owner;
    private volatile Set<String> own = Set.of();

    public TailscaleOwner(Function<List<String>, String> tailscale, JsonMapper json, Clock clock) {
        this.tailscale = tailscale;
        this.json = json;
        this.clock = clock;
    }

    /** The real CLI: {@code tailscale <args>}, stdout, with a timeout. */
    public static Function<List<String>, String> cli() {
        return args -> {
            try {
                List<String> cmd = new java.util.ArrayList<>(List.of("tailscale"));
                cmd.addAll(args);
                Process p = new ProcessBuilder(cmd).redirectErrorStream(false).redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                String out = new String(p.getInputStream().readAllBytes());
                if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    p.destroyForcibly();
                }
                return out;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        };
    }

    /** Read the owner and the box's own addresses; on failure there is no owner, and nobody is let in. */
    public synchronized void init() {
        try {
            JsonNode st = json.readTree(tailscale.apply(List.of("status", "--json")));
            String login = text(st.path("User").path(String.valueOf(st.path("Self").path("UserID").asLong()))
                    .path("LoginName"));
            if (login == null) {
                throw new IllegalStateException("no LoginName in tailscale status");
            }
            Set<String> addresses = new java.util.HashSet<>(Set.of("127.0.0.1", "::1"));
            st.path("Self").path("TailscaleIPs").forEach(n -> addresses.add(n.asString()));
            owner = login;
            own = addresses;
        } catch (RuntimeException e) {
            LOG.warn("tailscale status: {}", e.getMessage());
        }
    }

    private static String text(JsonNode n) {
        return n.isString() ? n.stringValue() : null;
    }

    @Override
    public String owner() {
        return owner;
    }

    @Override
    public synchronized Verdict allowed(String ip) {
        if (own.contains(ip) || owner == null) {
            return new Verdict(false, "niet vanaf de box zelf");
        }
        Seen hit = cache.get(ip);
        long now = clock.millis();
        if (hit == null || hit.until() < now) {
            String login = null;
            String device = null;
            try {
                JsonNode w = json.readTree(tailscale.apply(List.of("whois", "--json", ip)));
                login = text(w.path("UserProfile").path("LoginName"));
                device = text(w.path("Node").path("ComputedName"));
            } catch (RuntimeException e) {
                // unknown to the tailnet: no login, so not the owner
            }
            hit = new Seen(login, device, now + REMEMBER_MS);
            cache.put(ip, hit);
        }
        return new Verdict(owner.equals(hit.login()), hit.device() != null && !hit.device().isEmpty() ? hit.device() : ip);
    }
}
