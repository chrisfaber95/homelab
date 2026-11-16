"""H-020 C3: the runner contract. runner/SCHEMA.md and tests/fixtures/runner/ describe bin/homelab-inbox-task; the fake
in tests/fakes/ replays the fixtures. These tests keep the four in step: the real script (in a temp setup) still
produces the fixtures, the fake replays them, and every exit code is in the table of SCHEMA.md.
Run: python3 -m unittest discover -s tests"""
import json
import os
import re
import subprocess
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tests"))
import record_runner_fixtures as recorder  # noqa: E402

FAKE = ROOT / "tests/fakes/homelab-inbox-task"
SCHEMA = (ROOT / "runner/SCHEMA.md").read_text()


def fixtures():
    return {f.stem: json.loads(f.read_text()) for f in sorted(recorder.FIXTURES.glob("*.json"))}


def schema_exit_codes():
    """{action: {codes}} from the table under '## Exit codes': | action | code | meaning |. 'any' is the any-action row."""
    section = SCHEMA.split("## Exit codes", 1)[1]
    table = {}
    for m in re.finditer(r"^\|\s*`?([a-z, ]+?)`?\s*\|\s*(\d+)\s*\|", section, re.M):
        for action in re.split(r",\s*", m.group(1).strip()):
            table.setdefault(action, set()).add(int(m.group(2)))
    return table


def script_actions():
    text = (ROOT / "bin/homelab-inbox-task").read_text()
    body = text.split("case $action in", 1)[1]
    labels = re.findall(r"^  ([a-z|]+)\)\s*$", body, re.M)
    return {a for label in labels for a in label.split("|")} - {"*"}


class Fixtures(unittest.TestCase):
    def test_fixtures_are_scrubbed(self):
        for name, fx in fixtures().items():
            blob = json.dumps(fx)
            for bad in ("/home/", "/var/lib/", "/srv/", "tail4eb878", "ts.net", "ghp_", "github_pat_", "sk-ant"):
                self.assertNotIn(bad, blob, f"{name} contains {bad!r}")
            for key in ("name", "action", "args", "env", "stdout", "stderr", "exit_code"):
                self.assertIn(key, fx, name)
            self.assertEqual(fx["name"], name)

    def test_no_two_fixtures_share_arguments(self):
        # The fake finds a fixture by its arguments: a duplicate would make one of them unreachable.
        args = [json.dumps(fx["args"]) for fx in fixtures().values()]
        self.assertEqual(len(args), len(set(args)))

    def test_every_action_of_the_script_has_a_fixture_and_a_schema_entry(self):
        actions = script_actions()
        self.assertEqual(actions, {"prepare", "run", "bundle", "sync", "behind", "has", "kill", "script", "draft",
                                   "route", "plan", "review", "cleanup"})
        recorded = {fx["action"] for fx in fixtures().values()}
        for a in sorted(actions):
            self.assertIn(a, recorded, f"no fixture for {a}")
            self.assertRegex(SCHEMA, rf"(?m)^### .*`{a}\b", f"SCHEMA.md has no section for {a}")

    @unittest.skipUnless(recorder.available(), "git, rsync, timeout needed")
    def test_the_real_script_still_produces_the_fixtures(self):
        recorded = recorder.record()
        stored = fixtures()
        self.assertEqual(sorted(recorded), sorted(stored))
        for name, fx in recorded.items():
            self.assertEqual(fx, stored[name], f"{name} differs: run python3 tests/record_runner_fixtures.py --write")


class Fake(unittest.TestCase):
    def call(self, args, **kw):
        return subprocess.run([str(FAKE), *args], capture_output=True, text=True, **kw)

    def test_replays_every_fixture(self):
        for name, fx in fixtures().items():
            p = self.call(fx["args"])
            self.assertEqual((p.stdout, p.stderr, p.returncode), (fx["stdout"], fx["stderr"], fx["exit_code"]), name)

    def test_unknown_arguments_are_not_guessed(self):
        p = self.call(["has", "424242"])
        self.assertEqual(p.returncode, 99)
        self.assertEqual(p.stdout, "")

    def test_works_as_task_cmd_of_the_inbox(self):
        code = ("import sys; sys.path.insert(0, %r); from homelab_inbox.helpers import task_cmd; "
                "print(task_cmd('bundle', 504)); print(task_cmd('has', 596)); print(task_cmd('sync', 506))"
                % str(ROOT / "bin"))
        p = subprocess.run([sys.executable, "-I", "-c", code], capture_output=True, text=True,
                           env={**os.environ, "TASK_CMD": str(FAKE), "INBOX_RUNNER_HOME": "/nonexistent-runner-home",
                                "NTFY_FAKE": "1", "REPOS_CONF": str(ROOT / "config/repos.conf")})
        self.assertEqual(p.stdout.splitlines(), ["(0, 'abc1234 Add a thing')", "(1, '')", "(4, 'README.md')"], p.stderr)


class ExitCodeTable(unittest.TestCase):
    def test_every_recorded_exit_code_is_in_the_table(self):
        table = schema_exit_codes()
        self.assertTrue(table.get("any"), "no row for the codes of every action")
        for name, fx in fixtures().items():
            allowed = table["any"] | table.get(fx["action"], set())
            self.assertIn(fx["exit_code"], allowed, f"{name}: exit {fx['exit_code']} is not in the table of SCHEMA.md")

    def test_the_table_names_only_real_actions(self):
        self.assertLessEqual(set(schema_exit_codes()) - {"any"}, script_actions())

    def test_codes_the_inbox_branches_on(self):
        # queue.py: bundle 3 = nothing to push, sync 4 = conflict, behind 1 = up to date; jobs.py: script 65 = refused.
        table = schema_exit_codes()
        self.assertIn(3, table["bundle"])
        self.assertIn(4, table["sync"])
        self.assertIn(1, table["behind"])
        self.assertIn(65, table["script"])
        self.assertIn(1, table["has"])


if __name__ == "__main__":
    unittest.main()
