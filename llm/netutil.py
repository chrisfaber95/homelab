"""homelab: what the F-017 helper and the router (H-012) share for serving HTTP on the tailnet only."""
import ipaddress
import socket
from http.server import ThreadingHTTPServer

# Only loopback and the tailnet; ufw already blocks the LAN, this is the second layer.
ALLOWED_NETS = [ipaddress.ip_network(n) for n in
                ("127.0.0.0/8", "::1/128", "100.64.0.0/10", "fd7a:115c:a1e0::/48")]


def client_allowed(ip):
    addr = ipaddress.ip_address(ip.removeprefix("::ffff:"))
    return any(addr in net for net in ALLOWED_NETS)


class Server(ThreadingHTTPServer):
    # Dual-stack: MagicDNS also gives an IPv6 address, and a client that tries that first must not hang.
    address_family = socket.AF_INET6
    daemon_threads = True

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()
