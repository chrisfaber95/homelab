"""Overview data for the homelab page (F-024): health, timeline, usage, live items."""
import json
import os
import urllib.request
from datetime import datetime, time as dt_time, timedelta, timezone
from pathlib import Path

from . import conversation, forge
from .config import CONF, JOBS, SERVICES, TZ, _round_started
from .helpers import first_line, label, read_json
from .jobs import JOB_KINDS, LLM_URL, RESEARCH_JOB, RUN_NL, claude_runs_today, job_runs, load_agents, run_view
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


def local_time(iso):
    """'dd-mm HH:MM' in the inbox's time zone, '-' when empty, the text as is when it is not a date."""
    try:
        return datetime.fromisoformat(iso).astimezone(TZ).strftime("%d-%m %H:%M") if iso else "-"
    except ValueError:
        return iso  # a date edited by hand in the memory


def jobs_data():
    """What the Jobs page renders, as data (GET /api/jobs-page): the counter, the open drafts, the catalogue, all jobs and
    the latest runs. The texts are the ones the page shows (Dutch); a broken job or catalogue file has `error`."""
    def model(m):
        return "lokaal" if m == "local" else "Claude"

    jobs = []
    for name, job in sorted(JOBS.items()):
        if "error" in job:
            jobs.append({"name": name, "error": job["error"]})
            continue
        last = job_runs(name, 1)
        status = run_view(last[0])[0] if last else None
        nxt = "gepauzeerd" if job["paused"] else day_time(job["cron"].next_after(datetime.now(timezone.utc)))
        jobs.append({"name": name, "error": None, "kind": JOB_KINDS[job["kind"]], "model": model(job["model"]),
                     "when": job["when"] or job["schedule"], "next": nxt, "paused": bool(job["paused"]),
                     "last": RUN_NL.get(status, "") if status else None})
    catalogue = []
    for a in load_agents():
        if "error" in a:
            catalogue.append({"name": a["name"], "error": a["error"]})
            continue
        j = a["job"]
        catalogue.append({"name": a["name"], "error": None, "on": bool(a["on"]),
                          "what": j["instruction"].split("\n\n")[0][:300], "when": j["when"] or j["schedule"],
                          "model": model(j["model"]), "deliver": j["deliver"], "fill": a.get("fill") or None})
    with db() as c:
        drafts = c.execute("SELECT * FROM drafts WHERE status IN ('drafting','ready','failed') ORDER BY id DESC "
                           "LIMIT 10").fetchall()
    runs = []
    for r in job_runs(limit=20):
        status, summary, link, pr = run_view(r)
        runs.append({"id": r["id"], "job": r["job"],
                     "job_url": "/research" if r["job"] == RESEARCH_JOB and r["job"] not in JOBS else f"/job/{r['job']}",
                     "due": local_time(r["due"]), "status": status, "status_label": RUN_NL.get(status, status),
                     "url": link, "model": model(r["model"]), "manual": bool(r["manual"]), "pr": pr,
                     "summary": first_line(summary) if summary else ""})
    return {"claude_runs_today": claude_runs_today(), "claude_cap": int(CONF.get("JOB_CLAUDE_CAP", 10)),
            "timezone": TZ.key, "refresh": any(d["status"] == "drafting" for d in drafts),
            "drafts": [{"id": d["id"], "status": d["status"], "request": first_line(d["request"])} for d in drafts],
            "catalogue": catalogue, "jobs": jobs, "runs": runs}


def change_text(c):
    if c.get("kind") in ("instant", "refused"):
        what = f"{c.get('action')} {' '.join(c.get('args') or [])}"
    else:
        what = f"{c.get('change', 'machine.conf')}: {c.get('summary') or c.get('result', '')}"
    return what.strip()[:90]


def overview_json():
    """Everything the Overview page (pages.overview_page) shows, as data for GET /api/overview. Texts are the same
    Dutch sentences the HTML page uses; times are ISO strings (UTC offset kept), not "vandaag 07:00"."""
    h = health()
    sentence, cls = status_sentence(h)
    items, queued = live_items()
    lanes, ticks = timeline()
    u = usage()
    now_dt = datetime.now(TZ)

    def lane(l):
        job = l["job"]
        state = ("deleted" if not job else "error" if "error" in job else "paused" if job["paused"] else "active")
        return {"name": l["name"], "state": state,
                "model": job["model"] if state in ("active", "paused") else None,
                "when": (job["when"] or job["schedule"]) if state in ("active", "paused") else None,
                "marks": l["marks"]}
    checks = {n: {"status": c.get("status", "?"), "text": c.get("text", ""), "since": c.get("since")}
              for n, c in h["checks"].items()}
    deploys = [{"name": d["name"], "ok": (d.get("last_deploy") or {}).get("ok") if d.get("last_deploy") else None,
                "at": (d.get("last_deploy") or {}).get("at"), "sha": d.get("deployed_sha"),
                "sync_error": d.get("sync_error")} for d in h["deploys"]]
    b = h["backup"]
    backup = ({"ok": bool(b.get("ok")), "at": b.get("at"), "free_bytes": b.get("free_bytes", 0),
               "free_pct": b.get("free_pct")} if b else None)
    return {
        "status": {"sentence": sentence, "class": cls, "at": now_dt.isoformat(timespec="seconds"),
                   "activity": activity_sentence(items, queued)},
        "live": {"items": items, "queued": queued},
        "timeline": {"lanes": [lane(l) for l in lanes], "ticks": ticks, "now_x": TL_NOW},
        "changes": [{"ok": bool(c.get("ok")), "text": change_text(c), "at": c.get("at"), "user": c.get("user", ""),
                     "reason": (c.get("reason") or "")[:80]} for c in h["changes"]],
        "services": [{"name": s["name"], "address": s["address"], "note": s["note"]} for s in SERVICES],
        "forge_link": next((s["address"] for s in SERVICES if s["name"] == "Forge"), ""),
        "switches": {"discord": conversation.switch("discord"),
                     "discord_approvals": conversation.switch("discord_approvals"),
                     "discord_on": conversation.state()["discord"],
                     "kitt": conversation.switch("kitt"),
                     "kitt_may": sorted(conversation.clients().get("kitt", set()))},
        "usage": {"days": [d.isoformat() for d in u["days"]], "llm": u["llm"], "claude_jobs": u["claude_jobs"],
                  "inbox": u["inbox"], "cap": u["cap"], "saved_minutes": u["saved"]},
        "health": {"checks": checks, "last": h["last"], "deploys": deploys, "backup": backup},
    }
