"""Search index (H-012): chunking per source type, secret scrubbing, incremental sync, filters, read-only sources.
A fake embedder (hashed bag of words) stands in for Ollama: no GPU, no network.
Run: python3 -m unittest discover -s tests"""
import json
import os
import shutil
import subprocess
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "search"))
import chunkers  # noqa: E402
import index  # noqa: E402

FAKE_KEY = "sk-ant-api03-FAKEFAKEFAKE1234567890abcdef"
FAKE_PASSWORD = "hunter2-planted-pw"
FAKE_ENV = "ZXCV9876planted"
FAKE_PEM = "MIIEplantedPRIVATEKEYBODY0123456789"
DIMS = 64


class FakeEmbedder:
    """Bag of words hashed into 64 dimensions; counts how many texts it was asked to embed."""

    def __init__(self):
        self.embedded = []

    def _vec(self, text):
        v = [0.0] * DIMS
        for w in text.lower().split():
            v[hash(w.strip(".,:;()[]\"'")) % DIMS] += 1.0
        return index.normalize(v)

    def documents(self, texts):
        self.embedded += texts
        return [self._vec(t) for t in texts]

    def query(self, text):
        return self._vec(text)


def jline(role, text, ts, cwd="/var/lib/inbox/work/9/repo"):
    return json.dumps({"type": role, "timestamp": ts, "cwd": cwd, "message": {"role": role, "content": text}})


class SearchTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp, True)
        t = self.tmp
        (t / "vault/wiki/projects").mkdir(parents=True)
        (t / "vault/raw").mkdir()
        (t / "vault/wiki/projects/alpha.md").write_text(
            "---\nupdated: 2026-11-01\n---\n# Alpha\nIntro about alpha.\n## Decisions\nWe chose sqlite for storage.\n"
            "## Open\nNothing open.\n")
        (t / "vault/raw/secret-note.md").write_text("# Raw\nshould never be indexed\n")
        (t / "product/homelab").mkdir(parents=True)
        (t / "product/homelab/ROADMAP.md").write_text("# Roadmap\nNext: router and search.\n")
        (t / "sessions/proj").mkdir(parents=True)
        (t / "sessions/proj/s1.jsonl").write_text("\n".join([
            jline("user", "Taak voor de repo homelab:\nhow do we deploy the dashboard?", "2026-11-02T10:00:00Z"),
            jline("assistant", "Use homelab-admin service restart.", "2026-11-02T10:00:05Z"),
            jline("user", f"my key is {FAKE_KEY} and the password: {FAKE_PASSWORD}\nMY_API_TOKEN={FAKE_ENV}\n"
                          f"-----BEGIN RSA PRIVATE KEY-----\n{FAKE_PEM}\n-----END RSA PRIVATE KEY-----",
                  "2026-11-02T10:01:00Z"),
            json.dumps({"type": "user", "timestamp": "2026-11-02T10:02:00Z", "message": {"content": [
                {"type": "tool_result", "content": "tool output not indexed"}]}}),
        ]) + "\n")
        (t / "repos/one").mkdir(parents=True)
        (t / "repos/one/README.md").write_text("# One\nA repo readme.\n")
        (t / "repos/one/app.py").write_text("import os\n\n\ndef first():\n    return 1\n\n\ndef second():\n    return 2\n")
        (t / "repos/one/.env").write_text(f"API_TOKEN={FAKE_ENV}\n")
        (t / "data").mkdir()
        subprocess.run(["git", "init", "-q", "-b", "memory", str(t / "data")], check=True)
        (t / "data/feiten").mkdir()
        (t / "data/feiten/box.md").write_text('---\nover: "box"\ngewijzigd: "2026-10-30T10:00:00+00:00"\n---\n'
                                              "# fact\nThe box runs ollama.\n")
        git = ["git", "-C", str(t / "data"), "-c", "user.name=t", "-c", "user.email=t@t"]
        subprocess.run([*git, "add", "-A"], check=True)
        subprocess.run([*git, "commit", "-qm", "m"], check=True)
        conf = json.loads((ROOT / "config" / "search.json").read_text())
        paths = {"second-brain": [str(t / "vault")], "product": [str(t / "product")],
                 "inbox-transcripts": [str(t / "sessions")], "claude-sessions": [str(t / "nonexistent")],
                 "repo-docs": [str(t / "repos/*")], "repo-code": [str(t / "repos/*")], "memory": [str(t / "data")]}
        for s in conf["sources"]:
            s["paths"] = paths[s["name"]]
        conf["sources"][-1]["git_branch"] = "memory"
        conf["sources"][-1]["fetch"] = False
        conf["db"] = str(t / "index.db")
        self.conf, self.emb = conf, FakeEmbedder()
        self.idx = index.Index(conf, self.emb)
        self.addCleanup(self.idx.close)

    def all_rows(self):
        return self.idx.db.execute("SELECT source, type, path, location, date, project, text FROM chunks").fetchall()

    def test_covers_every_source_type_with_metadata(self):
        self.idx.sync(log=lambda *_: None)
        types = {r[1] for r in self.all_rows()}
        self.assertEqual(types, {"wiki", "product", "transcript", "repo-doc", "repo-code", "memory"})
        by = {(r[0], r[2], r[3]): r for r in self.all_rows()}
        wiki = by[("second-brain", "wiki/projects/alpha.md", "Alpha > Decisions")]
        self.assertEqual((wiki[4], wiki[5]), ("2026-11-01", "alpha"))
        self.assertFalse(any("raw" in r[2] for r in self.all_rows()))  # excluded folder
        self.assertEqual(by[("product", "homelab/ROADMAP.md", "Roadmap")][5], "homelab")
        turn = by[("inbox-transcripts", "proj/s1.jsonl", "turn 2 (assistant)")]
        self.assertEqual((turn[4], turn[5]), ("2026-11-02", "homelab"))
        self.assertTrue(any(r[3].startswith("L") and "first" in r[3] for r in self.all_rows() if r[1] == "repo-code"))
        mem = [r for r in self.all_rows() if r[1] == "memory"][0]
        self.assertEqual((mem[2], mem[5], mem[4]), ("feiten/box.md", "box", "2026-10-30"))
        self.assertNotIn("tool output not indexed", " ".join(r[6] for r in self.all_rows()))

    def test_fake_secrets_are_not_stored_or_returned(self):
        self.idx.sync(log=lambda *_: None)
        secrets = (FAKE_KEY, FAKE_PASSWORD, FAKE_ENV, FAKE_PEM)
        for r in self.all_rows():
            for s in secrets:
                self.assertNotIn(s, " ".join(map(str, r)))
        self.idx.close()
        raw = Path(self.conf["db"]).read_bytes()
        for s in secrets:
            self.assertNotIn(s.encode(), raw)
        for s in secrets:  # a query for the secret itself returns nothing that contains it
            hits = index.Index(self.conf, self.emb).query(s, k=50)
            self.assertFalse([h for h in hits if s in json.dumps(h)])
        self.assertNotIn(FAKE_ENV.encode(), b"".join(  # the .env file is excluded outright
            r[6].encode() for r in index.Index(self.conf, self.emb).db.execute(
                "SELECT source, type, path, location, date, project, text FROM chunks WHERE path LIKE '%.env%'")))
        # nor did the embedder ever receive one
        for text in self.emb.embedded:
            for s in secrets:
                self.assertNotIn(s, text)

    def test_incremental_only_changed_file_is_reindexed(self):
        self.idx.sync(log=lambda *_: None)
        n = len(self.emb.embedded)
        again = self.idx.sync(log=lambda *_: None)
        self.assertEqual((again["files_indexed"], again["files_removed"], len(self.emb.embedded)), (0, 0, n))
        p = self.tmp / "vault/wiki/projects/alpha.md"
        p.write_text(p.read_text().replace("Nothing open.", "One open question about vectors."))
        os.utime(p, (1, 1))  # key changes even on a coarse clock
        log = []
        st = self.idx.sync(log=log.append)
        self.assertEqual(st["files_indexed"], 1)
        self.assertEqual(log, ["indexed second-brain: wiki/projects/alpha.md"])
        self.assertEqual(st["chunks_embedded"], 1)  # unchanged sections reuse their vectors
        self.assertEqual(len(self.emb.embedded), n + 1)
        self.assertIn("vectors", " ".join(r[6] for r in self.all_rows()))
        # touched but identical content: hashed, not re-indexed
        os.utime(p, (2, 2))
        self.assertEqual(self.idx.sync(log=lambda *_: None)["files_indexed"], 0)

    def test_deleted_and_switched_off_sources_leave_the_index(self):
        self.idx.sync(log=lambda *_: None)
        (self.tmp / "repos/one/README.md").unlink()
        st = self.idx.sync(log=lambda *_: None)
        self.assertEqual(st["files_removed"], 1)
        self.assertFalse([r for r in self.all_rows() if r[2].endswith("README.md")])
        for s in self.conf["sources"]:
            if s["name"] == "inbox-transcripts":
                s["enabled"] = False
        st = self.idx.sync(log=lambda *_: None)
        self.assertEqual(st["sources_removed"], 1)
        self.assertNotIn("transcript", {r[1] for r in self.all_rows()})

    def test_filters_by_project_and_type(self):
        self.idx.sync(log=lambda *_: None)
        hits = self.idx.query("deploy dashboard", k=50, type="transcript")
        self.assertTrue(hits and {h["type"] for h in hits} == {"transcript"})
        hits = self.idx.query("sqlite storage", k=50, project="alpha")
        self.assertTrue(hits and {h["project"] for h in hits} == {"alpha"})
        self.assertEqual(hits[0]["location"], "Alpha > Decisions")

    def test_sources_are_never_written(self):
        def snapshot():
            return {str(p): (p.stat().st_mtime_ns, p.read_bytes()) for p in self.tmp.rglob("*")
                    if p.is_file() and "index.db" not in p.name and ".git/" not in str(p)}
        before = snapshot()
        self.idx.sync(log=lambda *_: None)
        self.assertEqual(before, snapshot())

    def test_changed_scrub_patterns_need_a_rebuild(self):
        self.idx.sync(log=lambda *_: None)
        self.conf["scrub"] = self.conf["scrub"][:-1]
        with self.assertRaises(index.FingerprintMismatch):
            index.Index(self.conf, self.emb).sync(log=lambda *_: None)
        index.rebuild(self.conf, self.emb, log=lambda *_: None)
        self.assertGreater(index.Index(self.conf, self.emb).status()["chunks"], 0)

    def test_rebuild_matches_a_fresh_sync(self):
        self.idx.sync(log=lambda *_: None)
        want = sorted(self.all_rows())
        self.idx.close()
        index.rebuild(self.conf, self.emb, log=lambda *_: None)
        self.assertEqual(sorted(index.Index(self.conf, self.emb).db.execute(
            "SELECT source, type, path, location, date, project, text FROM chunks").fetchall()), want)


class ChunkerTest(unittest.TestCase):
    def test_markdown_headings_and_fences(self):
        text = "intro\n# A\nbody\n```\n# not a heading\n```\n## B\nmore\n## Empty\n# C\nlast\n"
        locs = [c["location"] for c in chunkers.chunk_markdown(text, "T")]
        self.assertEqual(locs, ["T", "A", "A > B", "C"])

    def test_long_sections_are_split(self):
        text = "# A\n" + "\n\n".join("word " * 100 for _ in range(20))
        parts = list(chunkers.chunk_markdown(text, "T"))
        self.assertGreater(len(parts), 1)
        self.assertTrue(all(len(p["text"]) <= chunkers.MAX_CHARS + 10 for p in parts))
        self.assertTrue(parts[0]["location"].endswith("(part 1/%d)" % len(parts)))

    def test_code_blocks_follow_functions(self):
        src = "import x\n\n" + "".join(f"def f{i}():\n" + "    pass\n" * 200 + "\n" for i in range(3))
        blocks = list(chunkers.chunk_code(src, ".py"))
        self.assertGreaterEqual(len(blocks), 3)
        self.assertTrue(any(b["location"].endswith("f1") for b in blocks))

    def test_transcript_turns_skip_tools(self):
        raw = "\n".join([jline("user", "hi", "2026-01-01T00:00:00Z"), "not json",
                         json.dumps({"type": "assistant", "message": {"content": [
                             {"type": "tool_use", "name": "Bash"}, {"type": "text", "text": "hello"}]}})])
        self.assertEqual([c["location"] for c in chunkers.chunk_transcript(raw)], ["turn 1 (user)", "turn 2 (assistant)"])


class EmbedderHttpTest(unittest.TestCase):
    def test_client_speaks_ollama_embed(self):
        seen = {}

        class H(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                seen.update(path=self.path, body=body)
                out = json.dumps({"embeddings": [[3.0, 4.0] for _ in body["input"]]}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(out)))
                self.end_headers()
                self.wfile.write(out)

            def log_message(self, *a):
                pass

        srv = HTTPServer(("127.0.0.1", 0), H)
        threading.Thread(target=srv.serve_forever, daemon=True).start()
        self.addCleanup(srv.shutdown)
        e = index.Embedder({"model": "m", "url": f"http://127.0.0.1:{srv.server_port}", "doc_prefix": "d: "})
        vecs = e.documents(["a", "b"])
        self.assertEqual(seen["path"], "/api/embed")
        self.assertEqual(seen["body"]["input"], ["d: a", "d: b"])
        self.assertAlmostEqual(vecs[0][0], 0.6)


if __name__ == "__main__":
    unittest.main()
