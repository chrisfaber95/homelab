#!/usr/bin/env python3
"""homelab: H-027 X1a, the data half of the cut-over from the Python inbox (SQLite) to the Java backend (PostgreSQL).

Run as root on the box: sudo python3 /var/lib/homelab/repo/scripts/inbox_cutover.py   (docs/inbox.md, "Cut-over to the Java backend")

In this order:
  1. refuse (exit 1) when any task in inbox.db has status 'running';
  2. stop homelab-inbox, so nothing writes inbox.db any more (and check 1 again on the stopped database);
  3. take a consistent snapshot of inbox.db and run the final `import-sqlite` of the jar;
  4. compare the row count per table between the snapshot and PostgreSQL; on any difference abort;
  5. keep the snapshot and a fresh pg_dump in a dated directory (kept for one release; X2 removes it), print both paths.
It never switches a port or a unit: on success homelab-inbox stays stopped for the task that does the switch; on an
abort it is started again if it was running before ("the old inbox stays in place").

Safe to rerun: the import refuses a non-empty target, so a marker (cutover-import.json next to inbox.db) records the
sha256 of the snapshot that was imported. The same snapshot again skips the import and only compares and backs up; a
changed inbox.db with an already filled target aborts (empty the tables by hand, see backend/README.md).

Tables compared: those the Python inbox still writes (TABLES). `conversation`, `conversation_refusals`, `approvals` and
the `switch:*` rows of `kv` belong to the Java store since H-024 M4 and move on their own, so their counts differ on purpose.
"""
import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

TABLES = ("tasks", "job_runs", "job_state", "drafts", "skill_prs")
UNIT = "homelab-inbox"
INBOX_DB = "/var/lib/homelab-inbox/inbox.db"
JAR = "/usr/local/lib/homelab/inbox-backend/inbox-backend.jar"
JAVA = "/usr/lib/jvm/java-21-openjdk-amd64/bin/java"
BACKUP_ROOT = "/var/backups/homelab"
RUNNER = "inbox-runner"
PG_RUN = "runuser -u postgres --"
DATABASE = "inbox"


class Abort(Exception):
    """The cut-over stops here; the message is for the user."""


def running_tasks(db_path):
    """Ids of the tasks that are running, read from inbox.db without writing to it."""
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=10)
    try:
        return [r[0] for r in conn.execute("SELECT id FROM tasks WHERE status = 'running' ORDER BY id")]
    finally:
        conn.close()


def sqlite_counts(db_path, tables=TABLES):
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=10)
    try:
        return {t: conn.execute(f'SELECT count(*) FROM "{t}"').fetchone()[0] for t in tables}
    finally:
        conn.close()


def compare_counts(sqlite_side, postgres_side):
    """The tables whose counts differ, as lines like 'tasks: sqlite=3 postgres=2'; empty = all equal."""
    out = []
    for table in sqlite_side:
        a, b = sqlite_side[table], postgres_side.get(table)
        if a != b:
            out.append(f"{table}: sqlite={a} postgres={b}")
    return out


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def snapshot(db_path, dest):
    """A consistent copy of inbox.db (WAL included) through the SQLite backup API; the original is not touched."""
    src = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=10)
    old_umask = os.umask(0o077)
    try:
        dst = sqlite3.connect(dest)
        try:
            src.backup(dst)
        finally:
            dst.close()
    finally:
        os.umask(old_umask)
        src.close()
    os.chmod(dest, 0o600)


def run(cmd, **kw):
    return subprocess.run(cmd, cwd="/", capture_output=True, text=True, **kw)


class Cutover:
    def __init__(self, a):
        self.a = a
        self.pg_run = a.pg_run.split()
        self.marker = Path(a.inbox_db).with_name("cutover-import.json")
        self.stamp = datetime.now(timezone.utc).strftime("%Y-%m-%d")
        self.dest = Path(a.backup_root) / f"inbox-cutover-{self.stamp}"

    def say(self, text):
        print(text, flush=True)

    def unit_active(self):
        return run(["systemctl", "is-active", "--quiet", self.a.unit]).returncode == 0

    def postgres_counts(self):
        counts = {}
        for t in TABLES:
            done = run([*self.pg_run, "psql", "-d", self.a.database, "-AtXc", f'select count(*) from "{t}"'])
            if done.returncode != 0 or not done.stdout.strip().isdigit():
                raise Abort(f"cannot count {t} in PostgreSQL: {done.stderr.strip() or done.stdout.strip()}")
            counts[t] = int(done.stdout.strip())
        return counts

    def guard(self, when):
        ids = running_tasks(self.a.inbox_db)
        if ids:
            raise Abort(f"REFUSED {when}: task(s) running in the old inbox: {', '.join(map(str, ids))}. "
                        "Wait until they have finished (or cancel them) and run this again; nothing was changed.")

    def do_import(self, snap_hash):
        if self.marker.exists() and json.loads(self.marker.read_text()).get("sha256") == snap_hash:
            self.say("step 3: this inbox.db was imported by an earlier run: import skipped")
            return
        cmd = ["runuser", "-u", self.a.runner, "--", self.a.java, "-Xmx256m", "-jar", self.a.jar, "import-sqlite",
               "--sqlite", self.a.inbox_db, "--database", self.a.database]
        done = run(cmd)
        self.say(done.stdout.rstrip())
        if done.returncode != 0:
            why = {2: "PostgreSQL already holds data from another inbox.db (empty the tables by hand first, "
                      "backend/README.md) or the options are wrong"}.get(done.returncode, "the import failed and was rolled back")
            raise Abort(f"import-sqlite exited {done.returncode}: {why}. {done.stderr.strip()}")
        self.marker.write_text(json.dumps({"sha256": snap_hash, "at": datetime.now(timezone.utc).isoformat()}))

    def dump(self, target):
        tmp = target.with_name("." + target.name + ".tmp")
        old_umask = os.umask(0o077)
        try:
            with open(tmp, "wb") as out:
                done = subprocess.run([*self.pg_run, "pg_dump", "--format=custom", "--dbname", self.a.database],
                                      cwd="/", stdout=out, stderr=subprocess.PIPE)
        finally:
            os.umask(old_umask)
        if done.returncode != 0 or tmp.stat().st_size == 0:
            tmp.unlink(missing_ok=True)
            raise Abort(f"pg_dump failed: {done.stderr.decode(errors='replace').strip() or 'empty output'}")
        tmp.chmod(0o600)
        tmp.replace(target)

    def main(self):
        a = self.a
        if not Path(a.inbox_db).is_file():
            raise Abort(f"{a.inbox_db} does not exist: nothing to cut over")
        self.say("step 1: checking that no task is running")
        self.guard("before stopping")
        was_active = self.unit_active()
        self.say(f"step 2: stopping {a.unit}" + ("" if was_active else " (was not running)"))
        if was_active:
            done = run(["systemctl", "stop", a.unit])
            if done.returncode != 0:
                raise Abort(f"systemctl stop {a.unit} failed: {done.stderr.strip()}")
        ok = False
        try:
            self.guard("after stopping (a task started in between)")
            self.dest.mkdir(parents=True, exist_ok=True)
            self.dest.chmod(0o700)
            final_db = self.dest / "inbox.db"
            tmp_db = self.dest / ".inbox.db.tmp"
            tmp_db.unlink(missing_ok=True)
            snapshot(a.inbox_db, str(tmp_db))
            snap_hash = sha256(tmp_db)
            self.say("step 3: final import of inbox.db into PostgreSQL")
            self.do_import(snap_hash)
            self.say("step 4: comparing row counts")
            left, right = sqlite_counts(str(tmp_db)), self.postgres_counts()
            for t in TABLES:
                self.say(f"  {t:<12} sqlite={left[t]} postgres={right[t]}")
            diff = compare_counts(left, right)
            if diff:
                self.marker.unlink(missing_ok=True)
                raise Abort("ABORTED, row counts differ (" + "; ".join(diff) + "): the old inbox stays in place")
            self.say("step 5: keeping the final copies")
            tmp_db.replace(final_db)
            final_dump = self.dest / "inbox.dump"
            self.dump(final_dump)
            ok = True
        finally:
            if not ok and was_active:
                run(["systemctl", "start", a.unit])
                self.say(f"{a.unit} started again (old inbox stays in place)")
        self.say(f"counts match. {a.unit} stays stopped for the switch; nothing else was changed.")
        self.say(f"final inbox.db copy: {final_db}")
        self.say(f"last pg_dump:        {final_dump}")
        self.say(f"Keep both for one release (X2 removes {self.dest}).")


def parse(argv):
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    for name, default in (("inbox-db", INBOX_DB), ("jar", JAR), ("java", JAVA), ("backup-root", BACKUP_ROOT),
                          ("runner", RUNNER), ("pg-run", PG_RUN), ("unit", UNIT), ("database", DATABASE)):
        p.add_argument("--" + name, default=os.environ.get("CUTOVER_" + name.upper().replace("-", "_"), default))
    return p.parse_args(argv)


def main(argv=None):
    a = parse(argv)
    if os.environ.get("CUTOVER_ALLOW_NON_ROOT") != "1" and os.geteuid() != 0:
        print("Run with sudo.", file=sys.stderr)
        return 1
    try:
        Cutover(a).main()
    except Abort as e:
        print(str(e), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
