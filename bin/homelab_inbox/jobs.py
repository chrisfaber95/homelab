"""Jobs (F-022): scheduled jobs, drafts, skills proposals; local and Claude runs."""
import html
import json
import os
import re
import shlex
import subprocess
import tempfile
import threading
import time
import urllib.request
import uuid
from datetime import datetime, time as dt_time, timedelta, timezone
from pathlib import Path

import yaml

from .config import CONF, HOME, JOBS, TZ, _cancel_runs, _lock
from .helpers import as_list, gh, git, label, log_line, notify, page_url, refresh_cache, remote_url, repo_list, sync_branch, task_cmd
from .memory import load_memory
from .storage import db, get, log, now


JOB_KINDS = {"box-report": "boxrapport", "repo-chore": "repo-klus", "digest": "digest", "script": "script"}


JOB_FIELDS = {"name", "when", "schedule", "kind", "model", "repo", "skills", "script", "args", "sources", "minutes",
              "paused"}


NAME_RE = re.compile(r"^[a-z0-9][a-z0-9-]{0,40}$")


JOBS_DIR = HOME / "jobs-repo"


OVERVIEW_CMD = shlex.split(os.environ.get("OVERVIEW_CMD", "/usr/local/bin/homelab-overview"))


LLM_URL = os.environ.get("LLM_URL", f"http://127.0.0.1:{CONF.get('LLM_PORT', '8017')}")


LOCAL_MAX_CHARS = 9000  # past met de instructie in de context van het lokale model (F-017: 4096 tokens)


RUN_NL = {"queued": "in de rij", "running": "bezig", "ok": "ok", "failed": "mislukt", "held": "wacht op limiet",
          "question": "vraag", "pr": "PR", "capped": "gestopt bij limiet", "cancelled": "geannuleerd"}


# Hoe een Claude-run er in de job-geschiedenis uitziet, afgeleid van zijn inbox-taak.
TASK_TO_RUN = {"queued": "queued", "running": "running", "waiting": "question", "pr_open": "pr", "merged": "pr",
               "closed": "pr", "answered": "ok", "capped": "capped", "cancelled": "cancelled", "failed": "failed",
               "refused": "failed"}


_jobs_lock = threading.RLock()


class Cron:
    """Een cron-schema (minuut uur dag maand weekdag) in TZ: *, a-b, lijsten en /stap."""
    RANGES = [(0, 59), (0, 23), (1, 31), (1, 12), (0, 7)]

    def __init__(self, expr):
        parts = str(expr).split()
        if len(parts) != 5:
            raise ValueError("een schema heeft 5 velden: minuut uur dag maand weekdag")
        self.sets = [self.field(p, lo, hi) for p, (lo, hi) in zip(parts, self.RANGES)]
        if 7 in self.sets[4]:
            self.sets[4] = (self.sets[4] - {7}) | {0}
        self.any_dom, self.any_dow = parts[2] == "*", parts[4] == "*"

    @staticmethod
    def field(text, lo, hi):
        out = set()
        for item in text.split(","):
            rng, _, step = item.partition("/")
            try:
                step = int(step) if step else 1
                if rng == "*":
                    a, b = lo, hi
                elif "-" in rng:
                    a, b = map(int, rng.split("-", 1))
                else:
                    a = b = int(rng)
                    b = hi if step != 1 else b
            except ValueError:
                raise ValueError(f"'{item}' is geen geldig schemaveld") from None
            if not lo <= a <= b <= hi or step < 1:
                raise ValueError(f"'{item}' valt buiten {lo}-{hi}")
            out.update(range(a, b + 1, step))
        return out

    def day_ok(self, d):
        if d.month not in self.sets[3]:
            return False
        dom, dow = d.day in self.sets[2], d.isoweekday() % 7 in self.sets[4]
        # Zoals cron: zijn dag en weekdag allebei beperkt, dan is één van de twee genoeg.
        return dom and dow if self.any_dom or self.any_dow else dom or dow

    def next_after(self, after):
        start = after.astimezone(TZ).replace(tzinfo=None, second=0, microsecond=0) + timedelta(minutes=1)
        hours, minutes = sorted(self.sets[1]), sorted(self.sets[0])
        day = start.date()
        for _ in range(366 * 5):
            if self.day_ok(day):
                for h in hours:
                    for m in minutes:
                        moment = datetime.combine(day, dt_time(h, m))
                        if moment >= start:
                            return moment.replace(tzinfo=TZ)
            day += timedelta(days=1)
        raise ValueError("dit schema valt nooit")

    def next_times(self, after, n=3):
        out = []
        for _ in range(n):
            after = self.next_after(after)
            out.append(after)
        return out


def parse_job(name, text, repos=None, skills=None):
    """Een job-bestand lezen en controleren; ValueError met een leesbare reden als er iets niet klopt."""
    if not NAME_RE.match(name):
        raise ValueError("de naam mag alleen kleine letters, cijfers en streepjes hebben")
    if not text.startswith("---\n"):
        raise ValueError("het bestand begint niet met een kop tussen --- en ---")
    head, sep, rest = text[4:].partition("\n---")
    if not sep:
        raise ValueError("de kop sluit niet af met ---")
    try:
        meta = yaml.safe_load(head) or {}
    except yaml.YAMLError as e:
        raise ValueError(f"de kop is geen geldige YAML: {e}") from None
    if not isinstance(meta, dict):
        raise ValueError("de kop is geen lijst van velden")
    if unknown := set(meta) - JOB_FIELDS:
        raise ValueError(f"onbekende velden: {', '.join(sorted(map(str, unknown)))}")
    if meta.get("name") not in (None, name):
        raise ValueError(f"name ({meta['name']}) is niet gelijk aan de bestandsnaam ({name})")
    job = {"name": name, "when": str(meta.get("when") or "").strip(), "schedule": str(meta.get("schedule") or ""),
           "kind": meta.get("kind"), "model": meta.get("model"), "repo": meta.get("repo") or None,
           "skills": as_list(meta.get("skills")), "script": meta.get("script"), "args": as_list(meta.get("args")),
           "sources": as_list(meta.get("sources")), "minutes": meta.get("minutes"),
           "paused": meta.get("paused") is True, "instruction": rest.partition("\n")[2].strip(), "text": text}
    job["cron"] = Cron(job["schedule"])
    if job["kind"] not in JOB_KINDS:
        raise ValueError(f"kind moet een van {', '.join(JOB_KINDS)} zijn")
    if job["model"] not in ("local", "claude"):
        raise ValueError("model moet local of claude zijn")
    if not job["instruction"]:
        raise ValueError("de instructie onder de kop ontbreekt")
    repos = repo_list() if repos is None else repos
    if job["repo"] and job["repo"] not in repos:
        raise ValueError(f"repo {job['repo']} staat niet in de lijst (F-018) en is niet homelab")
    if job["kind"] == "repo-chore" and (job["model"] != "claude" or not job["repo"]):
        raise ValueError("een repo-klus draait op Claude en heeft een repo nodig")
    if job["kind"] == "script":
        job["repo"] = job["repo"] or "homelab"
        if not re.match(r"^[A-Za-z0-9_][A-Za-z0-9._/-]*$", str(job["script"] or "")) or ".." in str(job["script"]):
            raise ValueError("script moet een pad in de repo zijn, bijvoorbeeld scripts/iets.sh")
    if job["kind"] == "digest" and not job["sources"]:
        raise ValueError("een digest heeft sources nodig (URL's of github:eigenaar/repo)")
    for s in job["sources"]:
        if not re.match(r"^(https?://\S+|github:[\w.-]+/[\w.-]+)$", s):
            raise ValueError(f"bron {s} is geen http(s)-URL of github:eigenaar/repo")
    if job["skills"] and job["model"] != "claude":
        raise ValueError("skills gelden alleen voor Claude-jobs (model: claude)")
    known = skill_list() if skills is None else skills
    if missing := [s for s in job["skills"] if s not in known]:
        raise ValueError(f"skill(s) {', '.join(missing)} staan (nog) niet op main")
    if job["minutes"] is not None and (not isinstance(job["minutes"], int) or job["minutes"] < 0):
        raise ValueError("minutes is een heel aantal minuten (0 of meer)")
    return job


def jobs_sync():
    """De clone met de job-branch."""
    return sync_branch(JOBS_DIR, CONF.get("JOBS_BRANCH", "jobs"))


def load_jobs(fetch=True):
    with _jobs_lock:
        if fetch:
            try:
                refresh_cache("homelab", CONF["HOMELAB_REPO"])  # voor de lijst met skills
                jobs_sync()
            except Exception as e:
                log(f"jobs ophalen: {e}")  # dan de laatst opgehaalde
        repos, skills, found = repo_list(), skill_list(), {}
        for f in sorted((JOBS_DIR / "jobs").glob("*.md")):
            text = f.read_text()
            try:
                found[f.stem] = parse_job(f.stem, text, repos, skills)
            except ValueError as e:
                found[f.stem] = {"name": f.stem, "error": str(e), "text": text}
        JOBS.clear()
        JOBS.update(found)


def save_job(name, text, message):
    """Een job opslaan (text) of weghalen (None): één commit op de job-branch, gepusht met het token van de dienst."""
    branch = CONF.get("JOBS_BRANCH", "jobs")
    with _jobs_lock:
        d = jobs_sync()
        f = d / "jobs" / f"{name}.md"
        if text is None:
            f.unlink(missing_ok=True)
        else:
            f.parent.mkdir(exist_ok=True)
            f.write_text(text.strip() + "\n")
        readme = d / "README.md"
        if not readme.exists():
            readme.write_text("# jobs\n\nDe geplande jobs van de homelab (F-022), één bestand per job in `jobs/`. "
                              "Uitleg: `docs/jobs.md` op `main`.\nDeze branch wordt nooit gemerged; de homelab-inbox "
                              "leest hem en slaat hier op wat je op de pagina bevestigt.\n")
        git(d, "add", "-A")
        if not git(d, "status", "--porcelain").strip():
            return
        git(d, "commit", "--quiet", "-m", message)
        # Alleen naar de job-branch: de bestemming staat hier vast.
        git(d, "push", "--quiet", "origin", f"HEAD:refs/heads/{branch}")
        load_jobs(fetch=False)


def set_paused(text, paused):
    head, sep, rest = text[4:].partition("\n---")
    lines = [l for l in head.splitlines() if not l.startswith("paused:")]
    if paused:
        lines.append("paused: true")
    return "---\n" + "\n".join(lines) + sep + rest


def today_start():
    return datetime.now(TZ).replace(hour=0, minute=0, second=0, microsecond=0).astimezone(timezone.utc) \
        .isoformat(timespec="seconds")


def kv_get(key):
    with db() as c:
        row = c.execute("SELECT value FROM kv WHERE key=?", (key,)).fetchone()
    return row["value"] if row else None


def kv_set(key, value):
    with _lock, db() as c:
        c.execute("INSERT OR REPLACE INTO kv (key, value) VALUES (?,?)", (key, value))


def update_run(run_id, **fields):
    with _lock, db() as c:
        c.execute(f"UPDATE job_runs SET {', '.join(k + '=?' for k in fields)} WHERE id=?", [*fields.values(), run_id])


def claude_runs_today():
    with db() as c:
        return c.execute("SELECT COUNT(*) FROM job_runs WHERE task_id IS NOT NULL AND dispatched >= ?",
                         (today_start(),)).fetchone()[0]


def job_scheduler():
    last_load = 0
    while True:
        try:
            if time.monotonic() - last_load > 60:
                load_jobs()
                load_memory()
                last_load = time.monotonic()
            tick(datetime.now(timezone.utc))
        except Exception as e:
            log(f"jobs: {e}")
        time.sleep(20)


def tick(now_dt):
    """Elke job die sinds zijn vorige run aan de beurt was, draait één keer (ook na een tijd uit: niet per gemist moment)."""
    for name, job in list(JOBS.items()):
        if "error" in job:
            continue
        with db() as c:
            row = c.execute("SELECT last_due FROM job_state WHERE name=?", (name,)).fetchone()
        if row is None or job["paused"]:
            # Nieuw of gepauzeerd: vanaf nu tellen, dus geen inhaalrun voor de tijd daarvoor.
            with _lock, db() as c:
                c.execute("INSERT OR REPLACE INTO job_state (name, last_due) VALUES (?,?)",
                          (name, now_dt.isoformat(timespec="seconds")))
            continue
        due, nxt = None, job["cron"].next_after(datetime.fromisoformat(row["last_due"]))
        for _ in range(5000):
            if nxt > now_dt:
                break
            due, nxt = nxt, job["cron"].next_after(nxt)
        if due:
            with _lock, db() as c:
                c.execute("UPDATE job_state SET last_due=? WHERE name=?", (due.isoformat(timespec="seconds"), name))
            dispatch(job, due.astimezone(timezone.utc).isoformat(timespec="seconds"))
    release_held()


def dispatch(job, due, manual=False):
    with _lock, db() as c:
        run_id = c.execute("INSERT INTO job_runs (job, kind, model, due, created, status, manual, minutes) "
                           "VALUES (?,?,?,?,?,?,?,?)", (job["name"], job["kind"], job["model"], due, now(), "queued",
                                                        int(manual), job["minutes"])).lastrowid
    threading.Thread(target=run_job, args=(run_id, job), daemon=True).start()
    return run_id


def run_job(run_id, job):
    try:
        if job["model"] == "local":
            return run_local(run_id, job)
        start_claude(run_id, job)
    except Exception as e:
        log(f"job {job['name']}: {e}")
        update_run(run_id, status="failed", ended=now(), note=str(e)[:500])
        notify(f"Job {job['name']} mislukt", str(e)[:300], job_url(job["name"]))


def gather(job, run_id):
    """De tekst waar een lokale job (of een Claude-boxrapport of -digest) mee werkt."""
    if job["kind"] == "box-report":
        p = subprocess.run(OVERVIEW_CMD, capture_output=True, text=True, timeout=60)
        if p.returncode != 0:
            raise RuntimeError(f"homelab-overview: {(p.stderr or p.stdout).strip()[-300:]}")
        return p.stdout
    if job["kind"] == "digest":
        per = LOCAL_MAX_CHARS // len(job["sources"])
        parts = [fetch_source(s)[:per] for s in job["sources"]]
        if all("(ophalen mislukt" in p for p in parts):
            raise RuntimeError("geen enkele bron kon worden opgehaald")
        return "\n\n".join(parts)
    if job["kind"] == "script":
        refresh_cache(job["repo"], repo_list()[job["repo"]])
        code, out = task_cmd("script", f"j{run_id}", job["repo"], job["script"], *job["args"],
                             timeout=60 * int(CONF.get("JOB_SCRIPT_MINUTES", 10)) + 60)
        if code == 65:  # homelab-inbox-task: repo of pad ongeldig, het script draaide niet
            raise RuntimeError(out[-300:])
        return f"$ {job['script']} {' '.join(job['args'])}\n(exitcode {code})\n\n{out}"
    raise RuntimeError(f"een {job['kind']} draait niet lokaal")


def fetch_source(src):
    try:
        if src.startswith("github:"):
            slug = src.removeprefix("github:")
            since = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat(timespec="seconds").replace("+00:00", "Z")
            commits = gh("GET", f"/repos/{slug}/commits?since={since}&per_page=30") or []
            pulls = [p for p in gh("GET", f"/repos/{slug}/pulls?state=all&sort=updated&direction=desc&per_page=20") or []
                     if (p.get("updated_at") or "") >= since]
            lines = [f"commit {c['sha'][:7]} {c['commit']['message'].splitlines()[0]}" for c in commits]
            lines += [f"PR #{p['number']} ({'gemerged' if p.get('merged_at') else p['state']}): {p['title']}"
                      for p in pulls]
            return f"## GitHub {slug}, laatste 24 uur\n" + ("\n".join(lines) or "geen activiteit")
        req = urllib.request.Request(src, headers={"User-Agent": "homelab-jobs/1"})
        with urllib.request.urlopen(req, timeout=20) as r:
            raw = r.read(2_000_000).decode(r.headers.get_content_charset() or "utf-8", "replace")
        return f"## {src}\n" + (feed_text(raw) if re.search(r"<(rss|feed)[\s>]", raw[:2000]) else html_text(raw))
    except Exception as e:
        return f"## {src}\n(ophalen mislukt: {e})"


def feed_text(raw):
    import xml.etree.ElementTree as ET
    root = ET.fromstring(raw)
    items = []
    for it in root.iter():
        tag = it.tag.rsplit("}", 1)[-1]
        if tag in ("item", "entry"):
            get_ = lambda name: next((c.text or "" for c in it if c.tag.rsplit("}", 1)[-1] == name), "").strip()
            link = get_("link") or next((c.get("href", "") for c in it if c.tag.rsplit("}", 1)[-1] == "link"), "")
            items.append(f"- {get_('title')} ({get_('pubDate') or get_('updated') or get_('published')}) {link}")
    return "\n".join(items[:20]) or "geen items"


def html_text(raw):
    raw = re.sub(r"(?is)<(script|style|nav|footer|header)\b.*?</\1>", " ", raw)
    return re.sub(r"\s+", " ", html.unescape(re.sub(r"<[^>]+>", " ", raw))).strip()


def call_llm(job, text):
    body = {"model": "summarize", "user": f"job:{job['name']}", "timeout": 120,
            "messages": [{"role": "system", "content": job["instruction"]}, {"role": "user", "content": text}]}
    req = urllib.request.Request(f"{LLM_URL}/v1/chat/completions", data=json.dumps(body).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "X-LLM-Caller": f"job:{job['name']}"})
    try:
        with urllib.request.urlopen(req, timeout=130) as r:
            return json.load(r)["choices"][0]["message"]["content"].strip()
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"LLM-helper: {e.read().decode(errors='replace')[:300]}") from None


def run_local(run_id, job):
    """Een lokale job: tekst verzamelen en door het lokale model laten samenvatten. Geen Claude."""
    update_run(run_id, status="running", started=now())
    key = f"r{run_id}"
    try:
        log_line(key, "info", f"{JOB_KINDS[job['kind']]}: tekst verzamelen")
        text = gather(job, run_id)
        log_line(key, "out" if job["kind"] == "script" else "info",
                 text[-4000:] if job["kind"] == "script" else f"{len(text)} tekens verzameld")
        if run_id in _cancel_runs:
            raise InterruptedError
        if len(text) > LOCAL_MAX_CHARS:
            text = text[:LOCAL_MAX_CHARS] + "\n[afgekapt]"
            log_line(key, "info", f"afgekapt op {LOCAL_MAX_CHARS} tekens")
        log_line(key, "tool", "lokaal model: samenvatten")
        started = time.monotonic()
        summary = call_llm(job, text)
        if run_id in _cancel_runs:
            raise InterruptedError
        log_line(key, "claude", summary)
        log_line(key, "info", f"klaar in {round(time.monotonic() - started)} s")
        update_run(run_id, status="ok", ended=now(), summary=summary)
    except InterruptedError:
        log_line(key, "info", "geannuleerd")
        update_run(run_id, status="cancelled", ended=now(), note="geannuleerd op de pagina")
    except Exception as e:
        if run_id in _cancel_runs:
            log_line(key, "info", "geannuleerd")
            update_run(run_id, status="cancelled", ended=now(), note="geannuleerd op de pagina")
        else:
            log_line(key, "error", str(e))
            update_run(run_id, status="failed", ended=now(), note=str(e)[:500])
            notify(f"Job {job['name']} mislukt", str(e)[:300], job_url(job["name"]))
    finally:
        _cancel_runs.discard(run_id)


def job_prompt(job, run_id):
    parts = [f"Dit is de geplande job '{job['name']}' ({JOB_KINDS[job['kind']]}), niet door de gebruiker getypt; hij "
             f"kijkt niet mee.\n\n{job['instruction']}"]
    if job["kind"] in ("box-report", "digest"):
        parts.append("Hieronder de verzamelde gegevens. Eindig met ANTWOORD: (je rapport of digest); commit niets.\n\n"
                     + gather(job, run_id)[:30000])
    elif job["kind"] == "script":
        cmd = " ".join([job["script"], *job["args"]])
        parts.append(f"Draai `{cmd}` in deze werkkopie (als dit account, zonder root) en vat de uitvoer samen zoals "
                     "hierboven gevraagd. Eindig met ANTWOORD:; commit niets.")
    else:
        parts.append("Maak de wijziging op deze branch en eindig met KLAAR:. Is er niets te doen, eindig dan met "
                     "ANTWOORD: en waarom (dan komt er geen PR).")
    if job["skills"]:
        parts.append(f"Gebruik hiervoor de skill(s): {', '.join(job['skills'])}.")
    return "\n\n".join(parts)


def start_claude(run_id, job):
    """Een Claude-job wordt een inbox-taak: zelfde rij, zelfde regels, plus een daglimiet voor jobs."""
    cap = int(CONF.get("JOB_CLAUDE_CAP", 10))
    with _lock:
        if claude_runs_today() >= cap:
            with db() as c:
                older = c.execute("SELECT id FROM job_runs WHERE job=? AND status='held' AND id<>?",
                                  (job["name"], run_id)).fetchone()
                if older:  # hooguit één wachtende run per job
                    c.execute("DELETE FROM job_runs WHERE id=?", (run_id,))
                else:
                    c.execute("UPDATE job_runs SET status='held', note=? WHERE id=?",
                              (f"daglimiet van {cap} Claude-runs bereikt; draait morgen", run_id))
            if kv_get("cap_notified") != today_start():
                kv_set("cap_notified", today_start())
                notify("Jobs: daglimiet Claude bereikt", f"{cap} Claude-runs vandaag; verdere Claude-jobs wachten tot "
                       "morgen. Lokale jobs en je eigen taken lopen gewoon.", page_url() + "/jobs")
            return
        text = job_prompt(job, run_id)
        with db() as c:
            task_id = c.execute("INSERT INTO tasks (repo, text, status, created, session, job, run_id) "
                                "VALUES (?,?,?,?,?,?,?)", (job["repo"] or "homelab", text, "queued", now(),
                                                           str(uuid.uuid4()), job["name"], run_id)).lastrowid
            c.execute("UPDATE job_runs SET status='claude', task_id=?, dispatched=?, note=NULL WHERE id=?",
                      (task_id, now(), run_id))


def release_held():
    """Wat gisteren (of eerder) op de daglimiet wachtte, gaat vandaag als eerste."""
    with db() as c:
        held = c.execute("SELECT * FROM job_runs WHERE status='held' AND created < ? ORDER BY id",
                         (today_start(),)).fetchall()
    for r in held:
        if claude_runs_today() >= int(CONF.get("JOB_CLAUDE_CAP", 10)):
            break
        job = JOBS.get(r["job"])
        if not job or "error" in job or job["paused"]:
            update_run(r["id"], status="cancelled", ended=now(), note="job weg, gepauzeerd of met een fout")
            continue
        run_job(r["id"], job)


def run_view(r):
    """Status, samenvatting en link van een run; voor een Claude-run uit zijn taak."""
    if r["task_id"] and (t := get(r["task_id"])):
        summary = t["answer"] or t["summary"] or t["question"] or t["note"] or ""
        return TASK_TO_RUN[t["status"]], summary, f"/task/{t['id']}", t["pr_url"]
    return r["status"], r["summary"] or r["note"] or "", f"/run/{r['id']}", None


def job_runs(name=None, limit=30):
    with db() as c:
        if name:
            return c.execute("SELECT * FROM job_runs WHERE job=? ORDER BY id DESC LIMIT ?", (name, limit)).fetchall()
        return c.execute("SELECT * FROM job_runs ORDER BY id DESC LIMIT ?", (limit,)).fetchall()


def job_url(name):
    return f"{page_url()}/job/{name}"


SKILL_RE = re.compile(r"^SKILL-VOORSTEL:\s*([a-z0-9][a-z0-9-]{0,40})\s*\n\s*beschrijving:\s*(.+?)\n(.+)$", re.S | re.M)


def skill_list():
    """De gemergde skills (uit de kopie van main van de dienst): naam -> beschrijving."""
    out = {}
    for f in sorted((HOME / "repos" / "homelab" / "skills").glob("*/SKILL.md")):
        text = f.read_text()
        m = re.search(r"^description:\s*(.+)$", text.partition("\n---")[0], re.M)
        out[f.parent.name] = (m.group(1).strip().strip('"') if m else "")
    return out


def split_skill(text):
    """Het antwoord van Claude zonder een eventueel skill-voorstel, en dat voorstel (of None)."""
    i = text.find("SKILL-VOORSTEL:")
    if i < 0:
        return text, None
    m = SKILL_RE.match(text[i:].strip())
    return text[:i].strip(), (m.groups() if m else None)


def propose_skill(t, proposal):
    name, desc, body = proposal
    slug = CONF["HOMELAB_REPO"]
    try:
        cache = refresh_cache("homelab", slug)
        exists = (cache / "skills" / name / "SKILL.md").exists()
        branch = f"skill/{name}-{t['id']}"
        with tempfile.TemporaryDirectory(dir=HOME) as tmp:
            repo = Path(tmp) / "r"
            git(HOME, "clone", "--quiet", str(cache), str(repo))
            git(repo, "checkout", "--quiet", "-b", branch)
            f = repo / "skills" / name / "SKILL.md"
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(f"---\nname: {name}\ndescription: {json.dumps(desc.strip(), ensure_ascii=False)}\n---\n\n"
                         f"{body.strip()}\n")
            git(repo, "add", "-A")
            git(repo, "-c", "user.name=Claude (homelab-inbox)", "-c", "user.email=noreply@anthropic.com", "commit",
                "--quiet", "-m", f"Skill {name}: {'verbetering' if exists else 'nieuw'} (voorstel uit {label(t)})")
            # Alleen naar skill/<naam>-<id>, nooit naar main.
            git(repo, "push", "--quiet", remote_url("homelab", slug), f"HEAD:refs/heads/{branch}")
        pr = gh("POST", f"/repos/{slug}/pulls", {
            "title": f"skill: {name}" + (" (verbetering)" if exists else ""), "head": branch, "base": "main",
            "body": f"{desc.strip()}\n\nVoorgesteld door Claude na {label(t)} ({page_url(t['id'])}). Na de merge "
                    "gebruiken taken en jobs deze skill; zonder merge niet. Claude kan hem niet zelf mergen."
                    "\n\n<!-- homelab-inbox skill -->"})
        with _lock, db() as c:
            c.execute("INSERT INTO skill_prs (name, task_id, pr_url, created) VALUES (?,?,?,?)",
                      (name, t["id"], pr["html_url"], now()))
        notify(f"Skill-voorstel: {name}", desc.strip()[:300], pr["html_url"])
    except Exception as e:
        log(f"skill-voorstel {name}: {e}")
        notify(f"Skill-voorstel {name} mislukt", str(e)[:300], page_url(t["id"]))


DRAFT_SYSTEM = """Je stelt een job-bestand op voor de homelab-inbox (F-022). Je hebt geen tools; je antwoordt alleen met
het bestand, in één ```job-blok, zonder verdere tekst. Formaat:

```job
---
name: korte-naam-met-streepjes
when: het schema in gewone woorden, in het Nederlands
schedule: "0 7 * * 1-5"   (alleen de vijf cron-velden: minuut uur dag maand weekdag; tijdzone {tz}; 0 = zondag)
kind: box-report | repo-chore | digest | script
model: local | claude
repo: alleen bij repo-chore (verplicht) en script (standaard homelab); een van: {repos}
skills: [alleen bij model claude, en alleen uit: {skills}]
script: alleen bij script: pad in de repo
args: [alleen bij script, optioneel]
sources: [alleen bij digest: https-URL's (RSS of webpagina) of github:eigenaar/repo]
minutes: hoeveel minuten handwerk een run de gebruiker bespaart (schatting, geheel getal)
---
De instructie: wat de job doet en hoe het resultaat eruit moet zien, kort en concreet.
```

Soorten: box-report (rapport uit de staat van de box: health checks, deploys, LLM-gebruik, wijzigingen, backup),
repo-chore (een klus in een repo die eindigt in een PR, bijvoorbeeld afhankelijkheden bijwerken of oude branches),
digest (bronnen ophalen en samenvatten), script (een script uit een repo draaien en de uitvoer samenvatten).
Model: local (het lokale model, gratis) als de job alleen tekst samenvat of eruit haalt die de job zelf verzamelt
(box-report, digest, script); claude als er tools, code of een repo nodig zijn (repo-chore altijd claude).
Laat velden die niet gelden weg. De instructie voor een lokale job is een opdracht aan een klein model: kort, één
taak, met de gewenste vorm (bijvoorbeeld "alleen wat faalde, in hooguit vijf regels; niets mis: zeg dat in één zin")."""


def start_draft(request, base=None):
    with _lock, db() as c:
        draft_id = c.execute("INSERT INTO drafts (created, request, base, status) VALUES (?,?,?,?)",
                             (now(), request, base, "drafting")).lastrowid
    threading.Thread(target=make_draft, args=(draft_id,), daemon=True).start()
    return draft_id


def make_draft(draft_id):
    with db() as c:
        d = c.execute("SELECT * FROM drafts WHERE id=?", (draft_id,)).fetchone()
    share = HOME / "share" / f"d{draft_id}"
    share.mkdir(parents=True, exist_ok=True)
    os.chmod(share, 0o2750)
    prompt = f"Maak een job uit deze beschrijving van de gebruiker:\n\n{d['request']}"
    if d["base"] and d["base"] in JOBS:
        prompt = (f"Pas deze bestaande job aan (houd de naam {d['base']}):\n\n{JOBS[d['base']]['text']}\n\n"
                  f"Gevraagde wijziging:\n\n{d['request']}")
    (share / "prompt.txt").write_text(prompt)
    (share / "system.txt").write_text(DRAFT_SYSTEM.format(tz=TZ.key, repos=", ".join(sorted(repo_list())),
                                                          skills=", ".join(skill_list()) or "(nog geen)"))
    for f in ("prompt.txt", "system.txt"):
        os.chmod(share / f, 0o640)
    try:
        code, out = task_cmd("draft", f"d{draft_id}", timeout=300)
        result = json.loads(out[out.find("{"):]) if "{" in out else {}
        if code != 0 or result.get("is_error"):
            raise RuntimeError(f"Claude: {(result.get('result') or out)[-300:]}")
        m = re.search(r"```job\n(.*?)```", result.get("result") or "", re.S)
        if not m:
            raise RuntimeError("Claude gaf geen job-bestand terug")
        update_draft(draft_id, status="ready", text=m.group(1).strip() + "\n")
    except Exception as e:
        update_draft(draft_id, status="failed", error=str(e)[:500])
    finally:
        for f in share.iterdir():
            f.unlink()
        share.rmdir()


def update_draft(draft_id, **fields):
    with _lock, db() as c:
        c.execute(f"UPDATE drafts SET {', '.join(k + '=?' for k in fields)} WHERE id=?", [*fields.values(), draft_id])


def get_draft(draft_id):
    with db() as c:
        return c.execute("SELECT * FROM drafts WHERE id=?", (draft_id,)).fetchone()


def draft_name(text):
    m = re.search(r"^name:\s*([a-z0-9][a-z0-9-]*)\s*$", text.partition("\n---")[0], re.M)
    return m.group(1) if m else ""
