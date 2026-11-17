"""An in-process stand-in for the Java conversation store (H-024 M4), for the Python tests that only need *a* store behind
`conversation.transport`: the same routes and answers as backend/src/main/java/dev/homelab/inbox/conversation/, kept in
SQLite in a temporary file (it is the code the Python inbox had before the move). The Java side has its own tests
(ConversationStoreTest) and tests/test_inbox_m4_parity.py compares the two. `install()` swaps the transport."""
import json
import re
import sqlite3
import tempfile
import urllib.parse
from datetime import datetime, timezone
from pathlib import Path

SECRET_RE = re.compile(
    r"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_\w{20,}|sk-ant-[\w-]{10,}|sk-[A-Za-z0-9]{20,}|xox[abprs]-[\w-]{10,}"
    r"|AKIA[0-9A-Z]{16}|tskey-[\w-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|eyJ[\w-]{10,}\.[\w-]{10,}\.[\w-]{5,}"
    r"|(?i:bearer)\s+[\w.~+/-]{16,}=*|(?i:token|password|passwd|secret|api[_-]?key|wachtwoord)\s*[:=]\s*\S{8,}")
REDACTED = "[removed: looks like a secret]"
AUTHORS = ("chris", "claude", "box", "system")
KINDS = ("message", "question", "answer", "notification", "approval_request", "approval")
SWITCHES = ("discord", "discord_approvals", "kitt")
CLIENT_KINDS = {"message": "write", "question": "ask", "answer": "answer"}
FORBIDDEN = ("ssh_key", "memory_edit", "merge_homelab")
PERMISSIONS = ("read", "write", "ask", "start_task", "answer", "approve", "run_job", "research")


def _now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class FakeBackend:
    def __init__(self, clients):
        """`clients`: a callable returning name -> set of permissions (conversation.clients)."""
        self.clients = clients
        self.down = False  # True: every call raises Unavailable, like a backend that does not answer
        self.calls = []
        self.db = sqlite3.connect(Path(tempfile.mkdtemp()) / "fake.db", check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            CREATE TABLE conversation (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL, author TEXT NOT NULL,
                client TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, link TEXT, ref TEXT);
            CREATE TABLE conversation_refusals (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL,
                client TEXT NOT NULL, action TEXT NOT NULL, why TEXT NOT NULL);
            CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT);""")

    def reset(self):
        for t in ("conversation", "conversation_refusals", "kv"):
            self.db.execute(f"DELETE FROM {t}")
        self.db.commit()
        self.down = False

    # -- the store ----------------------------------------------------------------------------------------------------
    def append(self, author, client, kind, text, link=None, ref=None):
        if author not in AUTHORS or kind not in KINDS:
            raise ValueError(f"unknown author or kind: {author}/{kind}")
        text = SECRET_RE.sub(REDACTED, str(text).strip())[:20000]
        if not text:
            raise ValueError("the text is empty")
        cur = self.db.execute("INSERT INTO conversation (time, author, client, kind, text, link, ref) VALUES (?,?,?,?,?,?,?)",
                              (_now(), author, client, kind, text, SECRET_RE.sub(REDACTED, link) if link else None, ref))
        self.db.commit()
        return self.row(cur.lastrowid)

    def row(self, i):
        r = self.db.execute("SELECT * FROM conversation WHERE id=?", (i,)).fetchone()
        return {k: r[k] for k in ("id", "time", "author", "client", "kind", "text", "link", "ref")}

    def read(self, after=0, limit=200):
        rows = self.db.execute("SELECT * FROM conversation WHERE id>? ORDER BY id LIMIT ?",
                               (max(0, int(after)), max(1, min(int(limit), 1000)))).fetchall()
        return [{k: r[k] for k in ("id", "time", "author", "client", "kind", "text", "link", "ref")} for r in rows]

    def switch(self, name):
        row = self.db.execute("SELECT value FROM kv WHERE key=?", (f"switch:{name}",)).fetchone()
        return not (row and row["value"] == "off")

    def set_switch(self, name, on):
        self.db.execute("INSERT OR REPLACE INTO kv (key, value) VALUES (?,?)", (f"switch:{name}", "on" if on else "off"))
        self.db.commit()

    def state(self):
        on = self.switch("discord")
        return {"discord": on, "discord_approvals": on and self.switch("discord_approvals")}

    def switched_off(self, client, action=None):
        if client == "kitt":
            return None if self.switch("kitt") else "Kitt is switched off on the homelab page"
        if client != "discord":
            return None
        if not self.switch("discord"):
            return "Discord is switched off on the homelab page"
        if action == "approve" and not self.switch("discord_approvals"):
            return "approvals from Discord are off"
        return None

    def allowed(self, client, action):
        have = self.clients()
        if action in FORBIDDEN:
            why = "chat clients can never do this"
        elif action in PERMISSIONS and action in have.get(client, set()):
            why = self.switched_off(client, action)
            if not why:
                return True
        else:
            why = "unknown client" if client not in have else f"no permission '{action}'"
        self.db.execute("INSERT INTO conversation_refusals (time, client, action, why) VALUES (?,?,?,?)",
                        (_now(), str(client)[:40], str(action)[:40], why))
        self.db.commit()
        return False

    def refusals(self, limit=100):
        return [dict(r) for r in self.db.execute("SELECT * FROM conversation_refusals ORDER BY id DESC LIMIT ?",
                                                 (limit,)).fetchall()]

    # -- the routes ---------------------------------------------------------------------------------------------------
    def __call__(self, method, path, data=None, client=None):
        """The transport signature of conversation.transport: (status, body bytes)."""
        self.calls.append((method, path, client))
        if self.down:
            from homelab_inbox import conversation
            raise conversation.Unavailable("fake backend is down")
        url = urllib.parse.urlsplit(path)
        q = urllib.parse.parse_qs(url.query)
        status, body = self.route(method, url.path, q, data, client)
        return status, json.dumps(body).encode()

    def route(self, method, path, q, data, client):
        def num(key, default):
            return int((q.get(key) or [str(default)])[0])

        def parse():
            try:
                b = json.loads(data or b"")
            except ValueError:
                return None
            return b if isinstance(b, dict) else None
        if path == "/api/chat/state":
            if not client:
                return 401, {"error": "unknown client or token"}
            return (200, self.state()) if method == "GET" else (405, {"error": "GET only"})
        if path == "/api/chat/entries":
            if not client:
                return 401, {"error": "unknown client or token"}
            if why := self.switched_off(client):
                return 403, {"error": f"refused: {why}", **self.state()}
            if method == "GET":
                try:
                    after, limit = num("after", 0), num("limit", 200)
                except ValueError:
                    return 400, {"error": "after and limit are numbers"}
                if not self.allowed(client, "read"):
                    return 403, {"error": "refused: no permission 'read'"}
                entries = self.read(after, limit)
                return 200, {"entries": entries, "next": entries[-1]["id"] if entries else after}
            try:
                body = json.loads(data)
                kind, text, ref = str(body.get("kind", "message")), str(body["text"]), body.get("ref")
                perm = CLIENT_KINDS.get(kind)
                if not perm:
                    self.allowed(client, f"append:{kind}"[:40])
                    return 403, {"error": f"refused: {client} may not write '{kind}'"}
                if not self.allowed(client, perm):
                    return 403, {"error": f"refused: {client} may not write '{kind}'"}
                return 200, {"entry": self.append("chris", client, kind, text, ref=str(ref)[:80] if ref else None)}
            except (ValueError, KeyError, TypeError, AttributeError) as e:
                return 400, {"error": f"bad request: {e}"}
        if path == "/internal/conversation/append":
            b = parse()
            if b is None:
                return 400, {"error": "bad request: not a JSON object"}
            try:
                return 200, {"entry": self.append(b.get("author", ""), b.get("client", ""), b.get("kind", ""),
                                                  b.get("text", ""), b.get("link"), b.get("ref"))}
            except ValueError as e:
                return 400, {"error": str(e)}
        if path == "/internal/conversation/entries":
            return 200, {"entries": self.read(num("after", 0), num("limit", 200))}
        if path == "/internal/conversation/refusals":
            return 200, {"refusals": self.refusals(num("limit", 100))}
        if path == "/internal/conversation/allowed":
            b = parse() or {}
            return 200, {"allowed": self.allowed(b.get("client", ""), b.get("action", ""))}
        if path.startswith("/internal/conversation/switch/"):
            name = path.rsplit("/", 1)[1]
            if name not in SWITCHES:
                return 400, {"error": f"unknown switch: {name}"}
            if method == "GET":
                return 200, {"on": self.switch(name)}
            b = parse()
            if b is None or not isinstance(b.get("on"), bool):
                return 400, {"error": 'bad request: {"on": true|false}'}
            self.set_switch(name, b["on"])
            return 200, {"on": b["on"]}
        return 404, {"error": "Not found."}


def install(conversation):
    """Point `conversation` at a fake store (one per process: every test module shares the same `conversation`); returns it."""
    if isinstance(conversation.transport, FakeBackend):
        return conversation.transport
    fake = FakeBackend(lambda: conversation.clients())  # looked up on each call: tests patch it
    conversation.transport = fake
    return fake
