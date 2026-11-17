"""The facade to the Java backend (H-023, B6): BACKEND_ROUTES, the secret header, the access check first, 502 on failure,
and, with the list empty, the server as before. A fake upstream stands in for Spring Boot.
Run: python3 -m unittest discover -s tests"""
import http.client
import json
import os
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"), INBOX_ALLOW_ALL="1")
os.environ.setdefault("BACKEND_ROUTES", "")  # config/inbox.conf forwards the M1 routes to Java (H-024); these tests run the Python handlers
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import access, facade, server  # noqa: E402

SECRET = "s3cret-for-tests"


class FakeBackend(BaseHTTPRequestHandler):
    calls = []  # (method, path, headers, body)

    def log_message(self, *a):
        pass

    def handle_any(self):
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        FakeBackend.calls.append((self.command, self.path, dict(self.headers), body))
        if self.headers.get("X-Inbox-Facade") != SECRET:
            data, status = b'{"error":"no secret"}', 403
        else:
            data, status = json.dumps({"echo": body.decode(), "path": self.path}).encode(), 201
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("X-Backend", "yes")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    do_GET = do_POST = do_PUT = do_DELETE = handle_any


class Facade(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.backend = ThreadingHTTPServer(("127.0.0.1", 0), FakeBackend)
        cls.inbox = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        for s in (cls.backend, cls.inbox):
            threading.Thread(target=s.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.backend.server_address[1]}"
        cls.port = cls.inbox.server_address[1]

    @classmethod
    def tearDownClass(cls):
        for s in (cls.backend, cls.inbox):
            s.shutdown()
            s.server_close()

    def setUp(self):
        FakeBackend.calls = []
        self.patches = [mock.patch.object(facade, "ROUTES", ("/api/backend",)),
                        mock.patch.object(facade, "BACKEND_URL", self.url),
                        mock.patch.dict(os.environ, {"BACKEND_SECRET": SECRET})]
        for p in self.patches:
            p.start()
            self.addCleanup(p.stop)

    def call(self, method, path, body=None, headers=None, token=True):
        h = dict(headers or {})
        if method != "GET":
            h.setdefault("Origin", f"http://127.0.0.1:{self.port}")
            if token:
                h["X-CSRF-Token"] = access.csrf_token()
        c = http.client.HTTPConnection("127.0.0.1", self.port)
        c.request(method, path, body=body, headers=h)
        r = c.getresponse()
        out = r.status, dict(r.getheaders()), r.read()
        c.close()
        return out

    def test_a_listed_route_is_forwarded_with_the_secret_and_answered_as_is(self):
        status, headers, body = self.call("GET", "/api/backend/health?x=1&y=2", headers={"Accept": "application/json", "Cookie": "a=b"})
        self.assertEqual(status, 201)
        self.assertEqual(headers["X-Backend"], "yes")
        self.assertEqual(json.loads(body)["path"], "/api/backend/health?x=1&y=2")
        method, path, sent, _ = FakeBackend.calls[0]
        self.assertEqual((method, sent["X-Inbox-Facade"], sent["X-Inbox-Who"], sent["Accept"]), ("GET", SECRET, "test", "application/json"))
        self.assertNotIn("Cookie", sent)

    def test_method_and_body_are_forwarded(self):
        status, _, body = self.call("POST", "/api/backend/things", body=b'{"a":1}', headers={"Content-Type": "application/json"})
        self.assertEqual((status, json.loads(body)["echo"]), (201, '{"a":1}'))
        self.assertEqual(FakeBackend.calls[0][0], "POST")
        self.assertEqual(self.call("DELETE", "/api/backend/things/1")[0], 201)

    def test_a_client_supplied_secret_header_is_replaced(self):
        self.call("GET", "/api/backend/x", headers={"X-Inbox-Facade": "forged", "X-Inbox-Who": "evil"})
        sent = FakeBackend.calls[0][2]
        self.assertEqual((sent["X-Inbox-Facade"], sent["X-Inbox-Who"]), (SECRET, "test"))

    def test_the_access_check_runs_before_forwarding(self):
        with mock.patch.object(access.Access, "allowed", return_value=(False, "x")):
            status, _, body = self.call("GET", "/api/backend/health")
        self.assertEqual(status, 403)
        self.assertIn("error", json.loads(body))
        self.assertEqual(FakeBackend.calls, [])

    def test_a_mutating_request_needs_the_token_first(self):
        self.assertEqual(self.call("POST", "/api/backend/things", body=b"{}", token=False)[0], 403)
        self.assertEqual(FakeBackend.calls, [])

    def test_a_wrong_or_missing_secret_is_refused_by_the_backend_and_passed_back(self):
        with mock.patch.dict(os.environ, {"BACKEND_SECRET": "wrong"}):
            status, _, body = self.call("GET", "/api/backend/x")
        self.assertEqual((status, json.loads(body)), (403, {"error": "no secret"}))

    def test_no_secret_at_all_is_a_502_and_nothing_is_sent(self):
        with mock.patch.dict(os.environ, {"BACKEND_SECRET": ""}), mock.patch.object(facade, "SECRET_FILE", "/nonexistent/secret"):
            status, _, body = self.call("GET", "/api/backend/x")
        self.assertEqual(status, 502)
        self.assertIn("error", json.loads(body))
        self.assertEqual(FakeBackend.calls, [])

    def test_the_secret_can_come_from_a_file(self):
        with tempfile.NamedTemporaryFile("w") as f, mock.patch.dict(os.environ, {"BACKEND_SECRET": ""}), \
                mock.patch.object(facade, "SECRET_FILE", f.name):
            f.write(SECRET + "\n")
            f.flush()
            self.assertEqual(self.call("GET", "/api/backend/x")[0], 201)

    def test_an_unreachable_backend_is_a_502_json_error_without_fallback(self):
        with mock.patch.object(facade, "BACKEND_URL", "http://127.0.0.1:9"):
            status, _, body = self.call("GET", "/api/backend/health")
        self.assertEqual(status, 502)
        self.assertIn("error", json.loads(body))

    def test_a_backend_url_off_loopback_is_never_used(self):
        with mock.patch.object(facade, "BACKEND_URL", "http://192.0.2.1:8130"):
            self.assertEqual(self.call("GET", "/api/backend/health")[0], 502)

    def test_prefix_matching_and_path_tricks(self):
        self.assertEqual(self.call("GET", "/api/backendx/health")[0], 404)  # a longer name is not under the prefix
        self.assertEqual(FakeBackend.calls, [])
        self.assertEqual(self.call("GET", "/api/backend/../session")[0], 400)
        self.assertEqual(self.call("GET", "/api/backend/%2e%2e/session")[0], 400)
        self.assertEqual(FakeBackend.calls, [])

    def test_only_api_prefixes_can_be_listed(self):
        self.assertEqual(facade.matches("/internal/x"), False)
        with mock.patch.dict(os.environ, {"BACKEND_ROUTES": "/internal,/,/api/a/, /api/b"}):
            import importlib
            reloaded = importlib.reload(facade)
            try:
                self.assertEqual(reloaded.ROUTES, ("/api/a", "/api/b"))
            finally:
                os.environ.pop("BACKEND_ROUTES", None)
                importlib.reload(facade)


class EmptyList(unittest.TestCase):
    """With BACKEND_ROUTES empty the server answers exactly as without the facade: nothing is forwarded."""
    @classmethod
    def setUpClass(cls):
        cls.backend = ThreadingHTTPServer(("127.0.0.1", 0), FakeBackend)
        cls.inbox = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        for s in (cls.backend, cls.inbox):
            threading.Thread(target=s.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        for s in (cls.backend, cls.inbox):
            s.shutdown()
            s.server_close()

    def test_the_tests_forward_nothing_by_default(self):
        self.assertEqual(facade.ROUTES, ())  # the tests set BACKEND_ROUTES="" (the shipped config lists the M1 routes)
        self.assertFalse(facade.matches("/api/backend/health"))

    def test_the_shipped_config_forwards_exactly_the_m1_routes(self):
        conf = (ROOT / "config" / "inbox.conf").read_text()
        self.assertIn('\nBACKEND_ROUTES="/api/overview,/api/live,/api/log"\n', conf)

    def test_nothing_is_forwarded_and_answers_are_the_python_ones(self):
        FakeBackend.calls = []
        port = self.inbox.server_address[1]
        base = f"http://127.0.0.1:{self.backend.server_address[1]}"
        with mock.patch.object(facade, "BACKEND_URL", base), mock.patch.dict(os.environ, {"BACKEND_SECRET": SECRET}):
            def get(path):
                c = http.client.HTTPConnection("127.0.0.1", port)
                c.request("GET", path)
                r = c.getresponse()
                return r.status, r.getheader("Content-Type"), r.read()
            health = get("/api/backend/health")
            other = get("/api/does-not-exist")
            session = get("/api/session")
            put = http.client.HTTPConnection("127.0.0.1", port)
            put.request("PUT", "/api/backend/health", headers={"Origin": f"http://127.0.0.1:{port}", "X-CSRF-Token": access.csrf_token()})
            put_status = put.getresponse().status
        self.assertEqual(FakeBackend.calls, [])
        self.assertEqual(health[:2], other[:2])  # the same Python answer as for any unknown /api path
        self.assertEqual(health[0], 404)
        self.assertEqual(session[0], 200)
        self.assertEqual(put_status, 405)  # the Python handler's "not supported", not the backend's 201


if __name__ == "__main__":
    unittest.main()
