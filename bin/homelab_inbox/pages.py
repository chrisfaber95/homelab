"""The page (F-024): HTML rendering of every tab and the CSRF token."""
import hashlib
import hmac
import html
import json
import os
import secrets
import socket
import subprocess
import time
from datetime import datetime, timezone

from .config import CONF, JOBS, ROOT, SECRET_FILE, STATUS_NL, TZ
from .helpers import first_line, label
from .jobs import JOB_KINDS, skill_list
from .overview import CHANGES_LOG, day_time
from .storage import db, log


def secret():
    if not SECRET_FILE.exists():
        SECRET_FILE.write_text(secrets.token_hex(32))
        SECRET_FILE.chmod(0o600)
    return SECRET_FILE.read_text().strip().encode()


def csrf_token():
    return hmac.new(secret(), b"inbox-form", hashlib.sha256).hexdigest()


class Access:
    """Wie mag de pagina zien: alleen de eigenaar van de box op Tailscale, niet de box zelf, niet het LAN."""
    own, owner, cache = set(), None, {}

    @classmethod
    def init(cls):
        try:
            st = json.loads(subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True,
                                           timeout=10).stdout)
            cls.owner = st["User"][str(st["Self"]["UserID"])]["LoginName"]
            cls.own = set(st["Self"]["TailscaleIPs"]) | {"127.0.0.1", "::1"}
        except Exception as e:
            log(f"tailscale status: {e}")

    @classmethod
    def allowed(cls, ip):
        if os.environ.get("INBOX_ALLOW_ALL") == "1":  # alleen voor tests
            return True, "test"
        if ip in cls.own or not cls.owner:
            return False, "niet vanaf de box zelf"
        hit = cls.cache.get(ip)
        if not hit or hit[1] < time.monotonic():
            try:
                w = json.loads(subprocess.run(["tailscale", "whois", "--json", ip], capture_output=True, text=True,
                                              timeout=5).stdout)
                login = w["UserProfile"]["LoginName"]
                device = w["Node"]["ComputedName"]
            except Exception:
                login, device = None, None
            hit = ((login, device), time.monotonic() + 300)
            cls.cache[ip] = hit
        login, device = hit[0]
        return login == cls.owner, device or ip


WEB = ROOT / "web"


TABS = (("overzicht", "/", "Overzicht"), ("taken", "/taken", "Taken"), ("briefs", "/briefs", "Briefs"), ("jobs", "/jobs", "Jobs"),
        ("search", "/search", "Search"), ("geheugen", "/memory", "Geheugen"), ("skills", "/skills", "Skills"), ("ssh", "/ssh", "SSH"))


def local(iso):
    try:
        return datetime.fromisoformat(iso).astimezone(TZ).strftime("%d-%m %H:%M") if iso else "-"
    except ValueError:
        return iso  # een met de hand bewerkte datum in het geheugen


def asset(name):
    """/static/<naam> met de wijzigingstijd erachter, zodat een nieuwe versie meteen geladen wordt."""
    try:
        return f"/static/{name}?v={int((WEB / name).stat().st_mtime)}"
    except OSError:
        return f"/static/{name}"


def tab_of(title):
    if title.startswith(("job", "concept", "run")):
        return "jobs"
    if title.startswith(("geheugen", "search", "skills")):
        return title.split()[0]
    return "overzicht" if title == "homelab" else "taken"


def page(title, body, refresh=False, narrow=True):
    meta = '<meta http-equiv="refresh" content="20">' if refresh else ""
    current = tab_of(title)
    tabs = "".join(f'<a href="{href}"{" aria-current=page" if key == current else ""}>{name}</a>'
                   for key, href, name in TABS)
    return (f'<!doctype html><html lang="nl"><head><meta charset="utf-8"><meta name="viewport" '
            f'content="width=device-width, initial-scale=1">{meta}<title>{html.escape(title)}</title>'
            f'<meta name="color-scheme" content="dark"><meta name="theme-color" content="#04070c">'
            f'<link rel="stylesheet" href="{asset("app.css")}">'
            f'<script src="{asset("app.js")}" defer></script></head><body>'
            f'<header class="top"><a class="mark" href="/">homelab</a><nav class="tabs" aria-label="Onderdelen">'
            f'{tabs}</nav></header><main{" class=narrow" if narrow else ""}>{body}</main></body></html>')


def form(action, inner, button, ghost=False, cls=None):
    cls = cls or ("ghost" if ghost else None)
    return (f'<form method="post" action="{action}"><input type="hidden" name="csrf" value="{csrf_token()}">'
            f'{inner}<button{f" class=\"{cls}\"" if cls else ""}>{button}</button></form>')


def meta(*parts):
    return '<div class="row-meta">' + "".join(f"<span>{p}</span>" for p in parts if p) + "</div>"


def task_row(t, link=True):
    e = html.escape
    head = f'<a href="/task/{t["id"]}">{e(label(t))}</a>' if link else f'<b>{e(label(t))}</b>'
    pr = f'<a href="{e(t["pr_url"])}">PR #{t["pr_number"]}</a>' if t["pr_url"] else ""
    what = JOB_KINDS.get(JOBS.get(t["job"], {}).get("kind"), "job") if t["job"] else first_line(t["text"])
    return (f'<div class="row"><div class="row-title">{head}<span class="st {t["status"]}">'
            f'{e(STATUS_NL[t["status"]])}</span><span>{e(what)}</span></div>'
            + meta(e(t["repo"]), f'via {e(t["source"].capitalize())}' if t["source"] else "", f'aangemaakt {local(t["created"])}',
                   f'klaar {local(t["ended"])}' if t["ended"] else "", pr) + '</div>')


def next_run(job):
    if "error" in job:
        return "-"
    if job["paused"]:
        return "gepauzeerd"
    return day_time(job["cron"].next_after(datetime.now(timezone.utc)))


SKILLS_INTRO = ("Gemergde skills op main; elke taak en Claude-job kan ze gebruiken. "
                "Nieuwe en betere komen alleen via een PR die jij merget.")
SKILLS_EMPTY, PROPOSALS_EMPTY = "Nog geen skills.", "Nog geen voorstellen."


def skills_data():
    """What the Skills page shows, as plain data (GET /api/skills): the skills with the jobs that use them and the
    latest skill proposals, plus the page's texts."""
    used = {}
    for job in JOBS.values():
        for s in job.get("skills", []):
            used.setdefault(s, []).append(job["name"])
    with db() as c:
        prs = c.execute("SELECT * FROM skill_prs ORDER BY id DESC LIMIT 20").fetchall()
    return {"intro": SKILLS_INTRO, "empty": SKILLS_EMPTY, "proposals_empty": PROPOSALS_EMPTY,
            "skills": [{"name": n, "description": d, "jobs": used.get(n, [])} for n, d in skill_list().items()],
            "proposals": [{"name": p["name"], "pr_url": p["pr_url"], "task_id": p["task_id"],
                           "created": local(p["created"])} for p in prs]}


def admin_ask(req):
    """Een verzoek aan homelab-admin (F-019) via zijn socket; die kijkt zelf wie het vraagt (inbox-runner)."""
    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.settimeout(30)
    try:
        s.connect(CONF["SOCKET"])
        s.sendall(json.dumps(req).encode() + b"\n")
        data = b""
        while not data.endswith(b"\n"):
            chunk = s.recv(65536)
            if not chunk:
                break
            data += chunk
        return json.loads(data)
    except (OSError, ValueError) as ex:
        return {"ok": False, "text": f"homelab-admin antwoordt niet ({ex})"}
    finally:
        s.close()


def ssh_state():
    """What the SSH page shows besides the form: the account, the confirm window and the last 15 key requests (newest
    first). `status` is waiting, ok or fail. Served by GET /api/ssh."""
    try:
        entries = [c for l in CHANGES_LOG.read_text().splitlines() if '"sshkey"' in l
                   and (c := json.loads(l)).get("kind") == "sshkey"]
    except (OSError, ValueError):
        entries = []

    def status(c):
        if c.get("result", "").startswith("aangevraagd"):
            return "waiting"
        return "ok" if c.get("ok") else "fail"
    return {"user": CONF.get("SSH_KEY_USER", "chris"),
            "confirm_minutes": int(CONF.get("CONFIRM_SECONDS", 300)) // 60,
            "history": [{"status": status(c), "summary": c.get("summary", ""), "result": c.get("result", ""),
                         "at": c["at"], "by": c.get("user", "")} for c in entries[::-1][:15]]}


def search_page():
    """Search tab (H-012): the question box, filters, cited answer, raw results and the thumbs-down. The script in
    app.js does the work; filters are filled from the index."""
    return page("search", f"""<h1>Search</h1>
<p class="muted">Ask about anything written down: the second brain, briefs, sessions, repo docs and memory. Private,
answered on the box.</p>
<form id="search-form" class="search" role="search">
<label for="sq">Question</label>
<textarea id="sq" name="q" rows="2" maxlength="2000" required placeholder="Where did we decide on sqlite?"></textarea>
<div class="search-filters">
<div><label for="sproject">Project</label><select id="sproject" name="project"><option value="">All projects</option></select></div>
<div><label for="stype">Source type</label><select id="stype" name="type"><option value="">All types</option></select></div>
</div>
<label class="check" for="shard"><input type="checkbox" id="shard" name="hard" value="1"><span>Hard question
<small class="muted">answered by Claude, uses allowance</small></span></label>
<div class="search-actions"><button type="submit" id="sask">Ask</button>
<button type="button" class="quiet" id="ssearch">Only list sources</button></div>
</form>
<p id="sstatus" class="muted" role="status" aria-live="polite"></p>
<section id="sanswer" hidden aria-live="polite"></section>
<section id="sresults" hidden></section>""")
