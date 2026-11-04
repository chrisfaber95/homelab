"""LLM router (H-012): tier table, fail/escalate, append-only log, claude CLI backend, HTTP endpoint, F-017 shim.
Fake backends only: no Ollama, no claude, no network beyond loopback.
Run: python3 -m unittest discover -s tests"""
import json
import os
import sqlite3
import stat
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TMP = Path(tempfile.mkdtemp())
os.environ.update(ROUTER_DB=str(TMP / "router.db"), LLM_DB=str(TMP / "usage.db"), ROUTER_CONFIG=str(TMP / "router.json"))
sys.path.insert(0, str(ROOT / "llm"))
import helper  # noqa: E402
import jobs  # noqa: E402
import netutil  # noqa: E402
import router  # noqa: E402

TABLE = {"tiers": {
    "easy": {"backend": "local", "timeout": 5, "on_failure": "fail"},
    "easy-esc": {"backend": "local", "timeout": 5, "on_failure": "escalate"},
    "hard": {"backend": "claude", "timeout": 5, "on_failure": "fail"},
    "hard-esc": {"backend": "claude", "timeout": 5, "on_failure": "escalate"},
}}


def write_table(table):
    (TMP / "router.json").write_text(json.dumps(table))


def rows(where="1"):
    conn = sqlite3.connect(os.environ["ROUTER_DB"])
    conn.row_factory = sqlite3.Row
    return [dict(r) for r in conn.execute(f"SELECT * FROM requests WHERE {where} ORDER BY rowid")]


class Fake:
    """A fake backend: returns text, or raises the given JobError; remembers its calls."""

    def __init__(self, text="answer", error=None, tokens=(3, 4)):
        self.text, self.error, self.tokens, self.calls = text, error, tokens, []

    def __call__(self, req, timeout):
        self.calls.append((req, timeout))
        if self.error:
            raise self.error
        return (self.text, *self.tokens)


def req(**kw):
    return {"caller": "test", "tier": "easy", "prompt": "hi"} | kw


class RouterCase(unittest.TestCase):
    def setUp(self):
        write_table(TABLE)
        router.db().close()
        self.saved = dict(router.BACKENDS)
        self.local, self.claude = Fake("from local"), Fake("from claude", tokens=(10, 20))
        router.BACKENDS.update(local=self.local, claude=self.claude)
        self.start = len(rows())

    def tearDown(self):
        router.BACKENDS.update(self.saved)

    def new_rows(self):
        return rows()[self.start:]


class Routing(RouterCase):
    def test_tier_picks_backend_and_logs(self):
        r = router.route(req(caller="job-a", tier="easy"))
        self.assertEqual((r["output"], r["backend"], r["outcome"]), ("from local", "local", "ok"))
        r = router.route(req(caller="job-b", tier="hard"))
        self.assertEqual((r["output"], r["backend"]), ("from claude", "claude"))
        a, b = self.new_rows()
        self.assertEqual((a["caller"], a["tier"], a["backend"], a["outcome"], a["prompt_tokens"]),
                         ("job-a", "easy", "local", "ok", 3))
        self.assertEqual((b["caller"], b["tier"], b["backend"], b["prompt_tokens"], b["completion_tokens"]),
                         ("job-b", "hard", "claude", 10, 20))
        self.assertIsNotNone(a["duration_ms"])

    def test_changing_the_table_changes_routing_without_restart(self):
        router.route(req(tier="easy"))
        self.assertEqual(len(self.local.calls), 1)
        write_table({"tiers": {"easy": {"backend": "claude", "timeout": 5}}})
        os.utime(TMP / "router.json", ns=(10**18, 10**18 + 1))  # force a new mtime
        router.route(req(tier="easy"))
        self.assertEqual(len(self.claude.calls), 1)
        write_table({"tiers": {"brand-new": {"backend": "local", "timeout": 5}}})
        os.utime(TMP / "router.json", ns=(10**18, 10**18 + 2))
        self.assertEqual(router.route(req(tier="brand-new"))["backend"], "local")

    def test_bad_requests_are_rejected_and_not_logged(self):
        for bad, code in [(req(caller=""), "bad_request"), ({"tier": "easy", "prompt": "x"}, "bad_request"),
                          (req(tier=None), "bad_request"), (req(tier="nope"), "unknown_tier"),
                          (req(prompt=" "), "bad_request"), (req(job="poem"), "bad_request"),
                          (req(timeout=99999), "bad_request"), ("text", "bad_request")]:
            with self.subTest(bad=bad), self.assertRaises(router.RouterError) as cm:
                router.route(bad)
            self.assertEqual(cm.exception.code, code)
        self.assertEqual(self.new_rows(), [])

    def test_broken_table_is_a_visible_error(self):
        write_table({"tiers": {"easy": {"backend": "gpt", "timeout": 5}}})
        os.utime(TMP / "router.json", ns=(10**18, 10**18 + 3))
        with self.assertRaises(router.RouterError) as cm:
            router.route(req())
        self.assertEqual(cm.exception.code, "bad_config")


class Failure(RouterCase):
    def test_fail_tier_returns_the_error_and_logs_failed(self):
        self.local.error = jobs.JobError(504, "too slow", "timeout")
        with self.assertRaises(router.RouterError) as cm:
            router.route(req(tier="easy"))
        self.assertEqual((cm.exception.status, cm.exception.code), (504, "timeout"))
        self.assertEqual(self.claude.calls, [])
        (row,) = self.new_rows()
        self.assertEqual((row["outcome"], row["backend"], row["escalated_from"]), ("failed", "local", None))

    def test_escalate_tries_the_other_backend_and_logs_it(self):
        self.local.error = jobs.JobError(504, "too slow", "timeout")
        r = router.route(req(tier="easy-esc"))
        self.assertEqual((r["output"], r["backend"], r["outcome"], r["escalated_from"]),
                         ("from claude", "claude", "escalated", "local"))
        (row,) = self.new_rows()
        self.assertEqual((row["outcome"], row["backend"], row["escalated_from"]), ("escalated", "claude", "local"))
        self.assertIn("timeout", row["error"])
        self.assertEqual(row["completion_tokens"], 20)

    def test_escalation_works_from_claude_to_local_too(self):
        self.claude.error = jobs.JobError(502, "cli broke", "claude_error")
        r = router.route(req(tier="hard-esc"))
        self.assertEqual((r["backend"], r["outcome"], r["escalated_from"]), ("local", "escalated", "claude"))

    def test_escalation_that_also_fails_is_failed_with_both_errors(self):
        self.local.error = jobs.JobError(503, "down", "ollama_unreachable")
        self.claude.error = jobs.JobError(502, "cli broke", "claude_error")
        with self.assertRaises(router.RouterError) as cm:
            router.route(req(tier="easy-esc"))
        self.assertIn("ollama_unreachable", str(cm.exception))
        self.assertIn("claude_error", str(cm.exception))
        (row,) = self.new_rows()
        self.assertEqual((row["outcome"], row["backend"], row["escalated_from"]), ("failed", "claude", "local"))

    def test_backend_bug_is_a_logged_failure(self):
        self.local.error = RuntimeError("boom")
        with self.assertRaises(router.RouterError) as cm:
            router.route(req())
        self.assertEqual(cm.exception.status, 500)
        self.assertEqual(self.new_rows()[0]["outcome"], "failed")

    def test_bad_request_from_a_backend_does_not_escalate(self):
        self.local.error = jobs.JobError(400, "no fields", "bad_request")
        with self.assertRaises(router.RouterError):
            router.route(req(tier="easy-esc"))
        self.assertEqual(self.claude.calls, [])


class Log(RouterCase):
    def test_log_is_append_only(self):
        router.route(req())
        conn = sqlite3.connect(os.environ["ROUTER_DB"])
        for sql in ("UPDATE requests SET caller = 'x'", "DELETE FROM requests"):
            with self.subTest(sql=sql), self.assertRaises(sqlite3.DatabaseError):
                conn.execute(sql)
        conn.close()

    def test_usage_view(self):
        self.local.tokens = (None, None)  # a backend that reports no tokens: stored as NULL
        router.route(req(caller="u-a", tier="easy"))
        router.route(req(caller="u-a", tier="hard"))
        u = router.usage(1)
        mine = [g for g in u["groups"] if g["caller"] == "u-a"]
        self.assertEqual({(g["tier"], g["backend"]) for g in mine}, {("easy", "local"), ("hard", "claude")})
        self.assertGreaterEqual(u["claude_runs_avoided"], 1)
        self.assertEqual(self.new_rows()[0]["prompt_tokens"], None)


class ClaudeCli(RouterCase):
    """The real run_claude against a fake `claude` script."""

    def setUp(self):
        super().setUp()
        router.BACKENDS["claude"] = self.saved["claude"]
        self.bin = TMP / "fake-claude"
        self.args_file = TMP / "args.txt"
        self.set_script('echo "$@" > "$ARGS"; cat > "$ARGS.stdin"; echo "key=${ANTHROPIC_API_KEY-unset}" >> "$ARGS"; '
                        'echo \'{"type":"result","is_error":false,"result":"cli answer","usage":{"input_tokens":5,'
                        '"cache_read_input_tokens":100,"output_tokens":7}}\'')

    def set_script(self, body):
        self.bin.write_text(f"#!/bin/sh\n{body}\n")
        self.bin.chmod(self.bin.stat().st_mode | stat.S_IXUSR)
        os.environ["ARGS"] = str(self.args_file)
        write_table(TABLE | {"claude": {"command": str(self.bin), "model": "sonnet"}})
        os.utime(TMP / "router.json", ns=(2 * 10**18 + len(body), 2 * 10**18 + len(body)))

    def reply(self, obj):
        (TMP / "reply.json").write_text(json.dumps(obj))
        self.set_script(f'cat >/dev/null; cat "{TMP / "reply.json"}"')

    def test_json_output_gives_text_and_tokens_and_no_api_key(self):
        os.environ["ANTHROPIC_API_KEY"] = "sk-must-not-leak"
        try:
            r = router.route(req(tier="hard", prompt="question?", system="be brief"))
        finally:
            del os.environ["ANTHROPIC_API_KEY"]
        self.assertEqual((r["output"], r["backend"]), ("cli answer", "claude"))
        self.assertEqual(r["usage"], {"prompt_tokens": 105, "completion_tokens": 7})
        args = self.args_file.read_text()
        self.assertIn("-p --output-format json --tools  --model sonnet", args)
        self.assertIn("key=unset", args)
        self.assertEqual(Path(str(self.args_file) + ".stdin").read_text(), "be brief\n\nquestion?")
        self.assertEqual(self.new_rows()[0]["prompt_tokens"], 105)

    def test_missing_token_counts_are_left_empty(self):
        self.set_script('cat >/dev/null; echo \'{"result":"no usage here","is_error":false}\'')
        router.route(req(tier="hard"))
        row = self.new_rows()[0]
        self.assertEqual((row["outcome"], row["prompt_tokens"], row["completion_tokens"]), ("ok", None, None))

    def test_cli_errors_and_timeouts_fail_visibly(self):
        for script, code in [('cat >/dev/null; echo oops >&2; exit 3', "claude_error"),
                             ('cat >/dev/null; echo \'{"is_error":true,"result":"rate limited"}\'', "claude_error"),
                             ('cat >/dev/null; echo not-json', "claude_error"),
                             ('cat >/dev/null; exec sleep 5', "timeout")]:
            self.set_script(script)
            with self.subTest(script=script), self.assertRaises(router.RouterError) as cm:
                router.route(req(tier="hard", timeout=1))
            self.assertEqual(cm.exception.code, code)
        self.assertTrue(all(r["outcome"] == "failed" for r in self.new_rows()))

    def test_missing_cli_is_unavailable(self):
        write_table(TABLE | {"claude": {"command": str(TMP / "does-not-exist")}})
        os.utime(TMP / "router.json", ns=(3 * 10**18, 3 * 10**18))
        with self.assertRaises(router.RouterError) as cm:
            router.route(req(tier="hard"))
        self.assertEqual(cm.exception.code, "claude_unavailable")

    def test_extract_job_on_claude_is_validated(self):
        self.reply({"result": "```json\n{\"name\": \"Ann\"}\n```", "is_error": False})
        r = router.route(req(tier="hard", job="extract", fields=["name"]))
        self.assertEqual(json.loads(r["output"]), {"name": "Ann"})
        self.reply({"result": '{"other": 1}', "is_error": False})
        with self.assertRaises(router.RouterError) as cm:
            router.route(req(tier="hard", job="extract", fields=["name"]))
        self.assertEqual(cm.exception.code, "invalid_output")


class LocalBackend(RouterCase):
    """The real run_local on the F-017 core, with a fake Ollama."""

    def setUp(self):
        super().setUp()
        router.BACKENDS["local"] = self.saved["local"]
        self.sent = []
        self.reply = {"message": {"content": '{"name": "Ann"}'}, "prompt_eval_count": 8, "eval_count": 3}
        self._ollama, jobs.ollama = jobs.ollama, lambda path, body=None, timeout=10: (self.sent.append(body), self.reply)[1]

    def tearDown(self):
        jobs.ollama = self._ollama
        super().tearDown()

    def test_summarize_job_uses_the_f017_prompt_and_reports_tokens(self):
        self.reply = {"message": {"content": "short"}, "prompt_eval_count": 8, "eval_count": 3}
        r = router.route(req(job="summarize", prompt="long text"))
        self.assertEqual((r["output"], r["usage"]), ("short", {"prompt_tokens": 8, "completion_tokens": 3}))
        self.assertEqual(self.sent[0]["messages"][0]["content"], jobs.SUMMARIZE_PROMPT)

    def test_extract_job_is_validated_and_plain_chat_works(self):
        r = router.route(req(job="extract", fields=["name"]))
        self.assertEqual(json.loads(r["output"]), {"name": "Ann"})
        self.reply = {"message": {"content": "hello"}}
        r = router.route(req(system="sys"))
        self.assertEqual((r["output"], r["usage"]["prompt_tokens"]), ("hello", None))
        self.assertEqual(self.sent[-1]["messages"][0], {"role": "system", "content": "sys"})

    def test_too_long_text_escalates_when_the_tier_says_so(self):
        r = router.route(req(tier="easy-esc", job="summarize", prompt="x" * 50000))
        self.assertEqual((r["backend"], r["outcome"]), ("claude", "escalated"))


class Http(RouterCase):
    @classmethod
    def setUpClass(cls):
        from http.server import ThreadingHTTPServer
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), router.Handler)
        cls.url = f"http://127.0.0.1:{cls.server.server_address[1]}"
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def post(self, body, path="/v1/route"):
        r = urllib.request.Request(self.url + path, data=json.dumps(body).encode(), method="POST")
        try:
            with urllib.request.urlopen(r) as resp:
                return resp.status, json.load(resp)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_route_ok_and_errors(self):
        status, body = self.post(req(tier="hard"))
        self.assertEqual((status, body["output"], body["backend"]), (200, "from claude", "claude"))
        status, body = self.post({"tier": "easy", "prompt": "x"})
        self.assertEqual((status, body["error"]["code"]), (400, "bad_request"))
        self.local.error = jobs.JobError(504, "slow", "timeout")
        status, body = self.post(req(tier="easy"))
        self.assertEqual((status, body["error"]["code"]), (504, "timeout"))
        self.assertEqual(self.post({}, "/nope")[0], 404)

    def test_tiers_usage_health(self):
        for path, key in [("/v1/tiers", "tiers"), ("/v1/usage?days=1", "groups"), ("/health", "ok")]:
            with urllib.request.urlopen(self.url + path) as resp:
                self.assertIn(key, json.load(resp))

    def test_only_loopback_and_tailnet_clients(self):
        for ip, ok in [("127.0.0.1", True), ("100.85.70.34", True), ("::ffff:100.64.1.1", True),
                       ("192.168.1.20", False), ("8.8.8.8", False)]:
            self.assertEqual(netutil.client_allowed(ip), ok, ip)


class Shim(unittest.TestCase):
    """The F-017 helper forwards to the router with caller f017-legacy and tier easy."""

    @classmethod
    def setUpClass(cls):
        from http.server import ThreadingHTTPServer
        write_table(TABLE)
        cls.seen = []
        outer = cls

        class FakeRouter(router.Handler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                outer.seen.append(body)
                if body["messages"][0]["content"] == "fail":
                    return self.send_error_json(504, "slow", "timeout")
                self.send_json(200, {"output": "summary", "backend": "local", "outcome": "ok",
                                     "usage": {"prompt_tokens": 2, "completion_tokens": 1}})

        cls.router = ThreadingHTTPServer(("127.0.0.1", 0), FakeRouter)
        jobs.CONF["router_port"] = cls.router.server_address[1]
        cls.helper = ThreadingHTTPServer(("127.0.0.1", 0), helper.Handler)
        for s in (cls.router, cls.helper):
            threading.Thread(target=s.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        for s in (cls.router, cls.helper):
            s.shutdown()
            s.server_close()

    def chat(self, text, headers=None):
        r = urllib.request.Request(f"http://127.0.0.1:{self.helper.server_address[1]}/v1/chat/completions",
                                   data=json.dumps({"model": "summarize", "messages": [{"role": "user", "content": text}]}).encode(),
                                   headers=headers or {}, method="POST")
        try:
            with urllib.request.urlopen(r) as resp:
                return resp.status, json.load(resp)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_default_caller_and_tier(self):
        status, body = self.chat("some text")
        self.assertEqual(status, 200)
        self.assertEqual(body["choices"][0]["message"]["content"], "summary")
        self.assertEqual(body["usage"]["total_tokens"], 3)
        self.assertEqual((self.seen[-1]["caller"], self.seen[-1]["tier"], self.seen[-1]["job"]),
                         ("f017-legacy", "easy", "summarize"))

    def test_given_caller_is_kept_and_router_errors_keep_their_shape(self):
        self.chat("x", {"X-LLM-Caller": "old-script"})
        self.assertEqual(self.seen[-1]["caller"], "old-script")
        status, body = self.chat("fail")
        self.assertEqual((status, body["error"]["code"]), (504, "timeout"))

    def test_router_down_is_503(self):
        port = jobs.CONF["router_port"]
        jobs.CONF["router_port"] = 1
        try:
            status, body = self.chat("x")
        finally:
            jobs.CONF["router_port"] = port
        self.assertEqual((status, body["error"]["code"]), (503, "router_unreachable"))


if __name__ == "__main__":
    unittest.main()
