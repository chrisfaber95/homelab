"""H-025 R2 part 1: runner/route.py against the fake homelab-inbox-task, and router.run_route on top of it.
Run: python3 -m unittest discover -s tests"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "bin"))
sys.path.insert(0, str(ROOT / "runner"))
RUNNER = ROOT / "runner/route.py"
FAKE = ROOT / "tests/fakes/homelab-inbox-task"


def run_route(args, fixture=None, task_cmd=None):
    env = {**os.environ, "TASK_CMD": task_cmd or str(FAKE)}
    with tempfile.TemporaryDirectory() as d:
        if fixture:
            fx = {"name": "x", "action": "route", "args": ["route", f"r{args[0]}"], "env": {}, "stderr": fixture[2],
                  "stdout": fixture[0], "exit_code": fixture[1]}
            (Path(d) / "x.json").write_text(json.dumps(fx))
            env["RUNNER_FIXTURES"] = d
        p = subprocess.run([sys.executable, "-I", str(RUNNER), *args], capture_output=True, text=True, env=env, timeout=30)
    return p, [json.loads(l) for l in p.stdout.splitlines()]


class Route(unittest.TestCase):
    def test_recorded_route_gives_one_result_line(self):
        p, evs = run_route(["520"])
        self.assertEqual(p.returncode, 0)
        self.assertEqual(len(evs), 1)
        self.assertEqual(evs[0]["event"], "result")
        self.assertEqual(evs[0]["v"], 1)
        self.assertEqual(evs[0]["text"], "fake claude")
        self.assertFalse(evs[0]["is_error"])
        self.assertEqual(evs[0]["exit_code"], 0)

    def test_exit_code_passes_through_and_marks_an_error(self):
        p, evs = run_route(["523"])
        self.assertEqual(p.returncode, 1)
        self.assertEqual(evs[-1]["exit_code"], 1)
        self.assertTrue(evs[-1]["is_error"])

    def test_warning_line_after_the_json_does_not_break_the_parse(self):
        out = json.dumps({"is_error": True, "result": "model unavailable"}) + '\n[claude-code:unrecognized_model] {"model":"x"}\n'
        p, evs = run_route(["5"], (out, 0, ""))
        self.assertEqual(evs[-1]["text"], "model unavailable")
        self.assertTrue(evs[-1]["is_error"])

    def test_no_json_is_an_error_with_stderr(self):
        p, evs = run_route(["5"], ("boom", 0, "oops\n"))
        self.assertTrue(evs[-1]["is_error"])
        self.assertIsNone(evs[-1]["raw"])
        self.assertEqual(evs[-1]["stderr"], "oops")
        self.assertIn("oops", p.stderr)

    def test_runner_failure_codes(self):
        p, evs = run_route(["5"], ("", 99, "x"))
        self.assertEqual((p.returncode, evs[-1]["exit_code"]), (99, 99))

    def test_bad_arguments_exit_2_without_output(self):
        for args in ([], ["x"], ["1", "2"], ["r5"], ["1234567"]):
            p, evs = run_route(args)
            self.assertEqual((p.returncode, evs), (2, []), args)

    def test_arguments_carry_only_the_task_id(self):
        # a task cmd that records its argv: no prompt, no token, only `route r<id>`
        with tempfile.TemporaryDirectory() as d:
            script = Path(d) / "cmd.sh"
            script.write_text(f'#!/bin/sh\necho "$@" > {d}/argv\necho \'{{"is_error":false,"result":"ok"}}\'\n')
            script.chmod(0o755)
            p, evs = run_route(["77"], task_cmd=str(script))
            self.assertEqual((Path(d) / "argv").read_text().strip(), "route r77")
        self.assertEqual(evs[-1]["text"], "ok")


class RouterUsesRunner(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        os.environ["INBOX_RUNNER_HOME"] = self.tmp.name
        from homelab_inbox import router
        self.router = router

    def tearDown(self):
        self.tmp.cleanup()

    def test_run_route_returns_the_runner_result(self):
        from unittest import mock
        with mock.patch.object(self.router, "TASK_CMD", [str(FAKE)]):
            res = self.router.run_route(520)
        self.assertEqual((res["text"], res["is_error"], res["exit_code"]), ("fake claude", False, 0))

    def test_run_route_without_a_result_line_is_an_error(self):
        from unittest import mock
        with mock.patch.object(self.router, "RUNNER_DIR", Path(self.tmp.name)):
            res = self.router.run_route(520)
        self.assertTrue(res["is_error"])
        self.assertTrue(res["stderr"])


if __name__ == "__main__":
    unittest.main()
