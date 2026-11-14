"""The conversation between Chris and the box (H-016): persistence, reading from a cursor, per-client permissions,
refusals, scrubbing, and the loopback door. Run: python3 -m unittest discover -s tests"""
import json
import os
import statistics
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOME = tempfile.mkdtemp()
TOKENS = tempfile.mkdtemp()
os.environ.update(INBOX_RUNNER_HOME=HOME, NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"),
                  CHAT_TOKEN_DIR=TOKENS, INBOX_ALLOW_ALL="1")
os.environ["CLIENTS_JSON"] = json.dumps({"clients": {
    "discord": {"permissions": ["read", "write", "ask", "answer"]},
    "reader": {"permissions": ["read"]},
    "mute": {"permissions": []}}})
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import config, conversation, helpers, server  # noqa: E402

FAKE = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4"  # a planted fake token, not a real one


class Store(unittest.TestCase):
    def test_entry_has_time_author_client_and_kind(self):
        e = conversation.append("claude", "inbox", "question", "Which repo?", "http://x/task/1", "task:1")
        self.assertEqual((e["author"], e["client"], e["kind"], e["ref"]), ("claude", "inbox", "question", "task:1"))
        self.assertRegex(e["time"], r"^\d{4}-\d\d-\d\dT")

    def test_unknown_author_or_kind_is_rejected(self):
        with self.assertRaises(ValueError):
            conversation.append("mallory", "inbox", "message", "x")
        with self.assertRaises(ValueError):
            conversation.append("chris", "inbox", "gossip", "x")

    def test_survives_a_restart(self):
        e = conversation.append("chris", "page", "message", "persist me")
        code = ("import sys;sys.path.insert(0,%r);from homelab_inbox import conversation as c;"
                "print([x['text'] for x in c.read(%d-1)])" % (str(ROOT / "bin"), e["id"]))
        out = subprocess.run([sys.executable, "-c", code], capture_output=True, text=True, check=True,
                             env={**os.environ, "INBOX_RUNNER_HOME": str(config.HOME)}).stdout
        self.assertIn("persist me", out)  # a new process (a restart) reads what the old one stored

    def test_read_from_cursor(self):
        a = conversation.append("chris", "page", "message", "one")
        b = conversation.append("box", "ntfy", "notification", "two")
        c = conversation.append("claude", "inbox", "answer", "three")
        self.assertEqual([e["id"] for e in conversation.read(a["id"])][:2], [b["id"], c["id"]])
        self.assertEqual(conversation.read(c["id"]), [])
        self.assertEqual(len(conversation.read(a["id"] - 1, limit=1)), 1)


class Scrubbing(unittest.TestCase):
    def test_planted_token_never_reaches_the_store(self):
        e = conversation.append("chris", "discord", "answer", f"use this: {FAKE} thanks")
        self.assertNotIn(FAKE, e["text"])
        self.assertIn("thanks", e["text"])
        for entry in conversation.read(0, 1000):
            self.assertNotIn(FAKE, json.dumps(entry))

    def test_token_in_link_and_notification(self):
        helpers.notify("Inbox 9: vraag", f"password: {FAKE}", f"http://x/?t={FAKE}")
        dump = json.dumps(conversation.read(0, 1000))
        self.assertNotIn(FAKE, dump)

    def test_token_through_client_write(self):
        e = conversation.client_append("discord", "message", f"Bearer {FAKE}{FAKE}")
        self.assertNotIn(FAKE, e["text"])


class Permissions(unittest.TestCase):
    def test_client_with_permission_reads_and_writes(self):
        e = conversation.client_append("discord", "message", "hello")
        self.assertEqual((e["author"], e["client"]), ("chris", "discord"))
        self.assertIsNotNone(conversation.client_read("discord", 0))

    def test_refused_without_permission_and_logged(self):
        before = len(conversation.refusals(1000))
        self.assertIsNone(conversation.client_append("reader", "message", "nope"))
        self.assertIsNone(conversation.client_read("mute"))
        self.assertIsNone(conversation.client_append("ghost", "message", "who"))
        refused = conversation.refusals(1000)[: len(conversation.refusals(1000)) - before]
        self.assertEqual({(r["client"], r["action"]) for r in refused},
                         {("reader", "write"), ("mute", "read"), ("ghost", "write")})
        self.assertNotIn("nope", [e["text"] for e in conversation.read(0, 1000)])

    def test_permission_not_granted_is_refused(self):
        self.assertFalse(conversation.allowed("discord", "approve"))
        self.assertFalse(conversation.allowed("discord", "merge"))  # exists for no client

    def test_client_cannot_write_as_claude_or_notify(self):
        self.assertIsNone(conversation.client_append("discord", "notification", "fake"))
        self.assertIsNone(conversation.client_append("discord", "approval", "fake"))


class Door(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (Path(TOKENS) / "chat-discord-token").write_text("tok-discord\n")
        (Path(TOKENS) / "chat-reader-token").write_text("tok-reader\n")
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.srv.server_address[1]}/api/chat/entries"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def call(self, token, data=None, query=""):
        req = urllib.request.Request(self.url + query, method="POST" if data is not None else "GET",
                                     data=json.dumps(data).encode() if data is not None else None,
                                     headers={"Authorization": f"Bearer {token}"})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_write_then_read_from_cursor(self):
        status, r = self.call("tok-discord", {"kind": "message", "text": f"hi {FAKE}"})
        self.assertEqual(status, 200)
        self.assertNotIn(FAKE, json.dumps(r))
        status, r = self.call("tok-discord", query=f"?after={r['entry']['id'] - 1}")
        self.assertEqual((status, [e["client"] for e in r["entries"]]), (200, ["discord"]))
        self.assertEqual(r["next"], r["entries"][-1]["id"])

    def test_wrong_token_and_missing_permission(self):
        self.assertEqual(self.call("wrong")[0], 401)
        self.assertEqual(self.call("tok-reader", {"text": "x"})[0], 403)
        self.assertEqual(self.call("tok-reader")[0], 200)

    def test_discord_may_not_approve_here(self):
        self.assertEqual(self.call("tok-discord", {"kind": "approval", "text": "approve H-016"})[0], 403)


class Speed(unittest.TestCase):
    def test_scrubbing_and_storing_cost_of_a_notification(self):
        """Measured, not assumed: the figures are in docs/conversation.md. Fails only if it becomes noticeable."""
        n = 200
        text = "Inbox 12: PR open\n" + "A normal sentence about a task. " * 3

        def run(store):
            t = []
            for _ in range(n):
                s = time.perf_counter()
                helpers.notify("Inbox 12: PR open", text, "http://homelab/task/12", store=store)
                t.append((time.perf_counter() - s) * 1000)
            return statistics.median(t), sorted(t)[int(n * .95)]
        with_ = run(True)
        without = run(False)
        print(f"\nnotify median/p95 ms: with conversation {with_[0]:.2f}/{with_[1]:.2f}, "
              f"without {without[0]:.2f}/{without[1]:.2f}", file=sys.stderr)
        self.assertLess(with_[0] - without[0], 50)  # ntfy itself is a local HTTP call to a daemon


if __name__ == "__main__":
    unittest.main()
