"""H-025 R1: runner/round.py against the fake homelab-inbox-task: NDJSON events, the final result line, exit codes,
no secrets in arguments. Run: python3 -m unittest discover -s tests"""
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RUNNER = ROOT / "runner/round.py"
FAKE = ROOT / "tests/fakes/homelab-inbox-task"
SESSION = "11111111-2222-3333-4444-555555555555"


def stream(*events):
    return "".join(json.dumps(e) + "\n" for e in events)


def run_round(args, fixture=None):
    """Run the runner with the fake as TASK_CMD; `fixture` (stdout, exit code) replaces the recorded `run` output."""
    env = {**os.environ, "TASK_CMD": str(FAKE)}
    with tempfile.TemporaryDirectory() as d:
        if fixture:
            fx = {"name": "x", "action": "run", "args": ["run", *args], "env": {}, "stderr": "claude says hi\n",
                  "stdout": fixture[0], "exit_code": fixture[1]}
            (Path(d) / "x.json").write_text(json.dumps(fx))
            env["RUNNER_FIXTURES"] = d
        p = subprocess.run([sys.executable, "-I", str(RUNNER), *args], capture_output=True, text=True, env=env, timeout=30)
    return p, [json.loads(l) for l in p.stdout.splitlines()]


class Round(unittest.TestCase):
    def test_recorded_run_gives_model_log_and_one_final_result(self):
        p, evs = run_round(["510", SESSION, "new"])
        self.assertEqual(p.returncode, 0)
        self.assertTrue(all(e["v"] == 1 for e in evs))
        self.assertEqual([e["event"] for e in evs].count("result"), 1)
        self.assertEqual(evs[-1]["event"], "result")
        self.assertIn({"event": "model", "v": 1, "model": "fake-model"}, evs)
        self.assertEqual(evs[-1]["text"], "fake claude")
        self.assertEqual(evs[-1]["outcome"], "none")
        self.assertEqual(evs[-1]["exit_code"], 0)
        self.assertEqual(evs[-1]["raw"]["type"], "result")

    def test_turns_logs_and_outcome(self):
        out = stream(
            {"type": "system", "subtype": "init", "model": "m1"},
            {"type": "assistant", "message": {"content": [{"type": "tool_use", "name": "Bash", "input": {"command": "ls"}}]}},
            {"type": "user", "message": {"content": [{"type": "tool_result", "content": "a\nb"}]}},
            {"type": "assistant", "message": {"content": [{"type": "text", "text": "KLAAR: gedaan"}]}},
            "not an event", {"type": "result", "is_error": False, "result": "KLAAR: gedaan", "num_turns": 2})
        p, evs = run_round(["7", SESSION, "resume"], (out.replace('"not an event"', "garbage"), 0))
        self.assertEqual([e["turns"] for e in evs if e["event"] == "turn"], [1, 2])
        logs = [(e["kind"], e["text"]) for e in evs if e["event"] == "log"]
        self.assertIn(("tool", "Bash: ls"), logs)
        self.assertIn(("result", "a (+1 regels)"), logs)
        self.assertEqual(evs[-1]["outcome"], "done")
        self.assertEqual(evs[-1]["turns"], 2)

    def test_outcomes(self):
        for text, outcome in (("ANTWOORD: x", "answer"), ("VRAAG: x?", "question"), ("GEWEIGERD: nee", "refused"), ("hm", "none")):
            out = stream({"type": "result", "is_error": False, "result": text})
            self.assertEqual(run_round(["7", SESSION, "new"], (out, 0))[1][-1]["outcome"], outcome, text)

    def test_claude_exit_code_passes_through_with_a_result_line(self):
        p, evs = run_round(["7", SESSION, "new"], ("", 7))
        self.assertEqual(p.returncode, 7)
        self.assertEqual(evs[-1]["event"], "result")
        self.assertEqual(evs[-1]["exit_code"], 7)
        self.assertIsNone(evs[-1]["raw"])
        self.assertEqual(evs[-1]["outcome"], "none")
        self.assertIn("claude says hi", p.stderr)

    def test_error_result_has_no_outcome(self):
        out = stream({"type": "result", "is_error": True, "result": "KLAAR: x"})
        self.assertEqual(run_round(["7", SESSION, "new"], (out, 1))[1][-1]["outcome"], "none")

    def test_refusals_of_homelab_inbox_task_keep_their_exit_code(self):
        p, evs = run_round(["510", "not-a-session", "new"])
        self.assertEqual(p.returncode, 2)
        self.assertEqual(evs[-1]["exit_code"], 2)

    def test_bad_runner_arguments_exit_2_without_output(self):
        for args in ([], ["510"], ["abc", SESSION, "new"], ["510", SESSION, "new", "x"]):
            p, evs = run_round(args)
            self.assertEqual((p.returncode, evs), (2, []), args)
            self.assertIn("usage", p.stderr)

    def test_research_is_passed_on(self):
        p, evs = run_round(["510", SESSION, "new", "research"])
        self.assertEqual(p.returncode, 0)
        self.assertEqual(evs[-1]["event"], "result")

    def test_runner_never_puts_a_prompt_or_token_in_arguments(self):
        text = RUNNER.read_text()
        for bad in ("prompt.txt", "OAUTH", "--append-system-prompt", "TOKEN_FILE"):
            self.assertNotIn(bad, text)


class InboxUsesRunner(unittest.TestCase):
    """rounds.stream_claude starts runner/round.py and reads its events (H-025 R1)."""

    def test_stream_claude_reads_the_runner_events(self):
        os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
        os.environ.setdefault("BACKEND_ROUTES", "")
        sys.path.insert(0, str(ROOT / "bin"))
        from homelab_inbox import rounds
        updates = []
        out = stream({"type": "system", "model": "m1"}, {"type": "assistant", "message": {"content": []}},
                     {"type": "result", "is_error": False, "result": "ANTWOORD: ok"})
        with tempfile.TemporaryDirectory() as d:
            (Path(d) / "x.json").write_text(json.dumps({"name": "x", "action": "run", "args": ["run", "9", SESSION, "new"],
                                                        "env": {}, "stderr": "", "stdout": out, "exit_code": 0}))
            os.environ["RUNNER_FIXTURES"] = d
            saved = (rounds.get, rounds.update, rounds.TASK_CMD, rounds.research_run)
            rounds.get = lambda i: {"id": i, "session": SESSION, "turns": 3}
            rounds.update = lambda i, **kw: updates.append(kw)
            rounds.TASK_CMD = [str(FAKE)]
            rounds.research_run = lambda t: None
            try:
                result, turns, capped = rounds.stream_claude(9, "new")
            finally:
                rounds.get, rounds.update, rounds.TASK_CMD, rounds.research_run = saved
                del os.environ["RUNNER_FIXTURES"]
        self.assertEqual((result["result"], turns, capped), ("ANTWOORD: ok", 4, None))
        self.assertIn({"model": "m1"}, updates)


if __name__ == "__main__":
    unittest.main()
