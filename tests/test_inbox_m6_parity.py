"""H-024 M6: the job-file writes moved to the Java backend. Two Python inboxes see the same requests: the reference one writes
the old way (tests/jobs_fake.py, the code the Python inbox had, against its own copy of the jobs branch), the other is the
real thing, in front of the Java backend, which owns the clone after `import-jobs` (a throwaway PostgreSQL is only there
because the backend starts its conversation store). Every POST of the Jobs pages goes to both, with the CSRF token and the
owner check of the Python server, and the answers must be the same; then the two branches must hold the same files and the
same commits (author and message). Then the same checks run against the Java base URL with the facade secret: the contract
of /internal/jobs/*. The public routes are unchanged (Python checks owner, CSRF and drafts, Java writes the file); the
rollback (export-jobs) is tested last. Skipped without java and a built jar (cd backend && ./mvnw -DskipTests package, or
BACKEND_JAR) and PostgreSQL binaries. Run: python3 -m unittest tests.test_inbox_m6_parity"""
import json
import os
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tests"))
JARS = sorted((ROOT / "backend" / "target").glob("inbox-backend-*.jar")) if (ROOT / "backend" / "target").is_dir() else []
JAR = Path(os.environ.get("BACKEND_JAR") or (JARS[0] if JARS else ""))
JAVA = shutil.which("java")
PG_BINS = sorted(Path("/usr/lib/postgresql").glob("*/bin")) if Path("/usr/lib/postgresql").is_dir() else []
PG_BIN = Path(os.environ["PG_BIN"]) if os.environ.get("PG_BIN") else (PG_BINS[-1] if PG_BINS else None)
SECRET = "m6-parity-secret"
GOOD = '---\nwhen: daily\nschedule: "0 7 * * *"\nkind: box-report\nmodel: local\n---\nReport what failed.\n'
EDITED = '---\nwhen: weekly\nschedule: "0 8 * * 1"\nkind: box-report\nmodel: local\npaused: true\n---\nReport it all.\n'
SERVE = """
import os, sys
from http.server import ThreadingHTTPServer
sys.path.insert(0, {bin!r}); sys.path.insert(0, {tests!r})
from homelab_inbox import jobs, server
if sys.argv[2] == "reference":
    import jobs_fake
    jobs_fake.install(jobs, remote=os.environ["REMOTE_HOMELAB_DATA"]).sync()
jobs.load_jobs(fetch=False)
ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), server.Handler).serve_forever()
""".format(bin=str(ROOT / "bin"), tests=str(ROOT / "tests"))


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_for(url, seconds=90):
    end = time.time() + seconds
    while time.time() < end:
        try:
            with urllib.request.urlopen(url, timeout=3):
                return
        except urllib.error.HTTPError:
            return
        except OSError:
            time.sleep(0.5)
    raise RuntimeError(f"{url} did not come up")


def sh(cwd, *cmd):
    p = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    assert p.returncode == 0, f"{cmd}: {p.stdout}{p.stderr}"
    return p.stdout


def seed_remote(path, tmp):
    """A bare repository with the branch `jobs` and one job, as the Python inbox left it before the switch."""
    sh(tmp, "git", "init", "--bare", "--quiet", "-b", "jobs", str(path))
    work = tmp / f"seed-{path.name}"
    sh(tmp, "git", "clone", "--quiet", str(path), str(work))
    sh(work, "git", "checkout", "--quiet", "-B", "jobs")
    (work / "jobs").mkdir()
    (work / "jobs/oud.md").write_text(GOOD)
    sh(work, "git", "add", "-A")
    sh(work, "git", "-c", "user.name=seed", "-c", "user.email=s@s", "commit", "--quiet", "-m", "seed")
    sh(work, "git", "push", "--quiet", "origin", "jobs")


def snapshot(remote, tmp, name):
    """The files and the commits (author, message) of the branch."""
    check = tmp / f"snap-{name}"
    shutil.rmtree(check, ignore_errors=True)
    sh(tmp, "git", "clone", "--quiet", "-b", "jobs", str(remote), str(check))
    files = {f.relative_to(check).as_posix(): f.read_text() for f in sorted(check.rglob("*.md")) if ".git" not in f.parts}
    return files, sh(check, "git", "log", "--format=%an|%ae|%s").splitlines()


@unittest.skipUnless(JAVA and JAR.is_file() and PG_BIN and (PG_BIN / "initdb").exists(),
                     "needs java, a built backend jar (backend/target/inbox-backend-*.jar) and PostgreSQL binaries")
class M6Parity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="m6"))
        cls.procs = []
        pg, sock = cls.tmp / "pg", cls.tmp / "sock"
        sock.mkdir()
        subprocess.run([PG_BIN / "initdb", "-D", pg, "-U", "postgres", "--auth=trust", "--locale=C.UTF-8", "-N"],
                       check=True, capture_output=True)
        subprocess.run([PG_BIN / "pg_ctl", "-D", pg, "-w", "-l", cls.tmp / "pg.log", "-o",
                        f"-c listen_addresses='' -c unix_socket_directories='{sock}' -c port=54360 -c fsync=off", "start"],
                       check=True, capture_output=True)
        cls.pg = pg
        subprocess.run([PG_BIN / "createdb", "-h", sock, "-p", "54360", "-U", "postgres", "inbox"], check=True)
        url = ("jdbc:postgresql://localhost/inbox?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg"
               f"&socketFactoryArg={sock}/.s.PGSQL.54360")
        cls.homes = {k: cls.tmp / f"home-{k}" for k in ("ref", "new")}
        cls.remotes = {k: cls.tmp / f"remote-{k}.git" for k in cls.homes}
        for k in cls.homes:
            cls.homes[k].mkdir()
            seed_remote(cls.remotes[k], cls.tmp)
        (cls.homes["new"] / "inbox.db").touch()
        sh(cls.tmp, str(JAVA), "-jar", str(JAR), "import-conversation", "--sqlite", str(cls.homes["new"] / "inbox.db"),
           "--url", url, "--user", "postgres", "--if-not-imported")
        cls.env = {**os.environ, "NTFY_FAKE": "1", "INBOX_ALLOW_ALL": "1", "BACKEND_ROUTES": "",
                   "REPOS_CONF": str(ROOT / "config/repos.conf"), "INBOX_CONFIG_DIR": str(ROOT / "config")}
        cls.env.pop("CLIENTS_JSON", None)
        cls.java_env = {**cls.env, "INBOX_RUNNER_HOME": str(cls.homes["new"]), "REMOTE_HOMELAB_DATA": str(cls.remotes["new"])}
        # The fresh import of the switch: the clone is brought to the branch and the marker makes Java its only writer.
        done = subprocess.run([JAVA, "-jar", JAR, "import-jobs"], env=cls.java_env, capture_output=True, text=True)
        assert done.returncode == 0, done.stdout + done.stderr
        cls.import_output = done.stdout
        cls.java_port, cls.ref_port, cls.new_port = free_port(), free_port(), free_port()
        cls.start_java()
        for port, which, key, extra in ((cls.ref_port, "reference", "ref", {}),
                                        (cls.new_port, "java", "new", {"BACKEND_URL": f"http://127.0.0.1:{cls.java_port}",
                                                                       "BACKEND_SECRET": SECRET})):
            cls.procs.append(subprocess.Popen([sys.executable, "-I", "-c", SERVE, str(port), which],
                                              env={**cls.env, "INBOX_RUNNER_HOME": str(cls.homes[key]),
                                                   "REMOTE_HOMELAB_DATA": str(cls.remotes[key]), **extra},
                                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            wait_for(f"http://127.0.0.1:{port}/api/session")
        cls.url = url

    @classmethod
    def start_java(cls):
        cls.java_proc = subprocess.Popen(
            [JAVA, "-Xmx256m", "-jar", JAR], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            env={**cls.java_env, "BACKEND_PORT": str(cls.java_port), "BACKEND_SECRET": SECRET, "SPRING_DATASOURCE_URL": cls.url
                 if hasattr(cls, "url") else cls._url(), "SPRING_DATASOURCE_USERNAME": "postgres"})
        cls.procs.append(cls.java_proc)
        wait_for(f"http://127.0.0.1:{cls.java_port}/api/backend/health")

    @classmethod
    def _url(cls):
        sock = cls.tmp / "sock"
        return ("jdbc:postgresql://localhost/inbox?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg"
                f"&socketFactoryArg={sock}/.s.PGSQL.54360")

    @classmethod
    def tearDownClass(cls):
        for p in cls.procs:
            p.terminate()
        for p in cls.procs:
            p.wait(timeout=30)
        subprocess.run([PG_BIN / "pg_ctl", "-D", cls.pg, "-m", "immediate", "-w", "stop"], capture_output=True)
        shutil.rmtree(cls.tmp, ignore_errors=True)

    # ---- helpers ----------------------------------------------------------------------------------------------------
    def post(self, port, path, fields):
        """A form post of the Jobs pages with CSRF: (status, Location or None, parsed body)."""
        base = f"http://127.0.0.1:{port}"
        csrf = json.loads(urllib.request.urlopen(base + "/api/session").read())["csrf_token"]
        req = urllib.request.Request(base + path, method="POST", data=urllib.parse.urlencode({"csrf": csrf, **fields}).encode(),
                                     headers={"Origin": base, "Content-Type": "application/x-www-form-urlencoded"})
        opener = urllib.request.build_opener(type("NoRedirect", (urllib.request.HTTPRedirectHandler,), {
            "redirect_request": lambda *a, **k: None}))
        try:
            with opener.open(req, timeout=60) as r:
                return r.status, r.headers.get("Location"), r.read().decode()
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                raw = json.loads(raw)
            except ValueError:
                pass
            return e.code, e.headers.get("Location"), raw

    def both(self, path, **fields):
        ref = self.post(self.ref_port, path, fields)
        new = self.post(self.new_port, path, fields)
        self.assertEqual(new, ref, f"POST {path} {fields}")
        return new

    def draft(self, text, base=None):
        """The same ready draft in both inboxes (the drafts table is Python's); returns its id."""
        ids = set()
        for k in ("ref", "new"):
            with sqlite3.connect(self.homes[k] / "inbox.db") as c:
                c.execute("CREATE TABLE IF NOT EXISTS drafts (id INTEGER PRIMARY KEY, created TEXT NOT NULL, request TEXT NOT NULL, "
                          "base TEXT, status TEXT NOT NULL, text TEXT, error TEXT)")
                ids.add(c.execute("INSERT INTO drafts (created, request, base, status, text) VALUES (?,?,?,?,?)",
                                  ("2026-11-18T08:00:00+00:00", "test", base, "ready", text)).lastrowid)
        self.assertEqual(len(ids), 1)
        return ids.pop()

    def java(self, path, body, secret=SECRET):
        req = urllib.request.Request(f"http://127.0.0.1:{self.java_port}{path}", method="POST", data=json.dumps(body).encode(),
                                     headers={"Content-Type": "application/json", **({"X-Inbox-Facade": secret} if secret else {})})
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                return r.status, json.loads(r.read())
        except urllib.error.HTTPError as e:
            return e.code, json.loads(e.read() or b"{}")

    def assert_same_branch(self):
        self.assertEqual(snapshot(self.remotes["new"], self.tmp, "new"), snapshot(self.remotes["ref"], self.tmp, "ref"))

    # ---- the parity -------------------------------------------------------------------------------------------------
    def test_a_the_import_left_java_the_only_writer_of_the_seeded_branch(self):
        self.assertIn("import ok: job files=1", self.import_output)
        self.assertTrue((self.homes["new"] / "jobs-repo/.git/inbox-jobs-owner").is_file())
        self.assert_same_branch()

    def test_b_the_page_posts_get_the_same_answers_and_leave_the_same_branch(self):
        named = GOOD.replace("---\nwhen", "---\nname: nieuw\nwhen", 1)
        d = self.draft(named)
        s, loc, _ = self.both(f"/draft/{d}/confirm", text=named)
        self.assertEqual((s, loc), (303, "/job/nieuw"))
        d = self.draft(named)
        self.both(f"/draft/{d}/confirm", text=named)  # a job of that name exists
        d = self.draft(GOOD.replace("kind: box-report", "kind: nope"))
        self.both(f"/draft/{d}/confirm", text=GOOD.replace("kind: box-report", "kind: nope"))
        d = self.draft(EDITED, base="nieuw")
        self.both(f"/draft/{d}/confirm", text=EDITED)  # a change of an existing job
        self.both("/job/oud/pause")
        self.both("/job/oud/pause")  # again: nothing changes, no commit
        self.both("/job/oud/resume")
        self.both("/job/oud/save", text=EDITED)
        self.both("/job/oud/save", text="geen kop")  # refused by parse_job in Python
        self.both("/job/nergens/pause")
        s, loc, _ = self.both("/job/nieuw/delete")
        self.assertEqual((s, loc), (303, "/jobs"))
        self.assert_same_branch()
        self.assertIn("README.md", snapshot(self.remotes["new"], self.tmp, "new")[0])

    def test_c_what_the_pages_read_agrees_after_the_writes(self):
        for path in ("/api/jobs-page", "/api/job/oud"):
            ref = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{self.ref_port}{path}").read())
            new = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{self.new_port}{path}").read())
            self.assertEqual(new, ref, path)

    def test_d_the_branch_is_in_step_when_someone_else_pushed_in_between(self):
        """GitHub is the truth: a change made there is part of the next write (Java syncs before it writes)."""
        for k in ("ref", "new"):
            work = self.tmp / f"hand-{k}"
            sh(self.tmp, "git", "clone", "--quiet", "-b", "jobs", str(self.remotes[k]), str(work))
            (work / "jobs/hand.md").write_text(GOOD)
            sh(work, "git", "add", "-A")
            sh(work, "git", "-c", "user.name=hand", "-c", "user.email=h@h", "commit", "--quiet", "-m", "hand")
            sh(work, "git", "push", "--quiet", "origin", "jobs")
        self.both("/job/oud/pause")
        self.assert_same_branch()
        self.assertIn("jobs/hand.md", snapshot(self.remotes["new"], self.tmp, "new")[0])

    # ---- the contract of /internal/jobs/* against the Java base URL ------------------------------------------------
    def test_zz_java_needs_the_secret(self):
        for route in ("sync", "save", "delete", "pause", "resume"):
            self.assertEqual(self.java(f"/internal/jobs/{route}", {}, secret=None)[0], 403, route)
            self.assertEqual(self.java(f"/internal/jobs/{route}", {}, secret="wrong")[0], 403, route)

    def test_zz_java_answers_the_shapes_the_python_inbox_expects(self):
        self.assertEqual(self.java("/internal/jobs/sync", {}), (200, {"ok": True, "owned": True}))
        self.assertEqual(self.java("/internal/jobs/save", {"name": "contract", "text": GOOD, "message": "jobs: contract nieuw (t)"}),
                         (200, {"saved": True}))
        self.assertEqual(self.java("/internal/jobs/save", {"name": "contract", "text": GOOD, "message": "again"}),
                         (200, {"saved": False}))
        s, body = self.java("/internal/jobs/save", {"name": "contract", "text": "geen kop", "message": "m"})
        self.assertEqual(s, 400)
        self.assertIn("error", body)
        self.assertEqual(self.java("/internal/jobs/save", {"name": "Bad Name", "text": GOOD, "message": "m"})[0], 400)
        self.assertEqual(self.java("/internal/jobs/save", {"name": "../x", "text": GOOD, "message": "m"})[0], 400)
        self.assertEqual(self.java("/internal/jobs/save", {"name": "contract", "text": GOOD})[0], 400)  # no message
        self.assertEqual(self.java("/internal/jobs/pause", {"name": "contract", "message": "p"}), (200, {"saved": True}))
        self.assertEqual(self.java("/internal/jobs/pause", {"name": "nergens", "message": "p"})[0], 404)
        self.assertEqual(self.java("/internal/jobs/resume", {"name": "contract", "message": "r"}), (200, {"saved": True}))
        self.assertEqual(self.java("/internal/jobs/delete", {"name": "contract", "message": "d"}), (200, {"saved": True}))
        self.assertEqual(self.java("/internal/jobs/delete", {"name": "contract", "message": "d"}), (200, {"saved": False}))
        self.assertEqual(self.java("/internal/jobs/nergens", {})[0], 404)

    def test_zzz_export_gives_the_store_back_and_import_takes_it_again(self):
        """The rollback: with the service stopped, export-jobs removes the marker (after checking GitHub has everything)."""
        self.java_proc.terminate()
        self.java_proc.wait(timeout=30)
        out = subprocess.run([JAVA, "-jar", JAR, "export-jobs"], env=self.java_env, capture_output=True, text=True)
        self.assertEqual(out.returncode, 0, out.stdout + out.stderr)
        self.assertIn("export ok", out.stdout)
        self.assertFalse((self.homes["new"] / "jobs-repo/.git/inbox-jobs-owner").exists())
        again = subprocess.run([JAVA, "-jar", JAR, "import-jobs"], env=self.java_env, capture_output=True, text=True)
        self.assertEqual(again.returncode, 0, again.stdout + again.stderr)
        self.assertTrue((self.homes["new"] / "jobs-repo/.git/inbox-jobs-owner").is_file())


if __name__ == "__main__":
    unittest.main()
