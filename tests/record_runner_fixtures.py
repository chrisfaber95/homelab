"""H-020 C3: record the outputs of bin/homelab-inbox-task, per action, in a throw-away setup (temp dirs, a fake
`claude`, local git repositories). Nothing here touches the live box. The result is scrubbed (temp paths, commit
hashes) so it can be committed as tests/fixtures/runner/*.json.

    python3 tests/record_runner_fixtures.py --write     # rewrite the fixtures
    tests/test_runner_contract.py                       # fails when the script and the fixtures disagree
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "tests/fixtures/runner"
SCRIPT = ROOT / "bin/homelab-inbox-task"

FAKE_CLAUDE = '''#!/usr/bin/env python3
import json, os, sys
stdin = "" if sys.stdin.isatty() else sys.stdin.read()
event = {"type": "result", "subtype": "success", "is_error": False, "result": "fake claude",
         "argv": sys.argv[1:], "stdin": stdin, "cwd": os.getcwd(),
         "token_set": bool(os.environ.get("CLAUDE_CODE_OAUTH_TOKEN")),
         "auto_memory_disabled": os.environ.get("CLAUDE_CODE_DISABLE_AUTO_MEMORY") == "1"}
print(json.dumps({"type": "system", "model": "fake-model"}))
print(json.dumps(event, sort_keys=True))
sys.exit(int(os.environ.get("FAKE_CLAUDE_EXIT", "0")))
'''


def available():
    return all(shutil.which(c) for c in ("git", "rsync", "timeout", "python3"))


class Setup:
    """A runner home (repos/demo, share/<id>), a task home, and a fake claude on the PATH of the script."""

    def __init__(self):
        self.tmp = Path(tempfile.mkdtemp(prefix="runner-fixtures-")).resolve()
        self.runner = self.tmp / "runner"
        self.task_home = self.tmp / "task"
        self.src = self.runner / "repos/demo"
        self.env = {k: v for k, v in os.environ.items() if k in ("PATH", "LANG", "LC_ALL")}
        self.env.update(INBOX_TASK_HOME=str(self.task_home), INBOX_RUNNER_HOME=str(self.runner),
                        INBOX_CLAUDE_TOKEN=str(self.tmp / "no-token"), GIT_CONFIG_GLOBAL="/dev/null",
                        GIT_CONFIG_SYSTEM="/dev/null", GIT_AUTHOR_NAME="t", GIT_AUTHOR_EMAIL="t@t",
                        GIT_COMMITTER_NAME="t", GIT_COMMITTER_EMAIL="t@t",
                        GIT_AUTHOR_DATE="2026-01-01T00:00:00Z", GIT_COMMITTER_DATE="2026-01-01T00:00:00Z")
        claude = self.task_home / ".local/bin/claude"  # the script's PATH starts with $INBOX_TASK_HOME/.local/bin
        claude.parent.mkdir(parents=True)
        claude.write_text(FAKE_CLAUDE)
        claude.chmod(0o755)
        self.src.mkdir(parents=True)
        self.git(self.src, "init", "--quiet", "--initial-branch=main")
        (self.src / "README.md").write_text("one\n")
        (self.src / "hello.sh").write_text('#!/usr/bin/env bash\necho "hello $1"\n')
        (self.src / "hello.sh").chmod(0o755)
        (self.src / "fail.sh").write_text("echo failing\nexit 1\n")
        self.git(self.src, "add", "-A")
        self.git(self.src, "commit", "--quiet", "-m", "first")

    def git(self, repo, *args):
        return subprocess.run(["git", "-C", str(repo), *args], check=True, capture_output=True, text=True,
                              env=self.env).stdout

    def work(self, task_id):
        return self.task_home / "work" / str(task_id) / "repo"

    def share(self, key, prompt="the prompt", system="the system", history=False):
        d = self.runner / "share" / str(key)
        d.mkdir(parents=True, exist_ok=True)
        (d / "prompt.txt").write_text(prompt)
        (d / "system.txt").write_text(system)
        if history:
            (d / "geschiedenis").mkdir(exist_ok=True)

    def commit(self, repo, name, text, message):
        (Path(repo) / name).write_text(text)
        self.git(repo, "commit", "--quiet", "-a", "-m", message)

    def run(self, args, env=None):
        p = subprocess.run([str(SCRIPT), *args], capture_output=True, text=True, stdin=subprocess.DEVNULL,
                           env={**self.env, **(env or {})}, timeout=120)
        return p

    def scrub(self, text):
        # git warns about the unreadable HOME of the claude account (the script points HOME there in test mode): noise
        # of the machine the fixtures are recorded on, not part of the contract.
        text = "".join(l for l in text.splitlines(keepends=True) if "unable to access" not in l)
        text = text.replace(str(self.tmp), "<tmp>")
        return re.sub(r"(?m)^[0-9a-f]{7,40} ", "abc1234 ", text)

    def case(self, name, args, env=None):
        p = self.run(args, env)
        scrubbed_env = {k: self.scrub(v) for k, v in (env or {}).items()}
        return {"name": name, "action": args[0] if args else "", "args": list(args), "env": scrubbed_env,
                "stdout": self.scrub(p.stdout), "stderr": self.scrub(p.stderr), "exit_code": p.returncode}


def record():
    """Run every case in order; return {name: fixture}. The order matters: some cases build on earlier ones."""
    s = Setup()
    out = {}

    def add(*a, **kw):
        c = s.case(*a, **kw)
        out[c["name"]] = c

    try:
        # The check every action starts with: the task number, then the action.
        add("no-arguments", [])
        add("bad-task-number", ["prepare", "abc"])
        add("task-number-wrong-prefix", ["has", "x5"])
        add("unknown-action", ["frobnicate", "501"])

        # prepare / has / cleanup
        add("prepare-unknown-repo", ["prepare", "501", "nope"])
        add("prepare-ok", ["prepare", "501", "demo"])
        add("has-yes", ["has", "501"])
        add("has-no", ["has", "596"])
        add("cleanup", ["cleanup", "501"])

        # bundle
        s.run(["prepare", "503", "demo"])
        add("bundle-nothing-new", ["bundle", "503"])
        s.run(["prepare", "504", "demo"])
        (s.work(504) / "new.txt").write_text("x\n")
        s.git(s.work(504), "add", "new.txt")
        s.git(s.work(504), "commit", "--quiet", "-m", "Add a thing")
        add("bundle-ok", ["bundle", "504"])

        # behind / sync
        s.run(["prepare", "507", "demo"])
        s.commit(s.src, "README.md", "two\n", "on main")
        add("behind-yes", ["behind", "507"])
        s.run(["prepare", "508", "demo"])
        add("behind-no", ["behind", "508"])
        add("behind-no-copy", ["behind", "597"])
        s.run(["prepare", "505", "demo"])
        s.commit(s.src, "README.md", "three\n", "more on main")
        add("sync-clean", ["sync", "505"])
        s.run(["prepare", "506", "demo"])
        s.commit(s.work(506), "README.md", "mine\n", "on the branch")
        s.commit(s.src, "README.md", "theirs\n", "conflicting on main")
        add("sync-conflict", ["sync", "506"])
        add("sync-no-copy", ["sync", "598"])

        # kill (a task without a running Claude: nothing to stop)
        add("kill-nothing-running", ["kill", "501"])

        # script (jobs)
        add("script-ok", ["script", "j601", "demo", "hello.sh", "world"])
        add("script-exit-code-passes-through", ["script", "j602", "demo", "fail.sh"])
        add("script-unknown-repo", ["script", "j603", "nope", "hello.sh"])
        add("script-bad-path", ["script", "j604", "demo", "../etc/passwd"])
        add("script-path-not-in-repo", ["script", "j605", "demo", "missing.sh"])
        # Not recorded: the timeout (exit 124 plus a marker line). config/inbox.conf sets JOB_SCRIPT_MINUTES after the
        # environment, so a test cannot shorten it below the configured minutes.

        # run (Claude with tools; the fake claude prints what it was called with)
        uid = "11111111-2222-3333-4444-555555555555"
        s.run(["prepare", "510", "demo"])
        s.share(510)
        add("run-new", ["run", "510", uid, "new"])
        s.share(510, history=True)
        add("run-resume-with-history", ["run", "510", uid, "resume"])
        add("run-research", ["run", "510", uid, "new", "research"])
        (s.tmp / "token").write_text("not-a-real-token\n")
        for n in (511, 512):  # their own ids: the fake finds a fixture by its arguments alone
            s.run(["prepare", str(n), "demo"])
            s.share(n)
        add("run-with-token-file", ["run", "511", uid, "new"], env={"INBOX_CLAUDE_TOKEN": str(s.tmp / "token")})
        add("run-claude-exit-code-passes-through", ["run", "512", uid, "new"], env={"FAKE_CLAUDE_EXIT": "1"})
        add("run-bad-kind", ["run", "510", uid, "new", "other"])
        add("run-bad-session", ["run", "510", "not-a-session", "new"])
        add("run-bad-mode", ["run", "510", uid, "later"])
        add("run-no-copy", ["run", "599", uid, "new"])

        # route / draft / review (Claude without tools, JSON on stdout)
        for action, key in (("route", "r520"), ("draft", "d521"), ("review", "v522"), ("plan", "r524")):
            s.share(key, prompt=f"prompt for {action}", system=f"system for {action}")
            add(f"{action}-ok", [action, key])
        s.share("r523")
        add("route-exit-code-passes-through", ["route", "r523"], env={"FAKE_CLAUDE_EXIT": "1"})
    finally:
        shutil.rmtree(s.tmp, ignore_errors=True)
    return out


def write():
    FIXTURES.mkdir(parents=True, exist_ok=True)
    for old in FIXTURES.glob("*.json"):
        old.unlink()
    for name, fx in record().items():
        (FIXTURES / f"{name}.json").write_text(json.dumps(fx, indent=2, ensure_ascii=False, sort_keys=True) + "\n")


if __name__ == "__main__":
    if "--write" not in sys.argv:
        sys.exit(__doc__)
    if not available():
        sys.exit("git, rsync, timeout and python3 are needed")
    write()
