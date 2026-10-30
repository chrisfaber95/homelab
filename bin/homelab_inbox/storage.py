"""Storage: the SQLite database (tasks, job runs, key/value), plus small readers of it."""
import sqlite3
from datetime import datetime, timezone

from .config import DB, _lock


def now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def log(msg):
    print(msg, flush=True)


def db():
    conn = sqlite3.connect(DB, timeout=10)
    conn.row_factory = sqlite3.Row
    conn.execute("""CREATE TABLE IF NOT EXISTS tasks (
        id INTEGER PRIMARY KEY, repo TEXT NOT NULL, text TEXT NOT NULL, status TEXT NOT NULL,
        created TEXT NOT NULL, started TEXT, ended TEXT, session TEXT NOT NULL, rounds INTEGER DEFAULT 0,
        pending_input TEXT, question TEXT, answer TEXT, summary TEXT, pr_number INTEGER, pr_url TEXT,
        last_comment_at TEXT, turns INTEGER DEFAULT 0, note TEXT)""")
    cols = {r[1] for r in conn.execute("PRAGMA table_info(tasks)")}
    # F-022: een taak die een job-run is; F-023: bron van wat Claude deze ronde leert, geschiedenis geschreven
    # F-024: het model van de laatste ronde
    for col, kind in (("job", "TEXT"), ("run_id", "INTEGER"), ("mem_source", "TEXT"), ("mem_link", "TEXT"),
                      ("remembered", "INTEGER DEFAULT 0"), ("model", "TEXT"),
                      ("reprepare", "INTEGER DEFAULT 0"), ("parent", "INTEGER"), ("after_task", "INTEGER"),
                      ("reason", "TEXT"), ("brief", "TEXT"), ("criteria", "TEXT"), ("reviews", "INTEGER DEFAULT 0"),
                      ("review", "TEXT"), ("handoff", "TEXT"), ("unattended", "INTEGER DEFAULT 0"),
                      ("review_ok", "INTEGER"), ("conflicts", "INTEGER DEFAULT 0"), ("conflict_sha", "TEXT")):  # reprepare: vervolg na een antwoord, werkkopie was opgeruimd
        # H-001: brief = the brief id (build parent and its children), criteria = JSON list for a child, reviews =
        # automatic rework rounds used, review = the reviewer's last verdict, handoff = the handed-off PR (or error)
        # Build without me: unattended = on a build, the inbox merges its PRs; review_ok = 1 when every criterion of
        # the last review passed
        # Merge conflicts: conflicts = automatic conflict rounds used, conflict_sha = the PR head last handled
        if col not in cols:
            conn.execute(f"ALTER TABLE tasks ADD COLUMN {col} {kind}")
    conn.executescript("""
        CREATE TABLE IF NOT EXISTS job_runs (id INTEGER PRIMARY KEY, job TEXT NOT NULL, kind TEXT, model TEXT,
            due TEXT, created TEXT NOT NULL, started TEXT, ended TEXT, dispatched TEXT, status TEXT NOT NULL,
            summary TEXT, note TEXT, task_id INTEGER, manual INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS job_state (name TEXT PRIMARY KEY, last_due TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS drafts (id INTEGER PRIMARY KEY, created TEXT NOT NULL, request TEXT NOT NULL,
            base TEXT, status TEXT NOT NULL, text TEXT, error TEXT);
        CREATE TABLE IF NOT EXISTS skill_prs (id INTEGER PRIMARY KEY, name TEXT NOT NULL, task_id INTEGER,
            pr_url TEXT, created TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS kv (key TEXT PRIMARY KEY, value TEXT);""")
    if "minutes" not in {r[1] for r in conn.execute("PRAGMA table_info(job_runs)")}:
        conn.execute("ALTER TABLE job_runs ADD COLUMN minutes INTEGER")  # F-024: bespaarde minuten, bij het starten
    return conn


def get(task_id):
    with db() as c:
        return c.execute("SELECT * FROM tasks WHERE id=?", (task_id,)).fetchone()


def update(task_id, **fields):
    with _lock, db() as c:
        c.execute(f"UPDATE tasks SET {', '.join(k + '=?' for k in fields)} WHERE id=?", [*fields.values(), task_id])


def tasks(limit=200):
    with db() as c:
        return c.execute("SELECT * FROM tasks ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
