"""Search query and answer side (H-012): ranked results with links and filters, cited answers through the router
(fake), "nothing relevant" without a model call, read-only index, HTTP API, MCP tool, CLI.
A fake embedder stands in for Ollama and a fake router for the router: no GPU, no network beyond localhost.
Run: python3 -m unittest discover -s tests"""
import contextlib
import importlib.machinery
import importlib.util
import io
import json
import os
import subprocess
import sys
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "search"))
import answer  # noqa: E402
import index  # noqa: E402
import server  # noqa: E402
from test_search_index import FAKE_KEY, SearchTest  # noqa: E402


def load_script(name):
    path = str(ROOT / "bin" / name)
    loader = importlib.machinery.SourceFileLoader(name.replace("-", "_"), path)
    spec = importlib.util.spec_from_loader(loader.name, loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


class FakeRouter:
    def __init__(self, output="We chose sqlite for storage [1]."):
        self.output, self.calls = output, []

    def __call__(self, conf, body):
        self.calls.append(body)
        return {"output": self.output, "backend": "local", "outcome": "ok", "request_id": "abc123def456"}


class QueryTest(unittest.TestCase):
    setUp = SearchTest.setUp  # the same fixture: vault, product, transcripts, repos, memory, fake embedder

    def synced(self):
        self.idx.sync(log=lambda *_: None)
        self.ro = index.Index(self.conf, self.emb, readonly=True)
        self.addCleanup(self.ro.close)
        return self.ro

    def test_results_show_source_location_date_project_and_link(self):
        ro = self.synced()
        hits = answer.search(ro, self.conf, "we chose sqlite for storage", k=3)
        top = hits[0]
        self.assertEqual((top["source"], top["type"], top["project"], top["date"]),
                         ("second-brain", "wiki", "alpha", "2026-11-01"))
        self.assertEqual(top["location"], "Alpha > Decisions")
        self.assertEqual(top["link"], "obsidian://open?vault=second-brain&file=wiki%2Fprojects%2Falpha")
        self.assertIn("sqlite", top["snippet"])
        code = answer.search(ro, self.conf, "def first", type="repo-code")[0]
        self.assertEqual(code["link"], "https://github.com/chrisfaber95/one/blob/main/app.py")
        turn = answer.search(ro, self.conf, "how do we deploy the dashboard", type="transcript")[0]
        self.assertEqual((turn["location"], turn["project"], turn["link"]), ("turn 1 (user)", "homelab", None))

    def test_filters_by_project_type_and_source(self):
        ro = self.synced()
        for kw, col in (({"project": "homelab"}, "project"), ({"type": "memory"}, "type"),
                        ({"source": "repo-docs"}, "source")):
            hits = answer.search(ro, self.conf, "anything", k=50, **kw)
            self.assertTrue(hits)
            self.assertEqual({h[col] for h in hits}, {next(iter(kw.values()))})
        with self.assertRaises(answer.AskError):
            answer.search(ro, self.conf, "  ")

    def test_ask_cites_with_links_and_returns_the_request_id(self):
        ro, router = self.synced(), FakeRouter()
        self.conf["query"]["min_score"] = 0.3
        r = answer.ask(ro, self.conf, "we chose sqlite for storage", project="alpha", router=router)
        self.assertTrue(r["found"])
        self.assertEqual((r["request_id"], r["backend"], r["tier"]), ("abc123def456", "local", "easy"))
        self.assertEqual(len(router.calls), 1)
        self.assertEqual((router.calls[0]["caller"], router.calls[0]["tier"]), ("search", "easy"))
        self.assertIn("Alpha > Decisions", router.calls[0]["prompt"])
        self.assertEqual([c["n"] for c in r["citations"]], [1])
        self.assertIn("[1](obsidian://open?vault=second-brain", r["answer_markdown"])
        self.assertEqual(r["citations"][0]["location"], "Alpha > Decisions")

    def test_ask_hard_uses_the_hard_tier(self):
        ro, router = self.synced(), FakeRouter()
        self.conf["query"]["min_score"] = 0.3
        r = answer.ask(ro, self.conf, "we chose sqlite for storage", hard=True, router=router)
        self.assertEqual((router.calls[0]["tier"], r["tier"], r["hard"]), ("hard", "hard", True))

    def test_uncited_answer_lists_the_sources_it_was_given_and_ignores_invented_numbers(self):
        ro = self.synced()
        self.conf["query"]["min_score"] = 0.0
        r = answer.ask(ro, self.conf, "sqlite", router=FakeRouter("Probably sqlite [99]."))
        self.assertTrue(r["citations"])
        self.assertFalse(any(c["cited"] for c in r["citations"]))
        self.assertNotIn("](", r["answer_markdown"])

    def test_nothing_relevant_says_so_and_does_not_call_the_router(self):
        ro, router = self.synced(), FakeRouter()
        self.conf["query"]["min_score"] = 0.99
        r = answer.ask(ro, self.conf, "zebra quantum pancake", router=router)
        self.assertFalse(r["found"])
        self.assertEqual(r["answer"], answer.NOTHING_FOUND)
        self.assertEqual((router.calls, r["citations"], r["request_id"]), ([], [], None))
        empty = answer.ask(ro, self.conf, "x", project="no-such-project", router=router)
        self.assertFalse(empty["found"])
        self.assertEqual(router.calls, [])

    def test_the_question_is_scrubbed_before_it_reaches_the_router(self):
        ro, router = self.synced(), FakeRouter()
        self.conf["query"]["min_score"] = 0.0
        r = answer.ask(ro, self.conf, f"what is {FAKE_KEY} used for", router=router)
        self.assertNotIn(FAKE_KEY, json.dumps(router.calls) + json.dumps(r))

    def test_router_failure_is_an_error_with_the_request_id(self):
        ro = self.synced()
        self.conf["query"]["min_score"] = 0.0

        def broken(conf, body):
            raise answer.AskError(502, "The router failed: boom", "rid1")
        with self.assertRaises(answer.AskError) as cm:
            answer.ask(ro, self.conf, "sqlite", router=broken)
        self.assertEqual(cm.exception.request_id, "rid1")

    def test_query_side_never_writes_the_index(self):
        self.synced()
        before = Path(self.conf["db"]).read_bytes()
        ro = index.Index(self.conf, self.emb, readonly=True)
        answer.search(ro, self.conf, "sqlite")
        with self.assertRaises(Exception):
            ro.db.execute("DELETE FROM chunks")
        ro.close()
        self.assertEqual(Path(self.conf["db"]).read_bytes(), before)
        missing = dict(self.conf, db=str(self.tmp / "none.db"))
        with self.assertRaises(FileNotFoundError):
            index.Index(missing, self.emb, readonly=True)
        self.assertFalse((self.tmp / "none.db").exists())

    def test_stale_index_is_refused_on_query(self):
        self.synced()
        self.conf["scrub"] = self.conf["scrub"] + [{"name": "x", "regex": "zzz"}]
        ro = index.Index(self.conf, self.emb, readonly=True)
        with self.assertRaises(index.FingerprintMismatch):
            ro.query("sqlite")
        ro.close()

    def test_link_for_unknown_source_is_none(self):
        self.assertIsNone(answer.link_for(self.conf, {"source": "inbox-transcripts", "path": "a/b.jsonl"}))


class ApiTest(unittest.TestCase):
    """HTTP API, MCP tool and CLI on top of the same fixture."""
    setUp = SearchTest.setUp
    synced = QueryTest.synced

    def start(self, router=None):
        self.synced()
        self.conf["query"]["min_score"] = 0.3
        srv = ThreadingHTTPServer(("127.0.0.1", 0), server.make_handler(self.conf, router, self.emb))
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.server_close)
        self.addCleanup(srv.shutdown)
        return f"http://127.0.0.1:{srv.server_port}"

    def get(self, url, data=None):
        try:
            req = urllib.request.Request(url, data=json.dumps(data).encode() if data is not None else None)
            with urllib.request.urlopen(req) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_http_search_ask_and_filters(self):
        router = FakeRouter()
        base = self.start(router)
        st, r = self.get(f"{base}/v1/search?q=sqlite+storage&type=wiki&k=2")
        self.assertEqual(st, 200)
        self.assertTrue(r["results"] and {h["type"] for h in r["results"]} == {"wiki"})
        self.assertLessEqual(len(r["results"]), 2)
        st, r = self.get(f"{base}/v1/ask?q=we+chose+sqlite+for+storage&hard=1")
        self.assertEqual((st, r["tier"], r["request_id"]), (200, "hard", "abc123def456"))
        st, r = self.get(f"{base}/v1/ask", {"question": "we chose sqlite for storage", "project": "alpha"})
        self.assertEqual((st, r["found"], router.calls[-1]["tier"]), (200, True, "easy"))
        st, r = self.get(f"{base}/v1/filters")
        self.assertIn("alpha", r["projects"])
        self.assertIn("transcript", r["types"])

    def test_http_errors_and_no_writes(self):
        base = self.start(FakeRouter())
        self.assertEqual(self.get(f"{base}/v1/search?q=")[0], 400)
        self.assertEqual(self.get(f"{base}/v1/search?q=a&k=x")[0], 400)
        self.assertEqual(self.get(f"{base}/v1/ask?q=" + "a" * 2001)[0], 413)
        self.assertEqual(self.get(f"{base}/nope")[0], 404)
        self.assertEqual(self.get(f"{base}/v1/search", {"q": "x"})[0], 404)  # POST only for ask
        self.assertEqual(self.get(f"{base}/health")[0], 200)

    def test_only_loopback_and_tailnet_clients(self):
        self.assertTrue(all(server.client_allowed(ip) for ip in ("127.0.0.1", "100.85.70.34", "::ffff:100.64.0.1")))
        self.assertFalse(any(server.client_allowed(ip) for ip in ("192.168.1.20", "8.8.8.8", "::ffff:10.0.0.5")))

    def test_mcp_tools_call_the_api(self):
        base = self.start(FakeRouter())
        mcp = load_script("homelab-search-mcp")
        mcp.URL = base
        init = mcp.handle({"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2024-11-05"}})
        self.assertEqual(init["result"]["protocolVersion"], "2024-11-05")
        self.assertIsNone(mcp.handle({"jsonrpc": "2.0", "method": "notifications/initialized"}))
        tools = mcp.handle({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})["result"]["tools"]
        self.assertEqual([t["name"] for t in tools], ["search", "ask"])
        self.assertTrue(all(t["annotations"]["readOnlyHint"] for t in tools))

        def call(name, args):
            return mcp.handle({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
                               "params": {"name": name, "arguments": args}})["result"]
        res = call("search", {"query": "we chose sqlite for storage", "project": "alpha", "k": 2})
        text = res["content"][0]["text"]
        self.assertNotIn("isError", res)
        self.assertIn("alpha · wiki · wiki/projects/alpha.md · Alpha > Decisions · 2026-11-01", text)
        self.assertIn("obsidian://", text)
        res = call("ask", {"question": "we chose sqlite for storage", "hard": True})
        self.assertIn("[1] alpha", res["content"][0]["text"])
        self.assertIn("abc123def456", res["content"][0]["text"])
        self.assertTrue(call("search", {"query": ""})["isError"])
        self.assertEqual(mcp.handle({"jsonrpc": "2.0", "id": 4, "method": "tools/call",
                                     "params": {"name": "write"}})["error"]["code"], -32602)
        mcp.URL = "http://127.0.0.1:9"
        self.assertIn("unreachable", call("search", {"query": "x"})["content"][0]["text"])

    def test_mcp_speaks_json_rpc_over_stdio(self):
        msgs = [{"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {}},
                {"jsonrpc": "2.0", "method": "notifications/initialized"},
                {"jsonrpc": "2.0", "id": 2, "method": "tools/list"}]
        out = subprocess.run([sys.executable, str(ROOT / "bin/homelab-search-mcp")], capture_output=True, text=True,
                             input="\n".join(json.dumps(m) for m in msgs) + "\n", timeout=20).stdout.splitlines()
        self.assertEqual([json.loads(line)["id"] for line in out], [1, 2])

    def test_cli_query_and_ask(self):
        self.synced()
        self.conf["query"]["min_score"] = 0.3
        conf_file = self.tmp / "search.json"
        conf_file.write_text(json.dumps(self.conf))
        cli = load_script("homelab-search")
        old = (index.Embedder, answer.route_http, os.environ.get("SEARCH_CONFIG"))
        index.Embedder = lambda c: self.emb
        answer.route_http = FakeRouter()
        os.environ["SEARCH_CONFIG"] = str(conf_file)

        def run(*argv):
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                code = cli.main(list(argv))
            return code, buf.getvalue()
        try:
            code, out = run("query", "we chose sqlite for storage", "--project", "alpha", "--json")
            self.assertEqual(code, 0)
            self.assertEqual(json.loads(out)[0]["location"], "Alpha > Decisions")
            code, out = run("query", "sqlite", "--type", "wiki")
            self.assertIn("[wiki] alpha · wiki/projects/alpha.md · Alpha > Decisions · 2026-11-01", out)
            code, out = run("ask", "we chose sqlite for storage", "--hard")
            self.assertEqual(code, 0)
            self.assertIn("We chose sqlite for storage [1].", out)
            self.assertIn("request id abc123def456", out)
            self.assertEqual(answer.route_http.calls[0]["tier"], "hard")
        finally:
            index.Embedder, answer.route_http = old[0], old[1]
            if old[2] is None:
                os.environ.pop("SEARCH_CONFIG", None)
            else:
                os.environ["SEARCH_CONFIG"] = old[2]


if __name__ == "__main__":
    unittest.main()
