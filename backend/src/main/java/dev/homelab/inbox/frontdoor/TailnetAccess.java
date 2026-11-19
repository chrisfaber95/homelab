package dev.homelab.inbox.frontdoor;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Who may open the page ({@code Access} in {@code access.py}): only the owner of the box, from their own devices on the
 * tailnet. Not the box itself and not the LAN. An address is looked up with {@code tailscale whois} and the answer is
 * kept for five minutes. Fails closed: with no known owner, or a whois that fails, nobody gets in.
 */
@Component
@FrontDoorEnabled
public class TailnetAccess {

    private static final Logger LOG = LoggerFactory.getLogger(TailnetAccess.class);
    private static final long CACHE_NANOS = 300L * 1_000_000_000L;

    /** The answer for one address: whether it may in, and a name for the logs (the device, else the address). */
    public record Decision(boolean allowed, String who) {}

    private record Hit(String login, String device, long until) {}

    private final Tailscale tailscale;
    private final JsonMapper json;
    private final LongSupplier clock;
    private final boolean allowAll;
    private final Map<String, Hit> cache = new ConcurrentHashMap<>();
    private volatile Set<String> own = Set.of();
    private volatile String owner;

    @Autowired
    public TailnetAccess(JsonMapper json) {
        this(Tailscale.CLI, json, System::nanoTime, "1".equals(System.getenv("INBOX_ALLOW_ALL")));
    }

    /** {@code allowAll} is INBOX_ALLOW_ALL=1, "only for tests" in Python too. */
    public TailnetAccess(Tailscale tailscale, JsonMapper json, LongSupplier clock, boolean allowAll) {
        this.tailscale = tailscale;
        this.json = json;
        this.clock = clock;
        this.allowAll = allowAll;
    }

    /** {@code Access.init}: the owner (login of this node's user) and this box's own addresses. */
    public void init() {
        try {
            JsonNode st = json.readTree(tailscale.status());
            JsonNode self = st.get("Self");
            String login = st.get("User").get(self.get("UserID").asString()).get("LoginName").asString();
            Set<String> addresses = new HashSet<>(Set.of("127.0.0.1", "::1"));
            self.get("TailscaleIPs").forEach(ip -> addresses.add(ip.asString()));
            this.owner = login;
            this.own = addresses;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.warn("tailscale status: {}", e.toString());
        }
    }

    public String owner() {
        return owner;
    }

    /** For tests and for kitt's peer check later: the box's own addresses and the owner, as {@code init} would set them. */
    public void setIdentity(String owner, Set<String> own) {
        this.owner = owner;
        this.own = Set.copyOf(own);
    }

    public Decision allowed(String rawIp) {
        String ip = rawIp.startsWith("::ffff:") ? rawIp.substring("::ffff:".length()) : rawIp;
        if (allowAll) {
            return new Decision(true, "test");
        }
        if (own.contains(ip) || owner == null) {
            return new Decision(false, "niet vanaf de box zelf");
        }
        Hit hit = cache.get(ip);
        if (hit == null || hit.until() < clock.getAsLong()) {
            hit = lookup(ip);
            cache.put(ip, hit);
        }
        return new Decision(owner.equals(hit.login()), hit.device() != null && !hit.device().isEmpty() ? hit.device() : ip);
    }

    private Hit lookup(String ip) {
        String login = null;
        String device = null;
        try {
            JsonNode w = json.readTree(tailscale.whois(ip));
            login = w.get("UserProfile").get("LoginName").asString();
            device = w.get("Node").get("ComputedName").asString();
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            login = null;
            device = null;
        }
        return new Hit(login, device, clock.getAsLong() + CACHE_NANOS);
    }
}
