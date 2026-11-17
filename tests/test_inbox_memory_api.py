"""GET /api/memory and GET /api/memory/e/<id> (H-022 F5a): the data the Geheugen pages render, as JSON.
Run: python3 -m unittest discover -s tests"""
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
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"), INBOX_ALLOW_ALL="1")
os.environ.setdefault("BACKEND_ROUTES", "")  # config/inbox.conf forwards the M1 routes to Java (H-024); these tests run the Python handlers
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import memory, server  # noqa: E402

JSON_UTF8 = "application/json; charset=utf-8"


def entry(mid, body, added="2026-11-01T10:00:00+00:00", **extra):
    return memory.parse_entry(mid, "---\nover: algemeen\ntoegevoegd: '%s'\nbron: test\n%s---\n%s\n" % (
        added, "".join(f"{k}: {v}\n" for k, v in extra.items()), body))


class MemoryApi(unittest.TestCase):
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
        self._old = list(memory.MEMORY)
        memory.MEMORY[:] = [
            entry("voorkeuren/algemeen/klein", "Keep PRs small\nmore"),
            entry("feiten/box/deploy", "Deploy runs at night"),
            *[entry(f"geschiedenis/2026-11/taak-{n}", f"# Task {n}\nbody", added=f"2026-11-{n % 28 + 1:02d}T10:00:00+00:00",
                    status="merged") for n in range(1, 36)]]

    def tearDown(self):
        memory.MEMORY[:] = self._old

    def get(self, path):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        c.request("GET", path)
        r = c.getresponse()
        raw = r.read().decode()
        c.close()
        return r.status, r.getheader("Content-Type"), json.loads(raw)

    def test_list_has_the_three_sections_in_page_order(self):
        code, ctype, body = self.get("/api/memory")
        self.assertEqual((code, ctype), (200, JSON_UTF8))
        self.assertEqual([s["kind"] for s in body["sections"]], ["voorkeuren", "feiten", "geschiedenis"])
        self.assertEqual(body["q"], "")
        self.assertEqual(body["abouts"][:2], ["algemeen", "box"])
        pref = body["sections"][0]["entries"][0]
        self.assertEqual((pref["title"], pref["summary"], pref["kind_label"]), ("klein", "Keep PRs small", "voorkeur"))
        self.assertEqual(pref["added_local"], "01-11 11:00")

    def test_history_is_newest_first_and_cut_to_30_without_a_query(self):
        hist = self.get("/api/memory")[2]["sections"][2]
        self.assertEqual((hist["count"], len(hist["entries"]), hist["truncated"]), (35, 30, True))
        dates = [e["added"] for e in hist["entries"]]
        self.assertEqual(dates, sorted(dates, reverse=True))
        self.assertEqual(hist["entries"][0]["title"][:4], "Task")
        self.assertEqual(hist["entries"][0]["summary"], "")

    def test_query_filters_every_word_and_shows_all_history(self):
        body = self.get("/api/memory?q=MERGED+task")[2]
        hist = body["sections"][2]
        self.assertEqual((hist["count"], len(hist["entries"]), hist["truncated"]), (35, 35, False))
        self.assertEqual(body["sections"][0]["count"], 0)
        none = self.get("/api/memory?q=deploy+merged")[2]
        self.assertEqual([s["count"] for s in none["sections"]], [0, 0, 0])

    def test_entry_has_the_page_fields_and_unknown_is_a_json_404(self):
        code, ctype, body = self.get("/api/memory/e/feiten/box/deploy")
        self.assertEqual((code, ctype), (200, JSON_UTF8))
        self.assertEqual((body["id"], body["body"], body["kind_label"]), ("feiten/box/deploy", "Deploy runs at night", "feit"))
        self.assertIn("branch", body)
        self.assertIn("data_repo", body)
        code, ctype, body = self.get("/api/memory/e/feiten/box/nothing")
        self.assertEqual((code, ctype, set(body)), (404, JSON_UTF8, {"error"}))

    def post(self, path, **fields):
        import urllib.parse
        token = self.get("/api/session")[2]["csrf_token"]
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        c.request("POST", path, urllib.parse.urlencode({"csrf": token, **fields}),
                  {"Content-Type": "application/x-www-form-urlencoded", "Origin": f"http://127.0.0.1:{self.port}"})
        r = c.getresponse()
        raw = r.read().decode()
        c.close()
        return r.status, r.getheader("Content-Type"), json.loads(raw)

    def test_failed_posts_answer_json_errors_for_the_page(self):
        code, ctype, body = self.post("/memory/add", kind="nope", about="algemeen", name="x", text="t")
        self.assertEqual((code, ctype, set(body)), (400, JSON_UTF8, {"error"}))
        code, ctype, body = self.post("/memory/add", kind="feiten", about="box", name="deploy", text="t")
        self.assertEqual((code, ctype), (400, JSON_UTF8))
        self.assertIn("bestaat al", body["error"])
        code, ctype, body = self.post("/memory/e/feiten/box/nothing/save", text="t")
        self.assertEqual((code, ctype, set(body)), (404, JSON_UTF8, {"error"}))
        code, ctype, body = self.post("/memory/e/feiten/box/deploy/save", text="  ")
        self.assertEqual((code, ctype, set(body)), (400, JSON_UTF8, {"error"}))


if __name__ == "__main__":
    unittest.main()
