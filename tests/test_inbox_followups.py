"""Follow-ups of a task that ended badly: a merge or "mark as fine" brings them back, and nothing runs twice.
Run: python3 -m unittest discover -s tests"""
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"))
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import github, queue, router, storage  # noqa: E402

CANCELLED = "niet gestart: de voorgaande taak #74 was geweigerd"
T = "2026-10-31T19:00:00+00:00"


class Followups(unittest.TestCase):
    def setUp(self):
        c = storage.db()
        c.execute("DELETE FROM tasks")
        c.execute("INSERT INTO tasks(id,repo,text,status,created,ended,session) VALUES(70,'brief','b','routed',?,?,'s')", (T, T))
        c.execute("INSERT INTO tasks(id,repo,text,status,created,ended,session,parent,pr_number,pr_url) "
                  "VALUES(74,'homelab','a','refused',?,?,'s',70,68,'u')", (T, T))
        for i, pre in ((75, 74), (76, 75)):
            c.execute("INSERT INTO tasks(id,repo,text,status,created,ended,session,parent,after_task,note) "
                      "VALUES(?,'homelab','f','cancelled',?,?,'s',70,?,?)", (i, T, T, pre, CANCELLED))
        c.commit()
        self.pr = {"state": "closed", "merged": True}
        github.gh, self._gh = lambda *a, **k: self.pr, github.gh
        github.remember_task, self._rt = lambda i: None, github.remember_task
        github.task_cmd, self._tc = lambda *a: (0, ""), github.task_cmd

    def tearDown(self):
        github.gh, github.remember_task, github.task_cmd = self._gh, self._rt, self._tc

    def status(self, i):
        return storage.get(i)["status"]

    def test_merged_pr_requeues_followups_in_a_chain(self):
        github.check_blocking_pr(storage.get(74))
        self.assertEqual([self.status(i) for i in (74, 75, 76)], ["merged", "queued", "queued"])
        self.assertIsNone(storage.get(70)["ended"])

    def test_open_or_closed_pr_changes_nothing(self):
        for pr in ({"state": "open"}, {"state": "closed", "merged": False}):
            self.pr = pr
            github.check_blocking_pr(storage.get(74))
            self.assertEqual([self.status(i) for i in (74, 75)], ["refused", "cancelled"])

    def test_merge_does_not_restart_a_followup_from_before_this_feature(self):
        storage.update(75, no_auto_revive=1)
        self.assertEqual(router.blocked_followups(storage.get(74), True), [])
        github.check_blocking_pr(storage.get(74))
        self.assertEqual([self.status(i) for i in (74, 75)], ["merged", "cancelled"])

    def test_mark_as_fine_requeues_followups_and_not_the_task(self):
        self.assertEqual(router.accept_task(storage.get(74)), 2)
        self.assertEqual([self.status(i) for i in (74, 75, 76)], ["refused", "queued", "queued"])
        router.settle_routed()  # the scheduler must not cancel them again
        self.assertEqual(self.status(75), "queued")
        self.assertIsNone(router.blocked_by(storage.get(75)))

    def test_mark_as_fine_needs_a_blocked_followup(self):
        storage.update(75, status="merged")
        self.assertEqual(router.accept_task(storage.get(74)), 0)
        self.assertFalse(storage.get(74)["accepted"])

    def test_unmarked_task_still_stops_followups(self):
        storage.update(70, ended=None)
        storage.update(75, status="queued", ended=None, note=None)
        router.settle_routed()
        self.assertEqual(self.status(75), "cancelled")

    def test_retry_clears_the_mark(self):
        router.accept_task(storage.get(74))
        storage.update(74, status="failed")
        queue.retry_task(storage.get(74))
        self.assertFalse(storage.get(74)["accepted"])

    def test_retry_a_refused_task(self):
        storage.update(74, rounds=1, summary="mag niet: F-019")
        queue.task_cmd, tc = lambda *a: (1, ""), queue.task_cmd  # its working copy was cleaned up
        queue.pushed, pu = lambda t: False, queue.pushed
        try:
            queue.retry_task(storage.get(74), "  it is fine: Chris said so  ")
        finally:
            queue.task_cmd, queue.pushed = tc, pu
        t = storage.get(74)
        self.assertEqual((t["status"], t["reprepare"], t["summary"]), ("queued", 1, None))
        self.assertIn("mag niet: F-019", t["pending_input"])
        self.assertIn("it is fine: Chris said so", t["pending_input"])
        self.assertEqual([self.status(i) for i in (75, 76)], ["queued", "queued"])

    def test_retry_note_of_a_task_that_never_ran_goes_in_its_text(self):
        storage.update(74, status="failed")
        queue.retry_task(storage.get(74), "try again")
        self.assertTrue(storage.get(74)["text"].endswith("try again"))

    def test_migration_flags_only_existing_followups(self):
        conn = storage.db()
        conn.execute("ALTER TABLE tasks DROP COLUMN no_auto_revive")
        conn.commit()
        conn.close()
        self.assertEqual(storage.get(75)["no_auto_revive"], 1)
        self.assertEqual(storage.get(74)["no_auto_revive"], 0)


if __name__ == "__main__":
    unittest.main()
