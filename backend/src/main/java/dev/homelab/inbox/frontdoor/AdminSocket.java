package dev.homelab.inbox.frontdoor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A request to {@code homelab-admin} (F-019) over its unix socket, as {@code admin_ask} in {@code overview.py}. */
public interface AdminSocket {

    /** One JSON line out, one JSON line back; a failure is {@code {"ok": false, "text": ...}}, never an exception. */
    JsonNode ask(Map<String, Object> request);

    /** The real socket, {@code SOCKET} of admin.conf; homelab-admin itself checks who is asking. */
    static AdminSocket at(Path socket, JsonMapper json) {
        return request -> {
            try (SocketChannel ch = SocketChannel.open(UnixDomainSocketAddress.of(socket))) {
                ch.write(ByteBuffer.wrap((json.writeValueAsString(request) + "\n").getBytes(StandardCharsets.UTF_8)));
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                ByteBuffer buf = ByteBuffer.allocate(65536);
                var in = Channels.newInputStream(ch);
                int n;
                // Like admin_ask: read until a newline, or until the other side closes.
                while (!endsWithNewline(data) && (n = in.read(buf.array())) > 0) {
                    data.write(buf.array(), 0, n);
                }
                return json.readTree(data.toString(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                return json.createObjectNode().put("ok", false).put("text", "homelab-admin antwoordt niet (" + e.getMessage() + ")");
            }
        };
    }

    private static boolean endsWithNewline(ByteArrayOutputStream data) {
        byte[] b = data.toByteArray();
        return b.length > 0 && b[b.length - 1] == '\n';
    }
}
