"""H-024 M1: the Python inbox and the Java backend answer the moved read routes (`/api/overview`, `/api/live`,
`/api/log/*`) the same, on the same state (tests/m1_fixture.py). Uses the parity harness of H-023 B7 and the contract
tests of H-020 against the Java base URL. Skipped without a built jar: `cd backend && ./mvnw -DskipTests package`, or set
BACKEND_JAR. Run: python3 -m unittest tests.test_inbox_m1_parity"""
import importlib.util
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import unittest
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "tests"))
import m1_fixture  # noqa: E402

JARS = sorted((ROOT / "backend" / "target").glob("inbox-backend-*.jar")) if (ROOT / "backend" / "target").is_dir() else []
JAR = Path(os.environ.get("BACKEND_JAR") or (JARS[0] if JARS else ""))
JAVA = shutil.which("java")
SECRET = "m1-parity-secret"
SERVE_PYTHON = f"""
import sys
from http.server import ThreadingHTTPServer
sys.path.insert(0, {str(ROOT / 'bin')!r})
from homelab_inbox import jobs, server
jobs.load_jobs(fetch=False)  # the job files of the fixture, as the scheduler loads them on the box
srv = ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1])), server.Handler)
srv.serve_forever()
"""


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_for(url, headers, seconds=90):
    end = time.time() + seconds
    while time.time() < end:
        try:
            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=3):
                return
        except OSError:
            time.sleep(0.5)
    raise RuntimeError(f"{url} did not come up")


def load_parity():
    spec = importlib.util.spec_from_file_location("parity", ROOT / "backend" / "parity" / "parity.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@unittest.skipUnless(JAVA and JAR.is_file(), "needs java and a built backend jar (backend/target/inbox-backend-*.jar)")
class M1Parity(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = Path(tempfile.mkdtemp())
        cls.up = m1_fixture.start_upstream()
        base = f"http://127.0.0.1:{cls.up.server_address[1]}"
        env = {**os.environ, **m1_fixture.build(cls.tmp, base, base), "NTFY_FAKE": "1", "INBOX_ALLOW_ALL": "1",
               "BACKEND_ROUTES": ""}  # the Python side must answer the moved routes itself
        cls.python_port, cls.java_port = free_port(), free_port()
        cls.procs = [
            subprocess.Popen([sys.executable, "-I", "-c", SERVE_PYTHON, str(cls.python_port)], env=env,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL),
            subprocess.Popen([JAVA, "-Xmx256m", "-jar", str(JAR)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                             env={**env, "BACKEND_PORT": str(cls.java_port), "BACKEND_SECRET": SECRET,
                                  "INBOX_CONFIG_DIR": str(ROOT / "config")})]
        wait_for(f"http://127.0.0.1:{cls.python_port}/api/live", {})
        wait_for(f"http://127.0.0.1:{cls.java_port}/api/backend/health", {})

    @classmethod
    def tearDownClass(cls):
        for p in cls.procs:
            p.terminate()
        for p in cls.procs:
            p.wait(timeout=20)
        cls.up.shutdown()
        shutil.rmtree(cls.tmp, ignore_errors=True)

    def compare(self, paths):
        parity = load_parity()
        config = json.loads((ROOT / "backend" / "parity" / "parity.json").read_text())
        out = []
        counts = parity.run([(p, None) for p in paths], config,
                            lambda p: parity.fetch(f"http://127.0.0.1:{self.python_port}", p, {}),
                            lambda p: parity.fetch(f"http://127.0.0.1:{self.java_port}", p, {"X-Inbox-Facade": SECRET}),
                            out=out.append)
        self.assertEqual(counts["diff"], 0, "\n".join(out))
        self.assertEqual(counts["same"], len(paths), "\n".join(out))

    def test_overview_and_live_are_the_same(self):
        self.compare(["/api/overview", "/api/live"])

    def test_logs_are_the_same(self):
        self.compare(["/api/log/t1", "/api/log/t1?from=3", "/api/log/t1?from=99", "/api/log/t1?from=-2",
                      "/api/log/t1?from=+2", "/api/log/t5", "/api/log/t7", "/api/log/t99", "/api/log/r1",
                      "/api/log/r2", "/api/log/r2?from=500", "/api/log/r99", "/api/log/f1", "/api/log/f1?from=3",
                      "/api/log/t1?from=abc", "/api/log/t", "/api/log/x1", "/api/log/t1/extra"])

    def test_contract_tests_pass_against_java(self):
        env = {**os.environ, "INBOX_CONTRACT_BASE_URL": f"http://127.0.0.1:{self.java_port}",
               "INBOX_CONTRACT_FACADE_SECRET": SECRET}
        done = subprocess.run([sys.executable, "-m", "unittest", "tests.test_inbox_contract.MovedReads", "-v"], env=env,
                              cwd=ROOT, capture_output=True, text=True)
        self.assertEqual(done.returncode, 0, done.stderr)
        self.assertIn("Ran 6 tests", done.stderr)


if __name__ == "__main__":
    unittest.main()
