#!/usr/bin/env python3
"""Parity run for the forge and search proxies (H-024, M3) without touching the live inbox.

Starts a fake forge/search/router (one loopback server, fixed answers), the Python inbox handler in this process on a
free port (state in a temporary directory, page check off, upstreams pointed at the fake), and runs parity.py with the
Python side on that handler and the Java side on a backend you started yourself:

    BACKEND_SECRET=s BACKEND_PORT=8139 FORGE_URL=http://127.0.0.1:18551 SEARCH_URL=http://127.0.0.1:18551 \\
      ROUTER_URL=http://127.0.0.1:18551 java -jar backend/target/inbox-backend-0.1.0.jar &
    python3 backend/parity/m3_fixture.py --java-url http://127.0.0.1:8139 --java-secret s --upstream-port 18551

It only reads (parity.py sends GET). Exit code as parity.py: 0 same, 1 different, 2 could not run."""
import argparse
import json
import os
import sys
import tempfile
import threading
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANSWERS = {
    "/api/state": (200, {"running": True, "step": 2, "project": "demo", "activity": "Edit: a.py",
                         "started_at": "2026-11-16T10:00:00+00:00", "paused": False}),
    "/api/log": (200, {"lines": ["2026-11-16T10:00:01+00:00 Bash: ls", "2026-11-16T10:00:02+00:00 Refused to start",
                                 "2026-11-16T10:00:03+00:00 plain line"], "next": 3, "running": False}),
    "/v1/filters": (200, {"projects": ["homelab"], "types": ["doc"], "sources": ["repo"]}),
    "/v1/search": (200, {"query": "ports", "results": [{"path": "docs/inbox.md", "score": 0.5}]}),
}


class Fake(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        status, body = ANSWERS.get(urllib.parse.urlsplit(self.path).path, (404, {"error": "no such route"}))
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--java-url", required=True)
    ap.add_argument("--java-secret", required=True)
    ap.add_argument("--upstream-port", type=int, required=True, help="the port the Java side has as FORGE_URL, SEARCH_URL, ROUTER_URL")
    ap.add_argument("--only", action="append", default=["/api/forge", "/api/search", "/api/log/f1"])
    args = ap.parse_args()
    os.environ.update(INBOX_RUNNER_HOME=tempfile.mkdtemp(), NTFY_FAKE="1", INBOX_ALLOW_ALL="1",
                      REPOS_CONF=str(ROOT / "config/repos.conf"), BACKEND_ROUTES="")
    sys.path.insert(0, str(ROOT / "bin"))
    sys.path.insert(0, str(ROOT / "backend/parity"))
    from homelab_inbox import forge, search, server
    import parity
    upstream = ThreadingHTTPServer(("127.0.0.1", args.upstream_port), Fake)
    threading.Thread(target=upstream.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{args.upstream_port}"
    forge.FORGE_URL, search.SEARCH_URL, search.ROUTER_URL = base, base, base
    inbox = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
    threading.Thread(target=inbox.serve_forever, daemon=True).start()
    argv = ["--python-url", f"http://127.0.0.1:{inbox.server_address[1]}", "--java-url", args.java_url,
            "--java-header", f"X-Inbox-Facade: {args.java_secret}"]
    for prefix in args.only:
        argv += ["--only", prefix]
    return parity.main(argv)


if __name__ == "__main__":
    sys.exit(main())
