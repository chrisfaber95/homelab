#!/usr/bin/env python3
"""homelab: the search HTTP API (H-012; docs/search.md). Tailnet only, read-only.

  GET  /v1/search?q=TEXT[&project=P][&type=T][&source=S][&k=N]   ranked results
  GET  /v1/ask?q=TEXT[&hard=1][&project=..][&type=..][&source=..]  one cited answer (also POST /v1/ask, JSON body
       {"question", "hard", "project", "type", "source"})
  GET  /v1/filters                                                projects, types and sources in the index
  GET  /health

The index is opened read-only per request. Clients: bin/homelab-search-mcp (Claude sessions), the Search tab of the
homelab page and claude-dashboard. Overridable for tests: SEARCH_CONFIG, SEARCH_DB, SEARCH_PORT, OLLAMA_URL.
"""
import ipaddress
import json
import os
import socket
import sys
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import answer  # noqa: E402
import index  # noqa: E402

MAX_BODY = 16_384
# Only loopback and the tailnet (same as llm/netutil.py; ufw already blocks the LAN, this is the second layer).
ALLOWED_NETS = [ipaddress.ip_network(n) for n in ("127.0.0.0/8", "::1/128", "100.64.0.0/10", "fd7a:115c:a1e0::/48")]


def client_allowed(ip):
    addr = ipaddress.ip_address(ip.removeprefix("::ffff:"))
    return any(addr in net for net in ALLOWED_NETS)


class Server(ThreadingHTTPServer):
    address_family = socket.AF_INET6  # dual-stack: MagicDNS may hand out the IPv6 address
    daemon_threads = True

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()


def truthy(v):
    return str(v).lower() in ("1", "true", "yes", "on")


def make_handler(conf, router=None, embedder=None):
    class Handler(BaseHTTPRequestHandler):
        server_version = "homelab-search/1"

        def log_message(self, fmt, *args):
            # The question can hold private text: log the path and status, not the query string.
            sys.stderr.write(f"{self.client_address[0]} {self.command} {self.path.partition('?')[0]} {args[1:2]}\n")

        def send_json(self, status, obj):
            data = json.dumps(obj, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def fail(self, status, message, request_id=None):
            err = {"message": message}
            if request_id:
                err["request_id"] = request_id
            self.send_json(status, {"error": err})

        def handle_request(self, path, params):
            if path == "/health":
                return self.send_json(200, {"ok": True})
            idx = index.Index(conf, embedder, readonly=True)
            try:
                if path == "/v1/filters":
                    st = idx.status()
                    return self.send_json(200, {"projects": sorted(st["by_project"]), "types": sorted(st["by_type"]),
                                                "sources": sorted(st["by_source"]), "chunks": st["chunks"],
                                                "last_sync": st["last_sync"]})
                filters = {k: params.get(k) or None for k in ("project", "type", "source")}
                if path == "/v1/search":
                    try:
                        k = int(params["k"]) if params.get("k") else None
                    except ValueError:
                        raise answer.AskError(400, "k must be a number.")
                    results = answer.search(idx, conf, params.get("q", ""), k=k, **filters)
                    return self.send_json(200, {"query": params["q"], "results": results})
                if path == "/v1/ask":
                    question = params.get("question") or params.get("q") or ""
                    if len(question) > 2000:
                        raise answer.AskError(413, "The question is too long (2000 characters at most).")
                    return self.send_json(200, answer.ask(idx, conf, question, hard=truthy(params.get("hard", "")),
                                                          router=router, **filters))
                self.fail(404, f"Unknown path {path}.")
            except answer.AskError as e:
                self.fail(e.status, str(e), e.request_id)
            except index.FingerprintMismatch as e:
                self.fail(503, str(e))
            except FileNotFoundError as e:
                self.fail(503, str(e))
            except Exception as e:  # noqa: BLE001  one JSON error instead of a dropped connection
                self.fail(500, f"{type(e).__name__}: {e}")
            finally:
                idx.close()

        def allowed(self):
            if client_allowed(self.client_address[0]):
                return True
            self.fail(403, "Tailnet only.")
            return False

        def do_GET(self):
            if self.allowed():
                path, _, query = self.path.partition("?")
                self.handle_request(path, {k: v[0] for k, v in urllib.parse.parse_qs(query).items()})

        def do_POST(self):
            if not self.allowed():
                return
            path = self.path.partition("?")[0]
            try:
                length = int(self.headers.get("Content-Length") or 0)
                if length > MAX_BODY:
                    return self.fail(413, "The body is too large.")
                body = json.loads(self.rfile.read(length) or b"{}")
                if not isinstance(body, dict) or path != "/v1/ask":
                    return self.fail(404 if path != "/v1/ask" else 400, "POST is for /v1/ask with a JSON object.")
            except ValueError:
                return self.fail(400, "The body is not JSON.")
            self.handle_request(path, {k: (v if isinstance(v, str) else str(v)) for k, v in body.items()
                                       if v is not None})
    return Handler


def main():
    conf = index.load_config()
    port = int(os.environ.get("SEARCH_PORT") or answer.query_conf(conf)["port"])
    server = Server(("::", port), make_handler(conf))
    sys.stderr.write(f"homelab-search-api on port {port}, index {conf['db']}\n")
    server.serve_forever()


if __name__ == "__main__":
    main()
