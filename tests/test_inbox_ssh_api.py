"""GET /api/ssh (H-022 F9a): the data the SSH page renders, as JSON, and the page still renders the same history.
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
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import pages, server  # noqa: E402

JSON_UTF8 = "application/json; charset=utf-8"


def entry(n, **kw):
    return json.dumps({"kind": "sshkey", "at": f"2026-11-16T10:{n:02d}:00+00:00", "summary": f"key {n}",
                       "result": "ok", "ok": True, "user": "inbox", **kw})


class SshApi(unittest.TestCase):
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
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.log = Path(tmp.name) / "changes.jsonl"
        old = pages.CHANGES_LOG
        pages.CHANGES_LOG = self.log
        self.addCleanup(lambda: setattr(pages, "CHANGES_LOG", old))

    def get(self, path):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        c.request("GET", path)
        r = c.getresponse()
        raw = r.read().decode()
        c.close()
        return r.status, r.getheader("Content-Type"), raw

    def api(self):
        code, ctype, raw = self.get("/api/ssh")
        self.assertEqual((code, ctype), (200, JSON_UTF8), raw)
        return json.loads(raw)

    def test_missing_log_gives_an_empty_history(self):
        body = self.api()
        self.assertEqual(body["history"], [])
        self.assertEqual(set(body), {"user", "confirm_minutes", "history"})
        self.assertIsInstance(body["user"], str)
        self.assertIsInstance(body["confirm_minutes"], int)

    def test_history_is_newest_first_with_statuses(self):
        self.log.write_text("\n".join([
            entry(1), entry(2, ok=False, result="mislukt"), entry(3, result="aangevraagd, wacht op bevestiging"),
            json.dumps({"kind": "other", "at": "x", "summary": "sshkey mention"}), "not json {\"sshkey\""]))
        # A garbled line makes the whole log unreadable, like the page: drop it and check the rest.
        self.assertEqual(self.api()["history"], [])
        self.log.write_text("\n".join([
            entry(1), entry(2, ok=False, result="mislukt"), entry(3, result="aangevraagd, wacht op bevestiging"),
            json.dumps({"kind": "other", "at": "x", "summary": "sshkey mention"})]))
        history = self.api()["history"]
        self.assertEqual([h["summary"] for h in history], ["key 3", "key 2", "key 1"])
        self.assertEqual([h["status"] for h in history], ["waiting", "fail", "ok"])
        self.assertEqual(history[0], {"status": "waiting", "summary": "key 3", "at": "2026-11-16T10:03:00+00:00",
                                      "result": "aangevraagd, wacht op bevestiging", "by": "inbox"})

    def test_only_the_last_15_entries(self):
        self.log.write_text("\n".join(entry(n) for n in range(20)))
        history = self.api()["history"]
        self.assertEqual(len(history), 15)
        self.assertEqual(history[0]["summary"], "key 19")
        self.assertEqual(history[-1]["summary"], "key 5")

    def test_page_shows_the_same_history(self):
        self.log.write_text(entry(7))
        code, _, html = self.get("/ssh")
        self.assertEqual(code, 200)
        self.assertIn("key 7", html)
        self.assertIn('<span class="st ok">ok</span>', html)

    def test_config_values(self):
        old = dict(pages.CONF)
        pages.CONF.update(SSH_KEY_USER="alice", CONFIRM_SECONDS="600")
        self.addCleanup(lambda: (pages.CONF.clear(), pages.CONF.update(old)))
        body = self.api()
        self.assertEqual((body["user"], body["confirm_minutes"]), ("alice", 10))


if __name__ == "__main__":
    unittest.main()
