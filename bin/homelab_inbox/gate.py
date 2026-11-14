"""The gate in front of kitt (H-018): the one tailnet listener for the app. It answers the same people as the homelab
page (Access.allowed: only Chris's own devices on the tailnet; another tailnet user, the LAN and the box itself get a
403) and forwards what passes to kitt's adapter, which listens on loopback only. The gate puts the owner's identity on
the forwarded request (Tailscale-User-Login) and removes any such header the caller sent, so the adapter, and the inbox
behind it, can trust it. Nothing here is stored."""
import http.client
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from .config import CONF
from .pages import Access
from .storage import log

HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers", "transfer-encoding",
       "upgrade", "proxy-connection"}
IDENTITY = ("tailscale-user-login", "tailscale-user-name", "x-homelab-client", "x-forwarded-for")


def upstream():
    u = urllib.parse.urlsplit(CONF.get("KITT_UPSTREAM", "http://127.0.0.1:8122"))
    return u.hostname, u.port or 80


class Gate(BaseHTTPRequestHandler):
    server_version = "homelab-kitt-gate/1"

    def log_message(self, fmt, *args):
        pass

    def refuse(self, code, text):
        data = text.encode()
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(data)

    def forward(self):
        ok, who = Access.allowed(self.client_address[0].removeprefix("::ffff:"))
        if not ok:
            return self.refuse(403, "Only from your own devices on the tailnet.\n")
        headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP | set(IDENTITY)}
        headers.update({"Tailscale-User-Login": Access.owner or "", "Tailscale-User-Name": who})
        try:
            body = self.rfile.read(min(int(self.headers.get("Content-Length") or 0), 1024 * 1024)) or None
        except ValueError:
            return self.refuse(400, "bad request\n")
        host, port = upstream()
        conn = http.client.HTTPConnection(host, port, timeout=5)
        try:
            conn.request(self.command, self.path, body, headers)
            if conn.sock:
                conn.sock.settimeout(300)  # a stream may be quiet for a while
            res = conn.getresponse()
        except (OSError, http.client.HTTPException):
            conn.close()
            return self.refuse(502, "kitt is not running.\n")
        self.close_connection = True
        self.send_response(res.status)
        for k, v in res.getheaders():
            if k.lower() not in HOP:
                self.send_header(k, v)
        self.send_header("Connection", "close")
        self.end_headers()
        try:
            while chunk := res.read1(8192):
                self.wfile.write(chunk)
                self.wfile.flush()
        except (OSError, http.client.HTTPException):
            pass  # the browser left, or kitt stopped mid-stream
        finally:
            conn.close()

    do_GET = do_POST = do_PUT = do_DELETE = do_HEAD = do_OPTIONS = do_PATCH = forward


class GateServer(ThreadingHTTPServer):
    daemon_threads = True


def run():
    """Listen on the Tailscale address (KITT_GATE_HOST) and keep trying: tailscaled may come up after the inbox."""
    host, port = CONF.get("KITT_GATE_HOST", ""), int(CONF.get("KITT_GATE_PORT", "8121"))
    if not host or host in ("0.0.0.0", "::", "*"):
        log("kitt gate: KITT_GATE_HOST is empty or a wildcard; the gate stays off")
        return
    while True:
        try:
            srv = GateServer((host, port), Gate)
        except OSError as e:
            log(f"kitt gate: cannot listen on {host}:{port} ({e}); trying again in 30 s")
            time.sleep(30)
            continue
        log(f"kitt gate on {host}:{port}, forwarding to {upstream()[0]}:{upstream()[1]}")
        srv.serve_forever()


def start():
    threading.Thread(target=run, daemon=True).start()
