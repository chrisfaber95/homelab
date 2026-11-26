package dev.homelab.inbox.door;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import tools.jackson.databind.json.JsonMapper;

/**
 * A listener for the two doors, for tests and for looking at the shadow door by hand: it turns an HTTP request into a
 * {@link Req} (with the real ends of the connection, so the peer check reads the kernel's table) and writes the
 * {@link Result}. The order is the one of {@code server.py}: the kitt door first (a request with the kitt header never
 * falls to the chat door), then the chat door; anything else is refused. It only listens on loopback, and nothing in
 * the application starts it.
 */
public final class DoorHttp implements AutoCloseable {

    private static final int MAX_BODY = 64 * 1024;

    private final KittDoor kitt;
    private final ChatDoor chat;
    private final JsonMapper json;
    private final HttpServer server;

    public DoorHttp(KittDoor kitt, ChatDoor chat, JsonMapper json) throws IOException {
        this.kitt = kitt;
        this.chat = chat;
        this.json = json;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    static Map<String, String> query(String raw) {
        Map<String, String> q = new LinkedHashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                String k = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
                String v = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
                if (!k.isEmpty() && !v.isEmpty()) {
                    q.putIfAbsent(k, v); // parse_qs drops blank values
                }
            }
        }
        return q;
    }

    private void handle(HttpExchange x) throws IOException {
        try (x) {
            Map<String, String> headers = new LinkedHashMap<>();
            x.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
            byte[] body = x.getRequestBody().readNBytes(MAX_BODY);
            Req req = new Req(x.getRequestMethod(), Optional.ofNullable(x.getRequestURI().getRawPath()).orElse(""),
                    query(x.getRequestURI().getRawQuery()), headers, body,
                    x.getRemoteAddress().getAddress().getHostAddress(), x.getRemoteAddress().getPort(),
                    x.getLocalAddress().getPort());
            Result result;
            if (!req.method().equals("GET") && !req.method().equals("POST")) {
                result = Result.error(405, req.method() + " is not supported");
            } else {
                Optional<Result> answer = kitt.handle(req);
                if (answer.isEmpty()) {
                    answer = chat.handle(req);
                }
                result = answer.orElse(Result.error(403, "not a call this door answers"));
            }
            byte[] data = json.writeValueAsBytes(result.body());
            x.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            x.sendResponseHeaders(result.status(), data.length);
            x.getResponseBody().write(data);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
