"""What a chat client may do besides reading and writing the conversation (H-016): start a task, answer a question
from Claude, list what runs, list and run jobs, and approve a draft brief in two steps. Every action is checked against
the client's permissions (conversation.allowed) and nothing here is decided by a model or by free text: a client
names the action explicitly. Each function returns (http status, body)."""
import re
import secrets
import time
from datetime import datetime, timezone

from . import conversation
from .briefs import brief_by_id, live_build, start_build
from .config import ACTIVE, AUTO, CONF, JOBS, TZ, _lock
from .helpers import first_line, notify, page_url, repo_list
from .jobs import dispatch
from .queue import answer_task
from .storage import db, get, log, new_task, now

BRIEF_ID = re.compile(r"[A-Z]-\d{3,4}")
MODES = {"build": False, "without-me": True}  # the two buttons: Approve & build, Approve & build without me


def clock():
    return time.time()  # a function so tests can move time


def _refused(client, action):
    why = conversation.switched_off(client, action) or f"{client} may not {action.replace('_', ' ')}"
    return 403, {"error": f"refused: {why}"}


def ttl():
    return int(CONF.get("CONFIRM_TTL_SECONDS", "300"))


def daily_cap(client):
    """Tasks a client may start per day: `tasks_per_day` on the client in config/clients.json, else
    CLIENT_TASKS_PER_DAY in config/inbox.conf."""
    own = conversation.client_setting(client, "tasks_per_day")
    try:
        return max(0, int(own if own is not None else CONF.get("CLIENT_TASKS_PER_DAY", "10")))
    except (TypeError, ValueError):
        return 10


def started_today(client):
    """Tasks (and builds) this client started since local midnight."""
    midnight = datetime.now(TZ).replace(hour=0, minute=0, second=0, microsecond=0)
    since = midnight.astimezone(timezone.utc).isoformat(timespec="seconds")
    with db() as c:
        return c.execute("SELECT COUNT(*) FROM tasks WHERE source=? AND created>=?", (client, since)).fetchone()[0]


def cap_reason(client):
    cap = daily_cap(client)
    if started_today(client) >= cap:
        return f"daily limit reached: {cap} task(s) per day may start from {client.capitalize()}; nothing was started"
    return None


def start_task(client, repo, text):
    """A task from a client: the same row, queue, limits, auto mode and PR as a task from the page, marked with where
    it came from."""
    if not conversation.allowed(client, "start_task"):
        return _refused(client, "start_task")
    repo, text = str(repo or "").strip(), str(text or "").strip()
    if (repo not in repo_list() and repo != AUTO) or not text:
        return 400, {"error": "give a repo from the list (or 'auto') and a task text"}
    with _lock:  # check and insert together, so two commands cannot both slip under the cap
        if why := cap_reason(client):
            conversation.note("system", "inbox", "notification", why)
            return 429, {"error": why}
        tid = new_task(repo, text, source=client)
    conversation.note("chris", client, "message", f"[{repo}] {text}", page_url(tid), f"task:{tid}")
    return 200, {"task": tid, "url": page_url(tid)}


def answer(client, task_id, text):
    """A reply to a question from Claude (or a reaction to its answer): continues the task as the page does."""
    if not conversation.allowed(client, "answer"):
        return _refused(client, "answer")
    t = get(int(task_id)) if str(task_id).isdigit() else None
    if not t:
        return 404, {"error": "no such task"}
    if not answer_task(t, text, source=client):
        return 409, {"error": f"task {t['id']} is not waiting for an answer"}
    return 200, {"task": t["id"], "url": page_url(t["id"])}


def tasks_state(client):
    """What is running, waiting for Chris and queued, oldest first."""
    if not conversation.allowed(client, "read"):
        return _refused(client, "read")
    with db() as c:
        rows = c.execute("SELECT * FROM tasks WHERE status IN ('queued','running','waiting') ORDER BY id").fetchall()
    return 200, {"tasks": [{"id": t["id"], "status": t["status"], "repo": t["repo"], "what": first_line(t["text"]),
                            "source": t["source"], "url": page_url(t["id"])} for t in rows]}


def jobs_list(client):
    if not conversation.allowed(client, "read"):
        return _refused(client, "read")
    return 200, {"jobs": [{"name": n, "kind": j.get("kind"), "when": j.get("schedule") or j.get("when"),
                           "paused": bool(j.get("paused")), "error": j.get("error")} for n, j in sorted(JOBS.items())]}


def run_job(client, name):
    if not conversation.allowed(client, "run_job"):
        return _refused(client, "run_job")
    job = JOBS.get(str(name))
    if not job or "error" in job:
        return 404, {"error": f"no runnable job '{name}'"}
    run_id = dispatch(job, now(), manual=True)
    conversation.note("chris", client, "message", f"Ran job {job['name']} now", page_url(), f"job:{job['name']}")
    return 200, {"run": run_id, "job": job["name"]}


def drafts(client):
    """Draft briefs, for a command or button to approve."""
    if not conversation.allowed(client, "read"):
        return _refused(client, "read")
    from .briefs import load_briefs
    return 200, {"briefs": [{"id": b["id"], "title": b["title"]} for b in load_briefs() if b["status"] == "draft"]}


def _init(c):
    c.execute("""CREATE TABLE IF NOT EXISTS approvals (token TEXT PRIMARY KEY, client TEXT NOT NULL, brief TEXT NOT NULL,
        mode TEXT NOT NULL, created TEXT NOT NULL, expires REAL NOT NULL, status TEXT NOT NULL, task_id INTEGER)""")


def approval_request(client, bid, mode):
    """Step one: a pending approval with a confirm token that expires. Nothing is approved or started yet."""
    if not conversation.allowed(client, "approve"):
        return _refused(client, "approve")
    if mode not in MODES or not BRIEF_ID.fullmatch(str(bid)):
        return 400, {"error": "give a brief id and a mode: build or without-me"}
    b = brief_by_id(bid, max_age=0)
    if not b or b["status"] != "draft":
        return 409, {"error": f"{bid} is not a draft brief"}
    if live_build(bid):
        return 409, {"error": f"{bid} is already being built"}
    if why := cap_reason(client):
        return 429, {"error": why}
    token = secrets.token_urlsafe(18)
    with db() as c:
        _init(c)
        c.execute("INSERT INTO approvals VALUES (?,?,?,?,?,?,?,NULL)",
                  (token, client, bid, mode, now(), clock() + ttl(), "pending"))
    what = "Approve & build without me" if MODES[mode] else "Approve & build"
    # The token is not in the conversation: only the client that asked gets it, in this response.
    conversation.note("system", client, "approval_request", f"{what} for {bid} ({b['title']}) is waiting for "
                      f"confirmation; it expires in {ttl() // 60} min", page_url(), f"brief:{bid}")
    return 200, {"confirm": token, "brief": bid, "mode": mode, "title": b["title"], "expires_in": ttl()}


def approval_confirm(client, token):
    """Step two: only a valid, unexpired, unused token from the same client approves. Switches and permission are
    checked again now, so turning approvals off between the steps stops it."""
    if not conversation.allowed(client, "approve"):
        return _refused(client, "approve")
    with db() as c:
        _init(c)
        # One use: the first confirm flips pending to used; a second, or an expired token, matches no row.
        row = c.execute("SELECT * FROM approvals WHERE token=?", (str(token),)).fetchone()
        taken = row and row["client"] == client and c.execute(
            "UPDATE approvals SET status='used' WHERE token=? AND status='pending' AND expires>?",
            (row["token"], clock())).rowcount == 1
    if not row or row["client"] != client:
        return 404, {"error": "unknown confirm token"}
    if not taken:
        why = "expired" if row["status"] == "pending" else f"already {row['status']}"
        return 410, {"error": f"this confirmation is {why}; nothing was approved"}
    bid, unattended = row["brief"], MODES[row["mode"]]
    try:
        if why := cap_reason(client):
            raise ValueError(why)
        b = brief_by_id(bid, max_age=0)
        if not b or b["status"] != "draft":
            raise ValueError(f"{bid} is no longer a draft brief")
        tid = start_build(bid, unattended=unattended, who="Chris", source=client)
    except Exception as e:  # noqa: BLE001
        with db() as c:
            c.execute("UPDATE approvals SET status='failed' WHERE token=?", (row["token"],))
        log(f"chat: approval of {bid} by {client} failed: {e}")
        return 409, {"error": str(e)}
    with db() as c:
        c.execute("UPDATE approvals SET task_id=? WHERE token=?", (tid, row["token"]))
    what = f"Approved {bid} from {client.capitalize()} and started the build" + (" without me" if unattended else "")
    conversation.note("chris", client, "approval", what, page_url(tid), f"brief:{bid}")
    notify(what, f"Build #{tid}", page_url(tid), store=False)  # ntfy as well: an approval is never silent
    return 200, {"task": tid, "url": page_url(tid), "brief": bid, "mode": row["mode"]}
