package dev.homelab.inbox.door;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import dev.homelab.inbox.door.DoorFixture.FakeOwner;
import dev.homelab.inbox.door.Ports.Verdict;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Ports the {@code Gate} class of {@code tests/test_inbox_kitt.py}: the same owner check as the homelab page (another
 * tailnet user, the LAN and the box itself get a 403), the identity headers the gate sets and the ones it removes, and
 * that a wildcard host keeps the gate off. Plus the exposure rule of the shadow gate: it only ever listens on loopback.
 */
class KittGateTest {

    static final String OWNER = DoorFixture.OWNER;

    final List<Map<String, String>> seen = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    HttpServer upstream;
    FakeOwner owner;
    KittGate gate;
    int port;

    @BeforeEach
    void setUp() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        upstream.createContext("/", x -> {
            Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            x.getRequestHeaders().forEach((k, v) -> h.put(k, v.get(0)));
            h.put(":method", x.getRequestMethod());
            h.put(":path", x.getRequestURI().toString());
            seen.add(h);
            bodies.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "data: one\n\n".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().set("Content-Type", "text/event-stream");
            x.getResponseHeaders().set("X-From-Kitt", "yes");
            x.sendResponseHeaders(200, x.getRequestMethod().equals("HEAD") ? -1 : body.length);
            if (!x.getRequestMethod().equals("HEAD")) {
                x.getResponseBody().write(body);
            }
            x.close();
        });
        upstream.start();
        owner = new FakeOwner();
        gate = new KittGate(owner, "http://127.0.0.1:" + upstream.getAddress().getPort(), false);
        port = gate.listen("127.0.0.1", 0).orElseThrow();
    }

    @AfterEach
    void tearDown() {
        gate.stop();
        upstream.stop(0);
    }

    HttpResponse<String> send(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            b.header(headers[i], headers[i + 1]);
        }
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void theOwnerPassesWithIdentityAndForgedHeadersAreDropped() throws Exception {
        HttpResponse<String> r = send("GET", "/v1/x?a=1", null, "Tailscale-User-Login", "evil@x", "X-Homelab-Client", "kitt",
                "X-Forwarded-For", "1.2.3.4", "Tailscale-User-Name", "fake", "X-Other", "kept");
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("data: one\n\n");
        assertThat(r.headers().firstValue("X-From-Kitt")).contains("yes");
        assertThat(r.headers().firstValue("Content-Type")).contains("text/event-stream");
        Map<String, String> got = seen.get(0);
        assertThat(got.get("tailscale-user-login")).isEqualTo(OWNER);
        assertThat(got.get("tailscale-user-name")).isEqualTo("phone");
        assertThat(got).doesNotContainKeys("x-homelab-client", "x-forwarded-for");
        assertThat(got.get("x-other")).isEqualTo("kept");
        assertThat(got.get(":path")).isEqualTo("/v1/x?a=1");
    }

    @Test
    void aBodyAndTheMethodAreForwarded() throws Exception {
        assertThat(send("POST", "/v1/send", "{\"a\":1}", "Content-Type", "application/json").statusCode()).isEqualTo(200);
        assertThat(seen.get(0).get(":method")).isEqualTo("POST");
        assertThat(bodies.get(0)).isEqualTo("{\"a\":1}");
        assertThat(send("HEAD", "/v1/x", null).statusCode()).isEqualTo(200);
        assertThat(seen.get(1).get(":method")).isEqualTo("HEAD");
    }

    @Test
    void anotherTailnetUserIsRefused() throws Exception {
        owner.verdicts.put("127.0.0.1", new Verdict(false, "laptop"));
        HttpResponse<String> r = send("GET", "/v1/x", null);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(r.body()).isEqualTo("Only from your own devices on the tailnet.\n");
        assertThat(r.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(seen).isEmpty();
    }

    @Test
    void theBoxItselfIsRefused() throws Exception {
        owner.verdicts.put("127.0.0.1", new Verdict(false, "niet vanaf de box zelf"));
        assertThat(send("GET", "/v1/x", null).statusCode()).isEqualTo(403);
        assertThat(send("POST", "/v1/x", "{}").statusCode()).isEqualTo(403);
        assertThat(seen).isEmpty();
    }

    @Test
    void everyMethodGoesThroughTheSameCheck() throws Exception {
        owner.verdicts.put("127.0.0.1", new Verdict(false, "x"));
        for (String m : List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")) {
            assertThat(send(m, "/v1/x", m.equals("GET") || m.equals("OPTIONS") ? null : "{}").statusCode()).as(m).isEqualTo(403);
        }
        assertThat(seen).isEmpty();
    }

    @Test
    void anUnknownPeerIsRefusedAndKittDownIsA502() throws Exception {
        owner.verdicts.put("127.0.0.1", new Verdict(false, "127.0.0.1")); // whois knew nobody
        assertThat(send("GET", "/v1/x", null).statusCode()).isEqualTo(403);
        owner.verdicts.clear();
        gate.stop();
        gate = new KittGate(owner, "http://127.0.0.1:1", false);
        port = gate.listen("127.0.0.1", 0).orElseThrow();
        HttpResponse<String> r = send("GET", "/v1/x", null);
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.body()).isEqualTo("kitt is not running.\n");
    }

    @Test
    void aWildcardOrEmptyHostKeepsTheGateOff() throws Exception {
        KittGate other = new KittGate(owner, "http://127.0.0.1:1", true); // even an exposed gate refuses a wildcard
        for (String host : new String[] {"0.0.0.0", "::", "*", "", null}) {
            assertThat(other.listen(host, 0)).as(String.valueOf(host)).isEmpty();
            assertThat(other.isListening()).isFalse();
        }
    }

    @Test
    void theShadowGateNeverListensOnTheTailnetAddress() throws Exception {
        KittGate shadow = new KittGate(owner, "http://127.0.0.1:1", false);
        assertThat(shadow.listen("100.85.70.34", 8121)).isEmpty(); // the address and port of the Python gate
        assertThat(shadow.listen("192.168.1.10", 0)).isEmpty();
        assertThat(shadow.isListening()).isFalse();
        Optional<Integer> loopback = shadow.listen("127.0.0.1", 0);
        assertThat(loopback).isPresent();
        shadow.stop();
        assertThat(shadow.isListening()).isFalse();
    }

    @Test
    void aGateObjectOpensNoSocketUntilItIsToldTo() {
        KittGate idle = new KittGate(owner, "http://127.0.0.1:1", false);
        assertThat(idle.isListening()).isFalse();
        try {
            idle.stop(); // stopping a gate that never listened is fine
        } catch (RuntimeException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void listeningTwiceIsAnError() throws IOException {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> gate.listen("127.0.0.1", 0));
    }
}
