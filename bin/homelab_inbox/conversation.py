"""The one conversation between Chris and the box (H-016): every message, answer, question, notification and approval
request with its time, author and client, read from a cursor and open to clients only with the permissions
config/clients.json gives them.

Since H-024 M4 the Java backend owns the store (PostgreSQL: the conversation and refusal tables and the switch keys); it
is the only writer. This module is its client on loopback (/internal/conversation/*, with the facade secret), keeps the
same function names the rest of the inbox already calls, and still does the bearer-token check of the chat door
(authenticate) until K7. When the backend cannot be reached nothing is written locally: note() logs and gives up,
switch() counts a switch as on, allowed() says no (fail closed), the others raise."""
import hmac
import json
import os
import urllib.parse
from pathlib import Path

from . import facade
from .config import ROOT
from .storage import log


AUTHORS = ("chris", "claude", "box", "system")  # box = notifications, system = the inbox itself


KINDS = ("message", "question", "answer", "notification", "approval_request", "approval")


PERMISSIONS = ("read", "write", "ask", "start_task", "answer", "approve", "run_job", "research")


# Actions no chat client can ever do, whatever config/clients.json says: this is code, not configuration. Checked first
# in allowed(), and never part of PERMISSIONS, so a config file that lists them gives nothing.
FORBIDDEN = ("ssh_key", "memory_edit", "merge_homelab")


# What a client may append, and the permission that takes: a client always writes as Chris.
CLIENT_KINDS = {"message": "write", "question": "ask", "answer": "answer"}


CLIENTS_FILE = ROOT / "config" / "clients.json"


def _config():
    """name -> its entry in config/clients.json, read on every call."""
    try:
        data = json.loads(os.environ.get("CLIENTS_JSON") or CLIENTS_FILE.read_text())
    except (OSError, ValueError) as e:
        log(f"conversation: {CLIENTS_FILE}: {e}; no client has permissions")
        return {}
    return {name: c for name, c in (data.get("clients") or {}).items() if isinstance(c, dict)}


def clients():
    """name -> set of permissions, read from config/clients.json on every call (an unknown permission is dropped,
    which is also why FORBIDDEN actions in the file give nothing)."""
    return {name: {p for p in c.get("permissions", []) if p in PERMISSIONS} for name, c in _config().items()}


def client_setting(client, key):
    """A setting of one client in config/clients.json (e.g. tasks_per_day), or None."""
    return _config().get(client, {}).get(key)


class Unavailable(RuntimeError):
    """The Java backend did not answer (down, not imported yet, or no secret): the store cannot be used right now."""


def _http(method, path, data=None, client=None):
    """The transport: one request to the backend, (status, body bytes). `client` is the chat door's authenticated client,
    sent as X-Inbox-Client (set here, never taken from a caller). Tests replace `transport` with an in-process fake
    (tests/conversation_fake.py); production always goes through facade.forward."""
    try:
        headers = {"Content-Type": "application/json"} if data else {}
        status, _, raw = facade.forward(method, path, headers, data, client or "inbox",
                                        extra={"X-Inbox-Client": client} if client else None, timeout=5)
        return status, raw
    except RuntimeError as e:
        raise Unavailable(f"conversation store: {e}") from e


transport = _http


def door(method, target, body, client):
    """The chat door's entries and state routes (/api/chat/entries, /api/chat/state): the backend answers them for the
    client the door has authenticated. Returns (status, body bytes); Unavailable when the backend does not answer."""
    return transport(method, target, body, client)


def _backend(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    status, raw = transport(method, path, data, None)
    try:
        return status, json.loads(raw or b"{}")
    except ValueError as e:
        raise Unavailable(f"conversation store: not JSON (HTTP {status})") from e


def _ok(method, path, body=None):
    """The answer body of a call that must succeed; Unavailable otherwise (a 400 is the caller's mistake: ValueError)."""
    status, data = _backend(method, path, body)
    if status == 400:
        raise ValueError(data.get("error", "bad request"))
    if status != 200:
        raise Unavailable(f"conversation store: HTTP {status}: {data.get('error', '')}")
    return data


def append(author, client, kind, text, link=None, ref=None):
    """Add an entry (internal callers: the inbox itself). Returns the entry; the backend scrubs the text first."""
    if author not in AUTHORS or kind not in KINDS:
        raise ValueError(f"unknown author or kind: {author}/{kind}")
    return _ok("POST", "/internal/conversation/append", {"author": author, "client": client, "kind": kind,
                                                         "text": str(text), "link": link, "ref": ref})["entry"]


def note(author, client, kind, text, link=None, ref=None):
    """append() for callers that must never fail because of the conversation (a notification, a task round)."""
    try:
        return append(author, client, kind, text, link, ref)
    except Exception as e:  # noqa: BLE001
        log(f"conversation: not stored ({e}): {kind}")


def read(after=0, limit=200):
    """Entries with an id above `after` (the cursor: pass the last id you saw; 0 = from the start), oldest first."""
    q = urllib.parse.urlencode({"after": max(0, int(after)), "limit": max(1, min(int(limit), 1000))})
    return _ok("GET", f"/internal/conversation/entries?{q}")["entries"]


def refusals(limit=100):
    return _ok("GET", f"/internal/conversation/refusals?limit={int(limit)}")["refusals"]


SWITCHES = ("discord", "discord_approvals", "kitt")  # on the homelab page; stored by the backend, read on every request


def switch(name):
    """True unless the switch was turned off. Read from the backend each time: no restart needed. A backend that does
    not answer reads as on (the default), so the homelab page still renders."""
    try:
        return bool(_ok("GET", f"/internal/conversation/switch/{name}")["on"])
    except (Unavailable, ValueError, KeyError) as e:
        log(f"conversation: switch {name} unreadable, taken as on ({e})")
        return True


def set_switch(name, on):
    if name not in SWITCHES:
        raise ValueError(f"unknown switch: {name}")
    _ok("POST", f"/internal/conversation/switch/{name}", {"on": bool(on)})


def state():
    """What clients may know: whether Discord is on, and whether approvals from Discord are on (off when Discord is)."""
    on = switch("discord")
    return {"discord": on, "discord_approvals": on and switch("discord_approvals")}


def switched_off(client, action=None):
    """Why a switch blocks this client (and action), or None. Discord and kitt are behind switches; kitt's approvals
    are governed by its permission list alone (config/clients.json)."""
    if client == "kitt":
        return None if switch("kitt") else "Kitt is switched off on the homelab page"
    if client != "discord":
        return None
    if not switch("discord"):
        return "Discord is switched off on the homelab page"
    if action == "approve" and not switch("discord_approvals"):
        return "approvals from Discord are off"
    return None


def allowed(client, action):
    """True when the client has the permission; otherwise False, and the backend logs the refusal (its journal and the
    refusal table). The same answer as before the move: forbidden actions never, the permission list of
    config/clients.json, the switches. Fail closed: without an answer from the backend the answer is no."""
    try:
        return bool(_ok("POST", "/internal/conversation/allowed", {"client": str(client), "action": str(action)})["allowed"])
    except (Unavailable, ValueError, KeyError) as e:
        log(f"conversation: refused {client}: {action} (the store did not answer: {e})")
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
