"""GET /api/jobs-page (H-022 F7a): the data behind the Jobs page and its catalogue, (the page itself is frontend/src/jobs.ts). Run: python3 -m unittest discover -s tests"""
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
from homelab_inbox import jobs, server  # noqa: E402
from homelab_inbox.config import JOBS  # noqa: E402
from homelab_inbox.storage import db  # noqa: E402

JOB = '---\nwhen: daily at 7:00\nschedule: "0 7 * * *"\nkind: box-report\nmodel: local\n---\nReport what failed.\n'
AGENT = ('---\nwhen: daily at 8:00\nschedule: "0 8 * * *"\nkind: box-report\nmodel: local\ndeliver: ntfy\n---\n'
         'Summarise the night.\n\nSecond paragraph.\n\nNOTHING TO REPORT when all is well.\n')


class JobsApi(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inbox = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=cls.inbox.serve_forever, daemon=True).start()
        cls.port = cls.inbox.server_address[1]
        cls.tmp = tempfile.TemporaryDirectory()
        agents = Path(cls.tmp.name)
        (agents / "night-digest.md").write_text(AGENT)
        (agents / "broken-agent.md").write_text("no header at all")
        cls._old = jobs.AGENTS_DIR, dict(JOBS)
        jobs.AGENTS_DIR = agents
        JOBS.clear()
        JOBS["a-job"] = jobs.parse_job("a-job", JOB, repos={}, skills={})
        JOBS["b-broken"] = {"error": "kapot <b>bestand</b>"}
        with db() as c:  # other test files share this database when discover runs them in one process
            c.execute("DELETE FROM drafts")
            c.execute("DELETE FROM job_runs")
            c.execute("INSERT INTO drafts (created, request, status) VALUES ('2026-11-16T07:00:00+00:00', "
                      "'Eerste regel <x>\nTweede regel', 'drafting')")
            c.execute("INSERT INTO drafts (created, request, status) VALUES ('2026-11-16T07:01:00+00:00', "
                      "'klaar', 'discarded')")
            c.execute("INSERT INTO job_runs (job, kind, model, due, created, status, summary, manual) VALUES "
                      "('a-job', 'box-report', 'local', '2026-11-16T06:00:00+00:00', '2026-11-16T06:00:00+00:00', "
                      "'ok', 'Alles goed\nregel twee', 1)")
            c.execute("INSERT INTO job_runs (job, kind, model, due, created, status) VALUES "
                      "('research', 'research', 'claude', '2026-11-16T05:00:00+00:00', '2026-11-16T05:00:00+00:00', "
                      "'queued')")

    @classmethod
    def tearDownClass(cls):
        with db() as c:
            c.execute("DELETE FROM drafts")
            c.execute("DELETE FROM job_runs")
        jobs.AGENTS_DIR = cls._old[0]
        JOBS.clear()
        JOBS.update(cls._old[1])
        cls.tmp.cleanup()
        cls.inbox.shutdown()
        cls.inbox.server_close()

    def get(self, path):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        c.request("GET", path)
        r = c.getresponse()
        raw = r.read().decode()
        c.close()
        return r.status, r.getheader("Content-Type"), raw

    def data(self):
        code, ctype, raw = self.get("/api/jobs-page")
        self.assertEqual((code, ctype), (200, "application/json; charset=utf-8"), raw)
        return json.loads(raw)

    def test_shape(self):
        d = self.data()
        self.assertEqual(set(d), {"claude_runs_today", "claude_cap", "timezone", "refresh", "drafts", "catalogue",
                                  "jobs", "runs"})
        self.assertEqual(d["claude_cap"], 10)
        self.assertEqual(d["timezone"], "Europe/Amsterdam")

    def test_jobs_sorted_and_broken_job_kept(self):
        a, b = self.data()["jobs"]
        self.assertEqual((a["name"], a["kind"], a["model"], a["when"], a["paused"]),
                         ("a-job", jobs.JOB_KINDS["box-report"], "lokaal", "daily at 7:00", False))
        self.assertRegex(a["next"], r"^(vandaag|morgen) 07:00$")
        self.assertEqual(a["last"], "ok")
        self.assertEqual(b, {"name": "b-broken", "error": "kapot <b>bestand</b>"})

    def test_catalogue_lists_good_and_broken_agents(self):
        good, broken = sorted(self.data()["catalogue"], key=lambda a: a["name"], reverse=True)
        self.assertEqual((good["name"], good["on"], good["what"], good["when"], good["model"], good["deliver"],
                          good["fill"]), ("night-digest", False, "Summarise the night.", "daily at 8:00", "lokaal",
                                          "ntfy", None))
        self.assertEqual(broken["name"], "broken-agent")
        self.assertTrue(broken["error"])
        self.assertNotIn("on", broken)

    def test_agent_with_a_job_of_that_name_is_on(self):
        JOBS["night-digest"] = JOBS["a-job"]
        self.addCleanup(JOBS.pop, "night-digest")
        self.assertTrue(next(a for a in self.data()["catalogue"] if a["name"] == "night-digest")["on"])

    def test_only_open_drafts_with_the_first_line(self):
        d = self.data()
        self.assertEqual(d["drafts"], [{"id": 1, "status": "drafting", "request": "Eerste regel <x>"}])
        self.assertTrue(d["refresh"])

    def test_runs_newest_first_with_links(self):
        research, ok = self.data()["runs"]
        self.assertEqual((research["job"], research["job_url"], research["status"], research["status_label"],
                          research["url"], research["model"], research["manual"], research["summary"]),
                         ("research", "/research", "queued", "in de rij", f"/run/{research['id']}", "Claude", False, ""))
        self.assertEqual((ok["job_url"], ok["due"], ok["manual"], ok["summary"], ok["pr"]),
                         ("/job/a-job", "16-11 07:00", True, "Alles goed", None))

    def test_query_string_is_ignored_and_other_paths_are_not_taken(self):
        self.assertEqual(self.get("/api/jobs-page?x=1")[0], 200)
        self.assertEqual(self.get("/api/jobs-pages")[0], 404)


if __name__ == "__main__":
    unittest.main()
