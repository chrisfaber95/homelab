"""Build without me: check_pr merges a green PR itself, and says so when it can't read the CI status.
Run: python3 -m unittest discover -s tests"""
import os
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.update(INBOX_RUNNER_HOME=tempfile.mkdtemp(), NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"))
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import github, storage  # noqa: E402


class AutoMerge(unittest.TestCase):
    def setUp(self):
        c = storage.db()
        c.execute("DELETE FROM tasks")
        c.execute("INSERT INTO tasks(id,repo,text,status,created,session,unattended) "
                  "VALUES(41,'brief','b','routed','2026-10-31T13:00:00+00:00','s',1)")
        c.execute("INSERT INTO tasks(id,repo,text,status,created,ended,session,pr_number,pr_url,parent,brief,review_ok,"
                  "last_comment_at) VALUES(42,'f1-dashboard','x','pr_open','2026-10-31T13:00:00+00:00',"
                  "'2026-10-31T13:07:09+00:00','s',13,'u',41,'H-005',1,'2026-10-31T13:07:13+00:00')")
        c.commit()
        self.calls = []
        self.denied = False
        github.gh, self._gh = self.fake_gh, github.gh
        github.remember_task, self._rt = lambda i: None, github.remember_task
        github.task_cmd, self._tc = lambda *a: (0, ""), github.task_cmd

    def tearDown(self):
        github.gh, github.remember_task, github.task_cmd = self._gh, self._rt, self._tc

    def fake_gh(self, method, path, body=None):
        self.calls.append(method)
        p = path.split("?")[0]
        if p.endswith("/pulls/13"):
            return {"state": "open", "mergeable": True, "draft": False, "head": {"sha": "abc"}}
        if p.endswith("check-runs"):
            if self.denied:
                raise urllib.error.HTTPError(f"https://api.github.com{p}", 403, "Forbidden", {}, None)
            return {"check_runs": [{"status": "completed", "conclusion": "success"}]}
        return {} if method == "PUT" or p.endswith("/status") else []

    def test_green_pr_is_merged(self):
        github.check_pr(storage.get(42), set())
        self.assertIn("PUT", self.calls)
        self.assertEqual(storage.get(42)["status"], "merged")

    def test_unreadable_ci_is_reported_once_and_retried(self):
        sent = []
        github.notify, notify = lambda *a: sent.append(a), github.notify
        self.addCleanup(setattr, github, "notify", notify)
        self.denied = True
        github.check_pr(storage.get(42), set())
        github.check_pr(storage.get(42), set())
        t = storage.get(42)
        self.assertNotIn("PUT", self.calls)
        self.assertEqual(t["review_ok"], 1)
        self.assertIn("403", t["review"])
        self.assertEqual(len(sent), 1)
        self.denied = False  # the token got the permission
        github.check_pr(storage.get(42), set())
        self.assertIn("PUT", self.calls)
        self.assertEqual(storage.get(42)["status"], "merged")


if __name__ == "__main__":
    unittest.main()
