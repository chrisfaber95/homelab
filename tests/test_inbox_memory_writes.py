"""The Python side of the memory writes (H-024 M5): memory.py no longer writes the clone, it asks the backend
(`memory.transport`, here the in-process stand-in of tests/memory_fake.py). The Java side is MemoryWriterTest and
tests/test_inbox_m5_parity.py. Run: python3 -m unittest tests.test_inbox_memory_writes"""
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"), INBOX_ALLOW_ALL="1")
os.environ.setdefault("BACKEND_ROUTES", "")  # the facade reads it once; keep the Python handlers for the other test modules
sys.path.insert(0, str(ROOT / "bin"))
sys.path.insert(0, str(ROOT / "tests"))
import memory_fake  # noqa: E402
from homelab_inbox import helpers, memory  # noqa: E402


class MemoryWrites(unittest.TestCase):
    def setUp(self):
        self._transport, self._get, self._update = memory.transport, memory.get, memory.update
        self.fake = memory_fake.install(memory, helpers.repo_list)
        self.home = Path(tempfile.mkdtemp())
        self._dir, memory.MEMORY_DIR = memory.MEMORY_DIR, self.home
        memory.MEMORY[:] = []

    def tearDown(self):
        memory.transport, memory.get, memory.update, memory.MEMORY_DIR = self._transport, self._get, self._update, self._dir
        memory.MEMORY[:] = []

    def test_python_has_no_write_path_of_its_own_left(self):
        for name in ("save_memory", "entry_text", "memory_sync", "CLAUDE_AUTHOR"):
            self.assertFalse(hasattr(memory, name), name)

    def task(self, **extra):
        return {"id": 7, "mem_source": None, "mem_link": None, "repo": "homelab", "job": None, "created": "2026-11-17T08:00:00+00:00",
                "status": "merged", "text": "doe iets", "answer": "klaar", "summary": "", "question": "", "note": "",
                "pr_url": "https://github.com/x/y/pull/1", "remembered": 0, "title": None, **extra}

    def test_save_learned_sends_the_blocks_and_gives_back_the_notes_and_refreshes_the_cache(self):
        t = self.task()
        notes = memory.save_learned(t, [("feit", "box", "nieuw", "Een feit"), ("feit", "nergens", "x", "t")])
        self.assertEqual(notes, ["1 item(s) in het geheugen bewaard", "x: onbekend onderwerp nergens"])
        path, body = self.fake.calls[-1]
        self.assertEqual(path, "/internal/memory/learned")
        self.assertEqual(body["blocks"][0], ["feit", "box", "nieuw", "Een feit"])
        self.assertEqual(body["source"], helpers.label(t))
        self.assertEqual(body["link"], helpers.page_url(7))
        self.assertEqual([m["id"] for m in memory.MEMORY], ["feiten/box/nieuw"])  # read back from the clone

    def test_remember_task_marks_the_task_only_when_the_backend_stored_it(self):
        marked = []
        memory.get = lambda i: self.task()
        memory.update = lambda i, **kw: marked.append((i, kw))
        memory.remember_task(7)
        path, body = self.fake.calls[-1]
        self.assertEqual((path, body["id"], body["status_nl"], body["asked"], body["result"], body["remembered"]),
                         ("/internal/memory/history", 7, "gemerged", "doe iets", "klaar", False))
        self.assertEqual(marked, [(7, {"remembered": 1})])
        marked.clear()
        memory.MEMORY[:] = []  # the user erased the entry on the page
        memory.get = lambda i: self.task(remembered=1)  # remembered once and erased by the user: left alone
        memory.remember_task(7)
        self.assertEqual(marked, [])
        memory.get = lambda i: self.task(status="running")  # not final: nothing is sent
        n = len(self.fake.calls)
        memory.remember_task(7)
        self.assertEqual(len(self.fake.calls), n)

    def test_a_backend_that_does_not_answer_writes_nothing_and_after_round_reports_it(self):
        def down(path, body):
            raise memory.Unavailable("memory store: the backend did not answer (ConnectionRefusedError)")
        memory.transport = down
        with self.assertRaises(memory.Unavailable):
            memory.save_learned(self.task(), [("feit", "box", "x", "t")])
        self.assertEqual(list(self.home.glob("**/*.md")), [])
        updates = []
        memory.get = lambda i: self.task(note="")
        memory.update = lambda i, **kw: updates.append(kw)
        memory.after_round(7, [("feit", "box", "x", "t")])
        self.assertIn("geheugen bewaren mislukt", updates[0]["note"])

    def test_load_memory_asks_the_backend_to_sync_and_still_reads_when_it_cannot(self):
        (self.home / "feiten/box").mkdir(parents=True)
        (self.home / "feiten/box/a.md").write_text('---\nover: "box"\n---\nx\n')
        memory.load_memory()
        self.assertEqual(self.fake.calls[-1][0], "/internal/memory/sync")
        self.assertEqual([m["id"] for m in memory.MEMORY], ["feiten/box/a"])

        def down(path, body):
            raise memory.Unavailable("down")
        memory.transport = down
        memory.load_memory()  # logs, keeps reading the clone as it is
        self.assertEqual([m["id"] for m in memory.MEMORY], ["feiten/box/a"])

    def test_the_page_answers_502_when_the_backend_is_down(self):
        import http.client
        import threading
        import urllib.parse
        from http.server import ThreadingHTTPServer
        from homelab_inbox import server

        def down(path, body):
            raise memory.Unavailable("memory store: no backend secret (BACKEND_SECRET or BACKEND_SECRET_FILE)")
        memory.transport = down
        srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            port = srv.server_address[1]
            c = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            c.request("GET", "/api/session")
            token = json.loads(c.getresponse().read())["csrf_token"]
            c.request("POST", "/memory/add", urllib.parse.urlencode({"csrf": token, "kind": "feiten", "about": "box", "name": "x", "text": "t"}),
                      {"Content-Type": "application/x-www-form-urlencoded", "Origin": f"http://127.0.0.1:{port}"})
            r = c.getresponse()
            self.assertEqual((r.status, list(json.loads(r.read()))), (502, ["error"]))
        finally:
            srv.shutdown()
            srv.server_close()


if __name__ == "__main__":
    unittest.main()
