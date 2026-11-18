"""The Python side of the job-file writes (H-024 M6): jobs.py no longer writes the clone, it asks the backend
(`jobs.transport`, here the in-process stand-in of tests/jobs_fake.py). The Java side is JobsWriterTest and
tests/test_inbox_m6_parity.py. Run: python3 -m unittest tests.test_inbox_jobs_writes"""
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
os.environ.setdefault("BACKEND_ROUTES", "")  # the facade reads it once; keep the Python handlers for the other test modules
sys.path.insert(0, str(ROOT / "bin"))
sys.path.insert(0, str(ROOT / "tests"))
import jobs_fake  # noqa: E402
from homelab_inbox import jobs, server  # noqa: E402
from homelab_inbox.config import JOBS  # noqa: E402

GOOD = '---\nwhen: daily\nschedule: "0 7 * * *"\nkind: box-report\nmodel: local\n---\nReport what failed.\n'


class JobWrites(unittest.TestCase):
    def setUp(self):
        self._transport, self._dir = jobs.transport, jobs.JOBS_DIR
        self.home = Path(tempfile.mkdtemp())
        jobs.JOBS_DIR = self.home
        self.fake = jobs_fake.install(jobs)
        JOBS.clear()

    def tearDown(self):
        jobs.transport, jobs.JOBS_DIR = self._transport, self._dir
        JOBS.clear()

    def test_python_has_no_write_path_of_its_own_left(self):
        for name in ("jobs_sync", "set_paused"):
            self.assertFalse(hasattr(jobs, name), name)
        self.assertFalse(hasattr(jobs, "git") or hasattr(jobs, "sync_branch"))

    def test_save_and_delete_ask_the_backend_and_refresh_the_cache(self):
        jobs.save_job("nieuw", GOOD, "jobs: nieuw nieuw (w)")
        self.assertEqual(self.fake.calls[-1], ("/internal/jobs/save", {"name": "nieuw", "text": GOOD, "message": "jobs: nieuw nieuw (w)"}))
        self.assertIn("nieuw", JOBS)  # read back from the clone
        jobs.save_job("nieuw", None, "jobs: nieuw verwijderd (w)")
        self.assertEqual(self.fake.calls[-1][0], "/internal/jobs/delete")
        self.assertNotIn("nieuw", JOBS)

    def test_pause_and_resume_send_only_the_name_and_the_message(self):
        jobs.save_job("nieuw", GOOD, "m")
        jobs.pause_job("nieuw", True, "jobs: nieuw gepauzeerd (w)")
        self.assertEqual(self.fake.calls[-1], ("/internal/jobs/pause", {"name": "nieuw", "message": "jobs: nieuw gepauzeerd (w)"}))
        self.assertTrue(JOBS["nieuw"]["paused"])
        jobs.pause_job("nieuw", False, "m")
        self.assertEqual(self.fake.calls[-1][0], "/internal/jobs/resume")
        self.assertFalse(JOBS["nieuw"]["paused"])

    def test_a_refusal_is_a_value_error_with_the_reason(self):
        with self.assertRaises(ValueError) as e:
            jobs.pause_job("nergens", True, "m")
        self.assertEqual(str(e.exception), "Niet gevonden.")

    def test_a_backend_that_does_not_answer_writes_nothing(self):
        def down(path, body):
            raise jobs.Unavailable("job files: the backend did not answer (ConnectionRefusedError)")
        jobs.transport = down
        with self.assertRaises(jobs.Unavailable):
            jobs.save_job("nieuw", GOOD, "m")
        self.assertEqual(list(self.home.glob("**/*.md")), [])

    def test_load_jobs_asks_the_backend_to_sync_and_still_reads_when_it_cannot(self):
        (self.home / "jobs").mkdir()
        (self.home / "jobs/a.md").write_text(GOOD)
        jobs.load_jobs()
        self.assertEqual(self.fake.calls[-1][0], "/internal/jobs/sync")
        self.assertEqual(list(JOBS), ["a"])

        def down(path, body):
            raise jobs.Unavailable("down")
        jobs.transport = down
        jobs.load_jobs()  # logs, keeps reading the clone as it is
        self.assertEqual(list(JOBS), ["a"])

    def post(self, path, **fields):
        import http.client
        srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        try:
            port = srv.server_address[1]
            c = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
            c.request("GET", "/api/session")
            token = json.loads(c.getresponse().read())["csrf_token"]
            c.request("POST", path, urllib.parse.urlencode({"csrf": token, **fields}),
                      {"Content-Type": "application/x-www-form-urlencoded", "Origin": f"http://127.0.0.1:{port}"})
            r = c.getresponse()
            return r.status, r.getheader("Location"), r.read()
        finally:
            srv.shutdown()
            srv.server_close()

    def test_the_pages_pass_the_forms_on_with_the_same_commit_messages(self):
        jobs.save_job("oud", GOOD, "seed")
        self.fake.calls.clear()
        s, loc, _ = self.post("/job/oud/save", text=GOOD.replace("failed", "broke"))
        self.assertEqual((s, loc), (303, "/job/oud"))
        self.assertRegex(self.fake.calls[-1][1]["message"], r"^jobs: oud bewerkt \(bevestigd op de pagina vanaf ")
        s, loc, _ = self.post("/job/oud/pause")
        self.assertEqual((s, loc, self.fake.calls[-1][0]), (303, "/job/oud", "/internal/jobs/pause"))
        s, loc, _ = self.post("/job/oud/resume")
        self.assertEqual((s, self.fake.calls[-1][0]), (303, "/internal/jobs/resume"))
        s, loc, _ = self.post("/job/oud/delete")
        self.assertEqual((s, loc, self.fake.calls[-1][0]), (303, "/jobs", "/internal/jobs/delete"))
        self.assertNotIn("oud", JOBS)

    def test_a_bad_file_never_reaches_the_backend_and_a_dead_backend_answers_502(self):
        jobs.save_job("oud", GOOD, "seed")
        self.fake.calls.clear()
        s, _, body = self.post("/job/oud/save", text="geen kop")
        self.assertEqual((s, json.loads(body)), (400, {"error": "het bestand begint niet met een kop tussen --- en ---"}))
        self.assertEqual(self.fake.calls, [])

        def down(path, body):
            raise jobs.Unavailable("job files: no backend secret (BACKEND_SECRET or BACKEND_SECRET_FILE)")
        jobs.transport = down
        s, _, body = self.post("/job/oud/delete")
        self.assertEqual((s, list(json.loads(body))), (502, ["error"]))


if __name__ == "__main__":
    unittest.main()
