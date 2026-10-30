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

from .briefs import builds, live_build, load_briefs, split_frontmatter
from .config import ACTIVE, AUTO, BRIEF, CONF, JOBS, RETRYABLE, ROOT, SECRET_FILE, SERVICES, STATUS_NL, TZ
from .helpers import first_line, label, read_log, repo_list
from .jobs import JOB_KINDS, RUN_NL, claude_runs_today, draft_name, job_runs, parse_job, run_view, skill_list
from .memory import MEMORY, MEM_KINDS
from .overview import CHANGES_LOG, DAYS_NL, TL_NOW, activity_sentence, ago, day_time, health, live_items, minutes_nl, model_name, status_sentence, timeline, ts, usage
from .router import children
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
        ("geheugen", "/memory", "Geheugen"), ("skills", "/skills", "Skills"), ("ssh", "/ssh", "SSH"))


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
    if title.startswith(("geheugen", "skills", "ssh", "briefs")):
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
            + meta(e(t["repo"]), f'aangemaakt {local(t["created"])}',
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
        parts.append(form(f'/task/{t["id"]}/retry', "", "Retry"))
    parts.append(log_html(f"t{t['id']}"))
    return page(f"inbox #{t['id']}", "".join(parts), refresh=t["status"] == "queued")


def brief_actions(b):
    """The buttons for a brief: Build (with or without you), on a draft also the approval, and Park."""
    bid, draft = b["id"], b["status"] == "draft"
    build = live_build(bid)
    if build:
        return f'<a href="/task/{build["id"]}">build #{build["id"]}</a>'
    buttons = [form(f'/briefs/{bid}/build', "", "Approve &amp; build" if draft else "Build", cls="small"),
               form(f'/briefs/{bid}/build', '<input type="hidden" name="mode" value="without-me">',
                    "Approve &amp; build without me" if draft else "Build without me", cls="small quiet")]
    if draft:
        buttons.append(form(f'/briefs/{bid}/park', "", "Park", cls="small quiet"))
    return '<div class="actions">' + "".join(buttons) + '</div>'


def brief_row(b, actions=True):
    e = html.escape
    return (f'<div class="row"><div class="row-title"><a href="/briefs/{e(b["id"])}">{e(b["id"])}</a>'
            f'<span class="st {"warn" if b["status"] != "approved" else "ok"}">{e(b["status"] or "no status")}</span>'
            f'<span>{e(b["title"])}</span></div>'
            + meta(e(b["project"]), e(b["priority"]), "repos: " + e(", ".join(b["repos"]) or "-"))
            + (brief_actions(b) if actions else "") + '</div>')


WITHOUT_ME = ("The inbox merges each task PR itself once the brief reviewer finds every criterion met and CI is green, "
              "and then the handoff. A PR on homelab, a missed criterion after the rework round, red CI or criteria "
              "only you can check still wait for you.")


def briefs_page(error=None):
    """H-001: drafts to approve and approved briefs from the product repo, each with Build, and the builds so far."""
    e = html.escape
    try:
        items = load_briefs()
    except Exception as ex:
        items, error = [], f"Can't read {CONF['PRODUCT_REPO']}: {ex}"
    drafts = [b for b in items if b["status"] == "draft"]
    approved = [b for b in items if b["status"] == "approved"]
    design = [b for b in items if b["status"] == "needs-design"]
    counts = {}
    for b in items:
        counts[b["status"]] = counts.get(b["status"], 0) + 1
    done = builds()
    body = ('<h1>Briefs</h1>' + (f'<p class="error">{e(error)}</p>' if error else "")
            + '<p class="muted">Briefs on main of the product repo. Read a draft, then approve it here: Approve &amp; '
              'build commits <code>status: approved</code> and starts the planner, which splits the brief into inbox '
              'tasks; each PR is checked against the brief. Design new briefs with <code>cd ~/dev/product &amp;&amp; '
              'claude</code> on the box.</p>'
            + f'<p class="muted">Build without me: {e(WITHOUT_ME)}</p>'
            + '<h2>To approve</h2>' + ("".join(brief_row(b) for b in drafts) or "<p class=empty>No drafts.</p>")
            + '<h2>Approved</h2>' + ("".join(brief_row(b) for b in approved) or "<p class=empty>No approved briefs.</p>")
            + ('<h2>Needs design</h2>' + "".join(brief_row(b, actions=False) for b in design) if design else "")
            + '<h2>Builds</h2>' + ("".join(task_row(t) for t in done) or "<p class=empty>No builds yet.</p>")
            + '<p class="muted">In the repo: ' + e(", ".join(f"{n} {k or 'no status'}" for k, n in sorted(counts.items())))
            + '.</p>')
    return page("briefs", body, refresh=any(t["status"] in ACTIVE for t in done))


def brief_page(b, error=None):
    e = html.escape
    _, text = split_frontmatter(b["text"])
    body = (f'<h1>{e(b["id"])} {e(b["title"])}</h1>' + (f'<p class="error">{e(error)}</p>' if error else "")
            + brief_row(b, actions=False) + f'<p class="muted">{e(b["path"])} on main of the product repo.</p>'
            + f'<pre>{e(text.strip())}</pre>'
            + (brief_actions(b) if b["status"] in ("draft", "approved") else ""))
    return page("briefs", body)


def when(dt):
    dt = dt.astimezone(TZ)
    return f'{DAYS_NL[dt.weekday()]} {dt:%d-%m %H:%M}'


def next_run(job):
    if "error" in job:
        return "-"
    if job["paused"]:
        return "gepauzeerd"
    return day_time(job["cron"].next_after(datetime.now(timezone.utc)))


def run_row(r, show_job=False):
    e = html.escape
    status, summary, link, pr = run_view(r)
    head = f'<a href="/job/{e(r["job"])}">{e(r["job"])}</a>' if show_job else ""
    return (f'<div class="row"><div class="row-title">{head}<a href="{link}">{local(r["due"])}</a>'
            f'<span class="st run-{status}">{e(RUN_NL.get(status, status))}</span></div>'
            + meta("lokaal model" if r["model"] == "local" else "Claude", "nu gedraaid" if r["manual"] else "",
                   f'<a href="{e(pr)}">PR</a>' if pr else "", e(first_line(summary)) if summary else "")
            + '</div>')


def live_row(it):
    e = html.escape
    return (f'<div class="live-item" data-ssr="{it["key"]}" data-started="{it["started"]}">'
            f'<span class="pulse" aria-hidden="true"></span><div><div class="row-title"><a href="{it["url"]}">'
            f'{e(it["title"])}</a><span>{e(it["what"])}</span></div>'
            + meta(e(it["repo"]), e(it["model"]), f'bezig sinds <span class="elapsed">{ago(it["started"])}</span>')
            + '</div></div>')


def timeline_html():
    e = html.escape
    lanes, ticks = timeline()
    if not lanes:
        return ('<p class="empty">Nog geen jobs. <a href="/jobs">Maak een job</a> en hij verschijnt hier met zijn '
                'runs en zijn volgende keren.</p>')
    scale = "".join(f'<span style="left:{t["x"]:.2f}%">{t["label"]}</span>' for t in ticks)
    days = "".join(f'<i class="tl-day" style="left:{t["x"]:.2f}%"></i>' for t in ticks if t["day"])
    rows = []
    for lane in lanes:
        job = lane["job"]
        if not job:
            sub = "verwijderd"
        elif "error" in job:
            sub = "fout in het bestand"
        else:
            sub = ("gepauzeerd" if job["paused"] else
                   ("lokaal" if job["model"] == "local" else "Claude") + f", {e(job['when'] or job['schedule'])}")
        marks = "".join(f'<a class="mk {m["status"]}" style="left:{m["x"]:.2f}%" href="{m["link"]}" '
                        f'title="{e(m["label"])}" aria-label="{e(m["label"])}"></a>' for m in lane["marks"])
        rows.append(f'<div class="tl-row"><div class="tl-name"><a href="/job/{e(lane["name"])}">{e(lane["name"])}</a>'
                    f'<small>{sub}</small></div><div class="tl-lane">{days}<i class="tl-now"></i>{marks}</div></div>')
    legend = "".join(f'<span><i class="mk {k}"></i>{v}</span>' for k, v in (
        ("ok", "gelukt"), ("pr", "PR"), ("failed", "mislukt"), ("held", "wacht of vraag"), ("running", "bezig"),
        ("plan", "gepland")))
    return (f'<div class="tl"><div class="tl-scale" aria-hidden="true">{scale}'
            f'<span class="now" style="left:{TL_NOW}%">nu</span></div>{"".join(rows)}</div>'
            f'<div class="legend">{legend}</div>')


def bars(values, days, cap=None):
    top = max([*values, cap or 0, 1])
    today = len(values) - 1
    cols = "".join(f'<i{" class=today" if i == today else ""} style="height:{v / top * 100:.0f}%" '
                   f'title="{DAYS_NL[d.weekday()]} {d:%d-%m}: {v}"></i>' for i, (v, d) in enumerate(zip(values, days)))
    capline = f'<span class="cap" style="bottom:{cap / top * 100:.0f}%" title="daglimiet {cap}"></span>' if cap else ""
    letters = "".join(f"<span>{DAYS_NL[d.weekday()]}</span>" for d in days)
    return f'<div><div class="bars" aria-hidden="true">{cols}{capline}</div><div class="days" aria-hidden="true">{letters}</div></div>'


def usage_html():
    u = usage()
    rows = []
    if u["llm"] is None:
        rows.append('<div class="use"><div><div class="use-label">Lokaal model</div><div class="use-value">'
                    '<small>de helper is niet bereikbaar</small></div></div></div>')
    else:
        rows.append(f'<div class="use"><div><div class="use-label">Lokaal model</div><div class="use-value">'
                    f'{sum(u["llm"])}<small>calls, vandaag {u["llm"][-1]}</small></div></div>{bars(u["llm"], u["days"])}</div>')
    rows.append(f'<div class="use"><div><div class="use-label">Claude voor jobs</div><div class="use-value">'
                f'{sum(u["claude_jobs"])}<small>runs, vandaag {u["claude_jobs"][-1]} van {u["cap"]}</small></div></div>'
                f'{bars(u["claude_jobs"], u["days"], u["cap"])}</div>')
    rows.append(f'<div class="use"><div><div class="use-label">Claude voor jouw taken</div><div class="use-value">'
                f'{sum(u["inbox"])}<small>taken, vandaag {u["inbox"][-1]}</small></div></div>'
                f'{bars(u["inbox"], u["days"])}</div>')
    total = sum(u["saved"].values())
    kinds = "".join(f'<li><span>{html.escape(k)}</span><b>{minutes_nl(v)}</b></li>'
                    for k, v in sorted(u["saved"].items(), key=lambda kv: -kv[1]))
    rows.append(f'<div class="use"><div><div class="use-label">Tijd bespaard door jobs</div><div class="use-value">'
                f'{minutes_nl(total)}</div></div>'
                + (f'<ul class="saved">{kinds}</ul>' if kinds else
                   '<p class="saved">Telt gelukte runs van jobs met <code>minutes</code> in hun bestand.</p>')
                + '</div>')
    return "".join(rows)


CHECK_NL = {"OK": "in orde", "WARN": "let op", "FAIL": "faalt"}


def change_text(c):
    if c.get("kind") in ("instant", "refused"):
        what = f"{c.get('action')} {' '.join(c.get('args') or [])}"
    else:
        what = f"{c.get('change', 'machine.conf')}: {c.get('summary') or c.get('result', '')}"
    return what.strip()[:90]


def health_html(h):
    e = html.escape
    checks = sorted(h["checks"].items(), key=lambda kv: (kv[1].get("status") == "OK", kv[0]))
    bad = [(n, c) for n, c in checks if c.get("status") != "OK"]

    def check_row(n, c):
        st = c.get("status", "?")
        return (f'<div class="check"><span class="st {"ok" if st == "OK" else st.lower()}">{CHECK_NL.get(st, st)}</span>'
                f'<div><b>{e(n)}</b>' + meta(e(c.get("text", "")), f'sinds {day_time(ts(c["since"]))}' if c.get("since")
                                               else "") + '</div></div>')
    parts = [f'<p class="note">{len(checks) - len(bad)} van {len(checks)} checks in orde, laatst gecontroleerd '
             f'{day_time(ts(h["last"])) if h["last"] else "nooit"}. Alleen lezen.</p>']
    parts += [check_row(n, c) for n, c in bad]
    if checks:
        parts.append(f'<details><summary>Alle checks</summary>{"".join(check_row(n, c) for n, c in checks)}</details>')
    facts = []
    for d in h["deploys"]:
        last = d.get("last_deploy") or {}
        state = ("ok" if last.get("ok") else "mislukt") if last else "nog niet"
        facts.append((e(d["name"]), f'<span class="st {"ok" if last.get("ok") else "fail"}">{state}</span>, '
                                    f'{e((d.get("deployed_sha") or "-")[:7])}, {e(day_time(ts(last["at"])) if last else "-")}'
                                    + (f'<br><span class="error">{e(d["sync_error"])}</span>' if d.get("sync_error") else "")))
    b = h["backup"]
    if b:
        facts.append(("Backup", f'<span class="st {"ok" if b.get("ok") else "fail"}">{"ok" if b.get("ok") else "mislukt"}'
                                f'</span>, {e(day_time(ts(b["at"])) if b.get("at") else "-")}; '
                                f'{b.get("free_bytes", 0) // 2**30} GB vrij ({b.get("free_pct", "?")}%)'))
    else:
        facts.append(("Backup", "nog geen"))
    parts.append('<h3>Deploys en backup</h3><dl class="facts">'
                 + "".join(f"<dt>{k}</dt><dd>{v}</dd>" for k, v in facts) + "</dl>")
    return "".join(parts)


def changes_html(h):
    e = html.escape
    parts = []
    if h["changes"]:
        parts.append("".join(
            f'<div class="row"><div class="row-title"><span class="st {"ok" if c.get("ok") else "fail"}">'
            f'{"ok" if c.get("ok") else "fout"}</span><span>{e(change_text(c))}</span></div>'
            + meta(e(day_time(ts(c["at"]))), e(c.get("user", "")), e((c.get("reason") or "")[:80])) + '</div>'
            for c in h["changes"]))
    return "".join(parts)


def services_html():
    e = html.escape

    def address(a):
        if a.startswith(("http://", "https://")):
            return f'<a href="{e(a)}">{e(a.split("://", 1)[1])}</a>'
        return f"<code>{e(a)}</code>"
    facts = "".join(f'<dt>{e(s["name"])}</dt><dd>{address(s["address"])}<br><span class="muted">{e(s["note"])}</span></dd>'
                    for s in SERVICES)
    return ((f'<dl class="facts">{facts}</dl>' if facts else '<p class="empty">No services in config/services.conf.</p>')
            + '<h3>Run the designer</h3><p class="note">From your PC with Tailscale on, as <code>claude</code>. tmux '
            'keeps the session alive if SSH drops; to get back in, run the first two lines again.</p>'
            '<pre>ssh homelab-claude\ntmux new -A -s designer\ncd ~/dev/product &amp;&amp; claude</pre>'
            '<p class="note">It interviews you and merges the brief as a draft; approve it on the '
            '<a href="/briefs">Briefs</a> tab. More in docs/briefs.md.</p>')


def overview_page():
    h = health()
    sentence, cls = status_sentence(h)
    items, queued = live_items()
    live = "".join(live_row(it) for it in items) or \
        '<p class="empty">Niets bezig. Taken en jobs verschijnen hier zodra ze starten.</p>'
    body = (f'<section class="hero {cls}{" busy" if items else ""}"><div class="state-label">Status om '
            f'{datetime.now(TZ):%H:%M}</div><h1>{html.escape(sentence)}</h1><div class="scanner" aria-hidden="true">'
            f'</div><p id="activity">{html.escape(activity_sentence(items, queued))}</p></section>'
            f'<div class="grid"><div>'
            f'<section id="live" class="sec-live" data-csrf="{csrf_token()}"><div class="section-head"><h2>Nu bezig</h2>'
            f'<p class="note"><span class="queued">{f"{queued} in de rij" if queued else ""}</span> '
            f'<a href="/taken#nieuw">Taak sturen</a></p></div><div class="live-list">{live}</div></section>'
            f'<section class="sec-jobs"><div class="section-head"><h2>Jobs</h2><p class="note">Zeven dagen terug, 24 uur vooruit. '
            f'<a href="/jobs">Alle jobs</a></p></div>{timeline_html()}</section>'
            f'<section class="sec-changes"><div class="section-head"><h2>Wijzigingen op de box</h2><p class="note">de laatste vijf, '
            f'via homelab-admin</p></div>{changes_html(h) or "<p class=empty>Nog geen wijzigingen.</p>"}</section>'
            f'</div><div>'
            f'<section class="sec-services"><div class="section-head"><h2>Services</h2><p class="note">only over '
            f'Tailscale</p></div>{services_html()}</section>'
            f'<section class="sec-week"><div class="section-head"><h2>Deze week</h2><p class="note">de laatste 7 dagen</p></div>'
            f'{usage_html()}</section>'
            f'<section class="sec-health"><h2>Gezondheid</h2>{health_html(h)}</section>'
            f'</div></div>')
    return page("homelab", body, narrow=False)


def job_facts(job):
    e = html.escape
    times = ", ".join(when(t) for t in job["cron"].next_times(datetime.now(timezone.utc)))
    rows = [("Wanneer", f'{e(job["when"] or "-")} <span class="muted">({e(job["schedule"])}, {TZ.key})</span>'),
            ("Volgende keren", "gepauzeerd" if job["paused"] else e(times)),
            ("Soort", e(JOB_KINDS[job["kind"]])),
            ("Model", "lokaal (gratis)" if job["model"] == "local" else "Claude (telt mee voor de daglimiet)")]
    if job["repo"]:
        rows.append(("Repo", e(job["repo"])))
    if job["script"]:
        rows.append(("Script", f'<code>{e(" ".join([job["script"], *job["args"]]))}</code>'))
    if job["sources"]:
        rows.append(("Bronnen", "<br>".join(e(s) for s in job["sources"])))
    rows.append(("Skills", ", ".join(f'<a href="/skills">{e(s)}</a>' for s in job["skills"]) or "-"))
    if job["minutes"] is not None:
        rows.append(("Bespaart", f'{job["minutes"]} min per run'))
    return ('<dl>' + "".join(f"<dt>{k}</dt><dd>{v}</dd>" for k, v in rows) + '</dl>'
            f'<h2>Instructie</h2><pre>{e(job["instruction"])}</pre>')


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
            + form("/jobs/draft", '<label for="jobtext">Wat moet de job doen, en wanneer?</label><textarea name="text" '
                   'id="jobtext" required placeholder="Bijvoorbeeld: elke werkdag om 7:00, vertel me wat er vannacht '
                   'misging"></textarea>', "Job opstellen")
            + (f'<h2>Concepten</h2>{open_drafts}' if open_drafts else "")
            + f'<h2>Alle jobs</h2>{"".join(rows) or "<p class=empty>Nog geen jobs.</p>"}'
            + f'<h2>Laatste runs</h2>{"".join(run_row(r, True) for r in job_runs(limit=20)) or "<p class=empty>Nog geen runs.</p>"}')
    return page("jobs", body, refresh=any(d["status"] == "drafting" for d in drafts))


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
    return page(f"run {r['id']}", f'<h1>Job <a href="/job/{e(r["job"])}">{e(r["job"])}</a></h1>'
                f'<div class="row-title"><span>{local(r["due"])}</span><span class="st run-{status}">'
                f'{e(RUN_NL.get(status, status))}</span><span>{"lokaal model" if r["model"] == "local" else "Claude"}'
                f'</span></div><pre>{e(r["summary"] or "")}</pre>'
                + (f'<p class="muted">{e(r["note"])}</p>' if r["note"] else "") + cancel
                + (log_html(f"r{r['id']}") if r["model"] == "local" else ""),
                refresh=status == "queued")


def skills_page():
    e = html.escape
    used = {}
    for job in JOBS.values():
        for s in job.get("skills", []):
            used.setdefault(s, []).append(job["name"])
    rows = "".join(f'<div class="row"><div class="row-title"><b>{e(n)}</b><span>{e(d)}</span></div>'
                   + meta("jobs: " + (", ".join(f"<a href=/job/{e(j)}>{e(j)}</a>" for j in used.get(n, [])) or "geen"))
                   + '</div>' for n, d in skill_list().items())
    with db() as c:
        prs = c.execute("SELECT * FROM skill_prs ORDER BY id DESC LIMIT 20").fetchall()
    props = "".join(f'<div class="row"><div class="row-title"><b>{e(p["name"])}</b><a href="{e(p["pr_url"])}">PR</a>'
                    f'</div>' + meta(f'uit <a href="/task/{p["task_id"]}">taak #{p["task_id"]}</a>', local(p["created"]))
                    + '</div>' for p in prs)
    return page("skills", f'<h1>Skills</h1><p class="muted">Gemergde skills op main; elke taak en Claude-job kan '
                f'ze gebruiken. Nieuwe en betere komen alleen via een PR die jij merget.</p>'
                f'{rows or "<p class=empty>Nog geen skills.</p>"}<h2>Voorstellen</h2>'
                f'{props or "<p class=empty>Nog geen voorstellen.</p>"}')


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


def ssh_page(done=None, error=None, key="", name=""):
    """SSH-sleutels (via homelab-admin): aanvragen hier, bevestigen op de telefoon."""
    e = html.escape
    user = CONF.get("SSH_KEY_USER", "chris")
    minutes = int(CONF.get("CONFIRM_SECONDS", 300)) // 60
    if done:
        top = (f'<div class="row"><div class="row-title"><span class="st waiting">bevestig op je telefoon</span>'
               f'<b>{e(name)}</b></div><div><code>{e(done["fingerprint"])}</code></div>'
               + meta(f'binnen {minutes} min, tot {local(done["deadline"])}', e(done["id"])) + '</div>')
    else:
        top = f'<p class="error">{e(error)}</p>' if error else ""
    try:
        entries = [c for l in CHANGES_LOG.read_text().splitlines() if '"sshkey"' in l
                   and (c := json.loads(l)).get("kind") == "sshkey"]
    except (OSError, ValueError):
        entries = []

    def st(c):
        if c.get("result", "").startswith("aangevraagd"):
            return "waiting", "aanvraag"
        return ("ok", "ok") if c.get("ok") else ("fail", "fout")
    rows = "".join(
        f'<div class="row"><div class="row-title"><span class="st {st(c)[0]}">{st(c)[1]}</span>'
        f'<span>{e(c.get("summary", ""))}</span></div><div class="muted wrap">{e(c.get("result", ""))}</div>'
        + meta(e(local(c["at"])), e(c.get("user", ""))) + '</div>'
        for c in entries[::-1][:15])
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
