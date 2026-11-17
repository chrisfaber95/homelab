"""GET /api/tasks and GET /api/tasks/<id> (H-022 F10a): the data the Tasks pages (/taken, /task/<id>) render, with the
log so far for the live log. The HTML pages stay as they are. Run: python3 -m unittest discover -s tests"""
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
from homelab_inbox import config, helpers, overview, server, storage  # noqa: E402

JSON_UTF8 = "application/json; charset=utf-8"


class TasksApi(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inbox = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=cls.inbox.serve_forever, daemon=True).start()
        cls.port = cls.inbox.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.inbox.shutdown()
        cls.inbox.server_close()

    def get(self, path):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        c.request("GET", path)
        r = c.getresponse()
        raw = r.read().decode()
        c.close()
        return r.status, r.getheader("Content-Type"), raw

    def ok(self, path):
        code, ctype, raw = self.get(path)
        # /api/log/<t|r>N is plain application/json (the contract keeps it exactly)
        self.assertEqual((code, ctype), (200, "application/json" if path.startswith("/api/log/") else JSON_UTF8), raw)
        return json.loads(raw)

    def task(self, status="queued", text="Doe iets nuttigs\nmet meer regels", repo="homelab", **fields):
        tid = storage.new_task(repo, text)
        storage.update(tid, status=status, **fields)
        return tid

    def test_list_has_the_form_repos_queue_and_history(self):
        done = self.task("merged", ended=storage.now(), pr_url="https://example.test/pr/7", pr_number=7)
        queued = self.task("queued")
        waiting = self.task("waiting")
        body = self.ok("/api/tasks")
        self.assertEqual(body["auto"], config.AUTO)
        self.assertEqual(body["repos"], sorted(helpers.repo_list()))
        queue_ids = [r["id"] for r in body["queue"]]
        self.assertEqual(queue_ids, sorted(queue_ids))  # oldest first
        self.assertIn(queued, queue_ids)
        self.assertIn(waiting, queue_ids)
        self.assertNotIn(done, queue_ids)
        hist = {r["id"]: r for r in body["history"]}
        self.assertIn(done, hist)
        self.assertEqual(hist[done]["pr_number"], 7)
        self.assertEqual(hist[done]["status_text"], config.STATUS_NL["merged"])
        self.assertEqual(hist[done]["what"], "Doe iets nuttigs")
        self.assertEqual(hist[done]["label"], f"Inbox {done}")
        self.assertTrue(body["refresh"])
        self.assertEqual([r["id"] for r in body["history"]], sorted(hist, reverse=True))  # newest first

    def test_task_detail_carries_what_the_page_shows(self):
        tid = self.task("waiting", question="Welke kant op?", turns=3, rounds=1)
        d = self.ok(f"/api/tasks/{tid}")
        self.assertEqual((d["id"], d["status"], d["text"]), (tid, "waiting", "Doe iets nuttigs\nmet meer regels"))
        self.assertEqual(d["question"], "Welke kant op?")
        self.assertEqual((d["turns"], d["rounds"], d["model"]), (3, 1, "Claude"))
        self.assertEqual((d["max_turns"], d["max_minutes"]), (config.CONF["MAX_TURNS"], config.CONF["MAX_MINUTES"]))
        self.assertEqual(d["actions"], {"answer": True, "cancel": True, "retry": False, "accept": False, "attended": False})
        self.assertEqual(d["criteria"], [])
        self.assertFalse(d["refresh"])
        self.assertTrue(d["timezone"])
        self.assertEqual(self.ok("/api/tasks")["timezone"], d["timezone"])

    def test_actions_follow_the_status(self):
        failed = self.ok(f"/api/tasks/{self.task('failed')}")
        self.assertEqual(failed["actions"]["retry"], True)
        self.assertEqual((failed["retry_label"], failed["retry_hint"]), ("Retry", "Note for Claude (optional)"))
        self.assertFalse(failed["actions"]["cancel"])
        capped = self.ok(f"/api/tasks/{self.task('capped')}")
        self.assertEqual(capped["retry_label"], "Continue")
        refused = self.ok(f"/api/tasks/{self.task('refused', summary='nee')}")
        self.assertEqual((refused["summary_title"], refused["summary"]), ("Reden", "nee"))
        self.assertTrue(refused["retry_hint"].startswith("Why it is fine"))
        queued = self.ok(f"/api/tasks/{self.task('queued')}")
        self.assertTrue(queued["refresh"])
        self.assertTrue(queued["actions"]["cancel"])
        self.assertFalse(queued["actions"]["retry"])

    def test_a_brief_build_and_its_children(self):
        build = self.task("routed", repo=config.BRIEF, brief="H-999", criteria=None, unattended=1)
        kid = self.task("queued", parent=build, reason="step 1", criteria=json.dumps(["it works"]))
        d = self.ok(f"/api/tasks/{build}")
        self.assertTrue(d["is_build"] and d["unattended"])
        self.assertTrue(d["actions"]["attended"])
        self.assertFalse(d["actions"]["retry"])
        self.assertEqual([k["id"] for k in d["children"]], [kid])
        self.assertEqual(d["without_me"], overview.WITHOUT_ME)
        k = self.ok(f"/api/tasks/{kid}")
        self.assertEqual((k["parent"], k["reason"], k["criteria"]), (build, "step 1", ["it works"]))

    def test_the_log_so_far_and_where_to_poll_on(self):
        tid = self.task("running")
        helpers.log_line(f"t{tid}", "info", "begin")
        helpers.log_line(f"t{tid}", "claude", "bezig")
        log = self.ok(f"/api/tasks/{tid}")["log"]
        self.assertEqual([(l["kind"], l["text"]) for l in log["lines"]], [("info", "begin"), ("claude", "bezig")])
        self.assertEqual((log["next"], log["done"]), (2, False))
        poll = self.ok(f"/api/log/t{tid}?from={log['next']}")  # the polling endpoint continues where the detail stopped
        self.assertEqual(poll["lines"], [])
        helpers.log_line(f"t{tid}", "result", "klaar")
        storage.update(tid, status="merged")
        self.assertEqual([l["text"] for l in self.ok(f"/api/log/t{tid}?from=2")["lines"]], ["klaar"])
        self.assertTrue(self.ok(f"/api/tasks/{tid}")["log"]["done"])

    def test_no_log_is_an_empty_chunk(self):
        log = self.ok(f"/api/tasks/{self.task('failed')}")["log"]
        self.assertEqual(log, {"lines": [], "next": 0, "done": True})

    def test_unknown_task_is_a_json_404(self):
        code, ctype, raw = self.get("/api/tasks/99999999")
        self.assertEqual((code, ctype, json.loads(raw)), (404, JSON_UTF8, {"error": "Not found."}))
        self.assertEqual(self.get("/api/tasks/x")[0], 404)

    def test_the_box_itself_is_refused(self):
        os.environ.pop("INBOX_ALLOW_ALL")
        try:
            for path in ("/api/tasks", f"/api/tasks/{self.task()}"):
                code, ctype, raw = self.get(path)
                self.assertEqual((code, ctype), (403, JSON_UTF8))
                self.assertEqual(set(json.loads(raw)), {"error"})
        finally:
            os.environ["INBOX_ALLOW_ALL"] = "1"


if __name__ == "__main__":
    unittest.main()
