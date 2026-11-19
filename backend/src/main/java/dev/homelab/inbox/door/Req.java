package dev.homelab.inbox.door;

import java.util.Map;
import java.util.TreeMap;

/**
 * One request as the door sees it, independent of the HTTP server under it: method, path and query (first value of
 * each key), headers by case-insensitive name, the body, and the two ends of the connection.
 */
public record Req(String method, String path, Map<String, String> query, Map<String, String> headers, byte[] body,
                  String remoteIp, int remotePort, int serverPort) {

    public Req {
        Map<String, String> h = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        h.putAll(headers);
        headers = h;
    }

    public String header(String name) {
        return headers.get(name);
    }
}
