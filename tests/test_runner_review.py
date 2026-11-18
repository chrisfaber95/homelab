"""H-025 R2: runner/review.py against the fake homelab-inbox-task, and briefs.review_pr using it.
Run: python3 -m unittest discover -s tests"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNNER = ROOT / "runner/review.py"
FAKE = ROOT / "tests/fakes/homelab-inbox-task"


def run_review(args, fixture=None):
    env = {**os.environ, "TASK_CMD": str(FAKE)}
    with tempfile.TemporaryDirectory() as d:
        if fixture:
            fx = {"name": "x", "action": "review", "args": ["review", f"v{args[0]}"], "env": {}, "stderr": fixture[2],
                  "stdout": fixture[0], "exit_code": fixture[1]}
            (Path(d) / "x.json").write_text(json.dumps(fx))
            env["RUNNER_FIXTURES"] = d
        p = subprocess.run([sys.executable, "-I", str(RUNNER), *args], capture_output=True, text=True, env=env, timeout=30)
    return p, [json.loads(l) for l in p.stdout.splitlines()]


class Review(unittest.TestCase):
    def test_recorded_review_gives_one_final_result(self):
        p, evs = run_review(["522"])
        self.assertEqual(p.returncode, 0)
        self.assertTrue(all(e["v"] == 1 for e in evs))
        self.assertEqual([e["event"] for e in evs].count("result"), 1)
        self.assertEqual(evs[-1]["event"], "result")
        self.assertTrue(evs[-1]["ok"])
        self.assertEqual(evs[-1]["text"], "fake claude")
        self.assertEqual(evs[-1]["exit_code"], 0)

    def test_warning_line_after_the_json_is_ignored(self):
        out = json.dumps({"is_error": False, "result": "hi"}) + "\n[claude-code:unrecognized_model] x\n"
        p, evs = run_review(["7"], (out, 0, ""))
        self.assertTrue(evs[-1]["ok"])
        self.assertEqual(evs[-1]["text"], "hi")

    def test_is_error_is_not_ok_and_keeps_detail(self):
        out = json.dumps({"is_error": True, "result": "model unknown"})
        p, evs = run_review(["7"], (out, 1, "boom\n"))
        self.assertEqual(p.returncode, 1)
        self.assertFalse(evs[-1]["ok"])
        self.assertIn("boom", evs[-1]["detail"])
        self.assertIn("boom", p.stderr)

    def test_no_json_at_all(self):
        p, evs = run_review(["7"], ("", 1, "kapot\n"))
        self.assertEqual((p.returncode, evs[-1]["ok"], evs[-1]["raw"]), (1, False, None))
        self.assertIn("kapot", evs[-1]["detail"])

    def test_refusal_exit_code_passes_through(self):
        p, evs = run_review(["7"], ("", 2, "geen werkkopie\n"))
        self.assertEqual((p.returncode, evs[-1]["exit_code"]), (2, 2))

    def test_bad_runner_arguments_exit_2_without_output(self):
        for args in ([], ["abc"], ["1", "2"], ["v7"]):
            p, evs = run_review(args)
            self.assertEqual((p.returncode, evs), (2, []), args)
            self.assertIn("usage", p.stderr)

    def test_runner_never_puts_a_prompt_or_token_in_arguments(self):
        text = RUNNER.read_text()
        for bad in ("prompt.txt", "system.txt", "OAUTH", "--append-system-prompt", "TOKEN_FILE"):
            self.assertNotIn(bad, text)


class InboxUsesRunner(unittest.TestCase):
    def test_run_runner_returns_the_result_event(self):
        os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
        os.environ.setdefault("BACKEND_ROUTES", "")
        sys.path.insert(0, str(ROOT / "bin"))
        from homelab_inbox import helpers
        saved = helpers.TASK_CMD
        helpers.TASK_CMD = [str(FAKE)]
        try:
            r = helpers.run_runner("review.py", 522, timeout=30)
            bad = helpers.run_runner("review.py", "x", timeout=30)
        finally:
            helpers.TASK_CMD = saved
        self.assertEqual((r["ok"], r["text"]), (True, "fake claude"))
        self.assertFalse(bad["ok"])

    def test_review_pr_reads_the_runner_result(self):
        from unittest import mock
        os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
        os.environ.setdefault("BACKEND_ROUTES", "")
        sys.path.insert(0, str(ROOT / "bin"))
        from homelab_inbox import briefs
        text = "```review\n" + json.dumps({"summary": "ok", "criteria": [{"criterion": "c", "verdict": "pass"}]}) + "\n```"
        t = {"id": 5, "repo": "homelab", "criteria": '["c"]', "text": "x", "pr_number": 3, "reviews": 0, "brief": "H-1",
             "parent": None, "job": None, "summary": "", "pr_url": "u", "unattended": 0}
        for result, review_ok in (({"ok": True, "text": text}, 1), ({"ok": False, "text": "", "detail": "boom"}, 0)):
            upd = {}
            with tempfile.TemporaryDirectory() as home, \
                    mock.patch.object(briefs, "HOME", Path(home)), mock.patch.object(briefs, "get", lambda i: t), \
                    mock.patch.object(briefs, "repo_list", lambda: {"homelab": "o/r"}), \
                    mock.patch.object(briefs, "review_diff", lambda c, i: ("diff", [])), \
                    mock.patch.object(briefs, "run_runner", lambda *a, **k: result), \
                    mock.patch.object(briefs, "gh"), mock.patch.object(briefs, "notify"), \
                    mock.patch.object(briefs, "log_line"), mock.patch.object(briefs, "merges_itself", lambda t: False), \
                    mock.patch.object(briefs, "update", lambda i, **kw: upd.update(kw)):
                briefs.review_pr(5, "cache")
            self.assertEqual(upd["review_ok"], review_ok)


if __name__ == "__main__":
    unittest.main()
