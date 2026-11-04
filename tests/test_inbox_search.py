"""Search tab of the homelab page (H-012): the inbox proxies the search API and the router, and the exposure checker
covers the tailnet-only rules. Run: python3 -m unittest discover -s tests"""
import http.client
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"), INBOX_ALLOW_ALL="1")
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import pages, search, server  # noqa: E402

ASK = {"question": "q", "answer": "Because [1].", "found": True, "request_id": "abc123def456",
       "citations": [{"n": 1, "path": "a.md", "location": "H", "link": "https://x/a", "cited": True}]}


def serve(handler):
    srv = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


class Upstream(BaseHTTPRequestHandler):
    calls = []

    def log_message(self, *a):
        pass

    def reply(self, status, obj):
        data = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        Upstream.calls.append(("GET", self.path, None))
        path = self.path.partition("?")[0]
        self.reply(200, {"projects": ["homelab"], "types": ["wiki"], "chunks": 3} if path == "/v1/filters"
                   else {"results": [{"path": "a.md", "location": "H", "date": "2026-11-01", "project": "homelab"}]})

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        Upstream.calls.append(("POST", self.path, body))
        if self.path == "/v1/feedback":
            self.reply(200, {"request_id": body["request_id"], "caller": "search", "tier": "easy", "backend": "local"})
        else:
            self.reply(200, ASK)


class SearchTab(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.up = serve(Upstream)
        url = f"http://127.0.0.1:{cls.up.server_address[1]}"
        cls._old = search.SEARCH_URL, search.ROUTER_URL
        search.SEARCH_URL = search.ROUTER_URL = url
        cls.inbox = serve(server.Handler)
        cls.port = cls.inbox.server_address[1]

    @classmethod
    def tearDownClass(cls):
        search.SEARCH_URL, search.ROUTER_URL = cls._old
        cls.inbox.shutdown()
        cls.up.shutdown()

    def setUp(self):
        Upstream.calls.clear()

    def req(self, method, path, form=None, csrf=True, origin=True):
        c = http.client.HTTPConnection("127.0.0.1", self.port)
        headers = {}
        body = None
        if form is not None:
            body = urllib.parse.urlencode({**({"csrf": pages.csrf_token()} if csrf else {}), **form})
            headers = {"Content-Type": "application/x-www-form-urlencoded"}
            if origin:
                headers["Origin"] = f"http://127.0.0.1:{self.port}"
        c.request(method, path, body, headers)
        r = c.getresponse()
        return r.status, r.read().decode()

    def test_page_is_a_tab_with_the_controls(self):
        status, html = self.req("GET", "/search")
        self.assertEqual(status, 200)
        for needle in ('href="/search" aria-current=page', 'id="sq"', 'name="hard"', 'id="sproject"', 'id="stype"',
                       'width=device-width', 'id="sanswer"', 'id="sresults"'):
            self.assertIn(needle, html)
        self.assertIn(pages.csrf_token(), html)
        self.assertIn('<a href="/search">Search</a>', self.req("GET", "/")[1])

    def test_filters_and_results_are_proxied_with_filters(self):
        self.assertEqual(json.loads(self.req("GET", "/api/search/filters")[1])["projects"], ["homelab"])
        status, body = self.req("GET", "/api/search/results?q=sqlite&project=homelab&type=wiki&evil=1")
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["results"][0]["location"], "H")
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(Upstream.calls[-1][1]).query)
        self.assertEqual(query, {"q": ["sqlite"], "project": ["homelab"], "type": ["wiki"]})

    def test_ask_sends_hard_and_filters_and_returns_the_request_id(self):
        status, body = self.req("POST", "/api/search/ask", {"question": " why? ", "hard": "1", "project": "homelab"})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["request_id"], "abc123def456")
        self.assertEqual(Upstream.calls[-1], ("POST", "/v1/ask", {"question": "why?", "project": "homelab", "hard": True}))
        self.req("POST", "/api/search/ask", {"question": "why?"})
        self.assertNotIn("hard", Upstream.calls[-1][2])

    def test_empty_question_never_reaches_the_service(self):
        status, body = self.req("POST", "/api/search/ask", {"question": "  "})
        self.assertEqual(status, 400)
        self.assertEqual(Upstream.calls, [])

    def test_thumbs_down_goes_to_the_router_feedback_endpoint(self):
        status, body = self.req("POST", "/api/search/feedback", {"request_id": "abc123def456", "note": "wrong"})
        self.assertEqual(status, 200)
        self.assertEqual(json.loads(body)["backend"], "local")
        self.assertEqual(Upstream.calls[-1], ("POST", "/v1/feedback", {"request_id": "abc123def456", "note": "wrong"}))

    def test_feedback_rejects_a_missing_or_odd_request_id(self):
        for rid in ("", "../x", "a b", "x" * 80):
            self.assertEqual(self.req("POST", "/api/search/feedback", {"request_id": rid})[0], 400)
        self.assertEqual(Upstream.calls, [])

    def test_posts_need_the_csrf_token_and_the_origin(self):
        self.assertEqual(self.req("POST", "/api/search/ask", {"question": "q"}, csrf=False)[0], 403)
        self.assertEqual(self.req("POST", "/api/search/feedback", {"request_id": "abc123def456"}, origin=False)[0], 403)
        self.assertEqual(Upstream.calls, [])

    def test_a_dead_search_service_is_a_json_502(self):
        old = search.SEARCH_URL
        search.SEARCH_URL = "http://127.0.0.1:9"
        try:
            status, body = self.req("GET", "/api/search/results?q=x")
        finally:
            search.SEARCH_URL = old
        self.assertEqual(status, 502)
        self.assertIn("unreachable", json.loads(body)["error"]["message"])

    def test_upstream_errors_keep_their_status(self):
        s = serve(type("E", (Upstream,), {"do_GET": lambda self: self.reply(503, {"error": {"message": "stale"}})}))
        old = search.SEARCH_URL
        search.SEARCH_URL = f"http://127.0.0.1:{s.server_address[1]}"
        try:
            status, body = self.req("GET", "/api/search/results?q=x")
        finally:
            search.SEARCH_URL = old
            s.shutdown()
        self.assertEqual((status, json.loads(body)["error"]["message"]), (503, "stale"))


class ExposureChecker(unittest.TestCase):
    """bin/homelab-exposure --static must pass on the repo and fail when a service would leave the tailnet."""

    @classmethod
    def setUpClass(cls):
        if not (shutil.which("docker") and shutil.which("jq")):
            raise unittest.SkipTest("docker and jq are needed")

    def run_in(self, edit=None):
        with tempfile.TemporaryDirectory() as tmp:
            repo = Path(tmp) / "repo"
            shutil.copytree(ROOT, repo, ignore=shutil.ignore_patterns(".git", "__pycache__", "node_modules"))
            if edit:
                edit(repo)
            p = subprocess.run([str(repo / "bin/homelab-exposure"), "--static"], capture_output=True, text=True)
            return p.returncode, p.stdout

    def test_repo_passes(self):
        code, out = self.run_in()
        self.assertEqual(code, 0, out)
        for name in ("exposure:router:ufw", "exposure:search:ufw", "exposure:search:filter", "exposure:inbox:guard"):
            self.assertIn(name, out)

    def test_open_ufw_rule_fails(self):
        def edit(repo):
            f = repo / "scripts/24-search.sh"
            f.write_text(f.read_text().replace("ufw allow in on tailscale0 to any port", "ufw allow to any port", 1))
        code, out = self.run_in(edit)
        self.assertEqual(code, 1, out)
        self.assertIn("FAIL  exposure:ufw-rules", out)
        self.assertIn("FAIL  exposure:search:ufw", out)

    def test_remote_search_url_fails(self):
        def edit(repo):
            f = repo / "config/inbox.conf"
            f.write_text(f.read_text().replace('SEARCH_URL="http://127.0.0.1:8019"', 'SEARCH_URL="http://192.168.1.5:8019"'))
        code, out = self.run_in(edit)
        self.assertEqual(code, 1, out)
        self.assertIn("FAIL  exposure:inbox:SEARCH_URL", out)

    def test_missing_client_filter_fails(self):
        def edit(repo):
            f = repo / "search/server.py"
            f.write_text(f.read_text().replace("100.64.0.0/10", "0.0.0.0/0"))
        code, out = self.run_in(edit)
        self.assertEqual(code, 1, out)
        self.assertIn("FAIL  exposure:search:filter", out)


if __name__ == "__main__":
    unittest.main()
