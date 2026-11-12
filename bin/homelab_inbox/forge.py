"""Forge controls on the homelab page (H-015): the page talks to the inbox only; the inbox calls forge's own
endpoints on the tailnet address. Forge starts stopped after every restart; only a press on Start runs it.

Forge is an agent like any other on this page: while it runs it appears in "Nu bezig" with what it is doing right
now and its own log, which is forge's `GET /api/log?from=<sequence>` passed through here."""
import re
import time

from .config import CONF
from .search import call
from .storage import now

FORGE_URL = CONF.get("FORGE_URL", "http://100.85.70.34:8120").rstrip("/")
PAGE_URL = CONF.get("FORGE_PAGE_URL", FORGE_URL).rstrip("/")
LIVE_KEY = "f1"  # the key of forge's row and its log in /api/log/<key>
UNREACHABLE = "Forge is not reachable (is homelab-app-forge running?)."
LOG_LIMIT = 500
# "Nu bezig" asks every few seconds and the overview page asks on every render, so the answer is
# kept for a moment; a forge that does not answer is not asked again at once (no stall per poll).
CACHE_SECONDS, FAIL_BACKOFF, LIVE_TIMEOUT = 2.0, 30.0, 2.0
_last = {"at": None, "data": None}


def running(data):
    """Forge's /api/state says whether the worker runs; accept a boolean or a status word."""
    if isinstance(data.get("running"), bool):
        return data["running"]
    return str(data.get("status") or data.get("state") or "").lower() == "running"


def view(timeout=5):
    """Forge's state as this page needs it, or None when forge does not answer."""
    status, data = call(f"{FORGE_URL}/api/state", timeout=timeout)
    if status != 200 or not isinstance(data, dict):
        return None
    return {"running": running(data), "step": data.get("step"), "project": data.get("project"),
            "activity": data.get("activity"), "started": data.get("started_at"),
            "paused": bool(data.get("paused")), "pause_reason": data.get("pause_reason"),
            "paused_until": data.get("paused_until")}


def recent_view():
    """`view()`, but at most once every few seconds, and not at all right after a failure."""
    age = None if _last["at"] is None else time.monotonic() - _last["at"]
    if age is not None and age < (CACHE_SECONDS if _last["data"] else FAIL_BACKOFF):
        return _last["data"]
    _last["data"], _last["at"] = view(LIVE_TIMEOUT), time.monotonic()
    return _last["data"]


def state():
    data = view()
    if data is None:
        return 502, {"error": {"message": UNREACHABLE}}
    return 200, data


def live_item():
    """Forge's row in "Nu bezig", or None when it is not working."""
    data = recent_view()
    if data is None or not data["running"]:
        return None
    step = data["step"] or "bezig"
    what = data["activity"] or (f"paused: {data['pause_reason']}" if data["paused"] else f"stap {step}")
    return {"key": LIVE_KEY, "title": "Forge", "what": what, "repo": data["project"] or "eigen projecten",
            "model": "Claude (eigen login)", "url": f"{PAGE_URL}/", "cancel": None,
            "started": data["started"] or now()}


ISO_RE = re.compile(r"\d{4}-\d{2}-\d{2}T[\d:+.-]+")
TOOL_RE = re.compile(r"[A-Z][A-Za-z_]*:")  # "Bash: pytest -q", "Edit: src/app.py": a tool call


def kind_of(text):
    """The colour of a log line: forge's own words, a tool it used, or something that went wrong."""
    low = text.lower()
    if low.startswith(("refused", "paused")) or "failed" in low or "not built" in low:
        return "error"
    if low.startswith(("claude ", "out of tokens")):
        return "claude"
    return "tool" if TOOL_RE.match(text) else "info"


def log_line(line):
    """One of forge's log lines ("<iso> <text>") as the page's log panel wants it."""
    at, _, text = str(line).partition(" ")
    if not (text and ISO_RE.fullmatch(at)):
        at, text = now(), str(line)
    return {"at": at, "kind": kind_of(text), "text": text[:600]}


def log(start):
    """Forge's log from sequence `start` on, in the shape the page's log panel reads."""
    status, data = call(f"{FORGE_URL}/api/log?from={max(0, int(start))}", timeout=5)
    if status != 200 or not isinstance(data, dict) or not isinstance(data.get("lines"), list):
        return 502, {"error": {"message": UNREACHABLE}}
    lines = [log_line(l) for l in data["lines"][:LOG_LIMIT]]
    nxt = data.get("next")
    return 200, {"lines": lines, "next": nxt if isinstance(nxt, int) else start + len(lines),
                 "done": not data.get("running")}


def forget():
    """Drop the kept answer: after a Start or Stop the next question must reach forge itself."""
    _last["at"], _last["data"] = None, None


def control(action, who="?"):
    if action not in ("start", "stop"):
        return 400, {"error": {"message": "Unknown action."}}
    status, data = call(f"{FORGE_URL}/api/{action}", {}, timeout=10)
    if status >= 400:
        message = data.get("error", {}).get("message") if isinstance(data.get("error"), dict) else data.get("error")
        return 502, {"error": {"message": f"Forge refused {action} (HTTP {status}): {message or 'no reason given'}"}}
    print(f"forge {action} by {who}", flush=True)
    forget()
    return state() if status == 200 else (status, data)
