"""Contract tests of the machine endpoints (H-020): what `backend/openapi.yaml` says about `/api/chat/*`, kitt's door
(`/api/clients/kitt`, `/api/conversation`, `/api/logs*`, `/api/approvals*`, `/api/jobs*`, `/api/health`), `/api/forge*`,
`/api/search/*`, `/api/live` and `/api/log/*`: status codes, response shape (checked against the schema of the spec when
PyYAML is installed), content type and error bodies. A replacement of the Python server has to pass these too.

INBOX_CONTRACT_BASE_URL
    Not set (the default): the tests start the in-process Python server (bin/homelab_inbox/server.py) on a free
    loopback port with a temporary state directory, and arrange what they need around it: client tokens and
    permissions, kitt's identity, fake forge/search/router upstreams, tasks and log lines.
    Set (for example http://127.0.0.1:8105): no server is started and every request goes to that URL. Tests that
    arrange state inside the Python process are skipped; the rest run against the server you point at. Optional for
    that mode: INBOX_CONTRACT_CHAT_TOKEN, the bearer token of a chat client with the `read` permission, enables the
    chat-door checks that need a valid token. Do not point it at a box whose conversation or tasks you care about:
    the POST checks write there.

Run: python3 -m unittest discover -s tests   (or: INBOX_CONTRACT_BASE_URL=http://127.0.0.1:PORT python3 tests/test_inbox_contract.py)
"""
import http.client
import json
import os
import re
import socket
import sys
import tempfile
import threading
import unittest
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
EXTERNAL = os.environ.get("INBOX_CONTRACT_BASE_URL", "").rstrip("/")
EXTERNAL_TOKEN = os.environ.get("INBOX_CONTRACT_CHAT_TOKEN", "")
SPEC_FILE = ROOT / "backend/openapi.yaml"
JSON_UTF8, JSON_PLAIN = "application/json; charset=utf-8", "application/json"
OWNER = "chris@example.com"
BID = "H-018"
KITT_ALL = ["read", "write", "ask", "start_task", "run_job", "approve"]
CLIENTS = {"clients": {
    "discord": {"permissions": ["read", "write", "ask", "start_task", "answer", "approve", "run_job", "research"],
                "tasks_per_day": 2},
    "reader": {"permissions": ["read"]},
    "kitt": {"permissions": KITT_ALL, "tasks_per_day": 5}}}

if not EXTERNAL:
    HOME, TOKENS = tempfile.mkdtemp(), tempfile.mkdtemp()
    os.environ.update(INBOX_RUNNER_HOME=HOME, NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"),
                      CHAT_TOKEN_DIR=TOKENS)
    sys.path.insert(0, str(ROOT / "bin"))
    from homelab_inbox import briefs, chat, config, conversation, forge, helpers, kitt, pages, search, server, storage  # noqa: E402

try:
    import yaml
    SPEC = yaml.safe_load(SPEC_FILE.read_text())
except ImportError:  # the shape checks against the spec are skipped; the explicit asserts below still run
    SPEC = None

# --- a small JSON-schema check for the subset of OpenAPI 3.0 the spec uses -------------------------------------------
TYPES = {"object": dict, "array": list, "string": str, "boolean": bool, "integer": int, "number": (int, float)}


def resolve(node):
    while isinstance(node, dict) and "$ref" in node:
        target = SPEC
        for part in node["$ref"].lstrip("#/").split("/"):
            target = target[part]
        node = target
    return node


def violations(schema, value, where="body"):
    schema = resolve(schema)
    if not isinstance(schema, dict):  # a few leaves in the spec are descriptions, not schemas
        return []
    if value is None and schema.get("nullable"):
        return []
    if "oneOf" in schema:
        options = [violations(s, value, where) for s in schema["oneOf"]]
        return [] if any(not o for o in options) else [f"{where}: matches none of oneOf ({options})"]
    out = []
    kind = schema.get("type")
    if kind and not (isinstance(value, TYPES[kind]) and not (kind in ("integer", "number") and isinstance(value, bool))):
        return [f"{where}: expected {kind}, got {type(value).__name__} {value!r:.60}"]
    if "enum" in schema and value not in schema["enum"]:
        out.append(f"{where}: {value!r} is not one of {schema['enum']}")
    if isinstance(value, str):
        if "pattern" in schema and not re.search(schema["pattern"], value):
            out.append(f"{where}: {value!r} does not match {schema['pattern']}")
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            out.append(f"{where}: longer than {schema['maxLength']}")
    if isinstance(value, dict):
        out += [f"{where}: missing '{k}'" for k in schema.get("required", []) if k not in value]
        for k, sub in schema.get("properties", {}).items():
            if k in value:
                out += violations(sub, value[k], f"{where}.{k}")
    if isinstance(value, list) and "items" in schema:
        for i, item in enumerate(value):
            out += violations(schema["items"], item, f"{where}[{i}]")
    return out


class Reply:
    def __init__(self, status, headers, raw):
        self.status, self.headers, self.raw = status, headers, raw
        self.ctype = headers.get("Content-Type", "")
        try:
            self.body = json.loads(raw) if "json" in self.ctype else raw.decode()
        except ValueError:
            self.body = raw.decode(errors="replace")

    def __repr__(self):
        return f"<{self.status} {self.ctype} {self.raw[:200]!r}>"


# --- fake upstreams: forge, the search API and the router, one loopback server ---------------------------------------
class Upstream(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def reply(self, status, obj):
        data = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def handle_any(self):
        state = self.server.state
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        state["seen"].append((self.command, self.path, json.loads(body) if body else None))
        url = urllib.parse.urlsplit(self.path)
        if url.path in ("/api/start", "/api/stop"):
            return self.reply(*state["control"])
        table = {"/api/state": state["forge"], "/api/log": state["forge_log"], "/v1/filters": state["filters"],
                 "/v1/search": state["results"], "/v1/ask": state["ask"], "/v1/feedback": state["feedback"]}
        self.reply(*table[url.path]) if url.path in table else self.reply(404, {"error": "no such route"})

    do_GET = do_POST = handle_any


def closed_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


class Contract(unittest.TestCase):
    """Base: the HTTP client, the spec check and (in-process mode) the server and the arranged state."""
    srv = upstream = None

    @classmethod
    def setUpClass(cls):
        if EXTERNAL:
            u = urllib.parse.urlsplit(EXTERNAL)
            cls.host, cls.port = u.hostname, u.port or 80
            return
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.host, cls.port = "127.0.0.1", cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.upstream = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
        threading.Thread(target=cls.upstream.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        for s in (cls.srv, cls.upstream):
            if s:
                s.shutdown()
                s.server_close()

    # -- arranging (in-process only) --
    def setUp(self):
        if EXTERNAL:
            return
        conversation.refusals()  # creates the conversation tables
        with storage.db() as c:
            for t in ("tasks", "kv", "conversation", "conversation_refusals", "job_runs"):
                c.execute(f"DELETE FROM {t}")
            c.execute("DROP TABLE IF EXISTS approvals")
        for old in helpers.LOGS.glob("*.log"):  # ids restart at 1 after the DELETE: no lines of an earlier test
            old.unlink()
        self._env = {k: os.environ.get(k) for k in ("CLIENTS_JSON", "CHAT_TOKEN_DIR", "INBOX_ALLOW_ALL")}
        os.environ.update(CLIENTS_JSON=json.dumps(CLIENTS), CHAT_TOKEN_DIR=TOKENS)
        os.environ.pop("INBOX_ALLOW_ALL", None)  # the box itself is refused by the page check, as in production
        for name in ("discord", "reader"):
            (Path(TOKENS) / f"chat-{name}-token").write_text(f"tok-{name}\n")
        for sw in ("discord", "discord_approvals", "kitt"):
            conversation.set_switch(sw, True)
        self.up = {"seen": [], "control": (200, {}), "forge": (200, {"running": True, "step": 2, "project": "demo",
                   "activity": "Edit: a.py", "started_at": "2026-11-16T10:00:00+00:00", "paused": False}),
                   "forge_log": (200, {"lines": ["2026-11-16T10:00:01+00:00 Bash: ls", "plain line"], "next": 2,
                                       "running": False}),
                   "filters": (200, {"projects": ["homelab"], "types": ["doc"]}),
                   "results": (200, {"query": "x", "results": []}), "ask": (200, {"found": False}),
                   "feedback": (200, {"ok": True})}
        self.upstream.state = self.up
        base = f"http://127.0.0.1:{self.upstream.server_address[1]}"
        self._saved = (forge.FORGE_URL, search.SEARCH_URL, search.ROUTER_URL, kitt.account_uid, kitt.peer_uid,
                       pages.Access.owner, briefs.brief_by_id, chat.brief_by_id, briefs.set_brief_status,
                       briefs.add_new_repo, chat.notify, kitt.load_briefs, kitt.health, chat.clock, kitt.answer_question)
        forge.FORGE_URL, search.SEARCH_URL, search.ROUTER_URL = base, base, base
        forge.forget()
        kitt.account_uid, kitt.peer_uid = lambda: 4242, lambda addr, port: 4242
        pages.Access.owner = OWNER
        self.brief = {"id": BID, "title": "Kitt", "status": "draft", "repos": ["homelab"], "build": {}}
        briefs.brief_by_id = chat.brief_by_id = lambda bid, max_age=60: self.brief if bid == BID else None
        briefs.add_new_repo = lambda b: None
        briefs.set_brief_status = lambda bid, status, why: self.brief.update(status=status)
        chat.notify = lambda title, msg, click=None, store=True: None
        kitt.load_briefs = lambda: [self.brief]
        kitt.health = lambda: {"checks": {"gpu": {"status": "OK", "text": "fine"}, "disk": {"status": "BOGUS"}}}

    def tearDown(self):
        if EXTERNAL:
            return
        (forge.FORGE_URL, search.SEARCH_URL, search.ROUTER_URL, kitt.account_uid, kitt.peer_uid, pages.Access.owner,
         briefs.brief_by_id, chat.brief_by_id, briefs.set_brief_status, briefs.add_new_repo, chat.notify,
         kitt.load_briefs, kitt.health, chat.clock, kitt.answer_question) = self._saved
        for k, v in self._env.items():
            os.environ.pop(k, None) if v is None else os.environ.__setitem__(k, v)
        for sw in ("discord", "discord_approvals", "kitt"):
            conversation.set_switch(sw, True)
        forge.forget()

    def local(self):
        if EXTERNAL:
            self.skipTest("arranges state inside the Python process; not possible against INBOX_CONTRACT_BASE_URL")

    def allow_page(self):
        """The page check lets everyone in (the same switch the other tests use); a real owner needs a tailnet."""
        os.environ["INBOX_ALLOW_ALL"] = "1"

    def dead_upstreams(self):
        url = f"http://127.0.0.1:{closed_port()}"
        forge.FORGE_URL, search.SEARCH_URL, search.ROUTER_URL = url, url, url
        forge.forget()

    # -- calling --
    def call(self, method, path, body=None, headers=None, raw=None):
        c = http.client.HTTPConnection(self.host, self.port, timeout=15)
        data = raw if raw is not None else (json.dumps(body) if body is not None else None)
        c.request(method, path, data, headers or {})
        r = c.getresponse()
        reply = Reply(r.status, dict(r.getheaders()), r.read())
        c.close()
        return reply

    def chat(self, method, path, body=None, token="discord", raw=None):
        headers = {"Authorization": f"Bearer {token if token.startswith('tok-') or EXTERNAL else 'tok-' + token}"} if token else {}
        return self.call(method, path, body, headers, raw)

    def kitt(self, method, path, body=None, headers=None, raw=None):
        h = {"X-Homelab-Client": "kitt", "Tailscale-User-Login": OWNER, **(headers or {})}
        return self.call(method, path, body, h, raw)

    def form(self, method, path, fields=None, origin=True, csrf=True):
        """A page POST (or GET): form-encoded, Origin equal to Host, and the CSRF field."""
        fields = dict(fields or {})
        headers = {"Content-Type": "application/x-www-form-urlencoded"}
        if csrf and path.startswith("/api/"):  # /api/* takes the token as a header (GET /api/session), pages as a field
            headers["X-CSRF-Token"] = pages.csrf_token()
        elif csrf:
            fields["csrf"] = pages.csrf_token()
        if origin:
            headers["Origin"] = f"http://{self.host}:{self.port}"
        return self.call(method, path, headers=headers, raw=urllib.parse.urlencode(fields) if method == "POST" else None)

    # -- the spec --
    def conforms(self, reply, spec_path, method, expect=None, ctype=None):
        """The status is documented for this operation and the body matches its schema; `expect` pins the status."""
        if expect is not None:
            self.assertEqual(reply.status, expect, reply)
        if SPEC is None:
            return reply
        op = SPEC["paths"][spec_path][method.lower()]
        responses = op["responses"]
        self.assertIn(str(reply.status), responses, f"{method} {spec_path}: status {reply.status} is not in the spec: {reply}")
        content = resolve(responses[str(reply.status)]).get("content") or {}
        if "json" in reply.ctype and content:
            wanted = ctype or next(iter(content))  # the spec lists one JSON entry; forge's log is utf-8 under plain JSON
            self.assertEqual(reply.ctype, wanted, f"{method} {spec_path} {reply.status}: content type")
            schema = (content.get(wanted) or next(iter(content.values()))).get("schema", {})
            self.assertEqual(violations(schema, reply.body), [], f"{spec_path} {reply}")
        elif content:  # an HTML page where the spec says HTML
            self.assertTrue(reply.ctype.startswith("text/html"), reply)
        return reply

    def error(self, reply, status, text=None, starts=None):
        self.assertEqual(reply.status, status, reply)
        self.assertEqual(reply.ctype, JSON_UTF8)
        self.assertEqual(list(reply.body)[:1], ["error"], reply)
        if text is not None:
            self.assertEqual(reply.body["error"], text)
        if starts is not None:
            self.assertTrue(reply.body["error"].startswith(starts), reply)
        return reply


# =====================================================================================================================
class ChatDoor(Contract):
    def test_a_missing_or_unknown_token_is_401_on_every_route(self):
        for method, path in (("GET", "/api/chat/state"), ("GET", "/api/chat/entries"), ("POST", "/api/chat/entries"),
                             ("POST", "/api/chat/action"), ("GET", "/api/chat/action"), ("POST", "/api/chat/state")):
            for token in (None, "tok-nobody"):
                r = self.chat(method, path, {} if method == "POST" else None, token=token)
                self.error(r, 401, "unknown client or token")
                self.conforms(r, path, method)

    def test_state(self):
        if EXTERNAL and not EXTERNAL_TOKEN:
            self.skipTest("set INBOX_CONTRACT_CHAT_TOKEN to run this against INBOX_CONTRACT_BASE_URL")
        r = self.conforms(self.chat("GET", "/api/chat/state", token=EXTERNAL_TOKEN or "discord"), "/api/chat/state", "get", 200,
                          JSON_UTF8)
        self.assertEqual(set(r.body), {"discord", "discord_approvals"})
        self.assertTrue(all(isinstance(v, bool) for v in r.body.values()))

    def test_state_is_answered_while_the_switch_is_off(self):
        self.local()
        conversation.set_switch("discord", False)
        r = self.conforms(self.chat("GET", "/api/chat/state"), "/api/chat/state", "get", 200)
        self.assertEqual(r.body, {"discord": False, "discord_approvals": False})

    def test_state_post_is_405(self):
        self.local()
        r = self.conforms(self.chat("POST", "/api/chat/state", {}), "/api/chat/state", "post", 405)
        self.error(r, 405, "GET only")

    def test_entries_read(self):
        self.local()
        conversation.append("chris", "page", "message", "hello", None, None)
        r = self.conforms(self.chat("GET", "/api/chat/entries"), "/api/chat/entries", "get", 200, JSON_UTF8)
        self.assertEqual([e["text"] for e in r.body["entries"]], ["hello"])
        self.assertEqual(r.body["next"], r.body["entries"][-1]["id"])
        r = self.chat("GET", f"/api/chat/entries?after={r.body['next']}&limit=")
        self.assertEqual((r.status, r.body), (200, {"entries": [], "next": r.body["next"]}))
        self.assertEqual(self.chat("GET", "/api/chat/entries", token="reader").status, 200)

    def test_entries_read_with_an_external_token(self):
        if not EXTERNAL_TOKEN:
            self.skipTest("set INBOX_CONTRACT_CHAT_TOKEN to run this against INBOX_CONTRACT_BASE_URL")
        r = self.conforms(self.chat("GET", "/api/chat/entries", token=EXTERNAL_TOKEN), "/api/chat/entries", "get", 200, JSON_UTF8)
        self.assertEqual(set(r.body), {"entries", "next"})

    def test_entries_bad_numbers_are_400(self):
        self.local()
        r = self.conforms(self.chat("GET", "/api/chat/entries?after=x"), "/api/chat/entries", "get", 400)
        self.error(r, 400, "after and limit are numbers")
        self.error(self.chat("GET", "/api/chat/entries?limit=1.5"), 400, "after and limit are numbers")

    def test_entries_write(self):
        self.local()
        r = self.conforms(self.chat("POST", "/api/chat/entries", {"text": "  hi  ", "ref": "task:1"}), "/api/chat/entries",
                          "post", 200, JSON_UTF8)
        self.assertEqual((r.body["entry"]["author"], r.body["entry"]["client"], r.body["entry"]["text"], r.body["entry"]["ref"]),
                         ("chris", "discord", "hi", "task:1"))
        r = self.chat("POST", "/api/chat/entries", {"kind": "question", "text": "why?"})
        self.assertEqual((r.status, r.body["entry"]["kind"]), (200, "question"))

    def test_entries_write_errors(self):
        self.local()
        r = self.conforms(self.chat("POST", "/api/chat/entries", {"kind": "message"}), "/api/chat/entries", "post", 400)
        self.error(r, 400, "bad request: 'text'")
        self.error(self.chat("POST", "/api/chat/entries", raw="{not json"), 400, starts="bad request:")
        self.error(self.chat("POST", "/api/chat/entries", {"text": "   "}), 400, "bad request: the text is empty")
        r = self.conforms(self.chat("POST", "/api/chat/entries", {"kind": "notification", "text": "x"}),
                          "/api/chat/entries", "post", 403)
        self.error(r, 403, "refused: discord may not write 'notification'")
        r = self.conforms(self.chat("POST", "/api/chat/entries", {"text": "x"}, token="reader"), "/api/chat/entries", "post", 403)
        self.error(r, 403, starts="refused:")

    def test_switch_off_refuses_with_the_state(self):
        self.local()
        conversation.set_switch("discord", False)
        for method, path in (("GET", "/api/chat/entries"), ("POST", "/api/chat/entries"), ("POST", "/api/chat/action"),
                             ("GET", "/api/chat/action")):
            r = self.conforms(self.chat(method, path, {"text": "x", "action": "state"} if method == "POST" else None),
                              path, method, 403)
            self.assertEqual(r.body["error"], "refused: Discord is switched off on the homelab page")
            self.assertEqual((r.body["discord"], r.body["discord_approvals"]), (False, False))

    def test_action_get_is_405(self):
        self.local()
        r = self.conforms(self.chat("GET", "/api/chat/action"), "/api/chat/action", "get", 405)
        self.error(r, 405, "POST only")

    def test_action_request_errors(self):
        self.local()
        for kwargs in ({"raw": "nope"}, {"body": {}}, {"body": []}):
            self.error(self.conforms(self.chat("POST", "/api/chat/action", **kwargs), "/api/chat/action", "post", 400),
                       400, starts="bad request:")
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "dance"}), "/api/chat/action", "post", 400)
        self.error(r, 400, "unknown action 'dance'")

    def test_action_forbidden_whatever_the_config(self):
        self.local()
        for action in ("ssh_key", "memory_edit", "merge_homelab"):
            r = self.conforms(self.chat("POST", "/api/chat/action", {"action": action}), "/api/chat/action", "post", 403)
            self.error(r, 403, f"refused: {action} cannot be done from a chat client")

    def test_action_reads(self):
        self.local()
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "state"}), "/api/chat/action", "post", 200, JSON_UTF8)
        self.assertEqual(r.body, {"tasks": []})
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "jobs"}), "/api/chat/action", "post", 200)
        self.assertEqual(list(r.body), ["jobs"])
        r = self.chat("POST", "/api/chat/action", {"action": "state"}, token="reader")
        self.assertEqual(r.status, 200)

    def test_action_task_and_the_daily_limit(self):
        self.local()
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "task", "repo": "auto", "text": "do it"}),
                          "/api/chat/action", "post", 200, JSON_UTF8)
        self.assertEqual(set(r.body), {"task", "url"})
        self.assertIsInstance(r.body["task"], int)
        self.assertEqual(storage.get(r.body["task"])["source"], "discord")
        self.error(self.chat("POST", "/api/chat/action", {"action": "task", "repo": "auto"}), 400,
                   "give a repo from the list (or 'auto') and a task text")
        self.error(self.chat("POST", "/api/chat/action", {"action": "task", "repo": "nope", "text": "x"}), 400,
                   "give a repo from the list (or 'auto') and a task text")
        self.assertEqual(self.chat("POST", "/api/chat/action", {"action": "task", "repo": "auto", "text": "two"}).status, 200)
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "task", "repo": "auto", "text": "three"}),
                          "/api/chat/action", "post", 429)
        self.error(r, 429, starts="daily limit reached: 2 task(s) per day may start from Discord")
        r = self.chat("POST", "/api/chat/action", {"action": "task", "repo": "auto", "text": "x"}, token="reader")
        self.error(self.conforms(r, "/api/chat/action", "post", 403), 403, starts="refused:")

    def test_action_answer_and_confirm_errors(self):
        self.local()
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "answer", "task": 999, "text": "x"}),
                          "/api/chat/action", "post", 404)
        self.error(r, 404, "no such task")
        tid = storage.new_task("homelab", "q")
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "answer", "task": tid, "text": "x"}),
                          "/api/chat/action", "post", 409)
        self.error(r, 409, f"task {tid} is not waiting for an answer")
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "confirm", "confirm": "nope"}),
                          "/api/chat/action", "post", 404)
        self.error(r, 404, "unknown confirm token")
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "run_job", "job": "nope"}),
                          "/api/chat/action", "post", 404)
        self.error(r, 404, "no runnable job 'nope'")

    def test_action_approval_in_two_steps(self):
        self.local()
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "approve", "brief": BID, "mode": "build"}),
                          "/api/chat/action", "post", 200)
        self.assertEqual({"confirm", "brief", "mode", "title", "expires_in"}, set(r.body))
        self.assertEqual(self.brief["status"], "draft")
        token = r.body["confirm"]
        r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "confirm", "confirm": token}),
                          "/api/chat/action", "post", 200)
        self.assertEqual((set(r.body), self.brief["status"]), ({"task", "url", "brief", "mode"}, "approved"))
        self.error(self.chat("POST", "/api/chat/action", {"action": "confirm", "confirm": token}), 410, starts="this confirmation")
        r = self.chat("POST", "/api/chat/action", {"action": "approve", "brief": BID})
        self.error(r, 400, "give a brief id and a mode: build or without-me")
        self.error(self.chat("POST", "/api/chat/action", {"action": "approve", "brief": "H-999", "mode": "build"}), 409,
                   "H-999 is not a draft brief")

    def test_action_failure_is_500_with_a_fixed_message(self):
        self.local()
        orig, chat.tasks_state = chat.tasks_state, lambda client: 1 / 0
        try:
            r = self.conforms(self.chat("POST", "/api/chat/action", {"action": "state"}), "/api/chat/action", "post", 500)
        finally:
            chat.tasks_state = orig
        self.error(r, 500, "the action failed; see the inbox journal")


# =====================================================================================================================
class KittDoor(Contract):
    def test_state_follows_the_switch(self):
        self.local()
        r = self.conforms(self.kitt("GET", "/api/clients/kitt"), "/api/clients/kitt", "get", 200, JSON_UTF8)
        self.assertEqual(r.body, {"enabled": True, "permissions": {"chat": True, "jobs": True, "approvals": True,
                                                                   "usage": True, "logs": True}})
        conversation.set_switch("kitt", False)
        r = self.conforms(self.kitt("GET", "/api/clients/kitt"), "/api/clients/kitt", "get", 200)
        self.assertEqual((r.body["enabled"], any(r.body["permissions"].values())), (False, False))

    def test_identity_is_refused_with_403_and_a_reason(self):
        self.local()
        r = self.conforms(self.kitt("GET", "/api/clients/kitt", headers={"X-Homelab-Client": "other"}),
                          "/api/clients/kitt", "get", 403)
        self.error(r, 403, "refused: not the kitt client")
        kitt.peer_uid = lambda addr, port: 1000
        self.error(self.conforms(self.kitt("GET", "/api/clients/kitt"), "/api/clients/kitt", "get", 403), 403,
                   "refused: not from kitt's service account")
        kitt.peer_uid = lambda addr, port: 4242
        r = self.kitt("GET", "/api/clients/kitt", headers={"Tailscale-User-Login": "other@example.com"})
        self.error(r, 403, "refused: not Chris at the app")
        self.error(self.kitt("GET", "/api/clients/kitt", headers={"Tailscale-User-Login": ""}), 403,
                   "refused: not Chris at the app")

    def test_without_the_client_header_the_page_check_answers(self):
        self.local()
        r = self.call("GET", "/api/jobs")
        self.assertEqual((r.status, r.ctype), (403, JSON_UTF8))
        self.assertEqual(r.body, {"error": "Only from your own devices on the tailnet."})

    def test_unknown_calls_are_404_with_a_fixed_body(self):
        self.local()
        for method, path in (("POST", "/api/memory"), ("POST", "/api/jobs/x/delete"), ("GET", "/api/jobs/x/run"),
                             ("POST", "/api/health"), ("GET", "/api/approvals/x"), ("GET", "/api/nothing")):
            r = self.kitt(method, path, {} if method == "POST" else None)
            self.error(r, 404, "not a call kitt may make")

    def test_bad_post_bodies_are_400(self):
        self.local()
        self.error(self.kitt("POST", "/api/conversation", raw="{nope"), 400, starts="bad request:")
        self.error(self.kitt("POST", "/api/conversation", raw="[1]"), 400, starts="bad request:")

    def test_conversation(self):
        self.local()
        conversation.append("chris", "discord", "message", "from discord")
        r = self.conforms(self.kitt("POST", "/api/conversation", {"kind": "task", "text": "do a thing"}),
                          "/api/conversation", "post", 200, JSON_UTF8)
        self.assertEqual((r.body["entry"]["client"], r.body["entry"]["kind"]), ("kitt", "task"))
        kitt.answer_question = lambda q: None  # no search service for the background answer
        r = self.conforms(self.kitt("POST", "/api/conversation", {"kind": "question", "text": "what?"}),
                          "/api/conversation", "post", 200)
        self.assertEqual(r.body["entry"]["kind"], "question")
        r = self.conforms(self.kitt("GET", "/api/conversation?after=0"), "/api/conversation", "get", 200, JSON_UTF8)
        self.assertEqual([e["client"] for e in r.body["entries"]], ["discord", "kitt", "kitt"])
        self.assertEqual(r.body["cursor"], r.body["entries"][-1]["id"])
        r = self.kitt("GET", f"/api/conversation?after={r.body['cursor']}")
        self.assertEqual(r.body, {"entries": [], "cursor": r.body["cursor"]})
        self.assertEqual(self.kitt("GET", "/api/conversation?after=abc").status, 200)  # a non-number counts as 0

    def test_conversation_errors(self):
        self.local()
        r = self.conforms(self.kitt("POST", "/api/conversation", {"kind": "poem", "text": "x"}), "/api/conversation", "post", 400)
        self.error(r, 400, "send a question or a task with a text")
        self.error(self.kitt("POST", "/api/conversation", {"kind": "task", "text": " "}), 400,
                   "send a question or a task with a text")
        os.environ["CLIENTS_JSON"] = json.dumps({"clients": {"kitt": {"permissions": ["read"], "tasks_per_day": 1}}})
        r = self.conforms(self.kitt("POST", "/api/conversation", {"kind": "question", "text": "x"}), "/api/conversation",
                          "post", 403)
        self.error(r, 403, "kitt may not ask")
        self.error(self.conforms(self.kitt("POST", "/api/conversation", {"kind": "task", "text": "x"}),
                                 "/api/conversation", "post", 403), 403, starts="refused:")
        os.environ["CLIENTS_JSON"] = json.dumps({"clients": {"kitt": {"permissions": KITT_ALL, "tasks_per_day": 1}}})
        self.assertEqual(self.kitt("POST", "/api/conversation", {"kind": "task", "text": "one"}).status, 200)
        self.error(self.conforms(self.kitt("POST", "/api/conversation", {"kind": "task", "text": "two"}),
                                 "/api/conversation", "post", 429), 429, starts="daily limit reached")

    def test_switch_off_refuses_the_handlers(self):
        self.local()
        conversation.set_switch("kitt", False)
        for method, path in (("GET", "/api/conversation"), ("GET", "/api/jobs"), ("GET", "/api/logs"), ("GET", "/api/health"),
                             ("GET", "/api/approvals")):
            r = self.conforms(self.kitt(method, path), path, method, 403)
            self.assertEqual(r.body, {"error": "Kitt is switched off on the homelab page"})

    def test_logs(self):
        self.local()
        tid = storage.new_task("homelab", "first line\nsecond")
        storage.update(tid, status="running", started=storage.now())
        helpers.log_line(f"t{tid}", "info", "started")
        helpers.log_line(f"t{tid}", "claude", "working")
        r = self.conforms(self.kitt("GET", "/api/logs"), "/api/logs", "get", 200, JSON_UTF8)
        self.assertEqual([(l["id"], l["kind"], l["state"]) for l in r.body["logs"]], [(f"t{tid}", "task", "running")])
        r = self.conforms(self.kitt("GET", f"/api/logs/t{tid}?from=1"), "/api/logs/{key}", "get", 200, JSON_UTF8)
        self.assertEqual((len(r.body["lines"]), r.body["next"], r.body["state"]), (1, 2, "running"))
        self.assertRegex(r.body["lines"][0], r"^\d\d:\d\d:\d\d \[claude\] working$")
        for key in ("t99999", "r99999", "x1", "..%2Fetc"):
            self.error(self.conforms(self.kitt("GET", f"/api/logs/{key}"), "/api/logs/{key}", "get", 404), 404, "no such log")

    def test_approvals(self):
        self.local()
        tid = storage.new_task("homelab", "q")
        storage.update(tid, status="waiting", question="Which one?")
        r = self.conforms(self.kitt("GET", "/api/approvals"), "/api/approvals", "get", 200, JSON_UTF8)
        self.assertEqual([(i["id"], i["kind"]) for i in r.body["items"]], [(BID, "brief"), (f"question:{tid}", "question")])
        r = self.conforms(self.kitt("POST", f"/api/approvals/{BID}", {"decision": "approve_unattended"}),
                          "/api/approvals/{item}", "post", 200, JSON_UTF8)
        self.assertEqual((r.body["brief"], r.body["mode"], self.brief["status"]), (BID, "without-me", "approved"))

    def test_approval_errors(self):
        self.local()
        r = self.conforms(self.kitt("POST", f"/api/approvals/{BID}", {"decision": "deny"}), "/api/approvals/{item}", "post", 400)
        self.error(r, 400, "kitt approves with approve or approve_unattended; denying is not offered")
        r = self.conforms(self.kitt("POST", "/api/approvals/question:3", {"decision": "approve"}), "/api/approvals/{item}",
                          "post", 409)
        self.error(r, 409, "only a brief is approved; answer a question from Claude in the thread")
        os.environ["CLIENTS_JSON"] = json.dumps({"clients": {"kitt": {"permissions": ["read"]}}})
        r = self.conforms(self.kitt("POST", f"/api/approvals/{BID}", {"decision": "approve"}), "/api/approvals/{item}", "post", 403)
        self.error(r, 403, "approvals from kitt are off")
        self.assertEqual(self.brief["status"], "draft")

    def test_jobs(self):
        self.local()
        from homelab_inbox.jobs import Cron
        config.JOBS["nightly"] = {"name": "nightly", "when": "daily 7:00", "schedule": "0 7 * * *", "model": "local",
                                  "deliver": "page", "paused": False, "text": "---\nkind: digest\n---\nx",
                                  "cron": Cron("0 7 * * *")}
        saved, orig = [], kitt.save_job
        kitt.save_job = lambda name, text, msg: saved.append((name, msg))
        try:
            r = self.conforms(self.kitt("GET", "/api/jobs"), "/api/jobs", "get", 200, JSON_UTF8)
            self.assertEqual([(j["name"], j["paused"], j["lastResult"], j["error"]) for j in r.body["jobs"]],
                             [("nightly", False, None, None)])
            r = self.conforms(self.kitt("POST", "/api/jobs/nightly/pause", {}), "/api/jobs/{name}/{action}", "post", 200)
            self.assertEqual((r.body, saved), ({"job": "nightly", "paused": True}, [("nightly", "jobs: nightly paused (from kitt)")]))
            r = self.kitt("POST", "/api/jobs/nightly/resume", {})
            self.assertEqual(r.body, {"job": "nightly", "paused": False})
            r = self.conforms(self.kitt("POST", "/api/jobs/nope/run", {}), "/api/jobs/{name}/{action}", "post", 404)
            self.error(r, 404, "no usable job 'nope'")
            os.environ["CLIENTS_JSON"] = json.dumps({"clients": {"kitt": {"permissions": ["read"]}}})
            r = self.conforms(self.kitt("POST", "/api/jobs/nightly/pause", {}), "/api/jobs/{name}/{action}", "post", 403)
            self.error(r, 403, "kitt may not run job")
        finally:
            kitt.save_job = orig
            config.JOBS.pop("nightly")

    def test_health(self):
        self.local()
        r = self.conforms(self.kitt("GET", "/api/health"), "/api/health", "get", 200, JSON_UTF8)
        self.assertEqual(r.body, {"checks": [{"name": "disk", "status": "warn", "text": ""},
                                             {"name": "gpu", "status": "ok", "text": "fine"}]})


# =====================================================================================================================
class PageMachineEndpoints(Contract):
    """The JSON endpoints of the homelab page: they need page access (the tailnet owner) and, for POST, Origin + CSRF."""

    def test_the_box_itself_gets_a_json_403(self):
        self.local()
        for method, path in (("GET", "/api/live"), ("GET", "/api/log/t1"), ("GET", "/api/forge"),
                             ("GET", "/api/search/filters"), ("GET", "/api/search/results?q=x")):
            r = self.call(method, path)
            self.assertEqual((r.status, r.ctype), (403, JSON_UTF8), path)
            self.assertEqual(r.body, {"error": "Only from your own devices on the tailnet."})
            self.conforms(r, path.split("?")[0].replace("/t1", "/{key}"), method)
        r = self.call("POST", "/api/forge/start", headers={"Origin": "http://x"}, raw="csrf=1")
        self.assertEqual((r.status, r.ctype), (403, JSON_UTF8))

    def test_live(self):
        self.local()
        self.allow_page()
        r = self.conforms(self.call("GET", "/api/live"), "/api/live", "get", 200, JSON_PLAIN)
        self.assertEqual(set(r.body), {"items", "queued", "activity"})
        self.assertEqual([i["key"] for i in r.body["items"]], ["f1"])  # forge is running in the fake
        self.assertEqual(r.body["items"][0]["title"], "Forge")
        tid = storage.new_task("homelab", "running now")
        storage.update(tid, status="running", started=storage.now())
        storage.new_task("homelab", "queued")
        r = self.call("GET", "/api/live")
        self.assertEqual(([i["key"] for i in r.body["items"]], r.body["queued"]), ([f"t{tid}", "f1"], 1))
        self.assertEqual(violations({"$ref": "#/components/schemas/LiveItem"}, r.body["items"][0]) if SPEC else [], [])

    def test_live_without_forge(self):
        self.local()
        self.allow_page()
        self.dead_upstreams()
        r = self.conforms(self.call("GET", "/api/live"), "/api/live", "get", 200)
        self.assertEqual(r.body["items"], [])

    def test_log_of_a_task(self):
        self.local()
        self.allow_page()
        tid = storage.new_task("homelab", "x")
        storage.update(tid, status="running")
        helpers.log_line(f"t{tid}", "info", "one")
        helpers.log_line(f"t{tid}", "claude", "two")
        r = self.conforms(self.call("GET", f"/api/log/t{tid}"), "/api/log/{key}", "get", 200, JSON_PLAIN)
        self.assertEqual((set(r.body), [l["text"] for l in r.body["lines"]], r.body["next"], r.body["done"]),
                         ({"lines", "next", "done"}, ["one", "two"], 2, False))
        self.assertEqual(set(r.body["lines"][0]), {"at", "kind", "text"})
        r = self.call("GET", f"/api/log/t{tid}?from=1")
        self.assertEqual(([l["text"] for l in r.body["lines"]], r.body["next"]), (["two"], 2))
        self.assertEqual(self.call("GET", f"/api/log/t{tid}?from=-5").body["next"], 2)  # a negative offset is 0
        storage.update(tid, status="done")
        self.assertTrue(self.call("GET", f"/api/log/t{tid}").body["done"])

    def test_log_of_a_run_and_of_nothing(self):
        self.local()
        self.allow_page()
        r = self.conforms(self.call("GET", "/api/log/t424242"), "/api/log/{key}", "get", 200)
        self.assertEqual(r.body, {"lines": [], "next": 0, "done": True})
        self.assertEqual(self.call("GET", "/api/log/r424242").body, {"lines": [], "next": 0, "done": True})
        for path in ("/api/log/t", "/api/log/x1", "/api/log/t1/extra"):
            r = self.conforms(self.call("GET", path), "/api/log/{key}", "get", 404)
            self.assertEqual((r.ctype, r.body), (JSON_UTF8, {"error": "Not found."}))

    def test_log_of_forge(self):
        self.local()
        self.allow_page()
        r = self.conforms(self.call("GET", "/api/log/f1?from=3"), "/api/log/{key}", "get", 200, JSON_UTF8)
        self.assertEqual(r.body["next"], 2)
        self.assertTrue(r.body["done"])
        self.assertEqual([(l["kind"], l["text"]) for l in r.body["lines"]], [("tool", "Bash: ls"), ("info", "plain line")])
        self.assertIn(("GET", "/api/log?from=3", None), self.up["seen"])
        self.up["forge_log"] = (500, {})
        r = self.conforms(self.call("GET", "/api/log/f1"), "/api/log/{key}", "get", 502)
        self.assertEqual(r.body, {"error": forge.UNREACHABLE})

    def test_forge_state(self):
        self.local()
        self.allow_page()
        r = self.conforms(self.call("GET", "/api/forge"), "/api/forge", "get", 200, JSON_UTF8)
        self.assertEqual(r.body, {"running": True, "step": 2, "project": "demo", "activity": "Edit: a.py",
                                  "started": "2026-11-16T10:00:00+00:00", "paused": False, "pause_reason": None,
                                  "paused_until": None})
        self.dead_upstreams()
        r = self.conforms(self.call("GET", "/api/forge"), "/api/forge", "get", 502)
        self.assertEqual(r.body, {"error": "Forge is not reachable (is homelab-app-forge running?)."})

    def test_forge_control(self):
        self.local()
        self.allow_page()
        r = self.conforms(self.form("POST", "/api/forge/stop"), "/api/forge/{action}", "post", 200, JSON_UTF8)
        self.assertEqual(r.body["running"], True)
        self.assertIn(("POST", "/api/stop", {}), self.up["seen"])
        self.up["control"] = (409, {"error": "busy"})
        r = self.conforms(self.form("POST", "/api/forge/start"), "/api/forge/{action}", "post", 502)
        self.assertEqual(r.body, {"error": "Forge refused start (HTTP 409): busy"})
        self.dead_upstreams()
        r = self.conforms(self.form("POST", "/api/forge/start"), "/api/forge/{action}", "post", 502)
        self.assertIn("Forge refused start", r.body["error"])

    def test_forge_control_checks_origin_csrf_and_action(self):
        self.local()
        self.allow_page()
        for r in (self.form("POST", "/api/forge/start", origin=False), self.form("POST", "/api/forge/start", csrf=False)):
            self.conforms(r, "/api/forge/{action}", "post", 403)
            self.assertEqual(r.ctype, JSON_UTF8)
        self.assertEqual(self.form("POST", "/api/forge/start", origin=False).body, {"error": "Wrong origin."})
        self.assertIn("X-CSRF-Token", self.form("POST", "/api/forge/start", csrf=False).body["error"])
        self.assertEqual(self.form("POST", "/api/forge/restart").body, {"error": "Not found."})
        self.assertEqual(self.form("POST", "/api/forge/restart").status, 404)
        self.assertEqual([s for s in self.up["seen"] if s[0] == "POST"], [])
        self.assertEqual(self.call("GET", "/api/forge/start").status, 404)

    def test_search_filters_and_results_pass_through(self):
        self.local()
        self.allow_page()
        r = self.conforms(self.call("GET", "/api/search/filters"), "/api/search/filters", "get", 200, JSON_UTF8)
        self.assertEqual(r.body, {"projects": ["homelab"], "types": ["doc"]})
        self.up["results"] = (200, {"query": "ports", "results": [{"path": "docs/inbox.md"}]})
        r = self.conforms(self.call("GET", "/api/search/results?q=ports&project=homelab&type=&junk=1"),
                          "/api/search/results", "get", 200, JSON_UTF8)
        self.assertEqual(r.body["results"], [{"path": "docs/inbox.md"}])
        self.assertIn(("GET", "/v1/search?q=ports&project=homelab", None), self.up["seen"])
        self.assertEqual(self.call("GET", "/api/search/results").status, 200)
        self.assertIn(("GET", "/v1/search?q=", None), self.up["seen"])
        self.up["filters"] = (503, {"error": {"message": "index is locked", "request_id": "abc123"}})
        r = self.call("GET", "/api/search/filters")
        self.assertEqual((r.status, r.body["error"]), (503, "index is locked"))

    def test_search_upstream_trouble(self):
        self.local()
        self.allow_page()
        self.dead_upstreams()
        for path in ("/api/search/filters", "/api/search/results?q=x"):
            r = self.conforms(self.call("GET", path), path.split("?")[0], "get", 502, JSON_UTF8)
            self.assertTrue(r.body["error"].startswith("The search service is unreachable: "), r)

    def test_search_ask(self):
        self.local()
        self.allow_page()
        self.up["ask"] = (200, {"found": True, "answer": "Port 8105.", "citations": [{"n": 1, "path": "docs/inbox.md",
                                                                                      "location": "top"}]})
        r = self.conforms(self.form("POST", "/api/search/ask", {"question": "  which port?  ", "project": "homelab", "hard": "on"}),
                          "/api/search/ask", "post", 200, JSON_UTF8)
        self.assertEqual(r.body["answer"], "Port 8105.")
        self.assertIn(("POST", "/v1/ask", {"question": "which port?", "project": "homelab", "hard": True}), self.up["seen"])
        r = self.conforms(self.form("POST", "/api/search/ask", {"question": "   "}), "/api/search/ask", "post", 400)
        self.assertEqual(r.body, {"error": "Type a question first."})
        for r in (self.form("POST", "/api/search/ask", {"question": "x"}, origin=False),
                  self.form("POST", "/api/search/ask", {"question": "x"}, csrf=False)):
            self.conforms(r, "/api/search/ask", "post", 403)

    def test_search_feedback(self):
        self.local()
        self.allow_page()
        r = self.conforms(self.form("POST", "/api/search/feedback", {"request_id": "req_12345", "note": "n" * 2000}),
                          "/api/search/feedback", "post", 200, JSON_UTF8)
        self.assertEqual(r.body, {"ok": True})
        self.assertIn(("POST", "/v1/feedback", {"request_id": "req_12345", "note": "n" * 1000}), self.up["seen"])
        for rid in ("", "short", "bad id!", "x" * 65):
            r = self.conforms(self.form("POST", "/api/search/feedback", {"request_id": rid}), "/api/search/feedback", "post", 400)
            self.assertEqual(r.body, {"error": "That answer has no request id to mark."})
        self.dead_upstreams()
        r = self.conforms(self.form("POST", "/api/search/feedback", {"request_id": "req_12345"}), "/api/search/feedback",
                          "post", 502)
        self.assertTrue(r.body["error"].startswith("The search service is unreachable: "))


class Spec(unittest.TestCase):
    """The checks above are only as strong as the schema check: prove it rejects a wrong body."""

    def test_the_schema_check_rejects_a_wrong_shape(self):
        if SPEC is None:
            self.skipTest("PyYAML is not installed")
        state = {"$ref": "#/components/schemas/ChatState"}
        self.assertEqual(violations(state, {"discord": True, "discord_approvals": False}), [])
        self.assertTrue(violations(state, {"discord": True}))
        self.assertTrue(violations(state, {"discord": "yes", "discord_approvals": False}))
        self.assertTrue(violations({"$ref": "#/components/schemas/KittEntry"},
                                   {"id": 1, "at": "x", "author": "a", "client": "alien", "kind": "task", "text": "t"}))


if __name__ == "__main__":
    unittest.main()
