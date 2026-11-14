"""The agent catalogue (H-017): loader, broken files, switching on, and that nothing exists before the confirmation.
Run: python3 -m unittest discover -s tests"""
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
os.environ.update(INBOX_RUNNER_HOME=tempfile.mkdtemp(), NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"),
                  INBOX_ALLOW_ALL="1")
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import jobs, pages  # noqa: E402
from homelab_inbox.config import JOBS  # noqa: E402

GOOD = '---\nwhen: daily\nschedule: "0 7 * * *"\nkind: box-report\nmodel: local\n---\nReport what failed.\n'


class Catalogue(unittest.TestCase):
    def setUp(self):
        self.dir = Path(tempfile.mkdtemp())
        p = mock.patch.object(jobs, "AGENTS_DIR", self.dir)
        p.start()
        self.addCleanup(p.stop)
        JOBS.clear()
        self.addCleanup(JOBS.clear)

    def test_repo_example_loads(self):
        with mock.patch.object(jobs, "AGENTS_DIR", ROOT / "agents"):
            agents = jobs.load_agents()
        self.assertTrue(agents)
        self.assertTrue(all("error" not in a for a in agents), agents)

    def test_broken_file_does_not_hide_the_others(self):
        (self.dir / "good.md").write_text(GOOD)
        (self.dir / "bad.md").write_text("no header")
        (self.dir / "worse.md").write_text(GOOD.replace("local", "gpt"))
        by = {a["name"]: a for a in jobs.load_agents()}
        self.assertIn("job", by["good"])
        self.assertIn("kop", by["bad"]["error"])
        self.assertIn("model", by["worse"]["error"])
        html = pages.catalogue_html()
        self.assertIn("fout in het bestand", html)
        self.assertIn("Aanzetten", html)

    def test_catalogue_entry_shows_what_schedule_model_delivery(self):
        (self.dir / "good.md").write_text(GOOD)
        html = pages.catalogue_html()
        for s in ("Report what failed.", "daily", "lokaal", "bezorgt: page"):
            self.assertIn(s, html)

    def test_switch_on_makes_only_a_draft(self):
        (self.dir / "good.md").write_text(GOOD)
        with mock.patch.object(jobs, "save_job") as save, mock.patch.object(jobs, "dispatch") as run:
            did = jobs.switch_on_agent("good")
        save.assert_not_called()
        run.assert_not_called()
        d = jobs.get_draft(did)
        self.assertEqual(d["status"], "ready")
        self.assertEqual(jobs.draft_name(d["text"]), "good")
        self.assertEqual(jobs.parse_job("good", d["text"], {}, {})["kind"], "box-report")
        self.assertNotIn("good", JOBS)

    def test_broken_or_unknown_agent_cannot_be_switched_on(self):
        (self.dir / "bad.md").write_text("no header")
        self.assertIsNone(jobs.switch_on_agent("bad"))
        self.assertIsNone(jobs.switch_on_agent("nope"))

    def test_on_once_a_job_exists(self):
        (self.dir / "good.md").write_text(GOOD)
        JOBS["good"] = {"name": "good"}
        self.assertIn("staat aan", pages.catalogue_html())
        self.assertNotIn("/agents/good/on", pages.catalogue_html())


if __name__ == "__main__":
    unittest.main()
