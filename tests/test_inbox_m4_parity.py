"""H-024 M4: the conversation store moved to the Java backend. Two Python inboxes see the same requests: the reference
one keeps the store in-process the old way (tests/conversation_fake.py, the code the Python inbox had), the other is the
real thing, its chat door in front of the Java backend and a throwaway PostgreSQL (the jar imports an empty SQLite
first, as scripts/27-backend.sh does). Every request goes to both, with the tokens and the permissions of
config/clients.json, and the answers must be the same (status and body; times differ by nature). Then the same checks
run against the Java base URL directly, with the client in X-Inbox-Client and the facade secret, which is how the Python
door talks to it. Skipped without java, a built jar (cd backend && ./mvnw -DskipTests package, or BACKEND_JAR) or
PostgreSQL binaries. Run: python3 -m unittest tests.test_inbox_m4_parity"""
import json
import os
import re
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
JARS = sorted((ROOT / "backend" / "target").glob("inbox-backend-*.jar")) if (ROOT / "backend" / "target").is_dir() else []
JAR = Path(os.environ.get("BACKEND_JAR") or (JARS[0] if JARS else ""))
JAVA = shutil.which("java")
PG_BINS = sorted(Path("/usr/lib/postgresql").glob("*/bin")) if Path("/usr/lib/postgresql").is_dir() else []
PG_BIN = Path(os.environ["PG_BIN"]) if os.environ.get("PG_BIN") else (PG_BINS[-1] if PG_BINS else None)
SECRET = "m4-parity-secret"
CLIENTS = json.loads((ROOT / "config" / "clients.json").read_text())["clients"]
TOKENS = {"discord": "tok-discord-m4", "jarvis": "tok-jarvis-m4"}  # kitt has no token: it comes in by its own door
FAKE_TOKEN = "ghp_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4"  # a planted fake, never a real one
SERVE = """
import sys
from http.server import ThreadingHTTPServer
sys.path.insert(0, {bin!r}); sys.path.insert(0, {tests!r})
from homelab_inbox import conversation, server
if sys.argv[2] == "reference":
    import conversation_fake
    conversation_fake.install(conversation)
ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), server.Handler).serve_forever()
""".format(bin=str(ROOT / "bin"), tests=str(ROOT / "tests"))


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_for(url, headers=None, seconds=90):
    end = time.time() + seconds
    while time.time() < end:
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=headers or {}), timeout=3):
                return
        except urllib.error.HTTPError:
            return  # it answered
        except OSError:
            time.sleep(0.5)
    raise RuntimeError(f"{url} did not come up")


def call(base, method, path, headers=None, body=None):
    """(status, parsed JSON) with the time stripped: the one thing that differs by nature."""
    data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
    req = urllib.request.Request(base + path, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            status, raw = r.status, r.read()
    except urllib.error.HTTPError as e:
        status, raw = e.code, e.read()
    try:
        parsed = json.loads(raw)
    except ValueError:
        parsed = raw.decode()
    return status, strip_time(parsed)


def strip_time(v):
    if isinstance(v, dict):
        return {k: ("<time>" if k == "time" else strip_time(x)) for k, x in v.items()}
    if isinstance(v, list):
        return [strip_time(x) for x in v]
    return v


@unittest.skipUnless(JAVA and JAR.is_file() and PG_BIN and (PG_BIN / "initdb").exists(),
                     "needs java, a built backend jar (backend/target/inbox-backend-*.jar) and PostgreSQL binaries")
class M4Parity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp(prefix="m4"))
        cls.procs = []
        pg = cls.tmp / "pg"
        sock = cls.tmp / "sock"
        sock.mkdir()
        subprocess.run([PG_BIN / "initdb", "-D", pg, "-U", "postgres", "--auth=trust", "--locale=C.UTF-8", "-N"],
                       check=True, capture_output=True)
        subprocess.run([PG_BIN / "pg_ctl", "-D", pg, "-w", "-l", cls.tmp / "pg.log", "-o",
                        f"-c listen_addresses='' -c unix_socket_directories='{sock}' -c port=54330 -c fsync=off", "start"],
                       check=True, capture_output=True)
        cls.pg = pg
        subprocess.run([PG_BIN / "createdb", "-h", sock, "-p", "54330", "-U", "postgres", "inbox"], check=True)
        cls.url = ("jdbc:postgresql://localhost/inbox?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg"
                   f"&socketFactoryArg={sock}/.s.PGSQL.54330")
        # The import of the switch: an (empty) inbox.db of the Python inbox, then the marker is there.
        cls.home = cls.tmp / "home"
        cls.home.mkdir()
        (cls.home / "inbox.db").touch()
        done = subprocess.run([JAVA, "-jar", JAR, "import-conversation", "--sqlite", cls.home / "inbox.db", "--url", cls.url,
                               "--user", "postgres", "--if-not-imported"], capture_output=True, text=True)
        assert done.returncode == 0, done.stdout + done.stderr
        cls.tokens = cls.tmp / "tokens"
        cls.tokens.mkdir()
        for name, token in TOKENS.items():
            (cls.tokens / f"chat-{name}-token").write_text(token + "\n")
        (cls.tmp / "secret").write_text(SECRET)
        cls.java_port, cls.ref_port, cls.new_port = free_port(), free_port(), free_port()
        env = {**os.environ, "NTFY_FAKE": "1", "INBOX_ALLOW_ALL": "1", "BACKEND_ROUTES": "", "CHAT_TOKEN_DIR": str(cls.tokens),
               "REPOS_CONF": str(ROOT / "config/repos.conf")}
        env.pop("CLIENTS_JSON", None)
        cls.procs.append(subprocess.Popen(
            [JAVA, "-Xmx256m", "-jar", JAR], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
            env={**env, "BACKEND_PORT": str(cls.java_port), "BACKEND_SECRET": SECRET, "INBOX_CONFIG_DIR": str(ROOT / "config"),
                 "SPRING_DATASOURCE_URL": cls.url, "SPRING_DATASOURCE_USERNAME": "postgres"}))
        for port, which, home, extra in ((cls.ref_port, "reference", cls.tmp / "home-ref", {}),
                                         (cls.new_port, "java", cls.tmp / "home-new",
                                          {"BACKEND_URL": f"http://127.0.0.1:{cls.java_port}", "BACKEND_SECRET": SECRET})):
            home.mkdir()
            cls.procs.append(subprocess.Popen([sys.executable, "-I", "-c", SERVE, str(port), which],
                                              env={**env, "INBOX_RUNNER_HOME": str(home), **extra},
                                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
        wait_for(f"http://127.0.0.1:{cls.java_port}/api/backend/health")
        wait_for(f"http://127.0.0.1:{cls.ref_port}/api/session")
        wait_for(f"http://127.0.0.1:{cls.new_port}/api/session")

    @classmethod
    def tearDownClass(cls):
        for p in cls.procs:
            p.terminate()
        for p in cls.procs:
            p.wait(timeout=30)
        subprocess.run([PG_BIN / "pg_ctl", "-D", cls.pg, "-m", "immediate", "-w", "stop"], capture_output=True)
        shutil.rmtree(cls.tmp, ignore_errors=True)

    # ---- helpers ----------------------------------------------------------------------------------------------------
    def both(self, method, path, token=None, body=None, loose=False):
        """The same request to the reference inbox and to the one in front of Java; the answers must be equal.
        `loose`: only the status and the start of the error (Python's own JSON parser words its complaint differently)."""
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        if body is not None:
            headers["Content-Type"] = "application/json"
        ref = call(f"http://127.0.0.1:{self.ref_port}", method, path, headers, body)
        new = call(f"http://127.0.0.1:{self.new_port}", method, path, headers, body)
        if loose:
            self.assertEqual(new[0], ref[0])
            self.assertTrue(new[1]["error"].startswith("bad request:") and ref[1]["error"].startswith("bad request:"))
        else:
            self.assertEqual(new, ref, f"{method} {path} as {token}")
        return new

    def java(self, method, path, client=None, body=None, secret=SECRET):
        headers = {"X-Inbox-Facade": secret} if secret else {}
        if client:
            headers["X-Inbox-Client"] = client
        return call(f"http://127.0.0.1:{self.java_port}", method, path, headers, body)

    def switch(self, name, on):
        """The homelab page's switch form on both inboxes (what Chris presses), CSRF and all."""
        for port in (self.ref_port, self.new_port):
            base = f"http://127.0.0.1:{port}"
            csrf = json.loads(urllib.request.urlopen(base + "/api/session").read())["csrf_token"]
            req = urllib.request.Request(base + f"/discord/{name}/{'on' if on else 'off'}", method="POST",
                                         data=urllib.parse.urlencode({"csrf": csrf}).encode(),
                                         headers={"Origin": base, "Content-Type": "application/x-www-form-urlencoded"})
            opener = urllib.request.build_opener(type("NoRedirect", (urllib.request.HTTPRedirectHandler,), {
                "redirect_request": lambda *a, **k: None}))
            try:
                opener.open(req, timeout=20)
            except urllib.error.HTTPError as e:
                self.assertIn(e.code, (301, 302, 303), e.read())

    # ---- the parity -------------------------------------------------------------------------------------------------
    def test_a_script_of_requests_gets_the_same_answers(self):
        d, j = TOKENS["discord"], TOKENS["jarvis"]
        s, body = self.both("GET", "/api/chat/state", d)
        self.assertEqual((s, body), (200, {"discord": True, "discord_approvals": True}))
        self.both("GET", "/api/chat/entries", d)  # empty
        self.both("POST", "/api/chat/entries", d, {"text": "hallo"})
        self.both("POST", "/api/chat/entries", d, {"kind": "question", "text": "wat?", "ref": "task:1"})
        self.both("POST", "/api/chat/entries", d, {"kind": "answer", "text": "ja"})
        self.both("POST", "/api/chat/entries", j, {"text": "van jarvis"})
        self.both("POST", "/api/chat/entries", j, {"kind": "question", "text": "mag niet"})  # jarvis may not ask? it may: see config
        self.both("POST", "/api/chat/entries", j, {"kind": "answer", "text": "mag niet"})
        self.both("POST", "/api/chat/entries", d, {"kind": "notification", "text": "nooit"})
        self.both("POST", "/api/chat/entries", d, {"kind": "approval", "text": "nooit"})
        self.both("POST", "/api/chat/entries", d, {"kind": "bogus", "text": "nooit"})
        self.both("POST", "/api/chat/entries", d, {"text": f"token {FAKE_TOKEN} en password: hunter2hunter2"})
        self.both("POST", "/api/chat/entries", d, {"text": "ü ✓ é"})
        self.both("POST", "/api/chat/entries", d, {"text": "x" * 25000})
        self.both("POST", "/api/chat/entries", d, {"text": "r", "ref": "r" * 100})
        self.both("POST", "/api/chat/entries", d, {"text": "   "})
        self.both("POST", "/api/chat/entries", d, {"kind": "message"})
        self.both("POST", "/api/chat/entries", d, b"not json", loose=True)  # the wording of a JSON parse error differs
        self.both("POST", "/api/chat/entries", d, [1, 2], loose=True)
        for query in ("", "?after=0", "?after=2", "?after=2&limit=1", "?limit=0", "?limit=5000", "?after=99", "?after=-3",
                      "?after=abc", "?limit=x", "?after=&limit=", "?after=1&after=2"):
            self.both("GET", "/api/chat/entries" + query, d)
            self.both("GET", "/api/chat/entries" + query, j)
        self.both("GET", "/api/chat/state", j)

    def test_the_token_is_still_checked_in_python(self):
        for token in (None, "wrong", "Bearer", ""):
            self.assertEqual(self.both("GET", "/api/chat/state", token)[0], 401)
            self.assertEqual(self.both("GET", "/api/chat/entries", token)[0], 401)
            self.assertEqual(self.both("POST", "/api/chat/entries", token, {"text": "x"})[0], 401)

    def test_switches_close_the_door_the_same_way(self):
        d = TOKENS["discord"]
        try:
            self.switch("discord_approvals", False)
            self.assertEqual(self.both("GET", "/api/chat/state", d)[1], {"discord": True, "discord_approvals": False})
            self.switch("discord", False)
            s, body = self.both("GET", "/api/chat/entries", d)
            self.assertEqual((s, body["error"]), (403, "refused: Discord is switched off on the homelab page"))
            self.assertEqual(self.both("POST", "/api/chat/entries", d, {"text": "x"})[0], 403)
            self.assertEqual(self.both("GET", "/api/chat/state", d)[1], {"discord": False, "discord_approvals": False})
            self.assertEqual(self.both("GET", "/api/chat/entries", TOKENS["jarvis"])[0], 200)  # jarvis has no switch
        finally:
            self.switch("discord", True)
            self.switch("discord_approvals", True)

    def test_the_action_route_is_still_python(self):
        """/api/chat/action stays in Python until K7: both inboxes answer it themselves, the same."""
        self.both("GET", "/api/chat/action", TOKENS["discord"])
        self.both("POST", "/api/chat/action", TOKENS["discord"], {"action": "ssh_key"})
        self.both("POST", "/api/chat/action", TOKENS["discord"], {"action": "nope"})
        self.both("POST", "/api/chat/action", TOKENS["jarvis"], {"action": "state"})
        self.both("POST", "/api/chat/action", None, {"action": "state"})

    def test_the_refusal_log_matches(self):
        """Refusals are written by whoever owns the store: the same ones in the same order on both sides."""
        self.both("GET", "/api/chat/entries", TOKENS["jarvis"])
        self.both("POST", "/api/chat/entries", TOKENS["jarvis"], {"kind": "answer", "text": "x"})
        self.both("POST", "/api/chat/action", TOKENS["jarvis"], {"action": "task", "repo": "homelab", "text": "x"})
        _, java = self.java("GET", "/internal/conversation/refusals?limit=1000")
        self.assertTrue(java["refusals"])
        self.assertIn("no permission 'answer'", [r["why"] for r in java["refusals"]])

    # ---- the contract tests against the Java base URL ---------------------------------------------------------------
    # They write into the Java store only, so they are named to run after every comparison above (alphabetical order).
    def test_zz_java_directly_needs_the_secret_and_a_client(self):
        self.assertEqual(self.java("GET", "/api/chat/state", "discord", secret=None)[0], 403)
        self.assertEqual(self.java("GET", "/api/chat/state", "discord", secret="wrong")[0], 403)
        self.assertEqual(self.java("GET", "/api/chat/entries")[0], 401)
        self.assertEqual(self.java("POST", "/internal/conversation/append", None,
                                   {"author": "box", "client": "x", "kind": "notification", "text": "t"}, secret=None)[0], 403)

    def test_zz_java_applies_the_permissions_of_each_client_in_clients_json(self):
        all_perms = {"read", "write", "ask", "answer"}
        for name, entry in CLIENTS.items():
            have = set(entry["permissions"])
            s, _ = self.java("GET", "/api/chat/entries", name)
            self.assertEqual(s, 200 if "read" in have else 403, f"{name} read")
            for kind, perm in (("message", "write"), ("question", "ask"), ("answer", "answer")):
                s, body = self.java("POST", "/api/chat/entries", name, {"kind": kind, "text": f"contract {name} {kind}"})
                self.assertEqual(s, 200 if perm in have else 403, f"{name} {kind}: {body}")
                if s == 200:
                    self.assertEqual((body["entry"]["author"], body["entry"]["client"], body["entry"]["kind"]),
                                     ("chris", name, kind))
                else:
                    self.assertEqual(body, {"error": f"refused: {name} may not write '{kind}'"})
            for kind in ("notification", "approval_request", "approval"):  # no client writes these, whatever it may
                self.assertEqual(self.java("POST", "/api/chat/entries", name, {"kind": kind, "text": "x"})[0], 403)
            for action in ("ssh_key", "memory_edit", "merge_homelab"):
                self.assertEqual(self.java("POST", "/internal/conversation/allowed", None,
                                           {"client": name, "action": action})[1], {"allowed": False})
            for perm in sorted(all_perms | {"start_task", "approve", "run_job", "research"}):
                self.assertEqual(self.java("POST", "/internal/conversation/allowed", None,
                                           {"client": name, "action": perm})[1], {"allowed": perm in have}, f"{name} {perm}")
        self.assertEqual(self.java("GET", "/api/chat/entries", "nobody")[1], {"error": "refused: no permission 'read'"})

    def test_zz_java_answers_503_for_nothing_it_can_do_and_keeps_the_paths(self):
        self.assertEqual(self.java("GET", "/api/chat/action", "discord")[0], 404)  # not moved: Python's until K7
        self.assertEqual(self.java("POST", "/api/chat/action", "discord", {"action": "state"})[0], 404)


if __name__ == "__main__":
    unittest.main()
