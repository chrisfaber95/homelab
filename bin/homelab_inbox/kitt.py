"""The door for kitt (H-018), the cockpit app in front of the box. kitt's adapter calls the inbox on loopback with the
header `X-Homelab-Client: kitt`; this module decides whether that call is really kitt acting for Chris, and answers it
with the same code the page and Discord use. Everything kitt may do is the `kitt` entry in config/clients.json, read on
every call, and the kitt switch on the homelab page. Each handler returns (http status, body)."""
import pwd
import re
import threading
from datetime import datetime, timezone

from . import chat, conversation, search
from .briefs import load_briefs
from .config import CONF, JOBS
from .helpers import first_line, read_log
from .jobs import dispatch, job_runs, run_view, save_job, set_paused
from .overview import health
from .storage import db, get, now, tasks

CLIENT = "kitt"

# What kitt's screens call a capability, and the permissions in config/clients.json it takes. All of them must be there.
CAPABILITIES = {"chat": ("read", "write", "ask"), "jobs": ("read", "run_job"), "approvals": ("approve",),
                "usage": ("read",), "logs": ("read",)}

KINDS = {"question", "task", "answer", "notification", "approval_request", "approval"}
LOG_ID = re.compile(r"[tr]\d+")
ITEM_ID = re.compile(r"question:\d+")


def account_uid():
    """The user id of kitt's service account (a systemd DynamicUser, resolved while the unit runs), or None."""
    try:
        return pwd.getpwnam(CONF.get("KITT_ACCOUNT", "homelab-app-kitt")).pw_uid
    except KeyError:
        return None


def peer_uid(addr, server_port):
    """The user id that owns the loopback connection coming from `addr` (ip, port) to our `server_port`, read from the
    kernel's socket table: a local process cannot choose it. None when it cannot be found."""
    ip, port = addr[0].removeprefix("::ffff:"), addr[1]
    if ip not in ("127.0.0.1", "::1"):
        return None
    for table in ("/proc/net/tcp", "/proc/net/tcp6"):
        try:
            rows = open(table).read().splitlines()[1:]
        except OSError:
            continue
        for row in rows:
            f = row.split()
            if len(f) > 7 and int(f[1].rpartition(":")[2], 16) == port and int(f[2].rpartition(":")[2], 16) == server_port:
                return int(f[7])
    return None


def identify(addr, server_port, headers, owner):
    """Why this is not kitt acting for Chris (a string), or None when it is. Checked: the client header, the account
    that owns the connection, and that the person at the app is the owner of the tailnet (the gate puts that header
    on; a request that did not pass the gate has none)."""
    if headers.get("X-Homelab-Client") != CLIENT:
        return "not the kitt client"
    want = account_uid()
    if want is None or peer_uid(addr, server_port) != want:
        return "not from kitt's service account"
    if not owner or headers.get("Tailscale-User-Login") != owner:
        return "not Chris at the app"
    return None


def permissions():
    """kitt's capabilities, from the permission list, and false for all of them while the switch is off."""
    have = conversation.clients().get(CLIENT, set())
    on = conversation.switch(CLIENT)
    return {cap: on and all(p in have for p in need) for cap, need in CAPABILITIES.items()}


def client_state():
    return 200, {"enabled": conversation.switch(CLIENT), "permissions": permissions()}


def _need(action):
    """None when kitt may do this; otherwise the refusal to return (the refusal is logged by conversation.allowed)."""
    return None if conversation.allowed(CLIENT, action) else _refuse_text(action)


def _refuse_text(action):
    why = conversation.switched_off(CLIENT) or ("approvals from kitt are off" if action == "approve"
                                                else f"kitt may not {action.replace('_', ' ')}")
    return 403, {"error": why}


# --- conversation -------------------------------------------------------------------------------------------------
def entry(e):
    """A conversation entry as kitt shows it: Chris's own messages are a question or a task, and a client the app does
    not know (the inbox itself, ntfy) is the box."""
    kind = e["kind"]
    if kind == "message":
        kind = "task" if str(e["ref"] or "").startswith(("task:", "job:")) else "question"
    if kind not in KINDS:
        kind = "notification"
    client = e["client"] if e["client"] in ("discord", "kitt", "page") else "box"
    return {"id": e["id"], "at": e["time"], "author": e["author"], "client": client, "kind": kind, "text": e["text"]}


def read_conversation(after):
    if (r := _need("read")):
        return r
    entries = conversation.read(after, 200)
    return 200, {"entries": [entry(e) for e in entries], "cursor": entries[-1]["id"] if entries else after}


def answer_question(question):
    """The private search's answer to a question from kitt, written to the conversation as the box's answer (the same
    thing Discord's bot does for a plain message, there shown in Discord and here in the thread)."""
    status, body = search.ask({"question": question})
    if status == 200 and body.get("found"):
        cites = "\n".join(f"[{c['n']}] {c['path']} ({c['location']})" + (f" <{c['link']}>" if c.get("link") else "")
                          for c in body.get("citations", []))
        text = f"{body['answer']}\n\nSources\n{cites}" if cites else body["answer"]
    elif status == 200:
        text = "Nothing relevant found in your private search for that question."
    else:
        text = f"The search could not answer: {(body.get('error') or status) if isinstance(body, dict) else status}."
    conversation.note("box", CLIENT, "answer", text)


def send(kind, text):
    """A question or task typed in kitt. A question is stored and answered by the private search in the background; a
    task is queued exactly as a task from Discord (chat.start_task: same queue, cap and PR), with source kitt."""
    text = str(text or "").strip()
    if kind not in ("question", "task") or not text:
        return 400, {"error": "send a question or a task with a text"}
    if kind == "task":
        status, body = chat.start_task(CLIENT, "auto", text)
        if status != 200:
            return status, body
        found = [e for e in conversation.read(0, 1000)[-50:] if e["ref"] == f"task:{body['task']}"]
        return 200, {"entry": entry(found[-1])} if found else (502, {"error": "the task started but is not in the thread"})
    if (r := _need("ask")):
        return r
    e = conversation.client_append(CLIENT, "question", text)
    if e is None:
        return _refuse_text("ask")
    threading.Thread(target=answer_question, args=(text,), daemon=True).start()
    return 200, {"entry": entry(e)}


# --- logs ---------------------------------------------------------------------------------------------------------
def _task_state(status):
    return "running" if status == "running" else "failed" if status == "failed" else "finished"


def _run_state(status):
    return "running" if status in ("queued", "running") else "failed" if status == "failed" else "finished"


def logs_list():
    if (r := _need("read")):
        return r
    items = [{"id": f"t{t['id']}", "title": f"{'Job ' + t['job'] if t['job'] else 'Task #' + str(t['id'])}: "
              f"{first_line(t['text'])}"[:120], "kind": "job" if t["job"] else "task", "state": _task_state(t["status"]),
              "startedAt": t["started"] or t["created"]} for t in tasks(30) if t["status"] != "queued"]
    items += [{"id": f"r{r['id']}", "title": f"Job {r['job']}", "kind": "job", "state": _run_state(r["status"]),
               "startedAt": r["started"] or r["created"]} for r in job_runs(limit=30) if not r["task_id"]]
    return 200, {"logs": sorted(items, key=lambda i: i["startedAt"], reverse=True)[:40]}


def read_log_chunk(key, start):
    if (r := _need("read")):
        return r
    if not LOG_ID.fullmatch(key):
        return 404, {"error": "no such log"}
    if key[0] == "t":
        row = get(int(key[1:]))
        state = _task_state(row["status"]) if row else None
    else:
        with db() as c:
            row = c.execute("SELECT status FROM job_runs WHERE id=?", (int(key[1:]),)).fetchone()
        state = _run_state(row["status"]) if row else None
    if state is None:
        return 404, {"error": "no such log"}
    lines, nxt = read_log(key, max(0, start))
    return 200, {"lines": [f"{e['at'][11:19]} [{e['kind']}] {e['text']}" for e in lines], "next": nxt, "state": state}


# --- approvals ----------------------------------------------------------------------------------------------------
def approvals_list():
    """The bell: draft briefs waiting for Chris, and tasks waiting on his answer."""
    if (r := _need("read")):
        return r
    items = [{"id": b["id"], "kind": "brief", "title": f"{b['id']}: {b['title']}", "at": now()}
             for b in load_briefs() if b["status"] == "draft"]
    with db() as c:
        waiting = c.execute("SELECT * FROM tasks WHERE status='waiting' ORDER BY id").fetchall()
    items += [{"id": f"question:{t['id']}", "kind": "question", "title": first_line(t["question"] or t["text"])[:200],
               "at": t["ended"] or t["started"] or t["created"]} for t in waiting]
    return 200, {"items": items}


def decide(item, decision):
    """Approve & build (`approve`) or Approve & build without me (`approve_unattended`) for a brief. kitt has asked for
    its own confirm already, so both steps of the approval run here (chat.approval_request and approval_confirm), which
    keeps the same checks, the "approved from kitt" record and the ntfy notification as Discord's approval."""
    mode = {"approve": "build", "approve_unattended": "without-me"}.get(decision)
    if not mode:
        return 400, {"error": "kitt approves with approve or approve_unattended; denying is not offered"}
    if not chat.BRIEF_ID.fullmatch(str(item)):
        return 409, {"error": "only a brief is approved; answer a question from Claude in the thread"}
    if not conversation.allowed(CLIENT, "approve"):
        return _refuse_text("approve")
    status, body = chat.approval_request(CLIENT, item, mode)
    return chat.approval_confirm(CLIENT, body["confirm"]) if status == 200 else (status, body)


# --- jobs ---------------------------------------------------------------------------------------------------------
def job_card(name, job):
    if "error" in job:
        return {"name": name, "schedule": "", "model": "", "deliver": "", "paused": False, "running": False,
                "lastResult": None, "nextRun": None, "error": str(job["error"])}
    runs = [(r, run_view(r)) for r in job_runs(name, 5)]
    last = next(((r, v) for r, v in runs if v[0] not in ("queued", "running", "claude")), None)
    return {"name": name, "schedule": job["when"] or job["schedule"], "model": job["model"], "deliver": job["deliver"],
            "paused": job["paused"], "running": bool(runs) and runs[0][1][0] in ("queued", "running", "claude"),
            "lastResult": {"state": "failed" if last[1][0] == "failed" else "ok", "at": last[0]["ended"] or last[0]["created"],
                           "summary": last[1][1][:300]} if last else None,
            "nextRun": None if job["paused"] else job["cron"].next_after(datetime.now(timezone.utc)).isoformat(),
            "error": None}


def jobs_list():
    if (r := _need("read")):
        return r
    return 200, {"jobs": [job_card(n, j) for n, j in sorted(JOBS.items())]}


def job_action(name, action):
    """Run now, pause, resume: what the Jobs tab does. Creating, editing and deleting a job are not offered."""
    job = JOBS.get(name)
    if not job or "error" in job:
        return 404, {"error": f"no usable job '{name}'"}
    if action == "run":
        return chat.run_job(CLIENT, name)
    if not conversation.allowed(CLIENT, "run_job"):
        return _refuse_text("run_job")
    save_job(name, set_paused(job["text"], action == "pause"),
             f"jobs: {name} {'paused' if action == 'pause' else 'resumed'} (from kitt)")
    conversation.note("chris", CLIENT, "message", f"{'Paused' if action == 'pause' else 'Resumed'} job {name}",
                      ref=f"job:{name}")
    return 200, {"job": name, "paused": action == "pause"}


# --- health -------------------------------------------------------------------------------------------------------
def health_checks():
    if (r := _need("read")):
        return r
    word = {"OK": "ok", "WARN": "warn", "FAIL": "fail"}
    checks = health()["checks"]
    return 200, {"checks": [{"name": n, "status": word.get(str(c.get("status")), "warn"), "text": str(c.get("text") or "")}
                            for n, c in sorted(checks.items())]}


# --- routing ------------------------------------------------------------------------------------------------------
def route(method, path, query, body):
    """(status, body) for one of kitt's calls, or None when the path is not one of them."""
    q = {k: v[0] for k, v in query.items()}

    def number(key):
        try:
            return int(q.get(key) or 0)
        except ValueError:
            return 0
    if path == "/api/clients/kitt" and method == "GET":
        return client_state()
    if path == "/api/conversation":
        if method == "GET":
            return read_conversation(number("after"))
        if method == "POST":
            return send(body.get("kind"), body.get("text"))
    if path == "/api/logs" and method == "GET":
        return logs_list()
    if (m := re.fullmatch(r"/api/logs/([^/]+)", path)) and method == "GET":
        return read_log_chunk(m.group(1), number("from"))
    if path == "/api/approvals" and method == "GET":
        return approvals_list()
    if (m := re.fullmatch(r"/api/approvals/([^/]+)", path)) and method == "POST":
        return decide(m.group(1), body.get("decision"))
    if path == "/api/jobs" and method == "GET":
        return jobs_list()
    if (m := re.fullmatch(r"/api/jobs/([a-z0-9-]+)/(run|pause|resume)", path)) and method == "POST":
        return job_action(m.group(1), m.group(2))
    if path == "/api/health" and method == "GET":
        return health_checks()
    return None
