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
from homelab_inbox import forge, overview, pages  # noqa: E402


LOG = ["2026-11-12T07:00:00+00:00 builder: demo: writing the code on forge-x",
       "2026-11-12T07:00:05+00:00 Bash: pytest -q",
       "2026-11-12T07:00:09+00:00 paused: out of tokens (five_hour window is full)"]


class FakeForge(BaseHTTPRequestHandler):
    running = False
    calls = []  # the POSTs: start and stop
    gets = []  # the GETs: state and log
    activity = "Bash: pytest -q"

    def log_message(self, *a):
        pass

    def reply(self, status, obj):
        data = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        FakeForge.gets.append(self.path)
        if self.path.startswith("/api/log"):
            start = int(self.path.partition("from=")[2] or 0)
            return self.reply(200, {"lines": LOG[start:], "from": start, "next": len(LOG),
                                    "running": FakeForge.running})
        self.reply(200, {"running": FakeForge.running, "step": "build", "project": "demo",
                         "activity": FakeForge.activity, "started_at": "2026-11-12T06:50:00+00:00",
                         "paused": False, "pause_reason": None, "paused_until": None})

    def do_POST(self):
        FakeForge.calls.append(self.path)
        FakeForge.running = self.path == "/api/start"
        self.reply(200, {"ok": True})


class ForgeProxy(unittest.TestCase):
    def setUp(self):
        FakeForge.running, FakeForge.calls, FakeForge.gets = False, [], []
        FakeForge.activity = "Bash: pytest -q"
        self.srv = ThreadingHTTPServer(("127.0.0.1", 0), FakeForge)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.old = forge.FORGE_URL
        forge.FORGE_URL = f"http://127.0.0.1:{self.srv.server_port}"
        forge.forget()

    def tearDown(self):
        forge.FORGE_URL = self.old
        forge.forget()
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


class ForgeInLiveList(ForgeProxy):
    """Forge is an agent on the page: while it runs it is in "Nu bezig" with its own log."""

    def test_a_stopped_forge_is_not_in_the_list(self):
        self.assertIsNone(forge.live_item())
        items, _ = overview.live_items()
        self.assertEqual([i for i in items if i["key"] == forge.LIVE_KEY], [])

    def test_a_running_forge_is_in_the_list_with_what_it_is_doing(self):
        FakeForge.running = True
        item = forge.live_item()
        self.assertEqual(item["key"], forge.LIVE_KEY)
        self.assertEqual((item["title"], item["repo"]), ("Forge", "demo"))
        self.assertEqual(item["what"], "Bash: pytest -q")
        self.assertEqual(item["started"], "2026-11-12T06:50:00+00:00")
        self.assertIsNone(item["cancel"])  # forge has its own Start/Stop, not a cancel

    def test_the_header_sentence_says_what_forge_is_building(self):
        FakeForge.running = True
        forge.forget()
        items, queued = overview.live_items()
        sentence = overview.activity_sentence(items, queued)
        self.assertIn("Forge bouwt aan demo", sentence)
        self.assertIn("Bash: pytest -q", sentence)

    def test_the_hero_is_busy_and_shows_the_row(self):
        FakeForge.running = True
        forge.forget()
        html = pages.overview_page()
        self.assertIn("hero", html)
        self.assertIn("busy", html)
        self.assertIn(f'data-ssr="{forge.LIVE_KEY}"', html)
        self.assertIn("Forge", html)

    def test_without_a_start_time_the_row_still_has_one(self):
        FakeForge.running = True
        page = json.loads(json.dumps({"running": True}))  # forge from before started_at existed
        self.assertTrue(forge.running(page))
        forge.forget()
        item = forge.live_item()
        self.assertTrue(item["started"])

    def test_the_log_is_passed_through_with_kinds_and_a_sequence(self):
        FakeForge.running = True
        status, data = forge.log(0)
        self.assertEqual(status, 200)
        self.assertEqual([l["text"] for l in data["lines"]], [l.split(" ", 1)[1] for l in LOG])
        self.assertEqual([l["kind"] for l in data["lines"]], ["info", "tool", "error"])
        self.assertEqual(data["next"], len(LOG))
        self.assertFalse(data["done"])  # forge runs: the panel keeps polling
        # From a sequence on, only the newer lines come back.
        self.assertEqual(len(forge.log(2)[1]["lines"]), 1)

    def test_a_finished_run_closes_the_log_panel(self):
        self.assertTrue(forge.log(0)[1]["done"])

    def test_an_unreachable_forge_does_not_break_the_page(self):
        forge.FORGE_URL = "http://127.0.0.1:9"
        forge.forget()
        self.assertIsNone(forge.live_item())
        self.assertEqual(forge.log(0)[0], 502)
        self.assertIn("Niets bezig", pages.overview_page())

    def test_forge_is_not_asked_again_for_every_poll(self):
        FakeForge.running = True
        forge.forget()
        for _ in range(5):
            forge.live_item()
        self.assertEqual(FakeForge.gets.count("/api/state"), 1)


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
