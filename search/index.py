"""homelab: the search index (H-012, index side; docs/search.md).

Reads the configured sources (never writes to them), cuts them into chunks (chunkers.py), scrubs secrets from every
chunk (scrub.py), embeds the chunks with a local Ollama model and stores them in SQLite with filters by project and
source type. A sync re-indexes only the files that changed and forgets the ones that are gone.

Only stdlib: there is no numpy or sqlite-vec on the box, so vectors are float32 blobs and a query ranks the
(pre-filtered) rows in Python. Fine up to some tens of thousands of chunks; docs/search.md says what to do beyond that.
"""
import array
import fnmatch
import hashlib
import http.client
import json
import math
import os
import re
import sqlite3
import subprocess
import time
import urllib.request
from pathlib import Path

from chunkers import (CHUNKER_VERSION, chunk_code, chunk_markdown, chunk_transcript, iso_date,
                      transcript_turns, front_matter)
from scrub import Scrubber

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_CONFIG = ROOT / "config" / "search.json"
MAX_FILE_BYTES = 2_000_000


def load_config(path=None):
    path = Path(path or os.environ.get("SEARCH_CONFIG") or DEFAULT_CONFIG)
    conf = json.loads(path.read_text())
    conf["db"] = os.path.expanduser(os.environ.get("SEARCH_DB", conf["db"]))
    if os.environ.get("OLLAMA_URL"):
        conf["embedding"]["url"] = os.environ["OLLAMA_URL"]
    return conf


# --- Embeddings (Ollama, local)

class Embedder:
    def __init__(self, conf):
        self.url = conf["url"].rstrip("/")
        self.model = conf["model"]
        self.batch = conf.get("batch", 16)
        self.max_chars = conf.get("max_chars", 6000)
        self.keep_alive = conf.get("keep_alive", "30s")
        self.timeout = conf.get("timeout", 120)
        self.doc_prefix = conf.get("doc_prefix", "")
        self.query_prefix = conf.get("query_prefix", "")

    def _embed(self, texts):
        body = json.dumps({"model": self.model, "input": texts, "keep_alive": self.keep_alive}).encode()
        req = urllib.request.Request(self.url + "/api/embed", body, {"Content-Type": "application/json"})
        for attempt in range(1, 5):
            try:
                with urllib.request.urlopen(req, timeout=self.timeout) as r:
                    vecs = json.load(r)["embeddings"]
                break
            except (OSError, http.client.HTTPException):
                # Ollama drops a request now and then while it swaps models on the small GPU: try again.
                if attempt == 4:
                    raise
                time.sleep(2 * attempt)
        if len(vecs) != len(texts):
            raise RuntimeError("embedding service returned the wrong number of vectors")
        return [normalize(v) for v in vecs]

    def documents(self, texts):
        out = []
        for i in range(0, len(texts), self.batch):
            out += self._embed([self.doc_prefix + t[:self.max_chars] for t in texts[i:i + self.batch]])
        return out

    def query(self, text):
        return self._embed([self.query_prefix + text[:self.max_chars]])[0]


def normalize(v):
    n = math.sqrt(sum(x * x for x in v)) or 1.0
    return [x / n for x in v]


def pack(vec):
    return array.array("f", vec).tobytes()


def unpack(blob):
    a = array.array("f")
    a.frombytes(blob)
    return a


# --- Sources: what files a source has (read-only)

class Entry:
    """One file of a source: `rel` path, a cheap `key` that changes when the file does, and a reader."""

    def __init__(self, rel, key, read, mtime):
        self.rel, self.key, self.read, self.mtime = rel, key, read, mtime


def roots_of(src):
    out = []
    for pattern in src["paths"]:
        pattern = os.path.expanduser(pattern)
        if any(c in pattern for c in "*?["):
            import glob
            out += sorted(p for p in glob.glob(pattern) if os.path.isdir(p))
        elif os.path.exists(pattern):
            out.append(pattern)
    return out


def _wanted(rel, src):
    parts = rel.split("/")
    for pat in src.get("exclude", []):
        if fnmatch.fnmatch(rel, pat) or any(fnmatch.fnmatch(p, pat) for p in parts):
            return False
    return any(fnmatch.fnmatch(rel, pat) for pat in src["include"])


def list_files(src, root):
    """Entries of one root, in a stable order. Directory sources walk the tree without following symlinks; a
    `git` source reads the files of a branch with git plumbing (ls-tree, cat-file): nothing is checked out."""
    if "git_branch" in src:
        yield from _git_entries(src, root)
        return
    skip_dirs = [p for p in src.get("exclude", []) if "*" not in p and "/" not in p]
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames[:] = sorted(d for d in dirnames if d not in skip_dirs)
        for name in sorted(filenames):
            full = os.path.join(dirpath, name)
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            if not _wanted(rel, src) or os.path.islink(full):
                continue
            try:
                st = os.stat(full)
            except OSError:
                continue
            if st.st_size > src.get("max_bytes", MAX_FILE_BYTES):
                continue
            yield Entry(rel, f"{st.st_size}:{st.st_mtime_ns}", lambda p=full: Path(p).read_bytes(), st.st_mtime)


def _git(root, *args):
    return subprocess.run(["git", "-C", root, *args], check=True, capture_output=True, timeout=120).stdout


def _git_entries(src, root):
    ref = src["git_branch"]
    if src.get("fetch"):
        branch = ref.split("/", 1)[-1]
        # Updates only the local remote-tracking ref; nothing is pushed or written to the branch itself.
        subprocess.run(["git", "-C", root, "fetch", "--quiet", "origin", branch], capture_output=True, timeout=120)
    try:
        listing = _git(root, "ls-tree", "-r", "-z", ref)
    except (subprocess.CalledProcessError, FileNotFoundError, subprocess.TimeoutExpired):
        return
    for rec in listing.split(b"\0"):
        if not rec:
            continue
        meta, rel = rec.decode(errors="replace").split("\t", 1)
        sha = meta.split()[2]
        if _wanted(rel, src):
            yield Entry(rel, sha, lambda s=sha: _git(root, "cat-file", "blob", s), time.time())


# --- Chunks of one file

def project_for(src, root, rel, meta=None, first_turn=None, cwd=None):
    for rule in src.get("project_rules", []):
        if "frontmatter" in rule:
            if (meta or {}).get(rule["frontmatter"]):
                return meta[rule["frontmatter"]]
        elif "transcript" in rule:
            return transcript_project(cwd, first_turn, src)
        else:
            m = re.search(rule["match"], rel)
            if m:
                return m.expand(rule["project"])
    return src.get("project", "{root}").replace("{root}", os.path.basename(root.rstrip("/")))


def transcript_project(cwd, first_turn, src):
    """The repo of the session: the name in a "Taak voor de repo X:" prompt (inbox tasks), else the working folder."""
    m = re.search(r"repo\s+(\S+?):", first_turn or "")
    if m and (first_turn or "").startswith("user: Taak voor de repo"):
        return m.group(1)
    name = os.path.basename((cwd or "").rstrip("/"))
    return name if name and name != "repo" else src.get("fallback_project", "unknown")


def chunk_file(src, root, entry, data):
    """Yield (project, location, text, date) for one file, before scrubbing."""
    text = data.decode("utf-8", errors="replace")
    fdate = iso_date(entry.mtime)
    kind = src["kind"]
    if kind == "transcript":
        turns = list(transcript_turns(text))
        first = f"{turns[0][0]}: {turns[0][1]}" if turns else ""
        cwd = next((c for *_, c in turns if c), None)
        project = project_for(src, root, entry.rel, first_turn=first, cwd=cwd)
        for ch in chunk_transcript(text):
            yield project, ch["location"], ch["text"], ch["date"] or fdate
    elif kind in ("markdown", "memory"):
        meta, _ = front_matter(text)
        project = project_for(src, root, entry.rel, meta=meta)
        title = meta.get("title") or Path(entry.rel).stem
        for ch in chunk_markdown(text, title):
            yield project, ch["location"], ch["text"], ch["date"] or fdate
    elif kind == "code":
        project = project_for(src, root, entry.rel)
        for ch in chunk_code(text, Path(entry.rel).suffix or ".py"):
            yield project, ch["location"], ch["text"], fdate
    else:
        raise ValueError(f"unknown source kind {kind!r}")


# --- The store

SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS files (
  source TEXT NOT NULL, path TEXT NOT NULL, key TEXT NOT NULL, hash TEXT NOT NULL,
  indexed_at TEXT NOT NULL, chunks INTEGER NOT NULL, PRIMARY KEY (source, path));
CREATE TABLE IF NOT EXISTS chunks (
  id INTEGER PRIMARY KEY, source TEXT NOT NULL, type TEXT NOT NULL, path TEXT NOT NULL, location TEXT NOT NULL,
  date TEXT, project TEXT NOT NULL, text TEXT NOT NULL, text_hash TEXT NOT NULL, embedding BLOB NOT NULL);
CREATE INDEX IF NOT EXISTS chunks_file ON chunks (source, path);
CREATE INDEX IF NOT EXISTS chunks_filter ON chunks (project, type);
"""


class FingerprintMismatch(Exception):
    pass


class Index:
    def __init__(self, conf, embedder=None, db=None, readonly=False):
        self.conf = conf
        self.readonly = readonly
        self.scrubber = Scrubber(conf["scrub"])
        self.embedder = embedder or Embedder(conf["embedding"])
        self.path = db or conf["db"]
        if readonly:  # the query side: never creates, migrates or writes the index
            if not os.path.exists(self.path):
                raise FileNotFoundError(f"no search index at {self.path}: run `homelab-search rebuild`")
            self.db = sqlite3.connect(f"file:{self.path}?mode=ro", uri=True)
            return
        os.makedirs(os.path.dirname(self.path) or ".", exist_ok=True)
        self.db = sqlite3.connect(self.path)
        self.db.executescript(SCHEMA)
        self.db.execute("INSERT OR IGNORE INTO meta VALUES ('fingerprint', ?)", (self.fingerprint(),))
        self.db.commit()

    def fingerprint(self):
        blob = json.dumps({"model": self.conf["embedding"]["model"], "chunker": CHUNKER_VERSION,
                           "prefix": self.conf["embedding"].get("doc_prefix", ""), "scrub": self.conf["scrub"]},
                          sort_keys=True)
        return hashlib.sha256(blob.encode()).hexdigest()[:16]

    def _check_fingerprint(self):
        row = self.db.execute("SELECT value FROM meta WHERE key='fingerprint'").fetchone()
        if row[0] != self.fingerprint():
            raise FingerprintMismatch(
                "the embedding model, chunking rules or scrub patterns changed since this index was built: "
                "run `homelab-search rebuild` (a sync never re-indexes everything by itself)")

    def sync(self, only=None, log=print):
        """Bring the index in line with the sources. Returns counts per action."""
        self._check_fingerprint()
        stats = {"files_indexed": 0, "files_unchanged": 0, "files_removed": 0, "chunks_embedded": 0,
                 "chunks_reused": 0, "sources_removed": 0}
        enabled = [s for s in self.conf["sources"] if s.get("enabled", True) and (not only or s["name"] == only)]
        names = {s["name"] for s in self.conf["sources"] if s.get("enabled", True)}
        if not only:
            for (name,) in self.db.execute("SELECT DISTINCT source FROM files").fetchall():
                if name not in names:  # switched off or removed from the config: its chunks go
                    self._drop_source(name)
                    stats["sources_removed"] += 1
        for src in enabled:
            self._sync_source(src, stats, log)
        self.db.execute("INSERT OR REPLACE INTO meta VALUES ('last_sync', ?)",
                        (time.strftime("%Y-%m-%dT%H:%M:%S%z"),))
        self.db.commit()
        return stats

    def _drop_source(self, name):
        self.db.execute("DELETE FROM chunks WHERE source=?", (name,))
        self.db.execute("DELETE FROM files WHERE source=?", (name,))
        self.db.commit()

    def _sync_source(self, src, stats, log):
        known = {(r[0]): (r[1], r[2]) for r in
                 self.db.execute("SELECT path, key, hash FROM files WHERE source=?", (src["name"],))}
        seen = set()
        for root in roots_of(src):
            for entry in list_files(src, root):
                path = f"{os.path.basename(root.rstrip('/'))}/{entry.rel}" if src.get("multi_root") else entry.rel
                seen.add(path)
                if path in known and known[path][0] == entry.key:
                    stats["files_unchanged"] += 1
                    continue
                try:
                    data = entry.read()
                except (OSError, subprocess.SubprocessError):
                    continue  # unreadable now: keep what we have, try again next time
                digest = hashlib.sha256(data).hexdigest()
                if path in known and known[path][1] == digest:  # touched, not changed
                    self.db.execute("UPDATE files SET key=? WHERE source=? AND path=?", (entry.key, src["name"], path))
                    stats["files_unchanged"] += 1
                    continue
                self._index_file(src, root, entry, path, data, digest, stats)
                log(f"indexed {src['name']}: {path}")
        for path in set(known) - seen:
            self._remove_file(src["name"], path)
            stats["files_removed"] += 1
            log(f"removed {src['name']}: {path}")
        self.db.commit()

    def _remove_file(self, source, path):
        self.db.execute("DELETE FROM chunks WHERE source=? AND path=?", (source, path))
        self.db.execute("DELETE FROM files WHERE source=? AND path=?", (source, path))

    def _index_file(self, src, root, entry, path, data, digest, stats):
        rows = []
        for project, location, text, date in chunk_file(src, root, entry, data):
            text = self.scrubber.scrub(text).strip()
            if text:
                rows.append((project, self.scrubber.scrub(location), text, date))
        old = {h: e for h, e in self.db.execute(
            "SELECT text_hash, embedding FROM chunks WHERE source=? AND path=?", (src["name"], path))}
        hashes = [hashlib.sha256(r[2].encode()).hexdigest() for r in rows]
        todo = sorted({h: r[2] for h, r in zip(hashes, rows) if h not in old}.items())
        fresh = dict(zip([h for h, _ in todo], self.embedder.documents([t for _, t in todo]))) if todo else {}
        stats["chunks_embedded"] += len(fresh)
        stats["chunks_reused"] += len(rows) - len(todo)
        # Everything is embedded before the old rows are touched: a failed embedding leaves the file as it was.
        self.db.execute("DELETE FROM chunks WHERE source=? AND path=?", (src["name"], path))
        for (project, location, text, date), h in zip(rows, hashes):
            blob = old[h] if h in old else pack(fresh[h])
            self.db.execute("""INSERT INTO chunks (source, type, path, location, date, project, text, text_hash, embedding)
                               VALUES (?,?,?,?,?,?,?,?,?)""",
                            (src["name"], src["type"], self.scrubber.scrub(path), location, date, project, text, h, blob))
        self.db.execute("INSERT OR REPLACE INTO files VALUES (?,?,?,?,?,?)",
                        (src["name"], path, entry.key, digest, time.strftime("%Y-%m-%dT%H:%M:%S%z"), len(rows)))
        self.db.commit()
        stats["files_indexed"] += 1

    # --- Reading the index

    def query(self, text, k=8, project=None, type=None, source=None):
        """The k chunks closest to `text`, best first, optionally filtered by project, source type or source name."""
        if self.readonly:  # vectors from another model or prefix would rank nonsense
            self._check_fingerprint()
        where, args = [], []
        for col, val in (("project", project), ("type", type), ("source", source)):
            if val:
                where.append(f"{col}=?")
                args.append(val)
        q = self.embedder.query(self.scrubber.scrub(text))
        rows = self.db.execute("SELECT source, type, path, location, date, project, text, embedding FROM chunks"
                               + (" WHERE " + " AND ".join(where) if where else ""), args)
        scored = []
        for s, t, path, loc, date, proj, body, blob in rows:
            score = sum(a * b for a, b in zip(q, unpack(blob)))
            scored.append({"score": round(score, 4), "source": s, "type": t, "path": path, "location": loc,
                           "date": date, "project": proj, "text": body})
        scored.sort(key=lambda r: -r["score"])
        return scored[:k]

    def status(self):
        out = {"db": self.path, "chunks": self.db.execute("SELECT COUNT(*) FROM chunks").fetchone()[0],
               "last_sync": (self.db.execute("SELECT value FROM meta WHERE key='last_sync'").fetchone() or [None])[0],
               "by_source": {}, "by_type": {}, "by_project": {}}
        for col, key in (("source", "by_source"), ("type", "by_type"), ("project", "by_project")):
            out[key] = dict(self.db.execute(f"SELECT {col}, COUNT(*) FROM chunks GROUP BY {col} ORDER BY 2 DESC"))
        return out

    def close(self):
        self.db.close()


def rebuild(conf, embedder=None, log=print):
    """Build a complete new index next to the old one and swap it in: the old index serves until the new one is whole."""
    new = conf["db"] + ".new"
    for suffix in ("", "-journal"):
        if os.path.exists(new + suffix):
            os.remove(new + suffix)
    idx = Index(conf, embedder, db=new)
    try:
        stats = idx.sync(log=log)
    finally:
        idx.close()
    os.replace(new, conf["db"])
    return stats
