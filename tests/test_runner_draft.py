"""H-025 R2: runner/draft.py against the fake homelab-inbox-task, and jobs.make_draft using it.
Run: python3 -m unittest discover -s tests"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNNER = ROOT / "runner/draft.py"
FAKE = ROOT / "tests/fakes/homelab-inbox-task"


def run_draft(args, fixture=None):
    env = {**os.environ, "TASK_CMD": str(FAKE)}
    with tempfile.TemporaryDirectory() as d:
        if fixture:
            fx = {"name": "x", "action": "draft", "args": ["draft", *args], "env": {}, "stderr": fixture[2],
                  "stdout": fixture[0], "exit_code": fixture[1]}
            (Path(d) / "x.json").write_text(json.dumps(fx))
            env["RUNNER_FIXTURES"] = d
        p = subprocess.run([sys.executable, "-I", str(RUNNER), *args], capture_output=True, text=True, env=env, timeout=30)
    return p, [json.loads(l) for l in p.stdout.splitlines()]


class Draft(unittest.TestCase):
    def test_recorded_draft_gives_one_result_line(self):
        p, evs = run_draft(["d521"])
        self.assertEqual(p.returncode, 0)
        self.assertEqual(len(evs), 1)
        # The fake prints a stray system line first; like the old claude_result the runner takes the first JSON object.
        self.assertEqual((evs[0]["event"], evs[0]["v"], evs[0]["is_error"], evs[0]["exit_code"]), ("result", 1, False, 0))

    def test_warning_line_after_the_json_is_ignored(self):
        out = json.dumps({"is_error": False, "result": "```job\nx\n```"}) + "\n[claude-code:unrecognized_model]\n"
        p, evs = run_draft(["d7"], (out, 0, ""))
        self.assertEqual(evs[0]["text"], "```job\nx\n```")
        self.assertFalse(evs[0]["is_error"])

    def test_claude_error_and_exit_code(self):
        out = json.dumps({"is_error": True, "result": "kapot"})
        p, evs = run_draft(["d7"], (out, 1, "warn\n"))
        self.assertEqual(p.returncode, 1)
        self.assertTrue(evs[0]["is_error"])
        self.assertEqual(evs[0]["text"], "kapot")
        self.assertIn("warn", p.stderr)

    def test_no_json_keeps_exit_code_and_tail(self):
        p, evs = run_draft(["d7"], ("", 7, "boom\n"))
        self.assertEqual((p.returncode, evs[0]["exit_code"], evs[0]["is_error"], evs[0]["raw"]), (7, 7, True, None))
        self.assertIn("boom", evs[0]["output_tail"])

    def test_refusal_of_homelab_inbox_task_passes_through(self):
        p, evs = run_draft(["d999999999"])
        self.assertEqual(p.returncode, 2)

    def test_bad_arguments_exit_2_without_output(self):
        for args in ([], ["521"], ["r521"], ["d1", "x"]):
            p, evs = run_draft(args)
            self.assertEqual((p.returncode, evs), (2, []), args)
            self.assertIn("usage", p.stderr)

    def test_no_prompt_or_token_in_the_runner(self):
        text = RUNNER.read_text()
        for bad in ("prompt.txt", "OAUTH", "--append-system-prompt", "TOKEN_FILE"):
            self.assertNotIn(bad, text)


class MakeDraftUsesRunner(unittest.TestCase):
    def test_make_draft_reads_the_runner_result(self):
        os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
        os.environ.setdefault("BACKEND_ROUTES", "")
        sys.path.insert(0, str(ROOT / "bin"))
        from homelab_inbox import jobs
        updates = []
        job = "name: x\n"
        out = json.dumps({"is_error": False, "result": f"ok\n```job\n{job}```\n"})
        with tempfile.TemporaryDirectory() as d, tempfile.TemporaryDirectory() as home:
            (Path(d) / "x.json").write_text(json.dumps({"name": "x", "action": "draft", "args": ["draft", "d5"], "env": {},
                                                        "stderr": "", "stdout": out, "exit_code": 0}))
            os.environ["RUNNER_FIXTURES"] = d
            saved = (jobs.db, jobs.update_draft, jobs.TASK_CMD, jobs.HOME, jobs.repo_list, jobs.skill_list)

            class C:
                def __enter__(s): return s
                def __exit__(s, *a): return False
                def execute(s, *a): return s
                def fetchone(s): return {"request": "doe iets", "base": None}
            jobs.db = lambda: C()
            jobs.update_draft = lambda i, **kw: updates.append(kw)
            jobs.TASK_CMD = [str(FAKE)]
            jobs.HOME = Path(home)
            jobs.repo_list = lambda: []
            jobs.skill_list = lambda: []
            try:
                jobs.make_draft(5)
            finally:
                jobs.db, jobs.update_draft, jobs.TASK_CMD, jobs.HOME, jobs.repo_list, jobs.skill_list = saved
                del os.environ["RUNNER_FIXTURES"]
        self.assertEqual(updates, [{"status": "ready", "text": job}])


if __name__ == "__main__":
    unittest.main()
