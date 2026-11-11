"""Forge block of the homelab page (H-015): the inbox proxies forge's start/stop, nothing else. Run:
python3 -m unittest discover -s tests"""
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"), INBOX_ALLOW_ALL="1")
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import forge, pages  # noqa: E402


class FakeForge(BaseHTTPRequestHandler):
    running = False
    calls = []

    def log_message(self, *a):
        pass

    def reply(self, status, obj):
        data = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.reply(200, {"running": FakeForge.running, "step": "build", "project": "demo"})

    def do_POST(self):
        FakeForge.calls.append(self.path)
        FakeForge.running = self.path == "/api/start"
        self.reply(200, {"ok": True})


class ForgeProxy(unittest.TestCase):
    def setUp(self):
        FakeForge.running, FakeForge.calls = False, []
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeForge)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.old = forge.FORGE_URL
        forge.FORGE_URL = f"http://127.0.0.1:{self.srv.server_port}"

    def tearDown(self):
        forge.FORGE_URL = self.old
        self.srv.shutdown()

    def test_start_then_stop(self):
        status, d = forge.control("start")
        self.assertEqual((status, d["running"], d["project"]), (200, True, "demo"))
        status, d = forge.control("stop")
        self.assertEqual((status, d["running"]), (200, False))
        self.assertEqual(FakeForge.calls, ["/api/start", "/api/stop"])

    def test_only_start_and_stop(self):
        self.assertEqual(forge.control("restart")[0], 400)
        self.assertEqual(FakeForge.calls, [])

    def test_unreachable_forge_is_a_clear_error(self):
        forge.FORGE_URL = "http://127.0.0.1:9"
        status, d = forge.state()
        self.assertEqual(status, 502)
        self.assertIn("not reachable", d["error"]["message"])

    def test_page_has_controls_and_link(self):
        html = pages.overview_page()
        self.assertIn('data-forge="start"', html)
        self.assertIn('data-forge="stop"', html)
        self.assertIn('href="http://homelab:8120"', html)


class ForgeFiles(unittest.TestCase):
    def test_unit_binds_tailnet_and_is_not_in_repos_as_project(self):
        unit = (ROOT / "deploy/forge.service").read_text()
        self.assertIn("FORGE_BIND_HOST=100.85.70.34", unit)
        self.assertNotIn("BIND_HOST=0.0.0.0", unit)
        self.assertNotIn("API_KEY", unit + (ROOT / "config/forge.env").read_text())
        repos = [l.split() for l in (ROOT / "config/repos.conf").read_text().splitlines()
                 if l.strip() and not l.startswith("#")]
        self.assertEqual([r[1] for r in repos if "forge" in r[1]], ["chrisfaber95/forge"])


if __name__ == "__main__":
    unittest.main()
