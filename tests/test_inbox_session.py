"""GET /api/session and the JSON conventions of /api/* (H-021 F2): every mutating /api/* request needs the Origin of
the page and the token of /api/session as X-CSRF-Token, and every /api/* error is {"error": "..."}. The page's forms keep
their csrf field. Run: python3 -m unittest discover -s tests"""
import http.client
import json
import os
import re
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
from homelab_inbox import forge, pages, search, server  # noqa: E402

JSON_UTF8 = "application/json; charset=utf-8"
MUTATING = (("POST", "/api/forge/start"), ("POST", "/api/search/ask"), ("POST", "/api/search/feedback"),
            ("POST", "/api/nothing"), ("PUT", "/api/forge/start"), ("PATCH", "/api/live"), ("DELETE", "/api/live"))


class Session(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inbox = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=cls.inbox.serve_forever, daemon=True).start()
        cls.port = cls.inbox.server_address[1]
        cls._old = forge.FORGE_URL, search.SEARCH_URL, search.ROUTER_URL
        dead = "http://127.0.0.1:9"  # nothing listens: a call that gets through the checks ends in a 502
        forge.FORGE_URL = search.SEARCH_URL = search.ROUTER_URL = dead
        forge.forget()

    @classmethod
    def tearDownClass(cls):
        forge.FORGE_URL, search.SEARCH_URL, search.ROUTER_URL = cls._old
        forge.forget()
        cls.inbox.shutdown()
        cls.inbox.server_close()

    def call(self, method, path, headers=None, body=None, origin=True):
        headers = dict(headers or {})
        if origin:
            headers.setdefault("Origin", f"http://127.0.0.1:{self.port}")
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=15)
        c.request(method, path, body, headers)
        r = c.getresponse()
        raw = r.read().decode()
        c.close()
        return r.status, r.getheader("Content-Type"), raw

    def error(self, reply, status):
        code, ctype, raw = reply
        self.assertEqual((code, ctype), (status, JSON_UTF8), raw)
        body = json.loads(raw)
        self.assertEqual(set(body), {"error"}, raw)
        self.assertIsInstance(body["error"], str)
        return body["error"]

    def token(self):
        code, ctype, raw = self.call("GET", "/api/session")
        self.assertEqual((code, ctype), (200, JSON_UTF8))
        return json.loads(raw)["csrf_token"]

    # -- GET /api/session --
    def test_session_returns_the_token_of_the_pages(self):
        self.assertEqual(json.loads(self.call("GET", "/api/session")[2]), {"csrf_token": pages.csrf_token()})
        self.assertRegex(self.token(), r"^[0-9a-f]{64}$")

    def test_session_is_not_cached_and_is_for_get_only(self):
        c = http.client.HTTPConnection("127.0.0.1", self.port)
        c.request("GET", "/api/session")
        self.assertEqual(c.getresponse().getheader("Cache-Control"), "no-store")
        self.error(self.call("POST", "/api/session", {"X-CSRF-Token": self.token()}), 404)

    def test_the_box_itself_does_not_get_the_token(self):
        os.environ.pop("INBOX_ALLOW_ALL")
        try:
            body = self.error(self.call("GET", "/api/session"), 403)
        finally:
            os.environ["INBOX_ALLOW_ALL"] = "1"
        self.assertNotIn(pages.csrf_token(), body)

    # -- the token check --
    def test_a_missing_token_is_403_on_every_mutating_method(self):
        for method, path in MUTATING:
            self.assertIn("X-CSRF-Token", self.error(self.call(method, path), 403), (method, path))

    def test_a_wrong_token_is_403(self):
        for bad in ("wrong", "", self.token()[:-1], self.token() + "0"):
            for method, path in MUTATING:
                self.error(self.call(method, path, {"X-CSRF-Token": bad}), 403)

    def test_the_old_form_field_does_not_count_for_api(self):
        body = urllib.parse.urlencode({"csrf": self.token(), "question": "x"})
        reply = self.call("POST", "/api/search/ask", {"Content-Type": "application/x-www-form-urlencoded"}, body)
        self.assertIn("X-CSRF-Token", self.error(reply, 403))

    def test_a_wrong_origin_is_403_even_with_the_token(self):
        h = {"X-CSRF-Token": self.token()}
        self.assertEqual(self.error(self.call("POST", "/api/forge/start", {**h, "Origin": "http://evil.example"}), 403),
                         "Wrong origin.")
        self.assertEqual(self.error(self.call("POST", "/api/forge/start", h, origin=False), 403), "Wrong origin.")

    def test_the_right_token_gets_through_the_checks(self):
        h = {"X-CSRF-Token": self.token()}
        self.assertIn("Forge", self.error(self.call("POST", "/api/forge/start", h), 502))
        self.assertEqual(self.error(self.call("POST", "/api/nothing", h), 404), "Not found.")
        for method in ("PUT", "PATCH", "DELETE"):
            self.assertEqual(self.error(self.call(method, "/api/live", h), 405), f"{method} is not supported")

    def test_a_get_needs_no_token(self):
        self.assertEqual(self.call("GET", "/api/live")[0], 200)

    # -- the error shape --
    def test_errors_are_a_flat_error_string(self):
        h = {"X-CSRF-Token": self.token()}
        self.assertEqual(self.error(self.call("GET", "/api/nothing"), 404), "Not found.")
        self.assertEqual(self.error(self.call("GET", "/api/log/t1?from=x"), 400), "from must be a number")
        self.assertEqual(self.error(self.call("GET", "/api/log/f1?from=x"), 400), "from must be a number")
        self.assertEqual(self.error(self.call("POST", "/api/search/ask", h, "question="), 400), "Type a question first.")
        self.assertEqual(self.error(self.call("POST", "/api/search/feedback", h, "request_id=x"), 400),
                         "That answer has no request id to mark.")
        self.assertIn("unreachable", self.error(self.call("GET", "/api/search/filters"), 502))
        self.assertEqual(self.error(self.call("POST", "/api/forge/start", {**h, "Content-Length": "x"}, ""), 400),
                         "Bad Content-Length.")

    def test_an_unexpected_failure_is_a_json_500(self):
        old = server.live_items
        server.live_items = lambda: 1 / 0
        try:
            self.assertIn("inbox journal", self.error(self.call("GET", "/api/live"), 500))
        finally:
            server.live_items = old

    def test_upstream_errors_are_unwrapped_but_keep_their_other_keys(self):
        self.assertEqual(server.flat_error(503, {"error": {"message": "locked", "request_id": "abc"}}),
                         {"request_id": "abc", "error": "locked"})
        self.assertEqual(server.flat_error(502, {"error": {}}), {"error": "HTTP 502"})
        ok = {"error": {"message": "not an error"}}
        self.assertIs(server.flat_error(200, ok), ok)
        self.assertEqual(server.flat_error(400, {"error": "x"}), {"error": "x"})

    # -- the pages keep working --
    def test_a_page_form_still_uses_its_csrf_field(self):
        form = urllib.parse.urlencode({"csrf": pages.csrf_token(), "repo": "nope", "text": ""})
        code, ctype, raw = self.call("POST", "/tasks", {"Content-Type": "application/x-www-form-urlencoded"}, form)
        self.assertEqual(code, 400)
        self.assertIn("text/html", ctype)
        code, ctype, raw = self.call("POST", "/tasks", {"Content-Type": "application/x-www-form-urlencoded"}, "repo=x")
        self.assertEqual((code, "text/html" in ctype, "Verlopen formulier" in raw), (403, True, True))

    def test_pages_stay_html(self):
        code, ctype, _ = self.call("GET", "/nope")
        self.assertEqual((code, ctype), (404, "text/html; charset=utf-8"))

    def test_the_page_script_sends_the_header_and_reads_the_flat_error(self):
        js = (ROOT / "web/app.js").read_text()
        self.assertNotIn("error?.message", js)
        ts = (ROOT / "frontend/src/api/client.ts").read_text()  # the Overview's forge buttons, the Search page
        self.assertIn('fetch(path', ts)
        self.assertIn('"X-CSRF-Token": await csrfToken()', ts)
        self.assertIn('postForm("/api/search/ask"', (ROOT / "frontend/src/pages/search.ts").read_text())


if __name__ == "__main__":
    unittest.main()
