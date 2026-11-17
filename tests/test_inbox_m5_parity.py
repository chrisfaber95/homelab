"""H-024 M5: the memory writes moved to the Java backend. Two Python inboxes see the same requests: the reference one writes
the old way (tests/memory_fake.py, the code the Python inbox had, against its own copy of the memory branch), the other is
the real thing, in front of the Java backend, which owns the clone after `import-memory` (a throwaway PostgreSQL is only
there because the backend starts its conversation store). Every POST of the Geheugen page goes to both, with the CSRF token
and the owner check of the Python server, and the answers must be the same; then the two branches must hold the same
files and the same commits (author and message; the timestamps inside the files differ by nature). Then the routes the queue
uses (learned, history) go to both stores directly, and the same checks run against the Java base URL with the facade
secret: that is the contract of /internal/memory/*. Skipped without java and a built jar (cd backend && ./mvnw -DskipTests
package, or BACKEND_JAR) and PostgreSQL binaries. Run: python3 -m unittest tests.test_inbox_m5_parity"""
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import types
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
SECRET = "m5-parity-secret"
FAKE_TOKEN = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4"  # a planted fake, never a real one
SEEDED = "2026-11-01T10:00:00+00:00"
SERVE = """
import os, sys
from http.server import ThreadingHTTPServer
sys.path.insert(0, {bin!r}); sys.path.insert(0, {tests!r})
from homelab_inbox import helpers, memory, server
if sys.argv[2] == "reference":
    import memory_fake
    memory_fake.install(memory, helpers.repo_list, remote=os.environ["REMOTE_HOMELAB_DATA"])
memory.load_memory()
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
    """A bare repository with the branch `memory` and one entry, as the Python inbox left it before the switch."""
    sh(tmp, "git", "init", "--bare", "--quiet", "-b", "memory", str(path))
    work = tmp / f"seed-{path.name}"
    sh(tmp, "git", "clone", "--quiet", str(path), str(work))
    sh(work, "git", "checkout", "--quiet", "-B", "memory")
    (work / "feiten/box").mkdir(parents=True)
    (work / "feiten/box/oud.md").write_text(f'---\nover: "box"\ntoegevoegd: "{SEEDED}"\ngewijzigd: "{SEEDED}"\nbron: "inbox 5"\n'
                                            f'link: "http://x/task/5"\n---\nOud feit\n')
    (work / "geschiedenis/2026-11").mkdir(parents=True)
    (work / "geschiedenis/2026-11/taak-5.md").write_text(f'---\nover: "homelab"\ntoegevoegd: "{SEEDED}"\ngewijzigd: "{SEEDED}"\n'
                                                         f'bron: "Inbox 5"\nstatus: "gemerged"\n---\n# Taak\n\nGevraagd:\nx\n')
    sh(work, "git", "add", "-A")
    sh(work, "git", "-c", "user.name=seed", "-c", "user.email=s@s", "commit", "--quiet", "-m", "seed")
    sh(work, "git", "push", "--quiet", "origin", "memory")


def snapshot(remote, tmp, name):
    """The files and the commits of the branch, with the times that differ by nature replaced."""
    check = tmp / f"snap-{name}"
    shutil.rmtree(check, ignore_errors=True)
    sh(tmp, "git", "clone", "--quiet", "-b", "memory", str(remote), str(check))
    files = {}
    for f in sorted(check.rglob("*.md")):
        text = f.read_text()
        text = re.sub(r'^gewijzigd: ".*"$', 'gewijzigd: "<now>"', text, flags=re.M)
        text = re.sub(rf'^toegevoegd: "(?!{re.escape(SEEDED)}).*"$', 'toegevoegd: "<now>"', text, flags=re.M)
        files[f.relative_to(check).as_posix()] = text
    log = sh(check, "git", "log", "--format=%an|%ae|%s").splitlines()
    return files, log


@unittest.skipUnless(JAVA and JAR.is_file() and PG_BIN and (PG_BIN / "initdb").exists(),
                     "needs java, a built backend jar (backend/target/inbox-backend-*.jar) and PostgreSQL binaries")
class M5Parity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="m5"))
        cls.procs = []
        pg, sock = cls.tmp / "pg", cls.tmp / "sock"
        sock.mkdir()
        subprocess.run([PG_BIN / "initdb", "-D", pg, "-U", "postgres", "--auth=trust", "--locale=C.UTF-8", "-N"],
                       check=True, capture_output=True)
        subprocess.run([PG_BIN / "pg_ctl", "-D", pg, "-w", "-l", cls.tmp / "pg.log", "-o",
                        f"-c listen_addresses='' -c unix_socket_directories='{sock}' -c port=54350 -c fsync=off", "start"],
                       check=True, capture_output=True)
        cls.pg = pg
        subprocess.run([PG_BIN / "createdb", "-h", sock, "-p", "54350", "-U", "postgres", "inbox"], check=True)
        url = ("jdbc:postgresql://localhost/inbox?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg"
               f"&socketFactoryArg={sock}/.s.PGSQL.54350")
        homes = {k: cls.tmp / f"home-{k}" for k in ("ref", "new")}
        cls.remotes = {k: cls.tmp / f"remote-{k}.git" for k in homes}
        for k in homes:
            homes[k].mkdir()
            seed_remote(cls.remotes[k], cls.tmp)
        (homes["new"] / "inbox.db").touch()
        sh(cls.tmp, str(JAVA), "-jar", str(JAR), "import-conversation", "--sqlite", str(homes["new"] / "inbox.db"), "--url", url,
           "--user", "postgres", "--if-not-imported")
        env = {**os.environ, "NTFY_FAKE": "1", "INBOX_ALLOW_ALL": "1", "BACKEND_ROUTES": "", "REPOS_CONF": str(ROOT / "config/repos.conf"),
               "INBOX_CONFIG_DIR": str(ROOT / "config")}
        env.pop("CLIENTS_JSON", None)
        java_env = {**env, "INBOX_RUNNER_HOME": str(homes["new"]), "REMOTE_HOMELAB_DATA": str(cls.remotes["new"])}
        # The fresh import of the switch: the clone is brought to the branch and the marker makes Java its only writer.
        done = subprocess.run([JAVA, "-jar", JAR, "import-memory"], env=java_env, capture_output=True, text=True)
        assert done.returncode == 0, done.stdout + done.stderr
        cls.import_output = done.stdout
        cls.java_port, cls.ref_port, cls.new_port = free_port(), free_port(), free_port()
        cls.procs.append(subprocess.Popen(
            [JAVA, "-Xmx256m", "-jar", JAR], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            env={**java_env, "BACKEND_PORT": str(cls.java_port), "BACKEND_SECRET": SECRET, "SPRING_DATASOURCE_URL": url,
                 "SPRING_DATASOURCE_USERNAME": "postgres"}))
        wait_for(f"http://127.0.0.1:{cls.java_port}/api/backend/health")
        for port, which, key, extra in ((cls.ref_port, "reference", "ref", {}),
                                        (cls.new_port, "java", "new", {"BACKEND_URL": f"http://127.0.0.1:{cls.java_port}",
                                                                       "BACKEND_SECRET": SECRET})):
            cls.procs.append(subprocess.Popen([sys.executable, "-I", "-c", SERVE, str(port), which],
                                              env={**env, "INBOX_RUNNER_HOME": str(homes[key]),
                                                   "REMOTE_HOMELAB_DATA": str(cls.remotes[key]), **extra},
                                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
            wait_for(f"http://127.0.0.1:{port}/api/session")
        # the reference store of the direct routes: the old code on a shim of the reference inbox's clone
        import memory_fake
        sys.path.insert(0, str(ROOT / "bin"))
        os.environ.setdefault("INBOX_RUNNER_HOME", str(cls.tmp / "shim-home"))
        os.environ.setdefault("BACKEND_ROUTES", "")
        os.environ.setdefault("REPOS_CONF", str(ROOT / "config/repos.conf"))
        from homelab_inbox import helpers, memory
        ref_clone = homes["ref"] / "memory-repo"

        def mem_get(mid):
            f = ref_clone / f"{mid}.md"
            return memory.parse_entry(mid, f.read_text()) if f.exists() else None
        cls.shim = types.SimpleNamespace(MEMORY_DIR=ref_clone, MEM_ID_RE=memory.MEM_ID_RE, mem_get=mem_get)
        cls.reference = memory_fake.FakeBackend(cls.shim, helpers.repo_list, remote=str(cls.remotes["ref"]))

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
        """The Geheugen page's form post with CSRF: (status, Location or None, parsed body)."""
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
        self.assertIn("import ok: memory entries=2", self.import_output)
        self.assertTrue((self.tmp / "home-new/memory-repo/.git/inbox-memory-owner").is_file())
        self.assert_same_branch()

    def test_b_the_page_posts_get_the_same_answers_and_leave_the_same_branch(self):
        s, loc, _ = self.both("/memory/add", kind="feiten", about="kitt", name=" nieuw ", text="Een \"feit\"\r\nregel 2 é")
        self.assertEqual((s, loc), (303, "/memory/e/feiten/kitt/nieuw"))
        self.both("/memory/add", kind="voorkeuren", about="algemeen", name="kort", text="Houd het kort.")
        self.both("/memory/add", kind="feiten", about="kitt", name="nieuw", text="dubbel")  # exists
        self.both("/memory/add", kind="nope", about="algemeen", name="x", text="t")
        self.both("/memory/add", kind="feiten", about="onbekend", name="x", text="t")
        self.both("/memory/add", kind="feiten", about="box", name="Hoofd", text="t")
        self.both("/memory/add", kind="feiten", about="box", name="leeg", text="   ")
        self.both("/memory/add", kind="feiten", about="box", name="lang", text="a" * 4001)
        self.both("/memory/add", kind="feiten", about="box", name="geheim", text=f"token {FAKE_TOKEN}")
        self.both("/memory/e/feiten/kitt/nieuw/save", text="Aangepast\r\nmet twee regels")
        self.both("/memory/e/feiten/box/oud/save", text="Bewerkt")  # a seeded entry: source gets ", bewerkt door jou"
        self.both("/memory/e/geschiedenis/2026-11/taak-5/save", text="Geschiedenis bewerkt")  # keeps status
        self.both("/memory/e/feiten/box/oud/save", text="")
        self.both("/memory/e/feiten/box/oud/save", text=f"password: {'x' * 12}")
        self.both("/memory/e/feiten/box/nergens/save", text="t")
        self.both("/memory/e/feiten/box/nergens/delete")
        self.both("/memory/e/nonsense/delete")
        s, loc, _ = self.both("/memory/e/voorkeuren/algemeen/kort/delete")
        self.assertEqual((s, loc), (303, "/memory"))
        self.assert_same_branch()

    def test_c_the_reads_agree_after_the_writes(self):
        for path in ("/api/memory", "/api/memory?q=nieuw", "/api/memory/e/feiten/kitt/nieuw", "/api/memory/e/feiten/box/oud"):
            ref = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{self.ref_port}{path}").read())
            new = json.loads(urllib.request.urlopen(f"http://127.0.0.1:{self.new_port}{path}").read())
            for d in (ref, new):  # times inside the entries differ by nature
                for section in d.get("sections", [d]):
                    for e in section.get("entries", [section]):
                        for k in ("changed", "changed_local", "added", "added_local"):
                            e.pop(k, None)
            for d in (ref, new):
                d.pop("data_repo", None)
                d.pop("branch", None)
            self.assertEqual(new, ref, path)

    def test_d_what_the_queue_sends_is_stored_the_same(self):
        learned = {"blocks": [["voorkeur", "algemeen", "kort-2", "Houd het kort."], ["feit", "box", "oud", "Bijgewerkt feit"],
                              ["feit", "nergens", "x", "t"], ["feit", "box", "leeg", ""], ["feit", "box", "lang", "a" * 1501],
                              ["feit", "box", "geheim", "password: hunter2hunter2"]],
                   "source": "inbox 7", "link": "http://x/task/7", "label_lower": "taak 7"}
        history = {"id": 9, "created": "2026-11-17T08:00:00+00:00", "repo": "homelab", "label": "Inbox 9", "label_lower": "inbox 9",
                   "link": "http://x/task/9", "status_nl": "gemerged", "pr_url": "https://github.com/x/y/pull/1", "job": "",
                   "title": "Titel", "asked": f"Vraag met {FAKE_TOKEN}", "result": "Gedaan ü", "remembered": False}
        for route, body in (("learned", learned), ("history", history), ("history", {**history, "remembered": True}),
                            ("history", {**history, "id": 10, "remembered": True})):
            ref = self.reference.handle(f"/internal/memory/{route}", body)
            new = self.java(f"/internal/memory/{route}", body)
            self.assertEqual((new[0], new[1]), (ref[0], json.loads(ref[1])), route)
        self.assert_same_branch()

    def test_e_the_branch_is_in_step_when_someone_else_pushed_in_between(self):
        """GitHub is the truth: a change made there is part of the next write (Java syncs before it writes)."""
        for k in ("ref", "new"):
            work = self.tmp / f"hand-{k}"
            sh(self.tmp, "git", "clone", "--quiet", "-b", "memory", str(self.remotes[k]), str(work))
            (work / "feiten/box/hand.md").write_text('---\nover: "box"\n---\nvan de hand\n')
            sh(work, "git", "add", "-A")
            sh(work, "git", "-c", "user.name=hand", "-c", "user.email=h@h", "commit", "--quiet", "-m", "hand")
            sh(work, "git", "push", "--quiet", "origin", "memory")
        self.both("/memory/add", kind="feiten", about="box", name="daarna", text="na de hand")
        self.assert_same_branch()
        self.assertIn("feiten/box/hand.md", snapshot(self.remotes["new"], self.tmp, "new")[0])

    # ---- the contract of /internal/memory/* against the Java base URL ----------------------------------------------
    def test_zz_java_needs_the_secret(self):
        for route in ("sync", "add", "save", "delete", "learned", "history"):
            self.assertEqual(self.java(f"/internal/memory/{route}", {}, secret=None)[0], 403, route)
            self.assertEqual(self.java(f"/internal/memory/{route}", {}, secret="wrong")[0], 403, route)

    def test_zz_java_answers_the_shapes_the_python_inbox_expects(self):
        self.assertEqual(self.java("/internal/memory/sync", {}), (200, {"ok": True, "owned": True}))
        s, body = self.java("/internal/memory/add", {"form": {"kind": "feiten", "about": "box", "name": "contract", "text": "t"},
                                                      "who": "w", "author": ["a", "a@a"]})
        self.assertEqual((s, body), (200, {"location": "/memory/e/feiten/box/contract"}))
        self.assertEqual(self.java("/internal/memory/add", {"form": {}, "who": "w", "author": ["a", "a@a"]})[0], 400)
        s, body = self.java("/internal/memory/add", {"form": {}, "who": "w"})  # no author
        self.assertEqual(s, 400)
        self.assertIn("error", body)
        self.assertEqual(self.java("/internal/memory/delete", {"id": "feiten/box/nergens", "who": "w", "author": ["a", "a@a"]}),
                         (404, {"error": "Niet gevonden."}))
        s, body = self.java("/internal/memory/delete", {"id": "feiten/box/contract", "who": "w", "author": ["a", "a@a"]})
        self.assertEqual((s, body), (200, {"location": "/memory"}))
        self.assertEqual(self.java("/internal/memory/learned", {"blocks": [], "source": "s", "link": "l", "label_lower": "t"}),
                         (200, {"notes": []}))
        self.assertEqual(self.java("/internal/memory/nergens", {})[0], 404)


if __name__ == "__main__":
    unittest.main()
