"""The one conversation between Chris and the box (H-016): every message, answer, question, notification and approval
request with its time, author and client. Stored in the inbox database (survives a restart), scrubbed with the memory's
secret filter, read from a cursor, and open to clients only with the permissions config/clients.json gives them."""
import hmac
import json
import os
from pathlib import Path

from .config import ROOT, SECRET_RE
from .storage import db, log, now


AUTHORS = ("chris", "claude", "box", "system")  # box = notifications, system = the inbox itself


KINDS = ("message", "question", "answer", "notification", "approval_request", "approval")


PERMISSIONS = ("read", "write", "ask", "start_task", "answer", "approve", "run_job")


# What a client may append, and the permission that takes: a client always writes as Chris.
CLIENT_KINDS = {"message": "write", "question": "ask", "answer": "answer"}


MAX_TEXT = 20000


REDACTED = "[removed: looks like a secret]"


def scrub(text):
    """The same filter the memory uses (config.SECRET_RE), applied by replacing the match: the rest stays readable."""
    return SECRET_RE.sub(REDACTED, text or "")


def clients():
    """name -> set of permissions, read from config/clients.json on every call (an unknown permission is dropped)."""
    f = ROOT / "config" / "clients.json"
    try:
        data = json.loads(os.environ.get("CLIENTS_JSON") or f.read_text())
    except (OSError, ValueError) as e:
        log(f"conversation: {f}: {e}; no client has permissions")
        return {}
    return {name: {p for p in c.get("permissions", []) if p in PERMISSIONS}
            for name, c in (data.get("clients") or {}).items() if isinstance(c, dict)}


def _init(conn):
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS conversation (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL,
            author TEXT NOT NULL, client TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, link TEXT, ref TEXT);
        CREATE TABLE IF NOT EXISTS conversation_refusals (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL,
            client TEXT NOT NULL, action TEXT NOT NULL, why TEXT NOT NULL);""")


def _row(r):
    return {k: r[k] for k in ("id", "time", "author", "client", "kind", "text", "link", "ref")}


def append(author, client, kind, text, link=None, ref=None):
    """Add an entry (internal callers: the inbox itself). Returns the entry; the text is scrubbed first."""
    if author not in AUTHORS or kind not in KINDS:
        raise ValueError(f"unknown author or kind: {author}/{kind}")
    text = scrub(str(text).strip())[:MAX_TEXT]
    if not text:
        raise ValueError("the text is empty")
    with db() as c:
        _init(c)
        cur = c.execute("INSERT INTO conversation (time, author, client, kind, text, link, ref) VALUES (?,?,?,?,?,?,?)",
                        (now(), author, client, kind, text, scrub(link) if link else None, ref))
        return _row(c.execute("SELECT * FROM conversation WHERE id=?", (cur.lastrowid,)).fetchone())


def note(author, client, kind, text, link=None, ref=None):
    """append() for callers that must never fail because of the conversation (a notification, a task round)."""
    try:
        return append(author, client, kind, text, link, ref)
    except Exception as e:  # noqa: BLE001
        log(f"conversation: not stored ({e}): {kind}")


def read(after=0, limit=200):
    """Entries with an id above `after` (the cursor: pass the last id you saw; 0 = from the start), oldest first."""
    with db() as c:
        _init(c)
        rows = c.execute("SELECT * FROM conversation WHERE id>? ORDER BY id LIMIT ?",
                         (max(0, int(after)), max(1, min(int(limit), 1000)))).fetchall()
    return [_row(r) for r in rows]


def refusals(limit=100):
    with db() as c:
        _init(c)
        return [dict(r) for r in c.execute("SELECT * FROM conversation_refusals ORDER BY id DESC LIMIT ?",
                                           (limit,)).fetchall()]


SWITCHES = ("discord", "discord_approvals")  # on the homelab page; stored in the kv table, read on every request


def switch(name):
    """True unless the switch was turned off. Read from the database each time: no restart needed."""
    with db() as c:
        _init(c)
        row = c.execute("SELECT value FROM kv WHERE key=?", (f"switch:{name}",)).fetchone()
    return not (row and row["value"] == "off")


def set_switch(name, on):
    if name not in SWITCHES:
        raise ValueError(f"unknown switch: {name}")
    with db() as c:
        c.execute("INSERT OR REPLACE INTO kv (key, value) VALUES (?,?)", (f"switch:{name}", "on" if on else "off"))


def state():
    """What clients may know: whether Discord is on, and whether approvals from Discord are on (off when Discord is)."""
    on = switch("discord")
    return {"discord": on, "discord_approvals": on and switch("discord_approvals")}


def switched_off(client, action=None):
    """Why a switch blocks this client (and action), or None. Only the Discord client is behind the switches."""
    if client != "discord":
        return None
    if not switch("discord"):
        return "Discord is switched off on the homelab page"
    if action == "approve" and not switch("discord_approvals"):
        return "approvals from Discord are off"
    return None


def allowed(client, action):
    """True when the client has the permission; otherwise False, and the refusal is logged (journal and table)."""
    if action in PERMISSIONS and action in clients().get(client, set()):
        why = switched_off(client, action)
        if not why:
            return True
    else:
        why = "unknown client" if client not in clients() else f"no permission '{action}'"
    log(f"conversation: refused {client}: {action} ({why})")
    with db() as c:
        _init(c)
        c.execute("INSERT INTO conversation_refusals (time, client, action, why) VALUES (?,?,?,?)",
                  (now(), str(client)[:40], str(action)[:40], why))
    return False


def client_read(client, after=0, limit=200):
    """A client reads from a cursor. None = refused."""
    return read(after, limit) if allowed(client, "read") else None


def client_append(client, kind, text, ref=None):
    """A client writes, as Chris. None = refused (no permission for this kind, or an unknown kind)."""
    perm = CLIENT_KINDS.get(kind)
    if not perm:
        allowed(client, f"append:{kind}"[:40])  # logs the refusal: clients cannot write notifications or Claude's text
        return None
    return append("chris", client, kind, text, ref=ref) if allowed(client, perm) else None


def token_dir():
    return Path(os.environ.get("CHAT_TOKEN_DIR", "/etc/homelab"))


def authenticate(header):
    """The client a `Authorization: Bearer <token>` belongs to (token in chat-<name>-token), or None (logged)."""
    given = (header or "").removeprefix("Bearer ").strip()
    for name in clients():
        try:
            want = (token_dir() / f"chat-{name}-token").read_text().strip()
        except OSError:
            continue
        if want and hmac.compare_digest(given, want):
            return name
    allowed("unknown", "authenticate")
    return None
