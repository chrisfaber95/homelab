"""The comparison logic of the parity harness (backend/parity/parity.py, H-023 B7). No server needed except the last
test, which starts two throwaway local servers."""
import io
import json
import sys
import threading
import unittest
from contextlib import redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "backend/parity"))
import parity  # noqa: E402

J = {"content-type": "application/json; charset=utf-8"}


def answer(status, body, headers=J):
    return status, headers, json.dumps(body).encode()


class Normalize(unittest.TestCase):
    def test_ignored_keys_at_any_depth_and_dotted(self):
        v = {"now": 1, "a": {"now": 2, "keep": 3}, "l": [{"now": 4, "x": 1}], "h": {"checked": 1, "ok": 2}}
        self.assertEqual(parity.strip_keys(v, ["now", "h.checked"]), {"a": {"keep": 3}, "l": [{"x": 1}], "h": {"ok": 2}})

    def test_key_order_and_charset_do_not_matter(self):
        a = parity.compare("/x", answer(200, {"a": 1, "b": 2}), answer(200, {"b": 2, "a": 1}, {"content-type": "application/json"}), [])
        self.assertEqual(a[0], "same")

    def test_html_whitespace_collapsed(self):
        h = {"content-type": "text/html"}
        r = parity.compare("/", (200, h, b"<p>a\n  b</p>"), (200, h, b"<p>a b</p>"), [])
        self.assertEqual(r[0], "same")


class Compare(unittest.TestCase):
    def test_status_differs(self):
        outcome, lines = parity.compare("/x", answer(200, {"a": 1}), answer(500, {"a": 1}), [])
        self.assertEqual(outcome, "diff")
        self.assertIn("-status: 200", lines)
        self.assertIn("+status: 500", lines)

    def test_body_differs_shows_the_field(self):
        outcome, lines = parity.compare("/x", answer(200, {"n": 1}), answer(200, {"n": 2}), [])
        self.assertEqual(outcome, "diff")
        self.assertTrue(any(l.startswith('-  "n": 1') for l in lines))
        self.assertTrue(any(l.startswith('+  "n": 2') for l in lines))

    def test_ignored_field_difference_is_same(self):
        r = parity.compare("/x", answer(200, {"t": "a", "k": 1}), answer(200, {"t": "b", "k": 1}), ["t"])
        self.assertEqual(r[0], "same")

    def test_not_implemented_in_java_is_skipped_not_failed(self):
        for status in (404, 501):
            self.assertEqual(parity.compare("/x", answer(200, {}), answer(status, {"error": "x"}), [])[0], "skipped")

    def test_both_404_is_a_comparison(self):
        self.assertEqual(parity.compare("/x", answer(404, {"error": "n"}), answer(404, {"error": "n"}), [])[0], "same")
        self.assertEqual(parity.compare("/x", answer(404, {"error": "n"}), answer(404, {"error": "m"}), [])[0], "diff")

    def test_java_403_is_a_difference(self):
        self.assertEqual(parity.compare("/x", answer(200, {}), answer(403, {"error": "no"}), [])[0], "diff")

    def test_failed_request_is_a_difference(self):
        self.assertEqual(parity.compare("/x", None, answer(200, {}), [])[0], "diff")


class Routes(unittest.TestCase):
    def test_build_routes(self):
        cfg = {"examples": {"/t/{id}": ["/t/1", "/t/2"]}, "exclude": ["/static/{f}"], "skip": ["/s"]}
        paths = ["/a", "/t/{id}", "/u/{id}", "/static/{f}", "/s"]
        self.assertEqual(parity.build_routes(paths, cfg), [
            ("/a", None), ("/t/1", None), ("/t/2", None), ("/u/{id}", "no example in parity.json"), ("/s", "listed in skip")])

    def test_queries_add_routes_after_their_path(self):
        cfg = {"queries": {"/a": ["?q=1", "?q=2"]}, "skip": ["/s"]}
        self.assertEqual(parity.build_routes(["/a", "/b"], cfg), [("/a", None), ("/a?q=1", None), ("/a?q=2", None), ("/b", None)])
        self.assertEqual(parity.spec_key("/a?q=1", cfg), "/a")

    def test_spec_paths_only_get(self):
        spec = {"paths": {"/a": {"get": {}}, "/b": {"post": {}}, "/c": {"get": {}, "post": {}}}}
        self.assertEqual(parity.spec_paths(spec), ["/a", "/c"])

    def test_ignore_key_follows_the_template(self):
        cfg = {"examples": {"/t/{id}": ["/t/1"]}}
        self.assertEqual(parity.spec_key("/t/1", cfg), "/t/{id}")

    def test_real_config_matches_the_spec(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML not installed")
        cfg = json.loads((ROOT / "backend/parity/parity.json").read_text())
        paths = parity.spec_paths(yaml.safe_load((ROOT / "backend/openapi.yaml").read_text()))
        for key in list(cfg["examples"]) + list(cfg["queries"]) + cfg["exclude"] + cfg["skip"] + [k for k in cfg["ignore"] if k != "*"]:
            self.assertIn(key, paths, f"parity.json names {key}, which is not a GET path of openapi.yaml")


class ReadOnly(unittest.TestCase):
    def test_only_get_is_sent_to_both_sides(self):
        seen = []

        def server(name, handler_answer):
            class H(BaseHTTPRequestHandler):
                def do_GET(self):
                    seen.append((name, "GET", self.path))
                    body = json.dumps(handler_answer(self.path)).encode()
                    self.send_response(404 if self.path == "/gone" else 200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)

                def do_POST(self):
                    seen.append((name, "POST", self.path))
                    self.send_response(500)
                    self.end_headers()

                log_message = lambda *a: None  # noqa: E731
            s = ThreadingHTTPServer(("127.0.0.1", 0), H)
            threading.Thread(target=s.serve_forever, daemon=True).start()
            self.addCleanup(s.server_close)
            self.addCleanup(s.shutdown)
            return f"http://127.0.0.1:{s.server_port}"

        py = server("py", lambda p: {"p": p, "n": 1})
        jv = server("java", lambda p: {"p": p, "n": 1 if p != "/b" else 2})
        buf = io.StringIO()
        with redirect_stdout(buf):
            counts = parity.run([("/a", None), ("/b", None), ("/gone", None), ("/s", "listed in skip")], {},
                                lambda p: parity.fetch(py, p, {}), lambda p: parity.fetch(jv, p, {}))
        self.assertEqual(counts, {"same": 2, "diff": 1, "skipped": 1})
        self.assertTrue(all(m == "GET" for _, m, _ in seen))
        self.assertIn("DIFF /b", buf.getvalue())
        self.assertNotIn("/s", [p for _, _, p in seen])


class Main(unittest.TestCase):
    def test_unreachable_side_is_exit_2(self):
        err = io.StringIO()
        from contextlib import redirect_stderr
        with redirect_stderr(err):
            code = parity.main(["--python-url", "http://127.0.0.1:1", "--java-url", "http://127.0.0.1:1"])
        self.assertEqual(code, 2)

    def test_bad_header(self):
        with self.assertRaises(parity.Setup):
            parity.parse_headers(["nocolon"])


if __name__ == "__main__":
    unittest.main()
