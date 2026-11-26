package dev.homelab.inbox.frontdoor;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import org.apache.catalina.connector.Connector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * The tailnet connector (H-028 W5b). With {@code inbox.public.port} set (env {@code INBOX_PUBLIC_PORT}) Tomcat gets a
 * second connector that binds only the IPv4 address of the tailnet interface ({@code inbox.public.interface}, default
 * {@code tailscale0}) on that port: never 0.0.0.0, never the LAN. Without a value there is no second connector and the
 * backend listens on {@code server.address} only, as before.
 * <p>
 * It fails the start, and does not fall back to something wider, when the port is not valid or equals the main port,
 * when the interface or its address is missing, or when the front door is off (the owner and CSRF checks are what
 * guard the pages on this connector). The loopback-only doors stay refused here: {@link PublicPortGuard}.
 */
@Component
public class PublicConnector implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {

    private final String port;
    private final String iface;
    private final boolean frontDoor;
    private final int mainPort;
    private final String cidr;

    public PublicConnector(@Value("${inbox.public.port:}") String port,
            @Value("${inbox.public.interface:tailscale0}") String iface,
            @Value("${inbox.front-door.enabled:false}") boolean frontDoor,
            @Value("${server.port:8130}") int mainPort,
            @Value("${inbox.public.cidr:100.64.0.0/10}") String cidr) {
        this.cidr = cidr.strip();
        this.port = port.strip();
        this.iface = iface.strip();
        this.frontDoor = frontDoor;
        this.mainPort = mainPort;
    }

    @Override
    public void customize(TomcatServletWebServerFactory factory) {
        if (port.isEmpty()) {
            return;
        }
        int number = parse(port);
        if (number == mainPort) {
            throw new IllegalStateException("inbox.public.port must differ from server.port (" + mainPort + ")");
        }
        if (!frontDoor) {
            throw new IllegalStateException("inbox.public.port needs inbox.front-door.enabled=true");
        }
        Connector connector = new Connector("org.apache.coyote.http11.Http11NioProtocol");
        connector.setPort(number);
        connector.setProperty("address", tailnetAddress(iface, cidr).getHostAddress());
        factory.addAdditionalConnectors(connector);
    }

    static int parse(String value) {
        try {
            int n = Integer.parseInt(value);
            if (n >= 1 && n <= 65535) {
                return n;
            }
        } catch (NumberFormatException e) {
            // falls through
        }
        throw new IllegalStateException("inbox.public.port is not a port number: " + value);
    }

    /** Whether the IPv4 address is in the CIDR block ({@code a.b.c.d/n}). */
    static boolean inside(InetAddress address, String cidr) {
        try {
            String[] parts = cidr.split("/");
            int bits = Integer.parseInt(parts[1]);
            byte[] net = InetAddress.getByName(parts[0]).getAddress();
            byte[] ip = address.getAddress();
            if (net.length != 4 || ip.length != 4 || bits < 0 || bits > 32) {
                return false;
            }
            int mask = bits == 0 ? 0 : -1 << (32 - bits);
            return (toInt(net) & mask) == (toInt(ip) & mask);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static int toInt(byte[] b) {
        return (b[0] & 0xff) << 24 | (b[1] & 0xff) << 16 | (b[2] & 0xff) << 8 | (b[3] & 0xff);
    }

    /** The first IPv4 address of the interface; an exception when there is none (no wildcard fallback). */
    static InetAddress tailnetAddress(String name, String cidr) {
        try {
            NetworkInterface nic = NetworkInterface.getByName(name);
            if (nic == null) {
                throw new IllegalStateException("inbox.public.port: no network interface " + name);
            }
            InetAddress address = Collections.list(nic.getInetAddresses()).stream()
                    .filter(Inet4Address.class::isInstance).findFirst().orElseThrow(() -> new IllegalStateException(
                            "inbox.public.port: interface " + name + " has no IPv4 address"));
            if (!inside(address, cidr)) { // never a LAN or other address, whatever the interface name says
                throw new IllegalStateException("inbox.public.port: " + address.getHostAddress() + " of " + name
                        + " is not in " + cidr + " (the tailnet range)");
            }
            return address;
        } catch (IOException e) {
            throw new IllegalStateException("inbox.public.port: cannot read interface " + name, e);
        }
    }
}
