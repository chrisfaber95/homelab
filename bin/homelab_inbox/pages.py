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

from .briefs import WITHOUT_ME, build_left_nothing
from .config import ACCEPTABLE, ACTIVE, AUTO, BRIEF, CONF, JOBS, RETRYABLE, ROOT, SECRET_FILE, STATUS_NL, TZ
from .helpers import first_line, label, read_log, repo_list
from .jobs import JOB_KINDS, RESEARCH_JOB, RESEARCH_MODES, RUN_NL, load_agents, claude_runs_today, draft_name, nothing_statement, job_runs, parse_job, run_view, skill_list
from .memory import MEMORY, MEM_KINDS
from .overview import CHANGES_LOG, DAYS_NL, day_time, model_name
from .router import blocked_by, blocked_followups, children
from .storage import db, log, tasks


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
    if title.startswith(("geheugen", "search", "skills", "ssh")):
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


def tasks_page():
    e = html.escape
    opts = "".join(f'<option value="{e(n)}">{e(n)}</option>' for n in sorted(repo_list()))
    auto = f'<option value="{AUTO}">auto (homelab beslist)</option>'
    all_tasks = tasks()
    queue = [t for t in all_tasks if t["status"] in ACTIVE + ("waiting",)][::-1]
    body = (f'<h1>Taken</h1><form method="post" action="/tasks" id="nieuw">'
            f'<input type="hidden" name="csrf" value="{csrf_token()}"><label for="repo">Repo</label>'
            f'<select name="repo" id="repo">{auto}{opts}</select><label for="text">Wat moet Claude doen of uitzoeken?</label>'
            f'<textarea name="text" id="text" required></textarea><button>Taak sturen</button></form>'
            f'<h2>Nu</h2>{"".join(task_row(t) for t in queue) or "<p class=empty>Niets in de rij.</p>"}'
            f'<h2>Geschiedenis</h2>'
            f'{"".join(task_row(t) for t in all_tasks if t not in queue) or "<p class=empty>Nog geen taken.</p>"}')
    return page("taken", body, refresh=bool(queue))


def log_html(key):
    """Het log van een taak of run; app.js houdt het bij zolang hij loopt (zonder script: de stand bij het laden)."""
    lines, _ = read_log(key, 0, 2000)
    rows = "".join(f'<div class="k-{e["kind"]}"><time>{local(e["at"])[6:]}</time>{html.escape(e["text"])}</div>'
                   for e in lines)
    return (f'<h2>Log</h2><div class="log" role="log" data-key="{key}">'
            f'{rows or "<div class=log-end>Nog geen regels.</div>"}</div>')


def task_page(t):
    e = html.escape
    parts = [f'<h1>{e(label(t))}</h1>', task_row(t, link=False), f'<h2>Taak</h2><pre>{e(t["text"])}</pre>']
    if t["source"]:
        parts.insert(2, f'<p class="muted">Sent from {e(t["source"].capitalize())}'
                     + (', approved there (two-step confirm)' if t["repo"] == BRIEF else '') + '.</p>')
    if t["question"]:
        parts.append(f'<h2>Vraag van Claude</h2><pre>{e(t["question"])}</pre>')
        if t["status"] == "waiting":
            parts.append(form(f'/task/{t["id"]}/answer', '<label for="answer">Jouw antwoord</label>'
                              '<textarea name="answer" id="answer" required></textarea>', "Antwoorden"))
    if t["answer"]:
        parts.append(f'<h2>Antwoord</h2><pre>{e(t["answer"])}</pre>')
        if t["status"] == "answered" and not t["job"]:
            parts.append(form(f'/task/{t["id"]}/answer', '<label for="answer">Reageren op dit antwoord</label>'
                              '<textarea name="answer" id="answer" required></textarea>', "Reactie sturen"))
    if t["parent"]:
        parts.append(f'<p class="muted">Onderdeel van <a href="/task/{t["parent"]}">bericht #{t["parent"]}</a>'
                     + (f': {e(t["reason"])}' if t["reason"] else "")
                     + (f'. Start pas na <a href="/task/{t["after_task"]}">#{t["after_task"]}</a>' if t["after_task"] else "")
                     + '.</p>')
    if t["criteria"]:
        parts.append("<h2>Acceptance criteria</h2><ul>"
                     + "".join(f"<li>{e(c)}</li>" for c in json.loads(t["criteria"])) + "</ul>")
    if t["review"]:
        parts.append(f'<h2>Brief review</h2><p>{e(t["review"])}</p>'
                     + (f'<p class="muted">{t["reviews"]} automatic rework round(s) used.</p>' if t["reviews"] else ""))
    if t["repo"] == BRIEF and t["unattended"]:
        parts.append('<h2>Build without me</h2><p class="muted">' + html.escape(WITHOUT_ME) + '</p>'
                     + form(f'/task/{t["id"]}/attended', "", "Stop: I merge the rest myself", cls="small quiet"))
    elif t["repo"] == BRIEF and t["brief"]:
        parts.append('<p class="muted">You merge the PRs of this build yourself.</p>')
    if t["repo"] == BRIEF and t["brief"] and build_left_nothing(t):
        parts.append('<p class="muted">Nothing of this build is left: no task of it is still running or merged. '
                     f'A build has no Retry; start it again on the brief itself '
                     f'(<a href="/briefs/{e(t["brief"])}">{e(t["brief"])}</a>), as long as it is still approved. '
                     'A new build plans the brief as it stands on main then.</p>')
    if t["repo"] == BRIEF and t["handoff"]:
        parts.append(f'<h2>Handoff</h2><p>' + (f'<a href="{e(t["handoff"])}">{e(t["handoff"])}</a>'
                                              if t["handoff"].startswith("http") else e(t["handoff"])) + '</p>')
    kids = children(t["id"])
    if kids:
        parts.append(f"<h2>{'Planned tasks' if t['repo'] == BRIEF else 'Doorgestuurd naar'}</h2>"
                     + "".join(task_row(k) for k in kids))
    if t["summary"]:
        parts.append(f'<h2>{"Reden" if t["status"] == "refused" else "Samenvatting"}</h2><pre>{e(t["summary"])}</pre>')
    if t["note"]:
        parts.append(f'<p class="muted">{e(t["note"])}</p>')
    parts.append(f'<p class="muted">{t["turns"]} beurten in {t["rounds"]} ronde(s), {e(model_name(t["model"]))}. '
                 f'Grens per ronde: {CONF["MAX_TURNS"]} beurten of {CONF["MAX_MINUTES"]} minuten.</p>')
    if t["status"] in ACTIVE + ("waiting",) or (t["status"] == "routed" and not t["ended"]):
        parts.append(form(f'/task/{t["id"]}/cancel', "", "Annuleren", ghost=True))
    if t["status"] in RETRYABLE and t["repo"] not in (AUTO, BRIEF):
        pre = blocked_by(t)
        if pre:  # a retry would be cancelled again on the next pass of the scheduler
            parts.append(f'<p class="muted">Waits for <a href="/task/{pre["id"]}">#{pre["id"]}</a>: continue, '
                         'retry or mark that task as fine first; this one then comes back in the queue by itself.</p>')
        else:
            hint = ("Why it is fine to do after all, or what to do differently (optional)" if t["status"] == "refused"
                    else "Note for Claude (optional)")
            parts.append(form(f'/task/{t["id"]}/retry', f'<label for="note">{hint}</label>'
                              '<textarea name="note" id="note"></textarea>',
                              "Continue" if t["status"] == "capped" else "Retry"))
    if t["accepted"]:
        parts.append('<p class="muted">You marked this task as fine; its follow-ups may run.</p>')
    elif t["status"] in ACCEPTABLE and blocked_followups(t):
        pr = f'PR #{t["pr_number"]} is not merged as far as the inbox knows. ' if t["pr_number"] else ""
        parts.append(f'<p class="muted">{pr}Follow-ups start from main, so work of this task that is not merged will '
                     'not be in them.</p>' + form(f'/task/{t["id"]}/accept', "", "Mark as fine, continue follow-ups"))
    parts.append(log_html(f"t{t['id']}"))
    return page(f"inbox #{t['id']}", "".join(parts), refresh=t["status"] == "queued")


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


def catalogue_html():
    e = html.escape
    rows = []
    for a in load_agents():
        name = e(a["name"])
        if "error" in a:
            rows.append(f'<div class="row"><div class="row-title">{name}<span class="st failed">fout in het bestand</span>'
                        f'</div>{meta(e(a["error"]))}</div>')
            continue
        j = a["job"]
        what = e(j["instruction"].split("\n\n")[0][:300])
        action = (f'<a href="/job/{name}">staat aan</a>' if a["on"]
                  else form(f"/agents/{name}/on", "", "Aanzetten", cls="small"))
        rows.append(f'<div class="row"><div class="row-title">{name}<span>{action}</span></div><p class="muted">{what}</p>'
                    + meta(e(j["when"] or j["schedule"]), "model: " + ("lokaal" if j["model"] == "local" else "Claude"),
                           "bezorgt: " + e(j["deliver"]), "in te vullen bij aanzetten: " + e(a["fill"]) if a.get("fill") else "")
                    + '</div>')
    return ('<h2>Catalogus</h2><p class="muted">Kant-en-klare agents uit <code>agents/</code> in de repo. Aanzetten toont '
            'eerst het jobbestand; pas na Bevestigen bestaat de job.</p>' + ("".join(rows) or "<p class=empty>Geen agents.</p>"))


def jobs_page():
    e = html.escape
    cap = int(CONF.get("JOB_CLAUDE_CAP", 10))
    rows = []
    for name, job in sorted(JOBS.items()):
        if "error" in job:
            rows.append(f'<div class="row"><div class="row-title"><a href="/job/{e(name)}">{e(name)}</a>'
                        f'<span class="st failed">fout in het bestand</span></div>{meta(e(job["error"]))}</div>')
            continue
        last = job_runs(name, 1)
        last_txt = f'laatste: {e(RUN_NL.get(run_view(last[0])[0], ""))}' if last else ""
        rows.append(f'<div class="row"><div class="row-title"><a href="/job/{e(name)}">{e(name)}</a>'
                    f'<span>{e(JOB_KINDS[job["kind"]])}, {"lokaal" if job["model"] == "local" else "Claude"}</span></div>'
                    + meta(e(job["when"] or job["schedule"]), f'volgende: {e(next_run(job))}', last_txt) + '</div>')
    with db() as c:
        drafts = c.execute("SELECT * FROM drafts WHERE status IN ('drafting','ready','failed') ORDER BY id DESC "
                           "LIMIT 10").fetchall()
    open_drafts = "".join(f'<div class="row"><div class="row-title"><a href="/draft/{d["id"]}">concept #{d["id"]}</a>'
                          f'<span>{e(first_line(d["request"]))}</span></div></div>' for d in drafts)
    body = (f'<h1>Jobs</h1><p class="muted">Claude-runs door jobs vandaag: {claude_runs_today()} van {cap}. '
            f'Tijden in {TZ.key}.</p>'
            + '<p><a href="/research">Onderzoeksvraag stellen</a></p>'
            + form("/jobs/draft", '<label for="jobtext">Wat moet de job doen, en wanneer?</label><textarea name="text" '
                   'id="jobtext" required placeholder="Bijvoorbeeld: elke werkdag om 7:00, vertel me wat er vannacht '
                   'misging"></textarea>', "Job opstellen")
            + (f'<h2>Concepten</h2>{open_drafts}' if open_drafts else "")
            + catalogue_html()
            + f'<h2>Alle jobs</h2>{"".join(rows) or "<p class=empty>Nog geen jobs.</p>"}'
            + f'<h2>Laatste runs</h2>{"".join(run_row(r, True) for r in job_runs(limit=20)) or "<p class=empty>Nog geen runs.</p>"}')
    return page("jobs", body, refresh=any(d["status"] == "drafting" for d in drafts))


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


def skills_page():
    e = html.escape
    data = skills_data()
    rows = "".join(f'<div class="row"><div class="row-title"><b>{e(s["name"])}</b><span>{e(s["description"])}</span></div>'
                   + meta("jobs: " + (", ".join(f"<a href=/job/{e(j)}>{e(j)}</a>" for j in s["jobs"]) or "geen"))
                   + '</div>' for s in data["skills"])
    props = "".join(f'<div class="row"><div class="row-title"><b>{e(p["name"])}</b><a href="{e(p["pr_url"])}">PR</a>'
                    f'</div>' + meta(f'uit <a href="/task/{p["task_id"]}">taak #{p["task_id"]}</a>', p["created"])
                    + '</div>' for p in data["proposals"])
    rows = rows or "<p class=empty>" + data["empty"] + "</p>"
    props = props or "<p class=empty>" + data["proposals_empty"] + "</p>"
    return page("skills", '<h1>Skills</h1><p class="muted">' + data["intro"] + "</p>" + rows
                + "<h2>Voorstellen</h2>" + props)


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
    first). `status` is waiting, ok or fail. Shared by ssh_page and GET /api/ssh."""
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


def ssh_page(done=None, error=None, key="", name=""):
    """SSH-sleutels (via homelab-admin): aanvragen hier, bevestigen op de telefoon."""
    e = html.escape
    state = ssh_state()
    user, minutes = state["user"], state["confirm_minutes"]
    if done:
        top = (f'<div class="row"><div class="row-title"><span class="st waiting">bevestig op je telefoon</span>'
               f'<b>{e(name)}</b></div><div><code>{e(done["fingerprint"])}</code></div>'
               + meta(f'binnen {minutes} min, tot {local(done["deadline"])}', e(done["id"])) + '</div>')
    else:
        top = f'<p class="error">{e(error)}</p>' if error else ""
    label = {"waiting": "aanvraag", "ok": "ok", "fail": "fout"}
    rows = "".join(
        f'<div class="row"><div class="row-title"><span class="st {h["status"]}">{label[h["status"]]}</span>'
        f'<span>{e(h["summary"])}</span></div><div class="muted wrap">{e(h["result"])}</div>'
        + meta(e(local(h["at"])), e(h["by"])) + '</div>'
        for h in state["history"])
    body = (f'<h1>SSH</h1><p class="muted">Een publieke sleutel om als <code>{e(user)}</code> in te loggen. Hij komt pas '
            f'in <code>authorized_keys</code> als je binnen {minutes} minuten op <b>Bevestig</b> drukt in de melding op je '
            f'telefoon. Vergelijk daar de vingerafdruk met <code>ssh-keygen -lf ~/.ssh/id_ed25519.pub</code>.</p>{top}'
            f'<form method="post" action="/ssh"><input type="hidden" name="csrf" value="{csrf_token()}">'
            f'<label for="name">Naam (bijvoorbeeld het apparaat)</label>'
            f'<input name="name" id="name" required maxlength="40" pattern="[A-Za-z0-9._@-]+" value="{e(name)}" '
            f'placeholder="windows-pc">'
            f'<label for="key">Publieke sleutel (de inhoud van het .pub-bestand)</label>'
            f'<textarea name="key" id="key" required spellcheck="false" placeholder="ssh-ed25519 AAAA...">{e(key)}'
            f'</textarea><button>Sleutel aanvragen</button></form>'
            f'<p class="note">Nooit de privésleutel (het bestand zonder .pub). Weghalen doe je zelf: '
            f'<code>~{e(user)}/.ssh/authorized_keys</code>; sleutels van deze pagina hebben "homelab-pagina" in hun '
            f'commentaar.</p><h2>Geschiedenis</h2>{rows or "<p class=empty>Nog geen sleutels via deze pagina.</p>"}')
    return page("ssh", body)


def mem_matches(m, words):
    hay = " ".join((m["id"], m["about"], m["source"], m["status"], m["body"])).lower()
    return all(w in hay for w in words)


def mem_row(m):
    e = html.escape
    title = first_line(m["body"]).lstrip("# ") if m["kind"] == "geschiedenis" else m["name"]
    status = f'<span class="st">{e(m["status"])}</span>' if m["status"] else ""
    body = f'<div>{e(first_line(m["body"]))}</div>' if m["kind"] != "geschiedenis" else ""
    return (f'<div class="row"><div class="row-title"><a href="/memory/e/{e(m["id"])}">{e(title)}</a>'
            f'<span class="kind">{e(m["about"])}</span>{status}</div>{body}'
            + meta(local(m["changed"]), e(m["source"] or "")) + '</div>')


def memory_page(q="", error=None):
    e = html.escape
    words = q.lower().split()
    hits = [m for m in MEMORY if mem_matches(m, words)]
    abouts = ["algemeen", "box", *sorted(repo_list())]
    parts = [f'<h1>Geheugen</h1><p class="muted">Wat Claude in de inbox over jou, de box en eerdere taken weet. '
             f'Elke taak en Claude-job begint met de voorkeuren en de feiten; de geschiedenis kan hij doorzoeken. Wat je '
             f'hier wist of verandert, geldt vanaf de volgende taak.</p>'
             f'<form method="get" action="/memory" class="searchbar" role="search"><input type="search" name="q" '
             f'value="{e(q)}" aria-label="Zoeken in het geheugen" placeholder="Zoeken, bijvoorbeeld: deploy mislukt">'
             f'<button>Zoeken</button></form>']
    if error:
        parts.append(f'<p class="st failed">{e(error)}</p>')
    for kind, title in (("voorkeuren", "Voorkeuren"), ("feiten", "Feiten"), ("geschiedenis", "Geschiedenis")):
        items = [m for m in hits if m["kind"] == kind]
        parts.append(f'<h2>{title} <span class="muted">({len(items)})</span></h2>')
        if kind == "geschiedenis":
            items.sort(key=lambda m: m["added"], reverse=True)
            shown = items if q else items[:30]
            parts.append("".join(mem_row(m) for m in shown) or '<p class="empty">Niets.</p>')
            if len(shown) < len(items):
                parts.append(f'<p class="muted">De nieuwste 30; zoek om de rest te vinden.</p>')
            continue
        for about in sorted({m["about"] for m in items}, key=lambda a: (a not in ("algemeen", "box"), a)):
            parts.append(f'<h3>{e(about)}</h3>' + "".join(mem_row(m) for m in items if m["about"] == about))
        if not items:
            parts.append('<p class="empty">Niets.</p>')
    opts = "".join(f'<option value="{e(a)}">{e(a)}</option>' for a in abouts)
    parts.append('<h2>Zelf toevoegen</h2>' + form(
        "/memory/add", '<label for="mkind">Soort</label><select name="kind" id="mkind"><option value="voorkeuren">'
        'voorkeur</option><option value="feiten">feit</option></select><label for="mabout">Over</label>'
        f'<select name="about" id="mabout">{opts}</select><label for="mname">Naam</label><input name="name" '
        'id="mname" required placeholder="naam-met-streepjes" pattern="[a-z0-9][a-z0-9-]{0,40}"><label for="mtext">'
        'Tekst</label><textarea name="text" id="mtext" required placeholder="Bijvoorbeeld: houd PR\'s klein, één '
        'onderwerp per PR"></textarea>', "Toevoegen"))
    return page("geheugen", "".join(parts))


def entry_page(m, error=None, text=None):
    e = html.escape
    src = e(m["source"] or "-")
    if m["link"]:
        src = f'<a href="{e(m["link"])}">{src}</a>'
    rows = [("Soort", MEM_KINDS[m["kind"]]), ("Over", e(m["about"])), ("Toegevoegd", local(m["added"])),
            ("Gewijzigd", local(m["changed"])), ("Bron", src)]
    if m["status"]:
        rows.append(("Status", e(m["status"])))
    if m["pr"]:
        rows.append(("PR", f'<a href="{e(m["pr"])}">{e(m["pr"])}</a>'))
    parts = [f'<h1>{e(m["name"])}</h1>', f'<p class="muted"><code>{e(m["id"])}.md</code> op de branch '
             f'{e(CONF.get("MEMORY_BRANCH", "memory"))} van {e(CONF["DATA_REPO"])}</p>']
    if error:
        parts.append(f'<p class="st failed">{e(error)}</p>')
    parts.append('<dl>' + "".join(f"<dt>{k}</dt><dd>{v}</dd>" for k, v in rows) + '</dl>')
    parts.append(form(f"/memory/e/{e(m['id'])}/save", f'<textarea name="text" rows="10">{e(text or m["body"])}'
                      '</textarea>', "Opslaan"))
    parts.append(form(f"/memory/e/{e(m['id'])}/delete", "", "Wissen", ghost=True))
    return page(f"geheugen {m['name']}", "".join(parts))


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
