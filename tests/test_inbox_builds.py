"""H-001: a build that left nothing lets the brief be built again; one that is still going does not.
Run: python3 -m unittest discover -s tests"""
import os
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"))
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import briefs, storage  # noqa: E402

T = "2026-11-06T21:00:00+00:00"
BID = "H-014"
BRIEF_FILE = {"id": BID, "title": "First playable", "status": "approved", "repos": ["homelab"],
              "build": {}, "project": "pixel-quest", "path": f"pixel-quest/briefs/{BID}.md", "text": "brief"}


class Builds(unittest.TestCase):
    def setUp(self):
        c = storage.db()
        c.execute("DELETE FROM tasks")
        c.commit()
        briefs.brief_by_id, self._by_id = lambda bid, max_age=60: BRIEF_FILE if bid == BID else None, briefs.brief_by_id
        briefs.add_new_repo, self._add = lambda b: None, briefs.add_new_repo

    def tearDown(self):
        briefs.brief_by_id, briefs.add_new_repo = self._by_id, self._add

    def build(self, status, ended=T):
        with storage.db() as c:
            return c.execute("INSERT INTO tasks(repo,text,status,created,ended,session,brief) "
                             "VALUES('brief','Build',?,?,?,'s',?)", (status, T, ended, BID)).lastrowid

    def child(self, parent, status, ended=T):
        with storage.db() as c:
            return c.execute("INSERT INTO tasks(repo,text,status,created,ended,session,brief,parent) "
                             "VALUES('homelab','task',?,?,?,'s',?,?)", (status, T, ended, BID, parent)).lastrowid

    def test_a_cancelled_build_does_not_block_a_new_one(self):
        self.build("cancelled")
        self.assertIsNone(briefs.live_build(BID))
        self.assertTrue(briefs.start_build(BID))

    def test_a_planned_build_whose_tasks_were_all_cancelled_can_be_started_again(self):
        # The state after cancelling the tasks of a build: the parent keeps status routed and gets an end time
        # (settle_routed), so without this it stayed "live" and the Build button never came back.
        parent = self.build("routed")
        self.child(parent, "cancelled")
        self.child(parent, "cancelled")
        self.assertTrue(briefs.build_left_nothing(storage.get(parent)))
        self.assertIsNone(briefs.live_build(BID))
        new = briefs.start_build(BID)
        self.assertEqual(storage.get(new)["status"], "queued")

    def test_a_build_with_a_merged_task_stays_live(self):
        parent = self.build("routed")
        self.child(parent, "merged")
        self.child(parent, "cancelled")
        self.assertFalse(briefs.build_left_nothing(storage.get(parent)))
        self.assertEqual(briefs.live_build(BID)["id"], parent)
        with self.assertRaises(ValueError):
            briefs.start_build(BID)

    def test_a_build_that_is_still_running_stays_live(self):
        parent = self.build("routed", ended=None)
        self.child(parent, "queued", ended=None)
        self.assertFalse(briefs.build_left_nothing(storage.get(parent)))
        self.assertEqual(briefs.live_build(BID)["id"], parent)
        with self.assertRaises(ValueError):
            briefs.start_build(BID)

    def test_a_build_without_tasks_yet_stays_live(self):
        parent = self.build("queued", ended=None)
        self.assertFalse(briefs.build_left_nothing(storage.get(parent)))
        self.assertEqual(briefs.live_build(BID)["id"], parent)

    def test_a_cancelled_build_does_not_hide_an_earlier_one_with_merged_work(self):
        old = self.build("routed")
        self.child(old, "merged")
        new = self.build("cancelled")
        self.assertEqual(briefs.live_build(BID)["id"], old)
        self.assertEqual([b["id"] for b in briefs.builds(BID)], [new, old])


if __name__ == "__main__":
    unittest.main()
