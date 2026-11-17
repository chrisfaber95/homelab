"""One inbox state for the M1 parity test (H-024): a RUNNER_HOME with an inbox.db, logs and job files, the monitor,
deploy and changes files of the overview, and a fake forge and LLM helper. The Python inbox and the Java backend are
both pointed at it, so any difference in their answers is a difference in code, not in data. Times are relative to
`build()`, so the state always has something running, queued, recent and planned."""
import json
import sqlite3
import threading
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def iso(dt):
    return dt.astimezone(timezone.utc).isoformat(timespec="seconds")


JOB_BOX = """---
when: elke ochtend om 7
schedule: "0 7 * * *"
kind: box-report
model: local
minutes: 5
---
Maak een rapport van de box.
"""
JOB_CHORE = """---
schedule: "*/30 * * * *"
kind: repo-chore
model: claude
repo: homelab
skills: [demo-skill]
---
Ruim iets op.
"""
JOB_PAUSED = """---
schedule: "0 12 * * 1-5"
kind: digest
model: local
paused: true
sources: [github:chrisfaber95/homelab]
deliver: ntfy
---
Vat samen. Is er niets, antwoord dan NOTHING TO REPORT.
"""
JOB_BROKEN = """---
schedule: "0 7 * * *"
kind: box-report
model: local
---
"""
JOB_BADCRON = """---
schedule: "61 7 * * *"
kind: box-report
model: local
---
Iets.
"""
JOB_UNKNOWN_SKILL = """---
schedule: "15 6 * * 0"
kind: repo-chore
model: claude
repo: homelab
skills: [nope]
---
Iets.
"""


def build(root: Path, forge_url: str, llm_url: str) -> dict:
    """Write the state under `root`; returns the environment variables that point an inbox at it."""
    now = datetime.now(timezone.utc)
    home, monitor, state = root / "home", root / "monitor", root / "state"
    for d in (home / "logs", home / "jobs-repo" / "jobs", home / "repos" / "homelab" / "skills" / "demo-skill",
              monitor, state):
        d.mkdir(parents=True, exist_ok=True)
    (home / "repos" / "homelab" / "skills" / "demo-skill" / "SKILL.md").write_text("---\ndescription: demo\n---\n")
    for name, text in {"nachtrapport": JOB_BOX, "klus": JOB_CHORE, "wacht": JOB_PAUSED, "stuk": JOB_BROKEN,
                       "slechtschema": JOB_BADCRON, "onbekende-skill": JOB_UNKNOWN_SKILL}.items():
        (home / "jobs-repo" / "jobs" / f"{name}.md").write_text(text)

    db = sqlite3.connect(home / "inbox.db")
    db.executescript("""
        CREATE TABLE tasks (id INTEGER PRIMARY KEY, repo TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL,
            created TEXT NOT NULL, started TEXT, ended TEXT, session TEXT NOT NULL, rounds INTEGER DEFAULT 0,
            pending_input TEXT, question TEXT, answer TEXT, summary TEXT, pr_number INTEGER, pr_url TEXT,
            last_comment_at TEXT, turns INTEGER DEFAULT 0, note TEXT, job TEXT, run_id INTEGER, model TEXT);
        CREATE TABLE job_runs (id INTEGER PRIMARY KEY, job TEXT NOT NULL, kind TEXT, model TEXT, due TEXT,
            created TEXT NOT NULL, started TEXT, ended TEXT, dispatched TEXT, status TEXT NOT NULL, summary TEXT,
            note TEXT, task_id INTEGER, manual INTEGER DEFAULT 0, minutes INTEGER);
        CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT);
        CREATE TABLE conversation (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL, author TEXT NOT NULL,
            client TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL, link TEXT, ref TEXT);
        CREATE TABLE conversation_refusals (id INTEGER PRIMARY KEY AUTOINCREMENT, time TEXT NOT NULL, client TEXT NOT NULL,
            action TEXT NOT NULL, why TEXT NOT NULL);""")
    t = lambda **ago: iso(now - timedelta(**ago))  # noqa: E731
    db.executemany(
        "INSERT INTO tasks (id, repo, text, status, created, started, session, rounds, model, job, summary, pr_url, "
        "answer, question, note) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", [
            (1, "homelab", "Fix de build\nmet meer regels", "running", t(hours=2), t(hours=1), "s1", 1, "claude-opus-5-5", None, None, None, None, None, None),
            (2, "product", "Taak met é en ü", "running", t(minutes=30), t(minutes=20), "s2", 1, None, None, None, None, None, None, None),
            (3, "homelab", "wacht", "queued", t(minutes=5), None, "s3", 0, None, None, None, None, None, None, None),
            (4, "homelab", "ook wachten", "queued", t(minutes=4), None, "s4", 0, None, None, None, None, None, None, None),
            (5, "homelab", "klaar", "merged", t(days=1), t(days=1), "s5", 1, None, None, "Alles gelukt.\nTweede regel.", "https://example.test/pr/5", None, None, None),
            (6, "homelab", "klus", "running", t(minutes=3), t(minutes=3), "s6", 1, "claude-sonnet-5-5", "klus", None, None, None, None, None),
            (7, "homelab", "vraag", "waiting", t(hours=5), t(hours=5), "s7", 1, None, "wacht", None, None, None, "Wat nu?", None),
        ])
    db.executemany(
        "INSERT INTO job_runs (id, job, kind, model, due, created, started, ended, dispatched, status, summary, note, "
        "task_id, manual, minutes) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", [
            (1, "nachtrapport", "box-report", "local", t(minutes=2), t(minutes=2), t(minutes=2), None, None, "running", None, None, None, 0, 5),
            (2, "nachtrapport", "box-report", "local", t(days=1), t(days=1), t(days=1), t(days=1), None, "ok", "Het rapport.\nMeer.", None, None, 0, 5),
            (3, "klus", "repo-chore", "claude", t(hours=3), t(hours=3), None, None, t(hours=3), "queued", None, None, 5, 0, None),
            (4, "wacht", "digest", "local", t(days=2), t(days=2), t(days=2), t(days=2), None, "failed", None, "mislukt: geen netwerk", None, 1, None),
            (5, "weg", "box-report", "local", t(days=3), t(days=3), t(days=3), t(days=3), None, "ok", "Verwijderde job", None, None, 0, None),
            (6, "klus", "repo-chore", "claude", t(hours=1), t(hours=1), None, None, t(hours=1), "queued", None, None, 7, 0, 12),
            (7, "nachtrapport", "box-report", "local", t(days=9), t(days=9), t(days=9), t(days=9), None, "ok", "oud", None, None, 0, 5),
        ])
    db.execute("INSERT INTO kv VALUES ('switch:kitt', 'off')")
    db.commit()
    db.close()

    (home / "logs" / "t1.log").write_text("".join(json.dumps({"at": t(minutes=i), "kind": "info", "text": f"regel {i} é"}) + "\n"
                                                  for i in range(7)))
    (home / "logs" / "t5.log").write_text(json.dumps({"at": t(days=1), "kind": "info", "text": "klaar"}) + "\n")
    (home / "logs" / "r1.log").write_text(json.dumps({"at": t(minutes=1), "kind": "info", "text": "bezig"}) + "\n")
    (home / "logs" / "r2.log").write_text("".join(json.dumps({"at": t(days=1), "kind": "info", "text": f"r{i}"}) + "\n"
                                                  for i in range(600)))

    (monitor / "checks.json").write_text(json.dumps({
        "disk": {"status": "OK", "text": "50% vrij"}, "gpu": {"status": "WARN", "text": "heet", "since": t(hours=1)},
        "net": {"status": "WARN", "text": "traag"}}))
    (monitor / "last.json").write_text(json.dumps({"at": t(minutes=1)}))
    (monitor / "backup.json").write_text(json.dumps({"ok": True, "at": t(hours=3), "free_bytes": 123456789, "free_pct": 41.5}))
    (state / "app.json").write_text(json.dumps({"last_deploy": {"ok": True, "at": t(hours=2)}, "deployed_sha": "abc1234",
                                                "sync_error": None}))
    (state / "kapot.json").write_text(json.dumps({"last_deploy": {"ok": False, "at": t(hours=1)}, "deployed_sha": "def", "sync_error": "boom"}))
    (state / "nieuw.json").write_text("{}")
    (state / "_intern.json").write_text(json.dumps({"x": 1}))
    changes = [{"kind": "instant", "action": "service", "args": ["restart", "x"], "ok": True, "at": t(hours=i), "user": "claude", "reason": "taak " + "r" * 100}
               for i in range(5)]
    changes.append({"kind": "change", "change": "0004-x", "summary": "iets gedaan", "ok": False, "at": t(minutes=5), "user": "chris"})
    changes.append({"kind": "refused", "action": "reboot", "args": [], "ok": False, "at": t(minutes=1)})
    (root / "changes.jsonl").write_text("".join(json.dumps(c) + "\n" for c in changes))

    return {"INBOX_RUNNER_HOME": str(home), "MONITOR_DIR": str(monitor), "DEPLOY_STATE": str(state),
            "LOG_FILE": str(root / "changes.jsonl"), "BACKUP_STATUS": str(monitor / "backup.json"),
            "FORGE_URL": forge_url, "FORGE_PAGE_URL": "http://homelab:8120", "LLM_URL": llm_url,
            "REPOS_CONF": str(Path(__file__).resolve().parent.parent / "config" / "repos.conf")}


class Upstream(BaseHTTPRequestHandler):
    """A fake forge (`/api/state`, `/api/log`) and a fake LLM helper (`/usage/hourly`)."""
    forge_running = True

    def log_message(self, *args):
        pass

    def do_GET(self):
        path = self.path.split("?")[0]
        if path == "/api/state":
            body = {"running": self.forge_running, "step": 3, "project": "ashfall", "activity": "bouwt een eiland",
                    "started_at": "2026-11-17T08:00:00+00:00", "paused": False}
        elif path == "/api/log":
            body = {"lines": ["2026-11-17T08:00:01+00:00 Bash: pytest -q", "2026-11-17T08:00:02+00:00 claude denkt na",
                              "2026-11-17T08:00:03+00:00 build failed", "zonder tijd", "2026-11-17T08:00:04+00:00 gewoon"],
                    "next": 5, "running": True}
        elif path == "/usage/hourly":
            now = datetime.now(timezone.utc)
            body = {"hours": [{"hour": (now - timedelta(hours=h)).strftime("%Y-%m-%dT%H"), "count": h + 1} for h in range(0, 60, 5)]}
        else:
            self.send_response(404)
            self.end_headers()
            return
        data = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


def start_upstream():
    srv = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv
