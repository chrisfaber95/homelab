"""GET /api/skills (H-022 F4a): the data the Skills page renders, as JSON. The page itself is TypeScript (frontend/dist).
Run: python3 -m unittest discover -s tests"""
import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"), INBOX_ALLOW_ALL="1")
os.environ.setdefault("BACKEND_ROUTES", "")  # config/inbox.conf forwards the M1 routes to Java (H-024); these tests run the Python handlers
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import overview, server  # noqa: E402
from homelab_inbox.storage import db  # noqa: E402


class SkillsApi(unittest.TestCase):
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
        out = r.status, r.getheader("Content-Type"), r.read().decode()
        c.close()
        return out

    def set_proposals(self, *rows):
        with db() as c:
            c.execute("DELETE FROM skill_prs")
            c.executemany("INSERT INTO skill_prs (name, task_id, pr_url, created) VALUES (?,?,?,?)", rows)

    def test_returns_what_the_page_renders(self):
        jobs = {"a-job": {"name": "a-job", "skills": ["s-one"]}}
        with mock.patch.object(overview, "JOBS", jobs), \
                mock.patch.object(overview, "skill_list", return_value={"s-one": "First", "s-two": "Second"}):
            self.set_proposals(("s-three", 7, "https://example.test/pr/1", "2026-11-16T10:00:00+00:00"))
            self.addCleanup(self.set_proposals)
            code, ctype, raw = self.get("/api/skills")
        self.assertEqual((code, ctype), (200, "application/json; charset=utf-8"), raw)
        body = json.loads(raw)
        self.assertEqual(body["skills"], [{"name": "s-one", "description": "First", "jobs": ["a-job"]},
                                          {"name": "s-two", "description": "Second", "jobs": []}])
        self.assertEqual(len(body["proposals"]), 1)
        prop = body["proposals"][0]
        self.assertEqual((prop["name"], prop["task_id"], prop["pr_url"]), ("s-three", 7, "https://example.test/pr/1"))
        self.assertRegex(prop["created"], r"^\d\d-\d\d \d\d:\d\d$")
        self.assertIn("Gemergde skills", body["intro"])

    def test_empty_texts(self):
        with mock.patch.object(overview, "skill_list", return_value={}):
            self.set_proposals()
            body = json.loads(self.get("/api/skills")[2])
        self.assertEqual((body["skills"], body["proposals"]), ([], []))
        self.assertEqual((body["empty"], body["proposals_empty"]), ("Nog geen skills.", "Nog geen voorstellen."))


if __name__ == "__main__":
    unittest.main()
