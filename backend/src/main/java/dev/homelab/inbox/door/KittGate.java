package dev.homelab.inbox.door;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.homelab.inbox.door.Ports.OwnerCheck;
import dev.homelab.inbox.door.Ports.Verdict;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The gate in front of kitt (H-018, {@code gate.py}), ported for H-026 K7: the one tailnet listener for the app. It
 * answers the same people as the homelab page ({@link OwnerCheck}: only Chris's own devices on the tailnet; another
 * tailnet user, the LAN and the box itself get a 403) and forwards what passes to kitt's adapter, which listens on
 * loopback only. The gate puts the owner's identity on the forwarded request ({@code Tailscale-User-Login}) and removes
 * any such header the caller sent, so the adapter, and the inbox behind it, can trust it. Nothing here is stored.
 *
 * <p>Exposure: this class opens no socket until {@link #listen} is called, and nothing in the application calls it.
 * {@link #listen} refuses an empty or wildcard host like {@code gate.run()}, and, while {@code exposed} is false (the
 * shadow default), every host that is not loopback, so a shadow gate can never take the place of the Python gate on
 * the Tailscale address (port 8121). H-027 builds the exposed gate when the cut-over happens.
 */
public class KittGate {

    private static final Logger LOG = LoggerFactory.getLogger(KittGate.class);
    private static final Set<String> HOP = Set.of("connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade", "proxy-connection");
    /** The identity headers the gate sets itself; anything the caller sent under these names is dropped. */
    private static final Set<String> IDENTITY =
            Set.of("tailscale-user-login", "tailscale-user-name", "x-homelab-client", "x-forwarded-for");
    /** Set by the HTTP client itself (it refuses to be told them): not forwarded as they are. */
    private static final Set<String> CLIENT_OWN = Set.of("host", "content-length", "expect");
    private static final int MAX_BODY = 1024 * 1024;

    private final OwnerCheck owner;
    private final String upstream;
    private final boolean exposed;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile HttpServer server;

    /** {@code upstream}: kitt's adapter, for example {@code http://127.0.0.1:8122}. */
    public KittGate(OwnerCheck owner, String upstream, boolean exposed) {
        this.owner = owner;
        this.upstream = upstream.replaceAll("/+$", "");
        this.exposed = exposed;
    }

    public boolean isListening() {
        return server != null;
    }

    /**
     * Listen on {@code host:port} (port 0: any free port). Empty when it refuses: an empty or wildcard host, or, in
     * shadow, a host that is not loopback. Otherwise the port it listens on.
     */
    public synchronized Optional<Integer> listen(String host, int port) throws IOException {
        if (host == null || host.isEmpty() || List.of("0.0.0.0", "::", "*").contains(host)) {
            LOG.warn("kitt gate: KITT_GATE_HOST is empty or a wildcard; the gate stays off");
            return Optional.empty();
        }
        InetAddress address = InetAddress.getByName(host);
        if (!exposed && !address.isLoopbackAddress()) {
            LOG.warn("kitt gate: shadow gate refuses to listen on {}: only loopback until the cut-over (H-027)", host);
            return Optional.empty();
        }
        if (server != null) {
            throw new IllegalStateException("the gate is already listening");
        }
        HttpServer s = HttpServer.create(new InetSocketAddress(address, port), 0);
        s.createContext("/", this::forward);
        s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        s.start();
        server = s;
        LOG.info("kitt gate on {}:{}, forwarding to {}", host, s.getAddress().getPort(), upstream);
        return Optional.of(s.getAddress().getPort());
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private static void refuse(HttpExchange x, int code, String text) throws IOException {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, data.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(data);
        }
    }

    private void forward(HttpExchange x) throws IOException {
        try (x) {
            String ip = x.getRemoteAddress().getAddress().getHostAddress();
            ip = ip.startsWith("::ffff:") ? ip.substring(7) : ip;
            Verdict verdict = owner.allowed(ip);
            if (!verdict.ok()) {
                refuse(x, 403, "Only from your own devices on the tailnet.\n");
                return;
            }
            String length = x.getRequestHeaders().getFirst("Content-Length");
            long n;
            try {
                n = length == null || length.isBlank() ? 0 : Long.parseLong(length.strip());
            } catch (NumberFormatException e) {
                refuse(x, 400, "bad request\n");
                return;
            }
            if (n < 0) {
                refuse(x, 400, "bad request\n");
                return;
            }
            if (n > MAX_BODY) { // stricter than gate.py, which cuts the body and keeps the length
                refuse(x, 413, "request too large\n");
                return;
            }
            byte[] body = x.getRequestBody().readNBytes((int) n);
            HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(upstream + x.getRequestURI().toString()))
                    .timeout(Duration.ofSeconds(300)); // a stream may be quiet for a while
            for (Map.Entry<String, List<String>> h : x.getRequestHeaders().entrySet()) {
                String name = h.getKey().toLowerCase(Locale.ROOT);
                if (HOP.contains(name) || IDENTITY.contains(name) || CLIENT_OWN.contains(name)) {
                    continue;
                }
                for (String v : h.getValue()) {
                    req.header(h.getKey(), v);
                }
            }
            String login = owner.owner() == null ? "" : owner.owner();
            req.header("Tailscale-User-Login", login).header("Tailscale-User-Name", verdict.who());
            req.method(x.getRequestMethod(), body.length == 0 && !"POST".equals(x.getRequestMethod())
                    && !"PUT".equals(x.getRequestMethod()) && !"PATCH".equals(x.getRequestMethod())
                    ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
            HttpResponse<InputStream> res;
            try {
                res = http.send(req.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException | RuntimeException e) {
                LOG.debug("kitt gate: upstream {} failed: {}", upstream, e.toString());
                refuse(x, 502, "kitt is not running.\n");
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                refuse(x, 502, "kitt is not running.\n");
                return;
            }
            res.headers().map().forEach((k, vs) -> {
                String name = k.toLowerCase(Locale.ROOT);
                if (!HOP.contains(name) && !name.equals("content-length")) {
                    vs.forEach(v -> x.getResponseHeaders().add(k, v));
                }
            });
            x.getResponseHeaders().set("Connection", "close");
            boolean head = x.getRequestMethod().equals("HEAD");
            x.sendResponseHeaders(res.statusCode(), head ? -1 : 0); // 0: stream as it arrives
            if (head) {
                return;
            }
            try (InputStream in = res.body(); OutputStream out = x.getResponseBody()) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = in.read(buf)) > 0) {
                    out.write(buf, 0, read);
                    out.flush();
                }
            } catch (IOException e) {
                // the browser left, or kitt stopped mid-stream
            }
        }
    }
}
