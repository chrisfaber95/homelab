"""Overview data for the homelab page (F-024): health, timeline, usage, live items."""
import json
import os
import urllib.request
from datetime import datetime, time as dt_time, timedelta, timezone
from pathlib import Path

from . import forge
from .briefs import WITHOUT_ME, build_left_nothing
from .config import ACCEPTABLE, ACTIVE, AUTO, BRIEF, CONF, JOBS, RETRYABLE, STATUS_NL, TZ, _round_started
from .helpers import first_line, label, read_json, read_log, repo_list
from .jobs import JOB_KINDS, LLM_URL, RUN_NL, run_view
from .router import blocked_by, blocked_followups, children
from .storage import db, tasks


MONITOR_DIR = Path(CONF.get("MONITOR_DIR", "/var/lib/homelab/monitor"))


DEPLOY_STATE = Path(os.environ.get("DEPLOY_STATE", "/srv/homelab/state"))


CHANGES_LOG = Path(CONF.get("LOG_FILE", "/var/log/homelab/changes.jsonl"))


DAYS_NL = ("ma", "di", "wo", "do", "vr", "za", "zo")


TL_PAST, TL_FUTURE, TL_NOW = timedelta(days=7), timedelta(hours=24), 70.0  # de laatste 24 uur krijgen 30% van de breedte


STALE_CHECK = timedelta(minutes=10)  # de monitor draait elke 2 minuten


def ts(iso):
    return datetime.fromisoformat(iso) if iso else None


def ago(iso):
    if not iso:
        return "nooit"
    sec = int((datetime.now(timezone.utc) - ts(iso)).total_seconds())
    return (f"{sec} s" if sec < 120 else f"{sec // 60} min" if sec < 7200 else f"{sec // 3600} uur" if sec < 172800
            else f"{sec // 86400} dagen")


def day_time(dt):
    """'vandaag 07:00', 'morgen 07:00', 'gisteren 22:10', of 'wo 07:00'; binnen een week, anders met datum."""
    d = dt.astimezone(TZ)
    delta = (d.date() - datetime.now(TZ).date()).days
    word = {0: "vandaag", 1: "morgen", -1: "gisteren"}.get(delta)
    if word is None:
        word = DAYS_NL[d.weekday()] + ("" if abs(delta) < 7 else f" {d:%d-%m}")
    return f"{word} {d:%H:%M}"


def minutes_nl(m):
    return f"{m // 60} u {m % 60:02d} min" if m >= 60 else f"{m} min"


def join_nl(items):
    items = list(items)
    return items[0] if len(items) == 1 else ", ".join(items[:-1]) + " en " + items[-1]


def health():
    checks = read_json(MONITOR_DIR / "checks.json", {})
    deploys = [read_json(f, {}) | {"name": f.stem} for f in sorted(DEPLOY_STATE.glob("*.json")) if not f.stem.startswith("_")]
    try:
        changes = [json.loads(l) for l in CHANGES_LOG.read_text().splitlines()[-5:]][::-1]
    except (OSError, ValueError):
        changes = []
    return {"checks": checks, "last": read_json(MONITOR_DIR / "last.json", {}).get("at"), "deploys": deploys,
            "backup": read_json(CONF.get("BACKUP_STATUS", MONITOR_DIR / "backup.json"), {}), "changes": changes}


def status_sentence(h):
    """De zin bovenaan: is alles in orde? Geeft (zin, klasse)."""
    fail = sorted(n for n, c in h["checks"].items() if c.get("status") == "FAIL")
    warn = sorted(n for n, c in h["checks"].items() if c.get("status") == "WARN")
    if not h["checks"]:
        return "Er is nog geen gezondheidscheck gedraaid.", "attention"
    if fail:
        return (f"Eén check faalt: {fail[0]}." if len(fail) == 1 else f"{len(fail)} checks falen: {join_nl(fail)}."), "bad"
    if h["last"] and datetime.now(timezone.utc) - ts(h["last"]) > STALE_CHECK:
        return f"De bewaking heeft sinds {day_time(ts(h['last']))} niet gecontroleerd.", "attention"
    if warn:
        return (f"Eén check vraagt aandacht: {warn[0]}." if len(warn) == 1
                else f"{len(warn)} checks vragen aandacht: {join_nl(warn)}."), "attention"
    return "Alles in orde.", "ok"


def model_name(model):
    return f"Claude ({model.removeprefix('claude-')})" if model else "Claude"


def live_items():
    """Wat nu draait: inbox-taken en Claude-jobs (als taak), lokale job-runs en forge (H-015)."""
    items = []
    recent = tasks(100)
    for t in recent:
        if t["status"] == "running":
            what = (JOB_KINDS.get(JOBS.get(t["job"], {}).get("kind"), "job") if t["job"] else first_line(t["text"]))
            items.append({"key": f"t{t['id']}", "title": label(t), "what": what, "repo": t["repo"],
                          "model": model_name(t["model"]), "url": f"/task/{t['id']}", "cancel": f"/task/{t['id']}/cancel",
                          "started": _round_started.get(t["id"]) or t["started"] or t["created"]})
    with db() as c:
        runs = c.execute("SELECT * FROM job_runs WHERE status='running' ORDER BY id").fetchall()
    for r in runs:
        items.append({"key": f"r{r['id']}", "title": f"Job {r['job']}", "what": JOB_KINDS.get(r["kind"], r["kind"]),
                      "repo": "box", "model": f"lokaal model ({CONF.get('LLM_MODEL', '?')})", "url": f"/run/{r['id']}",
                      "cancel": f"/run/{r['id']}/cancel", "started": r["started"] or r["created"]})
    # Forge draait als eigen dienst met eigen Claude-login: hij staat hier naast de taken.
    if (item := forge.live_item()) is not None:
        items.append(item)
    return items, sum(t["status"] == "queued" for t in recent)


def next_job():
    upcoming = [(job["cron"].next_after(datetime.now(timezone.utc)), name) for name, job in JOBS.items()
                if "error" not in job and not job["paused"]]
    return min(upcoming) if upcoming else None


WHO = {"t": "Claude", "r": "Het lokale model", "f": "Forge"}


def forge_sentence(it):
    """Forge noemt zijn project en wat hij nu doet; dat is zijn titel niet, dus een eigen zin."""
    where = f" aan {it['repo']}" if it["repo"] else ""
    return f"Forge bouwt{where}: {it['what']}."


def activity_sentence(items, queued):
    if not items:
        now_part = "Niets bezig."
    elif len(items) == 1 and items[0]["key"][0] == "f":
        now_part = forge_sentence(items[0])
    elif len(items) == 1:
        it = items[0]
        who = WHO.get(it["key"][0], "Claude")
        now_part = f"{who} werkt aan {it['title'][0].lower() + it['title'][1:]}."
    else:
        now_part = f"Bezig: {join_nl(i['title'][0].lower() + i['title'][1:] for i in items)}."
    if queued:
        now_part += f" {queued} in de rij."
    nxt = next_job()
    return now_part + (f" Volgende job: {nxt[1]}, {day_time(nxt[0])}." if nxt else "")


def tl_x(dt, now_dt):
    d = (dt - now_dt).total_seconds()
    if d <= 0:
        return max(0.0, TL_NOW * (1 + d / TL_PAST.total_seconds()))
    return min(100.0, TL_NOW + (100 - TL_NOW) * d / TL_FUTURE.total_seconds())


def timeline():
    """Per job: de runs van de laatste 7 dagen en de geplande van de komende 24 uur, met x in procenten."""
    now_dt = datetime.now(timezone.utc)
    with db() as c:
        runs = c.execute("SELECT * FROM job_runs WHERE COALESCE(started, created) >= ? ORDER BY id",
                         ((now_dt - TL_PAST).isoformat(timespec="seconds"),)).fetchall()
    lanes = []
    for name in sorted(set(JOBS) | {r["job"] for r in runs}):
        job, marks = JOBS.get(name), []
        for r in (r for r in runs if r["job"] == name):
            status, summary, link, _ = run_view(r)
            at = ts(r["started"] or r["created"])
            marks.append({"x": tl_x(at, now_dt), "status": status, "link": link,
                          "label": f"{day_time(at)}: {RUN_NL.get(status, status)}"
                                   + (f". {first_line(summary)}" if summary else "")})
        if job and "error" not in job and not job["paused"]:
            t = now_dt
            for _ in range(200):
                t = job["cron"].next_after(t)
                if t - now_dt > TL_FUTURE:
                    break
                marks.append({"x": tl_x(t, now_dt), "status": "plan", "link": f"/job/{name}",
                              "label": f"gepland {day_time(t)}"})
        lanes.append({"name": name, "job": job, "marks": marks})
    ticks, local_now = [], now_dt.astimezone(TZ)
    midnight = local_now.replace(hour=0, minute=0, second=0, microsecond=0)
    for i in range(-7, 2):  # middernachten: lijnen en weekdagen
        m = (midnight + timedelta(days=i)).replace(tzinfo=None).replace(tzinfo=TZ)
        x = tl_x(m, now_dt)
        if 0 < x < 100:
            ticks.append({"x": x, "label": DAYS_NL[m.weekday()], "day": True})
    for h in range(1, 25):  # de komende 24 uur: 6u, 12u, 18u
        t = (local_now + timedelta(hours=h)).replace(minute=0, second=0, microsecond=0)
        if t.hour in (6, 12, 18):
            ticks.append({"x": tl_x(t, now_dt), "label": f"{t.hour}u", "day": False})
    ticks = [t for t in ticks if abs(t["x"] - TL_NOW) > 6]  # niet over "nu" heen, ook op de telefoon
    return lanes, ticks


def usage():
    """Per dag (de laatste 7, in TZ): lokale LLM-calls, Claude-runs van jobs (tegen de daglimiet) en van inbox-taken,
    en de bespaarde minuten van gelukte job-runs per soort."""
    today = datetime.now(TZ).date()
    days = [today - timedelta(days=i) for i in range(6, -1, -1)]
    since = datetime.combine(days[0], dt_time(0), TZ).astimezone(timezone.utc).isoformat(timespec="seconds")

    def per_day(isos):
        counts = dict.fromkeys(days, 0)
        for iso in isos:
            d = ts(iso).astimezone(TZ).date()
            if d in counts:
                counts[d] += 1
        return [counts[d] for d in days]
    llm = None
    try:
        with urllib.request.urlopen(f"{LLM_URL}/usage/hourly?days=8", timeout=5) as r:
            hours = json.load(r)["hours"]
        counts = dict.fromkeys(days, 0)
        for h in hours:
            d = datetime.fromisoformat(h["hour"] + ":00:00+00:00").astimezone(TZ).date()
            if d in counts:
                counts[d] += h["count"]
        llm = [counts[d] for d in days]
    except (OSError, ValueError, KeyError):
        pass
    with db() as c:
        claude_jobs = [r[0] for r in c.execute("SELECT dispatched FROM job_runs WHERE task_id IS NOT NULL AND "
                                               "dispatched >= ?", (since,))]
        inbox = [r[0] for r in c.execute("SELECT created FROM tasks WHERE job IS NULL AND created >= ?", (since,))]
        runs = c.execute("SELECT * FROM job_runs WHERE COALESCE(started, created) >= ?", (since,)).fetchall()
    saved = {}
    for r in runs:
        mins = r["minutes"] if r["minutes"] is not None else JOBS.get(r["job"], {}).get("minutes")
        if mins and run_view(r)[0] in ("ok", "pr"):
            kind = JOB_KINDS.get(r["kind"], r["kind"])
            saved[kind] = saved.get(kind, 0) + mins
    return {"days": days, "llm": llm, "claude_jobs": per_day(claude_jobs), "inbox": per_day(inbox),
            "cap": int(CONF.get("JOB_CLAUDE_CAP", 10)), "saved": saved}


def task_row_view(t):
    """One row of the task lists: what pages.task_row renders, with the times as stored (ISO, UTC)."""
    return {"id": t["id"], "label": label(t), "status": t["status"], "status_text": STATUS_NL[t["status"]],
            "what": JOB_KINDS.get(JOBS.get(t["job"], {}).get("kind"), "job") if t["job"] else first_line(t["text"]),
            "repo": t["repo"], "source": t["source"], "created": t["created"], "ended": t["ended"],
            "pr_url": t["pr_url"], "pr_number": t["pr_number"]}


def tasks_view():
    """What pages.tasks_page renders: the repos for the new-task form, the queue (oldest first) and the history."""
    all_tasks = tasks()
    queue = [t for t in all_tasks if t["status"] in ACTIVE + ("waiting",)][::-1]
    return {"repos": sorted(repo_list()), "auto": AUTO, "queue": [task_row_view(t) for t in queue],
            "history": [task_row_view(t) for t in all_tasks if t not in queue], "refresh": bool(queue)}


def task_view(t):
    """What pages.task_page renders: the task with every derived fact the page shows, which forms apply (`actions`) and
    the log so far. Keep polling GET /api/log/t<id>?from=<log.next> until `done`."""
    pre = blocked_by(t) if t["status"] in RETRYABLE and t["repo"] not in (AUTO, BRIEF) else None
    is_build = t["repo"] == BRIEF
    lines, nxt = read_log(f"t{t['id']}", 0, 2000)
    active = t["status"] in ACTIVE + ("waiting",)
    more = read_log(f"t{t['id']}", nxt, 1)[0]  # past the 2000 lines: not done yet, the poll fetches the rest
    actions = {
        "answer": (t["status"] == "waiting" and bool(t["question"])) or (t["status"] == "answered" and not t["job"] and bool(t["answer"])),
        "cancel": active or (t["status"] == "routed" and not t["ended"]),
        "retry": t["status"] in RETRYABLE and not is_build and t["repo"] != AUTO and pre is None,
        "accept": not t["accepted"] and t["status"] in ACCEPTABLE and bool(blocked_followups(t)),
        "attended": is_build and bool(t["unattended"]),
    }
    return {
        **task_row_view(t), "text": t["text"], "question": t["question"], "answer": t["answer"], "parent": t["parent"],
        "reason": t["reason"], "after_task": t["after_task"], "brief": t["brief"], "handoff": t["handoff"],
        "criteria": json.loads(t["criteria"]) if t["criteria"] else [], "review": t["review"], "reviews": t["reviews"],
        "unattended": bool(t["unattended"]), "without_me": WITHOUT_ME, "is_build": is_build,
        "build_left_nothing": bool(is_build and t["brief"] and build_left_nothing(t)),
        "children": [task_row_view(k) for k in children(t["id"])], "summary": t["summary"], "summary_title":
        "Reden" if t["status"] == "refused" else "Samenvatting", "note": t["note"], "turns": t["turns"],
        "rounds": t["rounds"], "model": model_name(t["model"]), "max_turns": CONF["MAX_TURNS"],
        "max_minutes": CONF["MAX_MINUTES"], "accepted": bool(t["accepted"]),
        "blocked_by": pre["id"] if pre else None,
        "retry_label": "Continue" if t["status"] == "capped" else "Retry",
        "retry_hint": "Why it is fine to do after all, or what to do differently (optional)" if t["status"] == "refused"
        else "Note for Claude (optional)",
        "accept_pr_unmerged": bool(t["pr_number"]) and actions["accept"], "actions": actions,
        "refresh": t["status"] == "queued", "log": {"lines": lines, "next": nxt, "done": t["status"] not in ACTIVE and not more},
    }
