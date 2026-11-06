"""A task that continues without a PR gets the latest main first (nothing else notices main moved on).
Run: python3 -m unittest discover -s tests"""
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
os.environ.setdefault("INBOX_RUNNER_HOME", tempfile.mkdtemp())
os.environ.update(NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"))
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import queue, storage  # noqa: E402

T = "2026-11-06T22:30:00+00:00"
TASK = {"id": 119, "repo": "homelab", "pr_number": None}


class CatchUpMain(unittest.TestCase):
    """queue.catch_up_main: what the task is told, and when it leaves the working copy alone."""

    def setUp(self):
        self.calls = []
        self.answers = {}
        queue.task_cmd, self._tc = self.task_cmd, queue.task_cmd

    def tearDown(self):
        queue.task_cmd = self._tc

    def task_cmd(self, *args, **kw):
        self.calls.append(args[0])
        return self.answers.get(args[0], (0, ""))

    def test_a_pr_is_left_to_the_github_poller(self):
        # on_conflict already merges main into a branch with a PR, and counts its rounds: don't do it twice.
        self.assertEqual(queue.catch_up_main({**TASK, "pr_number": 7}), "")
        self.assertEqual(self.calls, [])

    def test_a_branch_that_already_has_main_is_not_touched(self):
        self.answers["behind"] = (1, "")
        self.assertEqual(queue.catch_up_main(TASK), "")
        self.assertEqual(self.calls, ["behind"])

    def test_no_working_copy_is_not_touched(self):
        self.answers["behind"] = (2, "geen werkkopie")
        self.assertEqual(queue.catch_up_main(TASK), "")
        self.assertEqual(self.calls, ["behind"])

    def test_a_clean_merge_is_told_to_the_task(self):
        out = queue.catch_up_main(TASK)
        self.assertEqual(self.calls, ["behind", "sync"])
        self.assertIn("merged into your branch", out)

    def test_conflicts_name_the_files(self):
        self.answers["sync"] = (4, "package.json\nclient/src/main.ts\n")
        out = queue.catch_up_main(TASK)
        self.assertIn("- package.json", out)
        self.assertIn("- client/src/main.ts", out)
        self.assertIn("commit the merge", out)

    def test_a_failed_merge_hands_over_the_command(self):
        self.answers["sync"] = (1, "git merge origin/main failed without conflicts (uncommitted changes?)")
        out = queue.catch_up_main(TASK)
        self.assertIn("uncommitted changes", out)
        self.assertIn("safe.directory", out)  # a plain git fetch is refused: the task cannot guess this


class RoundGetsMain(unittest.TestCase):
    """The round of a retried task starts with main merged in and says so in its prompt."""

    def setUp(self):
        c = storage.db()
        c.execute("DELETE FROM tasks")
        c.execute("INSERT INTO tasks(id,repo,text,status,created,session,rounds,pending_input) "
                  "VALUES(119,'homelab','scaffold it','queued',?,'s',1,'Retried by the user.')", (T,))
        c.commit()
        self.synced, self.prompt = [], None
        self._saved = {n: getattr(queue, n) for n in (
            "refresh_cache", "load_memory", "task_cmd", "write_prompt", "stream_claude", "finish_round",
            "after_round")}
        queue.refresh_cache = lambda name, slug: "/dev/null"
        queue.load_memory = lambda: None
        queue.task_cmd = lambda *a, **kw: (self.synced.append(a[0]), (0, ""))[1]
        queue.write_prompt = lambda t, prompt: setattr(self, "prompt", prompt)
        queue.stream_claude = lambda task_id, mode: ({"result": "KLAAR: done"}, 3, None)
        queue.finish_round = lambda *a, **kw: None
        queue.after_round = lambda *a, **kw: None

    def tearDown(self):
        for n, f in self._saved.items():
            setattr(queue, n, f)

    def test_retried_round_merges_main_and_keeps_the_users_note(self):
        queue.run_round(119)
        self.assertEqual(self.synced, ["behind", "sync"])
        self.assertTrue(self.prompt.startswith("Retried by the user."))
        self.assertIn("merged into your branch", self.prompt)

    def test_first_round_does_not_sync(self):
        storage.update(119, rounds=0)
        queue.run_round(119)
        self.assertEqual(self.synced, ["prepare"])
        self.assertIn("scaffold it", self.prompt)


class BehindSubcommand(unittest.TestCase):
    """homelab-inbox-task behind/sync against real git repositories."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.mkdtemp()
        cls.src = Path(cls.tmp) / "src"
        cls.work = Path(cls.tmp) / "task/work/777/repo"
        cls.env = {**os.environ, "INBOX_TASK_HOME": str(Path(cls.tmp) / "task"),
                   "GIT_AUTHOR_NAME": "t", "GIT_AUTHOR_EMAIL": "t@t",
                   "GIT_COMMITTER_NAME": "t", "GIT_COMMITTER_EMAIL": "t@t"}
        cls.git(cls.src, "init", "--quiet", "--initial-branch=main", str(cls.src), cwd=Path(cls.tmp))
        (cls.src / "README.md").write_text("one\n")
        cls.git(cls.src, "add", "-A")
        cls.git(cls.src, "commit", "--quiet", "-m", "first")
        cls.work.parent.mkdir(parents=True)
        cls.git(cls.src, "clone", "--quiet", str(cls.src), str(cls.work), cwd=Path(cls.tmp))
        cls.git(cls.work, "checkout", "--quiet", "-b", "inbox/777", "origin/main")
        cls.git(cls.work, "config", "user.email", "t@t")
        cls.git(cls.work, "config", "user.name", "t")
        (cls.work.parent / "base").write_text(cls.git(cls.work, "rev-parse", "origin/main"))

    @classmethod
    def git(cls, repo, *args, cwd=None):
        return subprocess.run(["git", "-C", str(cwd or repo), *args], check=True, capture_output=True, text=True,
                              env=cls.env).stdout

    def task(self, *args):
        return subprocess.run([str(ROOT / "bin/homelab-inbox-task"), *args], capture_output=True, text=True,
                              env=self.env)

    def commit_on_main(self, text):
        (self.src / "README.md").write_text(text)
        self.git(self.src, "commit", "--quiet", "-a", "-m", "on main")

    def test_1_a_branch_on_the_same_main_is_not_behind(self):
        self.assertEqual(self.task("behind", "777").returncode, 1)

    def test_2_a_new_commit_on_main_makes_it_behind(self):
        self.commit_on_main("two\n")
        self.assertEqual(self.task("behind", "777").returncode, 0)

    def test_3_sync_merges_it_and_then_it_is_no_longer_behind(self):
        self.assertEqual(self.task("sync", "777").returncode, 0)
        self.assertEqual((self.work / "README.md").read_text(), "two\n")
        self.assertEqual(self.task("behind", "777").returncode, 1)

    def test_3b_the_merged_commits_of_main_are_not_the_tasks_own_work(self):
        # The base moved with the merge, so a round that commits nothing of its own pushes nothing (exit 3) and
        # cannot open a PR with an empty diff.
        self.assertEqual((self.work.parent / "base").read_text().strip(),
                         self.git(self.work, "rev-parse", "origin/main").strip())
        self.assertEqual(self.task("bundle", "777").returncode, 3)

    def test_4_sync_reports_the_conflicted_files(self):
        (self.work / "README.md").write_text("mine\n")
        self.git(self.work, "commit", "--quiet", "-a", "-m", "on the branch")
        self.commit_on_main("theirs\n")
        self.assertEqual(self.task("behind", "777").returncode, 0)
        done = self.task("sync", "777")
        self.assertEqual(done.returncode, 4)
        self.assertEqual(done.stdout.split(), ["README.md"])

    def test_5_without_a_working_copy_it_says_so(self):
        done = self.task("behind", "778")
        self.assertEqual(done.returncode, 2)
        self.assertIn("werkkopie", done.stderr)


if __name__ == "__main__":
    unittest.main()
