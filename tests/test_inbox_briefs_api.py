"""GET /api/briefs and /api/briefs/<id> (H-022 F11a): the data the Briefs page renders, as JSON. The briefs and the
builds are faked. Run: python3 -m unittest discover -s tests"""
import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"), INBOX_ALLOW_ALL="1")
os.environ.setdefault("BACKEND_ROUTES", "")  # config/inbox.conf forwards the M1 routes to Java (H-024); these tests run the Python handlers
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import overview, server  # noqa: E402

JSON_UTF8 = "application/json; charset=utf-8"


def brief(bid, status, text="---\nid: x\n---\nBody of the brief."):
    return {"id": bid, "title": f"Title {bid}", "status": status, "priority": "should", "repos": ["homelab"],
            "project": "homelab", "path": f"homelab/briefs/{bid}.md", "text": text}


ITEMS = [brief("H-001", "draft"), brief("H-002", "approved"), brief("H-003", "approved"), brief("H-004", "needs-design")]
BUILD = {"id": 7, "job": None, "text": "Build H-003\nmore", "repo": "brief", "source": "web", "created": "2026-11-16T10:00:00+00:00",
         "ended": None, "pr_number": None, "pr_url": None, "status": "routed"}


class BriefsApi(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inbox = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=cls.inbox.serve_forever, daemon=True).start()
        cls.port = cls.inbox.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.inbox.shutdown()
        cls.inbox.server_close()

    def setUp(self):
        self._saved = overview.load_briefs, overview.builds, overview.live_build, server.brief_by_id, overview.label
        overview.load_briefs = lambda: ITEMS
        overview.builds = lambda: [BUILD]
        overview.live_build = lambda bid: BUILD if bid == "H-003" else None
        server.brief_by_id = lambda bid: next((b for b in ITEMS if b["id"] == bid), None)
        overview.label = lambda t: f"build #{t['id']}"

    def tearDown(self):
        overview.load_briefs, overview.builds, overview.live_build, server.brief_by_id, overview.label = self._saved

    def get(self, path):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        c.request("GET", path)
        r = c.getresponse()
        raw = r.read().decode()
        c.close()
        return r.status, r.getheader("Content-Type"), json.loads(raw)

    def test_list(self):
        code, ctype, d = self.get("/api/briefs")
        self.assertEqual((code, ctype), (200, JSON_UTF8))
        self.assertEqual([b["id"] for b in d["drafts"]], ["H-001"])
        self.assertEqual([b["id"] for b in d["approved"]], ["H-002", "H-003"])
        self.assertEqual([b["id"] for b in d["needs_design"]], ["H-004"])
        self.assertEqual(d["drafts"][0]["actions"], ["build", "build-without-me", "park"])
        self.assertEqual(d["approved"][0]["actions"], ["build", "build-without-me"])
        self.assertEqual(d["approved"][1]["actions"], [])
        self.assertEqual(d["approved"][1]["build"], {"id": 7, "url": "/task/7"})
        self.assertIsNone(d["approved"][0]["build"])
        self.assertNotIn("text", d["drafts"][0])
        self.assertEqual([(b["id"], b["label"], b["status"], b["status_label"], b["what"]) for b in d["builds"]],
                         [(7, "build #7", "routed", overview.STATUS_NL["routed"], "Build H-003")])
        self.assertEqual(d["counts"], [{"status": "approved", "count": 2}, {"status": "draft", "count": 1},
                                       {"status": "needs-design", "count": 1}])
        self.assertEqual(d["without_me"], overview.WITHOUT_ME)
        self.assertIsNone(d["error"])
        self.assertFalse(d["refresh"])

    def test_unreadable_product_repo(self):
        def boom():
            raise OSError("no clone")
        overview.load_briefs = boom
        code, _, d = self.get("/api/briefs")
        self.assertEqual(code, 200)
        self.assertIn("no clone", d["error"])
        self.assertEqual((d["drafts"], d["approved"], d["needs_design"], d["counts"]), ([], [], [], []))

    def test_one_brief(self):
        code, ctype, d = self.get("/api/briefs/H-001")
        self.assertEqual((code, ctype), (200, JSON_UTF8))
        self.assertEqual(d["brief"]["text"], "Body of the brief.")
        self.assertEqual(d["brief"]["path"], "homelab/briefs/H-001.md")
        self.assertIsNone(d["error"])

    def test_times_come_with_their_zone(self):
        self.assertEqual(self.get("/api/briefs")[2]["timezone"], str(overview.TZ))
        self.assertEqual(self.get("/api/briefs/H-001")[2]["timezone"], str(overview.TZ))

    def post(self, path, fields):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        c.request("POST", path, urllib.parse.urlencode({"csrf": server.csrf_token(), **fields}),
                  {"Origin": f"http://127.0.0.1:{self.port}", "Content-Type": "application/x-www-form-urlencoded"})
        r = c.getresponse()
        out = r.status, r.getheader("Content-Type"), r.getheader("Location"), r.read().decode()
        c.close()
        return out

    def test_a_failed_build_is_400_with_the_reason(self):
        saved = server.start_build
        self.addCleanup(lambda: setattr(server, "start_build", saved))
        def refuse(*a, **k):
            raise ValueError("H-003 is already being built")
        server.start_build = refuse
        code, ctype, _, body = self.post("/briefs/H-003/build", {"mode": "without-me"})
        self.assertEqual((code, ctype), (400, JSON_UTF8))
        self.assertEqual(json.loads(body), {"error": "H-003 is already being built"})

    def test_park_redirects_to_the_briefs_page(self):
        saved = server.set_brief_status
        self.addCleanup(lambda: setattr(server, "set_brief_status", saved))
        server.set_brief_status = lambda *a: None
        code, _, location, _ = self.post("/briefs/H-001/park", {})
        self.assertEqual((code, location), (303, "/briefs"))

    def test_unknown_brief(self):
        code, ctype, d = self.get("/api/briefs/H-999")
        self.assertEqual((code, ctype, set(d)), (404, JSON_UTF8, {"error"}))


if __name__ == "__main__":
    unittest.main()
