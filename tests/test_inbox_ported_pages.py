"""PORTED_PAGES (H-021): a listed path gets frontend/dist/index.html, every other path stays Python-rendered, and a
missing build falls back to the Python page with a warning. Uses a fake dist directory. Run:
python3 -m unittest discover -s tests"""
import contextlib
import io
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"), INBOX_ALLOW_ALL="1")
os.environ.pop("PORTED_PAGES", None)
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import config, server, storage  # noqa: E402

INDEX = b"<!doctype html><title>ported</title><script src=\"/app/assets/main.js\"></script>"


class PortedPages(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dist = Path(self.tmp.name) / "dist"
        (self.dist / "assets").mkdir(parents=True)
        (self.dist / "index.html").write_bytes(INDEX)
        (self.dist / "assets" / "main.js").write_text("console.log(1)")
        (Path(self.tmp.name) / "secret.txt").write_text("nope")
        self.saved = server.PORTED_PAGES, server.FRONTEND_DIST
        self.addCleanup(lambda: (setattr(server, "PORTED_PAGES", self.saved[0]),
                                 setattr(server, "FRONTEND_DIST", self.saved[1])))
        server.FRONTEND_DIST = self.dist
        self.srv = server.ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.addCleanup(self.srv.server_close)
        self.addCleanup(self.srv.shutdown)

    def get(self, path):
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{self.srv.server_port}{path}") as r:
                return r.status, r.read(), r.headers
        except urllib.error.HTTPError as e:
            return e.code, e.read(), e.headers

    def test_default_ports_the_overview_jobs_and_the_tasks_pages(self):
        self.assertEqual(config.PORTED_PAGES, frozenset({"/", "/jobs", "/taken", "/task"}))
        server.PORTED_PAGES = config.PORTED_PAGES
        status, body, _ = self.get("/briefs")
        self.assertEqual(status, 200)
        self.assertNotEqual(body, INDEX)
        for path in ("/", "/jobs", "/taken"):  # TypeScript pages
            self.assertEqual(self.get(path)[:2], (200, INDEX), path)

    def test_task_page_is_the_ported_path_for_a_known_task(self):
        server.PORTED_PAGES = config.PORTED_PAGES
        tid = storage.new_task("homelab", "x")
        self.assertEqual(self.get(f"/task/{tid}")[:2], (200, INDEX))
        self.assertEqual(self.get("/task/99999999")[0], 404)  # unknown task
        self.assertEqual(self.get(f"/task/{tid}?x=1")[0], 404)  # a query string is a 404, as before

    def test_tasks_pages_without_a_build_say_so(self):
        server.PORTED_PAGES = config.PORTED_PAGES
        tid = storage.new_task("homelab", "x")
        (self.dist / "index.html").unlink()
        with contextlib.redirect_stdout(io.StringIO()):
            for path in ("/taken", f"/task/{tid}"):
                status, body, _ = self.get(path)
                self.assertEqual(status, 503, path)
                self.assertIn(b"frontend", body)

    def test_overview_without_a_build_says_so_instead_of_a_python_page(self):
        server.PORTED_PAGES = config.PORTED_PAGES
        (self.dist / "index.html").unlink()
        with contextlib.redirect_stdout(io.StringIO()):
            status, body, _ = self.get("/")
            self.assertEqual(self.get("/jobs")[0], 503)
        self.assertEqual(status, 503)
        self.assertIn(b"frontend", body)

    def test_setting_is_parsed_from_the_environment(self):
        import importlib
        os.environ["PORTED_PAGES"] = " /taken, /jobs ,,nonsense"
        try:
            self.assertEqual(importlib.reload(config).PORTED_PAGES, frozenset({"/taken", "/jobs"}))
        finally:
            del os.environ["PORTED_PAGES"]
            importlib.reload(config)

    def test_ported_path_serves_index_and_assets(self):
        server.PORTED_PAGES = frozenset({"/taken"})
        status, body, _ = self.get("/taken")
        self.assertEqual((status, body), (200, INDEX))
        status, body, headers = self.get("/app/assets/main.js")
        self.assertEqual((status, body), (200, b"console.log(1)"))
        self.assertIn("javascript", headers["Content-Type"])

    def test_unported_path_is_rendered_by_python(self):
        server.PORTED_PAGES = frozenset({"/taken"})
        status, body, _ = self.get("/ssh")
        self.assertEqual(status, 200)
        self.assertNotEqual(body, INDEX)

    def test_missing_dist_falls_back_and_warns(self):
        server.PORTED_PAGES = frozenset({"/briefs"})
        (self.dist / "index.html").unlink()
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status, body, _ = self.get("/briefs")
        self.assertEqual(status, 200)
        self.assertNotIn(b"ported</title>", body)
        self.assertIn("PORTED_PAGES", out.getvalue())

    def test_assets_cannot_leave_dist(self):
        for path in ("/app/../secret.txt", "/app/%2e%2e/secret.txt", "/app/missing.js"):
            self.assertNotEqual(self.get(path)[0], 200, path)


if __name__ == "__main__":
    unittest.main()
