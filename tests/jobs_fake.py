"""An in-process stand-in for the Java job-file store (H-024 M6), for the Python tests that only need *a* writer behind
`jobs.transport`: the same /internal/jobs/* routes and answers as backend/src/main/java/dev/homelab/inbox/jobs/, done with the
code the Python inbox had before the move (`save_job`, `set_paused`, `sync_branch`: one commit per change, one file per job).
The Java side has its own tests (JobsWriterTest) and tests/test_inbox_m6_parity.py compares the two. `install()` swaps the
transport; with `remote` (a git URL or path) it also commits and pushes like the old save_job did, without it only the files
of the clone change."""
import json
import subprocess

README = ("# jobs\n\nDe geplande jobs van de homelab (F-022), één bestand per job in `jobs/`. "
          "Uitleg: `docs/jobs.md` op `main`.\nDeze branch wordt nooit gemerged; de homelab-inbox "
          "leest hem en slaat hier op wat je op de pagina bevestigt.\n")


def _git(d, *args):
    p = subprocess.run(["git", "-C", str(d), *args], capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"git {args[0]}: {(p.stderr or p.stdout).strip()[-300:]}")
    return p.stdout


def set_paused(text, paused):
    """The old jobs.set_paused, as it was."""
    head, sep, rest = text[4:].partition("\n---")
    lines = [l for l in head.splitlines() if not l.startswith("paused:")]
    if paused:
        lines.append("paused: true")
    return "---\n" + "\n".join(lines) + sep + rest


class FakeBackend:
    def __init__(self, jobs, remote=None, branch="jobs"):
        self.jobs, self.remote, self.branch = jobs, remote, branch
        self.calls = []

    def sync(self):
        d = self.jobs.JOBS_DIR
        if self.remote is None:
            d.mkdir(parents=True, exist_ok=True)
            return d
        if not (d / ".git").is_dir():
            d.mkdir(parents=True, exist_ok=True)
            _git(d, "init", "--quiet")
            _git(d, "remote", "add", "origin", self.remote)
            _git(d, "config", "user.name", "homelab-inbox")
            _git(d, "config", "user.email", "homelab-inbox@homelab")
        if _git(d, "ls-remote", "--heads", "origin", self.branch).strip():
            _git(d, "fetch", "--quiet", "origin", self.branch)
            _git(d, "checkout", "--quiet", "--force", "-B", self.branch, "FETCH_HEAD")
            _git(d, "clean", "-fdq")
        else:
            _git(d, "symbolic-ref", "HEAD", f"refs/heads/{self.branch}")
            _git(d, "update-ref", "-d", f"refs/heads/{self.branch}")
            _git(d, "read-tree", "--empty")
            _git(d, "clean", "-fdxq")
        return d

    def save(self, name, text, message):
        """The old save_job."""
        d = self.sync()
        f = d / "jobs" / f"{name}.md"
        if text is None:
            f.unlink(missing_ok=True)
        else:
            f.parent.mkdir(exist_ok=True)
            f.write_text(text.strip() + "\n")
        if self.remote is None:
            return True
        readme = d / "README.md"
        if not readme.exists():
            readme.write_text(README)
        _git(d, "add", "-A")
        if not _git(d, "status", "--porcelain").strip():
            return False
        _git(d, "commit", "--quiet", "-m", message)
        _git(d, "push", "--quiet", "origin", f"HEAD:refs/heads/{self.branch}")
        return True

    def handle(self, path, body):
        self.calls.append((path, body))
        route = path.removeprefix("/internal/jobs/")
        try:
            if route == "sync":
                self.sync()
                return 200, json.dumps({"ok": True, "owned": True}).encode()
            if route in ("save", "delete"):
                return 200, json.dumps({"saved": self.save(body["name"], body.get("text") if route == "save" else None,
                                                           body["message"])}).encode()
            if route in ("pause", "resume"):
                f = self.sync() / "jobs" / f"{body['name']}.md"
                if not f.exists():
                    return 404, json.dumps({"error": "Niet gevonden."}).encode()
                return 200, json.dumps({"saved": self.save(body["name"], set_paused(f.read_text(), route == "pause"),
                                                           body["message"])}).encode()
        except Exception as ex:
            return 400, json.dumps({"error": str(ex)}).encode()
        return 404, json.dumps({"error": "no such route"}).encode()


def install(jobs, remote=None):
    """Make `jobs` use the in-process store; returns it (its `calls` list records what was asked)."""
    fake = FakeBackend(jobs, remote)
    jobs.transport = fake.handle
    return fake
