"""The job pages (H-022 F7b): GET /api/job/<name>, /api/draft/<id>, /api/run/<id>, /api/research (the data behind the
TypeScript pages), the routes of the pages themselves, and that a failed POST answers `{"error": ...}` instead of an HTML
page. Run: python3 -m unittest discover -s tests"""
import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"), INBOX_ALLOW_ALL="1")
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import config, jobs, pages, server  # noqa: E402
from homelab_inbox.config import JOBS  # noqa: E402
from homelab_inbox.storage import db  # noqa: E402

JOB = ('---\nname: a-job\nwhen: daily at 7:00\nschedule: "0 7 * * *"\nkind: box-report\nmodel: local\ndeliver: ntfy\n'
       'skills: []\n---\nReport what failed.\n\nNOTHING TO REPORT when all is well.\n')
INDEX = b"<!doctype html><title>ported</title>"


class JobPages(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inbox = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=cls.inbox.serve_forever, daemon=True).start()
        cls.port = cls.inbox.server_address[1]
        cls.old = dict(JOBS), server.FRONTEND_DIST, server.PORTED_PAGES
        cls.tmp = tempfile.TemporaryDirectory()
        (Path(cls.tmp.name) / "index.html").write_bytes(INDEX)
        server.FRONTEND_DIST = Path(cls.tmp.name)
        server.PORTED_PAGES = config.PORTED_PAGES
        JOBS.clear()
        JOBS["a-job"] = jobs.parse_job("a-job", JOB, repos={}, skills={})
        JOBS["a-job"]["text"] = JOB
        JOBS["b-broken"] = {"error": "kapot", "text": "no header"}
        with db() as c:
            for t in ("drafts", "job_runs"):
                c.execute(f"DELETE FROM {t}")
            c.execute("INSERT INTO drafts (created, request, base, status, text) VALUES ('2026-11-16T07:00:00+00:00', "
                      "'Maak een job', NULL, 'ready', ?)", (JOB,))
            c.execute("INSERT INTO drafts (created, request, status) VALUES ('2026-11-16T07:01:00+00:00', 'wacht', 'drafting')")
            c.execute("INSERT INTO drafts (created, request, status, error) VALUES ('2026-11-16T07:02:00+00:00', 'stuk', "
                      "'failed', 'claude faalde')")
            c.execute("INSERT INTO drafts (created, request, status, text) VALUES ('2026-11-16T07:03:00+00:00', 'kapot', "
                      "'ready', 'geen kop')")
            c.execute("INSERT INTO job_runs (job, kind, model, due, created, status, summary, note, manual) VALUES "
                      "('a-job', 'box-report', 'local', '2026-11-16T06:00:00+00:00', '2026-11-16T06:00:00+00:00', "
                      "'running', 'Alles <goed>', 'een notitie', 1)")
            c.execute("INSERT INTO job_runs (job, kind, model, due, created, status, mode) VALUES "
                      "('research', 'research', 'claude', '2026-11-16T05:00:00+00:00', '2026-11-16T05:00:00+00:00', "
                      "'queued', 'quick')")

    @classmethod
    def tearDownClass(cls):
        with db() as c:
            c.execute("DELETE FROM drafts")
            c.execute("DELETE FROM job_runs")
        JOBS.clear()
        JOBS.update(cls.old[0])
        server.FRONTEND_DIST, server.PORTED_PAGES = cls.old[1:]
        cls.tmp.cleanup()
        cls.inbox.shutdown()
        cls.inbox.server_close()

    def call(self, method, path, fields=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        headers, body = {}, None
        if method == "POST":
            body = urllib.parse.urlencode({"csrf": pages.csrf_token(), **(fields or {})})
            headers = {"Content-Type": "application/x-www-form-urlencoded", "Origin": f"http://127.0.0.1:{self.port}"}
        c.request(method, path, body, headers)
        r = c.getresponse()
        raw = r.read()
        c.close()
        return r.status, r.getheader("Content-Type"), raw, r.getheader("Location")

    def api(self, path):
        code, ctype, raw, _ = self.call("GET", path)
        self.assertEqual(ctype, "application/json; charset=utf-8", path)
        return code, json.loads(raw)

    def run_ids(self):
        with db() as c:
            return {r["job"]: r["id"] for r in c.execute("SELECT id, job FROM job_runs")}

    def test_job_detail_has_facts_file_and_history(self):
        code, d = self.api("/api/job/a-job")
        self.assertEqual(code, 200)
        self.assertEqual((d["name"], d["error"], d["paused"], d["text"], d["refresh"]), ("a-job", None, False, JOB, True))
        f = d["facts"]
        self.assertEqual((f["when"], f["schedule"], f["timezone"], f["model"], f["deliver"], f["mode"], f["repo"], f["script"]),
                         ("daily at 7:00", "0 7 * * *", "Europe/Amsterdam", "local", "ntfy", None, None, None))
        self.assertIn("NOTHING TO REPORT", f["nothing"])
        self.assertTrue(f["next_times"])
        self.assertEqual([r["job"] for r in d["runs"]], ["a-job"])
        self.assertEqual(d["runs"][0]["summary"], "Alles <goed>")

    def test_a_broken_job_has_an_error_and_no_facts_but_its_file(self):
        code, d = self.api("/api/job/b-broken")
        self.assertEqual((code, d["error"], d["facts"], d["text"], d["runs"]), (200, "kapot", None, "no header", []))

    def test_unknown_job_is_a_json_404(self):
        self.assertEqual(self.api("/api/job/nope"), (404, {"error": "Not found."}))

    def test_draft_detail_by_status(self):
        with db() as c:
            ready, drafting, failed, bad = [r["id"] for r in c.execute("SELECT id FROM drafts ORDER BY id LIMIT 4")]
        d = self.api(f"/api/draft/{ready}")[1]
        self.assertEqual((d["status"], d["base"], d["text"], d["text_error"], d["refresh"]), ("ready", None, JOB, None, False))
        self.assertEqual(d["facts"]["kind"], jobs.JOB_KINDS["box-report"])
        d = self.api(f"/api/draft/{drafting}")[1]
        self.assertEqual((d["status"], d["facts"], d["refresh"]), ("drafting", None, True))
        d = self.api(f"/api/draft/{failed}")[1]
        self.assertEqual((d["status"], d["error"], d["facts"]), ("failed", "claude faalde", None))
        d = self.api(f"/api/draft/{bad}")[1]
        self.assertEqual(d["facts"], None)
        self.assertTrue(d["text_error"])
        self.assertEqual(self.api("/api/draft/99999")[0], 404)

    def test_run_detail_for_local_and_claude_runs(self):
        ids = self.run_ids()
        d = self.api(f"/api/run/{ids['a-job']}")[1]
        self.assertEqual((d["job"], d["job_url"], d["status"], d["text"], d["note"], d["cancellable"], d["log_key"], d["model"]),
                         ("a-job", "/job/a-job", "running", "Alles <goed>", "een notitie", True, f"r{ids['a-job']}", "lokaal"))
        d = self.api(f"/api/run/{ids['research']}")[1]
        self.assertEqual((d["job_url"], d["status"], d["cancellable"], d["log_key"], d["refresh"], d["note"]),
                         ("/research", "queued", False, None, True, None))
        self.assertEqual(self.api("/api/run/99999"), (404, {"error": "Not found."}))

    def test_research_data(self):
        code, d = self.api("/api/research")
        self.assertEqual((code, d["modes"], d["claude_cap"]), (200, ["quick", "deep"], 10))
        self.assertEqual([r["job"] for r in d["runs"]], ["research"])
        self.assertTrue(d["refresh"])

    def test_pages_get_the_frontend_index(self):
        ids = self.run_ids()
        with db() as c:
            draft = c.execute("SELECT id FROM drafts ORDER BY id").fetchone()["id"]
        for path in ("/job/a-job", f"/draft/{draft}", f"/run/{ids['a-job']}", "/research", "/job/research"):
            self.assertEqual(self.call("GET", path)[::2][:2], (200, INDEX), path)
        for path in ("/job/nope", "/draft/99999", "/run/99999", "/job"):
            self.assertEqual(self.call("GET", path)[0], 404, path)

    def test_a_job_named_research_wins_over_the_research_page(self):
        JOBS["research"] = JOBS["a-job"]
        self.addCleanup(JOBS.pop, "research")
        self.assertEqual(self.call("GET", "/research")[0], 404)
        self.assertEqual(self.call("GET", "/job/research")[2], INDEX)

    def test_without_a_build_the_pages_say_so(self):
        (Path(self.tmp.name) / "index.html").rename(Path(self.tmp.name) / "index.off")
        self.addCleanup((Path(self.tmp.name) / "index.off").rename, Path(self.tmp.name) / "index.html")
        ids = self.run_ids()
        for path in ("/job/a-job", f"/run/{ids['a-job']}", "/research"):
            code, ctype, raw, _ = self.call("GET", path)
            self.assertEqual(code, 503, path)
            self.assertIn(b"frontend", raw)
        self.assertEqual(self.call("GET", "/job/nope")[0], 404)

    def test_failed_posts_answer_json_errors(self):
        code, ctype, raw, _ = self.call("POST", "/research/ask", {"question": "  ", "mode": "quick"})
        self.assertEqual((code, ctype), (400, "application/json; charset=utf-8"))
        self.assertIn("give a question", json.loads(raw)["error"])
        code, ctype, raw, _ = self.call("POST", "/job/a-job/save", {"text": "geen kop <b>"})
        self.assertEqual((code, ctype), (400, "application/json; charset=utf-8"))
        self.assertTrue(json.loads(raw)["error"])
        with db() as c:
            ready = c.execute("SELECT id FROM drafts WHERE status='ready' ORDER BY id").fetchone()["id"]
        code, ctype, raw, _ = self.call("POST", f"/draft/{ready}/confirm", {"text": JOB})
        self.assertEqual((code, ctype), (400, "application/json; charset=utf-8"))  # a job of that name already exists
        self.assertIn("a-job", json.loads(raw)["error"])

    def test_cancelling_a_draft_still_redirects(self):
        with db() as c:
            ready = c.execute("INSERT INTO drafts (created, request, status, text) VALUES ('2026-11-16T08:00:00+00:00', "
                              "'x', 'ready', ?)", (JOB,)).lastrowid
        self.assertEqual(self.call("POST", f"/draft/{ready}/cancel")[::3], (303, "/jobs"))
        with db() as c:
            self.assertEqual(c.execute("SELECT status FROM drafts WHERE id=?", (ready,)).fetchone()["status"], "cancelled")


if __name__ == "__main__":
    unittest.main()
