"""GET /api/overview (H-022 F6a): the data behind the Overview page (frontend/src/pages/overview.ts). Run: python3 -m unittest discover -s tests"""
import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", BACKEND_ROUTES="", REPOS_CONF=str(ROOT / "config/repos.conf"), INBOX_ALLOW_ALL="1")
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import config, overview, server  # noqa: E402


class OverviewApi(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inbox = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=cls.inbox.serve_forever, daemon=True).start()
        cls.port = cls.inbox.server_address[1]
        cls.tmp = tempfile.TemporaryDirectory()
        tmp = Path(cls.tmp.name)
        (tmp / "checks.json").write_text(json.dumps({"disk": {"status": "OK", "text": "fine"},
                                                     "gpu": {"status": "FAIL", "text": "missing", "since": "2026-11-16T07:00:00+00:00"}}))
        (tmp / "changes.jsonl").write_text(json.dumps({"kind": "instant", "action": "service", "args": ["restart", "x"],
                                                       "ok": True, "at": "2026-11-16T07:00:00+00:00", "user": "claude",
                                                       "reason": "test"}) + "\n")
        cls._old = overview.MONITOR_DIR, overview.CHANGES_LOG, overview.DEPLOY_STATE, overview.LLM_URL
        overview.MONITOR_DIR, overview.CHANGES_LOG, overview.DEPLOY_STATE = tmp, tmp / "changes.jsonl", tmp / "state"
        overview.LLM_URL = "http://127.0.0.1:9"  # nothing listens: usage.llm is null

    @classmethod
    def tearDownClass(cls):
        overview.MONITOR_DIR, overview.CHANGES_LOG, overview.DEPLOY_STATE, overview.LLM_URL = cls._old
        cls.tmp.cleanup()
        cls.inbox.shutdown()
        cls.inbox.server_close()

    def get(self, path):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        c.request("GET", path)
        r = c.getresponse()
        raw = r.read().decode()
        c.close()
        return r.status, r.getheader("Content-Type"), raw

    def test_returns_the_overview_as_json(self):
        code, ctype, raw = self.get("/api/overview")
        self.assertEqual((code, ctype), (200, "application/json; charset=utf-8"), raw)
        d = json.loads(raw)
        self.assertEqual(set(d), {"status", "live", "timeline", "changes", "services", "forge_link", "switches",
                                  "usage", "health"})
        self.assertEqual(d["status"]["sentence"], "Eén check faalt: gpu.")
        self.assertEqual(d["status"]["class"], "bad")
        self.assertEqual(set(d["live"]), {"items", "queued"})
        self.assertEqual(d["changes"][0], {"ok": True, "text": "service restart x", "at": "2026-11-16T07:00:00+00:00",
                                           "user": "claude", "reason": "test"})
        self.assertEqual(d["health"]["checks"]["gpu"]["status"], "FAIL")
        self.assertIsNone(d["usage"]["llm"])
        self.assertEqual(len(d["usage"]["days"]), 7)
        self.assertEqual(len(d["usage"]["inbox"]), 7)

    def test_status_carries_the_time_zone_the_page_names_days_in(self):
        d = json.loads(self.get("/api/overview")[2])
        self.assertEqual(d["status"]["timezone"], config.TZ.key)
        self.assertTrue(d["status"]["at"].endswith(("+01:00", "+02:00", "+00:00")))

    def test_nothing_in_the_answer_is_html_or_a_python_object(self):
        d = json.loads(self.get("/api/overview")[2])
        self.assertNotIn("<", json.dumps(d["status"]) + json.dumps(d["changes"]))
        for lane in d["timeline"]["lanes"]:
            self.assertIn(lane["state"], ("active", "paused", "error", "deleted"))

    def test_other_methods_are_not_routes(self):
        c = http.client.HTTPConnection("127.0.0.1", self.port)
        c.request("DELETE", "/api/overview")
        self.assertNotEqual(c.getresponse().status, 200)


if __name__ == "__main__":
    unittest.main()
