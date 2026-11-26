"""A retry of a task whose first round never made a Claude session starts afresh, and a round without turns fails
loudly (inbox 235: --resume of a session that did not exist ended in 0 s and was reported as 'no commits').
Run: python3 -m unittest discover -s tests"""
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"))
os.environ.setdefault("BACKEND_ROUTES", "")
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import queue, rounds, storage  # noqa: E402

T = "2026-11-26T09:00:00+00:00"
SID = "05714350-6486-458a-bf1d-0c2a3299a6e2"


class RoundMode(unittest.TestCase):
    def setUp(self):
        c = storage.db()
        c.execute("DELETE FROM tasks")
        c.execute("INSERT INTO tasks(id,repo,text,status,created,session,rounds,pending_input) "
                  "VALUES(235,'homelab','port the router','queued',?,?,1,'Retried by the user.')", (T, SID))
        c.commit()
        self.session_code, self.mode, self.prompt = 0, None, None
        self.patches = [
            mock.patch.object(queue, "refresh_cache", return_value="/dev/null"),
            mock.patch.object(queue, "load_memory"),
            mock.patch.object(queue, "catch_up_main", return_value=""),
            mock.patch.object(queue, "task_cmd", side_effect=lambda *a, **k: (self.session_code, "")),
            mock.patch.object(queue, "write_prompt", side_effect=lambda t, p: setattr(self, "prompt", p)),
            mock.patch.object(queue, "stream_claude",
                              side_effect=lambda i, m: (setattr(self, "mode", m), ({"result": "KLAAR: x"}, 1, None))[1]),
            mock.patch.object(queue, "finish_round"),
            mock.patch.object(queue, "after_round"),
        ]
        for p in self.patches:
            p.start()

    def tearDown(self):
        mock.patch.stopall()

    def test_existing_session_is_resumed(self):
        queue.run_round(235)
        self.assertEqual(self.mode, "resume")
        self.assertTrue(self.prompt.startswith("Retried by the user."))

    def test_missing_session_starts_a_new_one_with_the_task_text(self):
        self.session_code = 1
        queue.run_round(235)
        self.assertEqual(self.mode, "new")
        self.assertIn("port the router", self.prompt)
        self.assertIn("Retried by the user.", self.prompt)


class EmptyRound(unittest.TestCase):
    def run_stream(self, result):
        c = storage.db()
        c.execute("DELETE FROM tasks")
        c.execute("INSERT INTO tasks(id,repo,text,status,created,session,rounds,turns) "
                  "VALUES(235,'homelab','x','running',?,?,2,0)", (T, SID))
        c.commit()
        p = mock.Mock(timed_out=False)
        p.wait.return_value = 0
        p.stdout = iter([json.dumps({"event": "result", "raw": result}) + "\n"])
        with mock.patch.object(rounds.subprocess, "Popen", return_value=p), mock.patch.object(rounds, "log_line"):
            return rounds.stream_claude(235, "resume")

    def test_error_result_without_turns_raises(self):
        with self.assertRaisesRegex(RuntimeError, "0 beurten.*No conversation"):
            self.run_stream({"is_error": True, "result": "No conversation found with session ID"})

    def test_empty_result_without_turns_raises(self):
        with self.assertRaises(RuntimeError):
            self.run_stream({"is_error": False, "result": ""})
