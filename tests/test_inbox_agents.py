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


class FirstAgents(unittest.TestCase):
    def setUp(self):
        with mock.patch.object(jobs, "AGENTS_DIR", ROOT / "agents"):
            self.by = {a["name"]: a for a in jobs.load_agents()}

    def test_the_three_are_there_and_none_is_broken(self):
        for n in ("morning-digest", "box-report", "repo-watcher"):
            self.assertIn(n, self.by)
            self.assertNotIn("error", self.by[n], self.by[n])
            j = self.by[n]["job"]
            self.assertEqual(j["deliver"], "conversation")
            self.assertEqual(j["model"], "local")
            self.assertIn(jobs.NOTHING_MARKER, jobs.nothing_statement(j))

    def test_sources_start_empty_and_are_asked_for(self):
        for n in ("morning-digest", "repo-watcher"):
            self.assertEqual(self.by[n]["job"]["sources"], [])
            self.assertIn("sources", self.by[n]["fill"])
        with mock.patch.object(jobs, "AGENTS_DIR", ROOT / "agents"):
            did = jobs.switch_on_agent("morning-digest")
            self.assertIsNotNone(did)
            self.assertIn("in te vullen", pages.catalogue_html())
        text = jobs.get_draft(did)["text"]
        with self.assertRaises(jobs.NeedsInput):  # cannot be confirmed until Chris fills them in
            jobs.parse_job("morning-digest", text, {}, {})
        filled = text.replace("sources: []", "sources: [https://example.org/feed.xml]")
        self.assertEqual(jobs.parse_job("morning-digest", filled, {}, {})["sources"], ["https://example.org/feed.xml"])

    def test_watcher_takes_only_github_repos(self):
        text = (ROOT / "agents/repo-watcher.md").read_text()
        ok = text.replace("sources: []", "sources: [github:chrisfaber95/homelab]")
        self.assertEqual(jobs.parse_job("repo-watcher", ok, {}, {})["kind"], "repo-watch")
        with self.assertRaises(ValueError):
            jobs.parse_job("repo-watcher", text.replace("sources: []", "sources: [https://example.org/x]"), {}, {})


class RepoWatcher(unittest.TestCase):
    def test_since_is_the_start_of_the_previous_runs_day(self):
        from datetime import datetime, timezone
        now_dt = datetime(2026, 11, 14, 8, 0, tzinfo=timezone.utc)
        got = jobs.since_day("2026-11-12T15:30:00+00:00", now_dt)
        self.assertEqual(datetime.fromisoformat(got.replace("Z", "+00:00")).astimezone(jobs.TZ).strftime("%Y-%m-%d %H:%M"),
                         "2026-11-12 00:00")

    def test_first_run_starts_yesterday_and_the_look_back_is_capped(self):
        from datetime import datetime, timezone
        now_dt = datetime(2026, 11, 14, 8, 0, tzinfo=timezone.utc)
        first = jobs.since_day(None, now_dt)
        self.assertEqual(datetime.fromisoformat(first.replace("Z", "+00:00")).astimezone(jobs.TZ).strftime("%Y-%m-%d"),
                         "2026-11-13")
        old = jobs.since_day("2025-01-01T00:00:00+00:00", now_dt)
        self.assertGreaterEqual(old[:10], "2026-10-13")

    def answers(self, since):
        old, new = "2026-11-01T10:00:00Z", "2026-11-13T10:00:00Z"
        return {
            "/repos/o/r/commits": [{"sha": "abcdef123", "commit": {"message": "Fix it\n\nlong"}}],
            "/repos/o/r/pulls": [{"number": 5, "state": "closed", "merged_at": new, "title": "New PR", "updated_at": new},
                                 {"number": 4, "state": "open", "title": "Old PR", "updated_at": old}],
            "/repos/o/r/releases": [{"tag_name": "v2", "name": "Two", "published_at": new, "html_url": "u"},
                                    {"tag_name": "v1", "published_at": old},
                                    {"tag_name": "v3", "draft": True, "published_at": new}]}

    def test_reports_commits_prs_and_releases_since(self):
        since = "2026-11-12T00:00:00Z"
        a = self.answers(since)
        with mock.patch.object(jobs, "gh", lambda m, path, body=None: a[path.split("?")[0]]):
            out = jobs.watch_repo("o/r", since)
        self.assertIn("commit abcdef1 Fix it", out)
        self.assertIn("PR #5 (gemerged): New PR", out)
        self.assertNotIn("Old PR", out)
        self.assertIn("release v2", out)
        self.assertNotIn("v1", out)
        self.assertNotIn("v3", out)

    def test_nothing_new_says_so(self):
        with mock.patch.object(jobs, "gh", lambda m, path, body=None: []):
            self.assertIn("geen activiteit", jobs.watch_repo("o/r", "2026-11-12T00:00:00Z"))

    def test_previous_run_is_the_last_ok_run_before_this_one(self):
        from homelab_inbox.storage import db
        with db() as c:
            for status, created in (("ok", "2026-11-10T08:00:00+00:00"), ("failed", "2026-11-12T08:00:00+00:00")):
                c.execute("INSERT INTO job_runs (job, kind, model, due, created, status, manual) VALUES (?,?,?,?,?,?,0)",
                          ("w", "repo-watch", "local", created, created, status))
            last = c.execute("SELECT MAX(id) FROM job_runs").fetchone()[0]
        self.assertEqual(jobs.previous_run_time("w", last + 1), "2026-11-10T08:00:00+00:00")
        self.assertIsNone(jobs.previous_run_time("other", last + 1))


if __name__ == "__main__":
    unittest.main()
