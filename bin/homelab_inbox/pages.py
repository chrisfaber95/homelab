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
from .helpers import first_line, label, read_log
from .jobs import JOB_KINDS, RESEARCH_JOB, RESEARCH_MODES, RUN_NL, load_agents, claude_runs_today, draft_name, nothing_statement, job_runs, parse_job, run_view, skill_list
from .overview import CHANGES_LOG, DAYS_NL, day_time
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


def log_html(key):
    """Het log van een taak of run; app.js houdt het bij zolang hij loopt (zonder script: de stand bij het laden)."""
    lines, _ = read_log(key, 0, 2000)
    rows = "".join(f'<div class="k-{e["kind"]}"><time>{local(e["at"])[6:]}</time>{html.escape(e["text"])}</div>'
                   for e in lines)
    return (f'<h2>Log</h2><div class="log" role="log" data-key="{key}">'
            f'{rows or "<div class=log-end>Nog geen regels.</div>"}</div>')




def when(dt):
    dt = dt.astimezone(TZ)
    return f'{DAYS_NL[dt.weekday()]} {dt:%d-%m %H:%M}'


def next_run(job):
    if "error" in job:
        return "-"
    if job["paused"]:
        return "gepauzeerd"
    return day_time(job["cron"].next_after(datetime.now(timezone.utc)))


def job_link(name):
    return "/research" if name == RESEARCH_JOB and name not in JOBS else f"/job/{html.escape(name)}"


def run_row(r, show_job=False):
    e = html.escape
    status, summary, link, pr = run_view(r)
    head = f'<a href="{job_link(r["job"])}">{e(r["job"])}</a>' if show_job else ""
    return (f'<div class="row"><div class="row-title">{head}<a href="{link}">{local(r["due"])}</a>'
            f'<span class="st run-{status}">{e(RUN_NL.get(status, status))}</span></div>'
            + meta("lokaal model" if r["model"] == "local" else "Claude", "nu gedraaid" if r["manual"] else "",
                   f'<a href="{e(pr)}">PR</a>' if pr else "", e(first_line(summary)) if summary else "")
            + '</div>')


def job_facts(job):
    e = html.escape
    times = ", ".join(when(t) for t in job["cron"].next_times(datetime.now(timezone.utc)))
    rows = [("Wanneer", f'{e(job["when"] or "-")} <span class="muted">({e(job["schedule"])}, {TZ.key})</span>'),
            ("Volgende keren", "gepauzeerd" if job["paused"] else e(times)),
            ("Soort", e(JOB_KINDS[job["kind"]])),
            ("Model", "lokaal (gratis)" if job["model"] == "local" else "Claude (telt mee voor de daglimiet)")]
    if job["mode"]:
        rows.append(("Modus", {"quick": "quick: een paar minuten, een handvol bronnen",
                               "deep": "deep: tot de grens van een inbox-taak"}[job["mode"]]))
    if job["repo"]:
        rows.append(("Repo", e(job["repo"])))
    if job["script"]:
        rows.append(("Script", f'<code>{e(" ".join([job["script"], *job["args"]]))}</code>'))
    if job["sources"]:
        rows.append(("Bronnen", "<br>".join(e(s) for s in job["sources"])))
    rows.append(("Resultaat naar", {"page": "alleen de pagina", "ntfy": "ntfy (push)",
                                    "conversation": "het gesprek (Discord)"}[job["deliver"]]))
    if job["deliver"] != "page":
        rows.append(("Niets te melden", f'<pre>{e(nothing_statement(job))}</pre>'))
    rows.append(("Skills", ", ".join(f'<a href="/skills">{e(s)}</a>' for s in job["skills"]) or "-"))
    if job["minutes"] is not None:
        rows.append(("Bespaart", f'{job["minutes"]} min per run'))
    return ('<dl>' + "".join(f"<dt>{k}</dt><dd>{v}</dd>" for k, v in rows) + '</dl>'
            f'<h2>Instructie</h2><pre>{e(job["instruction"])}</pre>')


def research_page(error=None, question=""):
    """Ask a research question and see the reports (H-017): the answer with its sources is on the task page."""
    e = html.escape
    opts = "".join(f'<option value="{m}">{m}</option>' for m in RESEARCH_MODES)
    runs = [r for r in job_runs(RESEARCH_JOB, 30)]
    body = ('<h1>Onderzoek</h1><p class="muted">Een vraag, een rapport met bronnen. Eerst de privézoekindex, dan het web. '
            '<b>quick</b>: een paar minuten, een handvol bronnen. <b>deep</b>: tot de grens van een inbox-taak. '
            f'Elke vraag telt als een Claude-run voor de daglimiet ({claude_runs_today()} van '
            f'{int(CONF.get("JOB_CLAUDE_CAP", 10))} vandaag). Alleen lezen: er komt nooit een commit of PR.</p>'
            + (f'<p class="st failed">{e(error)}</p>' if error else "")
            + form("/research/ask", '<label for="rq">Vraag</label><textarea name="question" id="rq" required>'
                   f'{e(question)}</textarea><label for="rm">Modus</label><select name="mode" id="rm">{opts}</select>',
                   "Onderzoek starten")
            + f'<h2>Rapporten</h2>{"".join(run_row(r) for r in runs) or "<p class=empty>Nog geen vragen.</p>"}'
            + '<p class="muted">Geplande onderzoeken staan als job (<code>kind: research</code>) op de tab Jobs.</p>')
    return page("onderzoek", body, refresh=any(run_view(r)[0] in ("queued", "running", "claude") for r in runs))


def job_page(name, error=None, text=None):
    e = html.escape
    job = JOBS[name]
    parts = [f'<h1>Job {e(name)}</h1>']
    if error:
        parts.append(f'<p class="st failed">{e(error)}</p>')
    if "error" in job:
        parts.append(f'<p class="st failed">Fout in het bestand: {e(job["error"])}</p>')
    else:
        parts.append(job_facts(job))
        parts.append(form(f"/job/{e(name)}/run", "", "Nu draaien"))
        parts.append(form(f"/job/{e(name)}/{'resume' if job['paused'] else 'pause'}", "",
                          "Hervatten" if job["paused"] else "Pauzeren", ghost=not job["paused"]))
        parts.append('<h2>Wijzigen</h2>' + form(f"/job/{e(name)}/change", '<textarea name="text" required '
                     'placeholder="Bijvoorbeeld: om 8:00 in plaats van 7:00"></textarea>', "Wijziging opstellen"))
    runs = job_runs(name)
    parts.append(f'<h2>Geschiedenis</h2>{"".join(run_row(r) for r in runs) or "<p class=empty>Nog geen runs.</p>"}')
    parts.append('<h2>Bestand</h2>' + form(f"/job/{e(name)}/save", f'<textarea name="text" rows="14">'
                 f'{e(text or job["text"])}</textarea>', "Opslaan"))
    parts.append(form(f"/job/{e(name)}/delete", "", "Job verwijderen", ghost=True))
    return page(f"job {name}", "".join(parts), refresh=any(run_view(r)[0] in ("queued", "running") for r in runs))


def draft_page(d, error=None, text=None):
    e = html.escape
    parts = [f'<h1>Concept #{d["id"]}{" voor " + e(d["base"]) if d["base"] else ""}</h1>',
             f'<pre>{e(d["request"])}</pre>']
    if d["status"] == "drafting":
        parts.append('<p class="muted">Claude stelt de job op&hellip;</p>')
    elif d["status"] == "failed":
        parts.append(f'<p class="st failed">Mislukt: {e(d["error"] or "")}</p>')
    elif d["status"] in ("confirmed", "cancelled"):
        parts.append(f'<p class="muted">{"Bevestigd" if d["status"] == "confirmed" else "Geannuleerd"}.</p>')
    else:
        text = text or d["text"]
        name = d["base"] or draft_name(text)
        try:
            parts.append(job_facts(parse_job(name, text)))
        except ValueError as ex:
            error = error or str(ex)
        if error:
            parts.append(f'<p class="st failed">{e(error)}</p>')
        parts.append(form(f"/draft/{d['id']}/confirm", f'<h2>Bestand</h2><textarea name="text" rows="14">{e(text)}'
                          f'</textarea>', "Bevestigen"))
        parts.append(form(f"/draft/{d['id']}/cancel", "", "Annuleren", ghost=True))
    return page(f"concept #{d['id']}", "".join(parts), refresh=d["status"] == "drafting")


def run_page(r):
    e = html.escape
    status, summary, _, _ = run_view(r)
    cancel = form(f"/run/{r['id']}/cancel", "", "Annuleren", ghost=True) if r["status"] == "running" else ""
    return page(f"run {r['id']}", f'<h1>Job <a href="{job_link(r["job"])}">{e(r["job"])}</a></h1>'
                f'<div class="row-title"><span>{local(r["due"])}</span><span class="st run-{status}">'
                f'{e(RUN_NL.get(status, status))}</span><span>{"lokaal model" if r["model"] == "local" else "Claude"}'
                f'</span></div><pre>{e(r["summary"] or "")}</pre>'
                + (f'<p class="muted">{e(r["note"])}</p>' if r["note"] else "") + cancel
                + (log_html(f"r{r['id']}") if r["model"] == "local" else ""),
                refresh=status == "queued")


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
