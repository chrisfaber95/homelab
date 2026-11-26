"""The cut-over script (H-027 X1a, scripts/inbox_cutover.py): the running-task guard, the row-count comparison, and a
whole run against fake systemctl/runuser (no PostgreSQL, no real unit).
Run: python3 -m unittest discover -s tests"""
import importlib.util
import json
import os
import sqlite3
import stat
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location("inbox_cutover", ROOT / "scripts/inbox_cutover.py")
cut = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cut)

FAKE_SYSTEMCTL = """#!/bin/sh
echo "$@" >> "$FAKE_LOG"
case "$1" in
  is-active) [ -f "$FAKE_DIR/active" ] ;;
  stop) rm -f "$FAKE_DIR/active" ;;
  start) touch "$FAKE_DIR/active" ;;
esac
"""
# runuser -u <user> -- <cmd...>: psql answers from $FAKE_DIR/pg_<table>, the jar import and pg_dump are faked.
FAKE_RUNUSER = """#!/bin/sh
shift 3
echo "runuser $@" >> "$FAKE_LOG"
case "$*" in
  *import-sqlite*) [ -f "$FAKE_DIR/import_fails" ] && { echo boom >&2; exit 1; }
                   for t in tasks job_runs job_state drafts skill_prs; do cp "$FAKE_DIR/want_$t" "$FAKE_DIR/pg_$t" 2>/dev/null || echo 0 > "$FAKE_DIR/pg_$t"; done
                   echo "import ok: all counts equal" ;;
  *pg_dump*) echo PGDMP ;;
  *psql*) for last; do :; done; t=${last#*from \\"}; t=${t%\\"}; cat "$FAKE_DIR/pg_$t" ;;
esac
"""


def make_db(path, running=(), tasks=2):
    conn = sqlite3.connect(path)
    conn.executescript("""CREATE TABLE tasks (id INTEGER PRIMARY KEY, status TEXT);
        CREATE TABLE job_runs (id INTEGER PRIMARY KEY); CREATE TABLE job_state (name TEXT);
        CREATE TABLE drafts (id INTEGER PRIMARY KEY); CREATE TABLE skill_prs (id INTEGER PRIMARY KEY);""")
    for i in range(tasks):
        conn.execute("INSERT INTO tasks (status) VALUES (?)", ("running" if i in running else "merged",))
    conn.execute("INSERT INTO job_runs DEFAULT VALUES")
    conn.commit()
    conn.close()


class Counts(unittest.TestCase):
    def test_matching_counts_give_no_difference(self):
        side = {"tasks": 3, "job_runs": 1, "job_state": 0, "drafts": 0, "skill_prs": 2}
        self.assertEqual(cut.compare_counts(side, dict(side)), [])

    def test_mismatch_names_the_table_and_both_counts(self):
        left = {"tasks": 3, "job_runs": 1}
        right = {"tasks": 2, "job_runs": 1}
        self.assertEqual(cut.compare_counts(left, right), ["tasks: sqlite=3 postgres=2"])

    def test_missing_table_in_postgres_is_a_difference(self):
        self.assertEqual(len(cut.compare_counts({"tasks": 0}, {})), 1)

    def test_sqlite_counts_read_the_tables(self):
        with tempfile.TemporaryDirectory() as d:
            make_db(f"{d}/inbox.db", tasks=4)
            self.assertEqual(cut.sqlite_counts(f"{d}/inbox.db"),
                             {"tasks": 4, "job_runs": 1, "job_state": 0, "drafts": 0, "skill_prs": 0})


class Guard(unittest.TestCase):
    def test_running_task_is_found_and_idle_db_is_not(self):
        with tempfile.TemporaryDirectory() as d:
            make_db(f"{d}/a.db", running=(1,), tasks=3)
            make_db(f"{d}/b.db")
            self.assertEqual(cut.running_tasks(f"{d}/a.db"), [2])
            self.assertEqual(cut.running_tasks(f"{d}/b.db"), [])


class Run(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        d = Path(self.tmp.name)
        self.fake = d / "fake"
        self.fake.mkdir()
        self.bin = d / "bin"
        self.bin.mkdir()
        for name, body in (("systemctl", FAKE_SYSTEMCTL), ("runuser", FAKE_RUNUSER)):
            (self.bin / name).write_text(body)
            (self.bin / name).chmod(0o755)
        self.log = self.fake / "log"
        self.db = d / "inbox.db"
        self.backup = d / "backups"
        (self.fake / "active").touch()
        for t, n in (("tasks", 2), ("job_runs", 1), ("job_state", 0), ("drafts", 0), ("skill_prs", 0)):
            (self.fake / f"want_{t}").write_text(f"{n}\n")
        env = {"PATH": f"{self.bin}:{os.environ['PATH']}", "FAKE_DIR": str(self.fake), "FAKE_LOG": str(self.log),
               "CUTOVER_ALLOW_NON_ROOT": "1"}
        p = mock.patch.dict(os.environ, env)
        p.start()
        self.addCleanup(p.stop)
        self.args = ["--inbox-db", str(self.db), "--backup-root", str(self.backup), "--jar", "x.jar", "--java", "java"]

    def go(self):
        with mock.patch("builtins.print") as pr:
            code = cut.main(self.args)
        out = "\n".join(" ".join(map(str, c.args)) for c in pr.call_args_list)
        return code, out

    def calls(self):
        return self.log.read_text() if self.log.exists() else ""

    def test_running_task_refuses_and_touches_nothing(self):
        make_db(self.db, running=(0,))
        code, out = self.go()
        self.assertEqual(code, 1)
        self.assertIn("REFUSED", out)
        self.assertNotIn("stop", self.calls())
        self.assertNotIn("import-sqlite", self.calls())
        self.assertFalse(self.backup.exists())
        self.assertTrue((self.fake / "active").exists())

    def test_matching_counts_keep_both_copies_and_leave_the_unit_stopped(self):
        make_db(self.db)
        code, out = self.go()
        self.assertEqual(code, 0, out)
        d = next(self.backup.glob("inbox-cutover-*"))
        self.assertEqual(sqlite3.connect(d / "inbox.db").execute("select count(*) from tasks").fetchone()[0], 2)
        self.assertEqual((d / "inbox.dump").read_bytes(), b"PGDMP\n")
        self.assertEqual(stat.S_IMODE((d / "inbox.dump").stat().st_mode), 0o600)
        self.assertIn(str(d / "inbox.db"), out)
        self.assertIn(str(d / "inbox.dump"), out)
        self.assertFalse((self.fake / "active").exists())
        self.assertNotIn("disable", self.calls())

    def test_count_mismatch_aborts_and_restarts_the_old_inbox(self):
        make_db(self.db)
        (self.fake / "want_tasks").write_text("1\n")
        code, out = self.go()
        self.assertEqual(code, 1)
        self.assertIn("tasks: sqlite=2 postgres=1", out)
        self.assertTrue((self.fake / "active").exists())
        self.assertEqual(list(self.backup.glob("inbox-cutover-*/inbox.dump")), [])

    def test_failed_import_aborts_and_restarts_the_old_inbox(self):
        make_db(self.db)
        (self.fake / "import_fails").touch()
        self.assertEqual(self.go()[0], 1)
        self.assertTrue((self.fake / "active").exists())

    def test_rerun_of_the_same_database_skips_the_import(self):
        make_db(self.db)
        self.assertEqual(self.go()[0], 0)
        self.log.unlink()
        code, out = self.go()
        self.assertEqual(code, 0, out)
        self.assertNotIn("import-sqlite", self.calls())
        self.assertIn("skipped", out)
        self.assertEqual(json.loads(self.db.with_name("cutover-import.json").read_text())["sha256"][:1].isalnum(), True)


if __name__ == "__main__":
    unittest.main()
