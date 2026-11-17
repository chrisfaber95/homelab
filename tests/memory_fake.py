"""An in-process stand-in for the Java memory store (H-024 M5), for the Python tests that only need *a* writer behind
`memory.transport`: the same /internal/memory/* routes and answers as backend/src/main/java/dev/homelab/inbox/memory/,
done with the code the Python inbox had before the move (the checks of the page, save_learned, remember_task, the front
matter, one commit per change). The Java side has its own tests (MemoryWriterTest) and tests/test_inbox_m5_parity.py
compares the two. `install()` swaps the transport; with `remote` (a git URL or path) it also commits and pushes like
the old save_memory did, without it only the files of the clone change."""
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

SECRET_RE = re.compile(
    r"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_\w{20,}|sk-ant-[\w-]{10,}|sk-[A-Za-z0-9]{20,}|xox[abprs]-[\w-]{10,}"
    r"|AKIA[0-9A-Z]{16}|tskey-[\w-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|eyJ[\w-]{10,}\.[\w-]{10,}\.[\w-]{5,}"
    r"|(?i:bearer)\s+[\w.~+/-]{16,}=*|(?i:token|password|passwd|secret|api[_-]?key|wachtwoord)\s*[:=]\s*\S{8,}")
MEM_MAX = 1500
CLAUDE_AUTHOR = ("Claude (homelab-inbox)", "noreply@anthropic.com")


def _now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _git(d, *args):
    p = subprocess.run(["git", "-C", str(d), *args], capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"git {args[0]}: {(p.stderr or p.stdout).strip()[-300:]}")
    return p.stdout


class FakeBackend:
    def __init__(self, memory, repo_list, remote=None, branch="memory"):
        self.memory, self.repo_list, self.remote, self.branch = memory, repo_list, remote, branch
        self.calls = []

    # -- the old store -----------------------------------------------------------------------------------------------
    def entry_text(self, about, source, link, body, added, **extra):
        head = {"over": about, "toegevoegd": added, "gewijzigd": _now(), "bron": source}
        head.update({k: v for k, v in {"link": link, **extra}.items() if v})
        return ("---\n" + "".join(f"{k}: {json.dumps(v, ensure_ascii=False)}\n" for k, v in head.items())
                + f"---\n{body.strip()}\n")

    def sync(self):
        d = self.memory.MEMORY_DIR
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

    def save(self, changes, message, author=("homelab-inbox", "homelab-inbox@homelab")):
        d = self.sync()
        for mid, text in changes.items():
            f = d / f"{mid}.md"
            if text is None:
                f.unlink(missing_ok=True)
            else:
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(text)
        if self.remote is None:
            return
        readme = d / "README.md"
        if not readme.exists():
            readme.write_text("# memory\n\nHet geheugen van Claude in de homelab-inbox (F-023): `voorkeuren/`, `feiten/` en "
                              "`geschiedenis/`, één bestand per item. Uitleg: `docs/memory.md` op `main`.\nDeze branch "
                              "wordt nooit gemerged; alleen de homelab-inbox schrijft erop (voor Claude en voor jou).\n")
        _git(d, "add", "-A")
        if not _git(d, "status", "--porcelain").strip():
            return
        _git(d, "-c", f"user.name={author[0]}", "-c", f"user.email={author[1]}", "commit", "--quiet", "-m", message)
        _git(d, "push", "--quiet", "origin", f"HEAD:refs/heads/{self.branch}")

    @staticmethod
    def check_text(text):
        if not text:
            raise ValueError("de tekst is leeg")
        if len(text) > 4000:
            raise ValueError("hooguit 4000 tekens")
        if SECRET_RE.search(text):
            raise ValueError("dit lijkt een token, wachtwoord of sleutel te bevatten; dat komt nooit in het geheugen")

    # -- the routes ----------------------------------------------------------------------------------------------------
    def page(self, action, body):
        mem, form, who, author = self.memory, body.get("form", {}), body["who"], tuple(body["author"])
        text = form.get("text", "").replace("\r\n", "\n").strip()
        try:
            if action == "add":
                kind, about, name = form.get("kind", ""), form.get("about", ""), form.get("name", "").strip()
                mid = f"{kind}/{about}/{name}"
                if kind not in ("voorkeuren", "feiten") or about not in {"algemeen", "box", *self.repo_list()} \
                        or not mem.MEM_ID_RE.match(mid):
                    raise ValueError("kies voorkeur of feit, een onderwerp uit de lijst en een naam met kleine "
                                     "letters, cijfers en streepjes")
                if mem.mem_get(mid):
                    raise ValueError(f"{mid} bestaat al; bewerk dat item")
                self.check_text(text)
                self.save({mid: self.entry_text(about, "door jou toegevoegd", "", text, _now())},
                          f"geheugen: {mid} nieuw ({who})", author)
                return 200, {"location": f"/memory/e/{mid}"}
            entry = mem.mem_get(body["id"])
            if not entry:
                return 404, {"error": "Niet gevonden."}
            if action == "delete":
                self.save({entry["id"]: None}, f"geheugen: {entry['id']} gewist ({who})", author)
                return 200, {"location": "/memory"}
            self.check_text(text)
            source = entry["source"] if entry["source"].endswith("bewerkt door jou") or \
                entry["source"] == "door jou toegevoegd" else f"{entry['source'] or '-'}, bewerkt door jou"
            extra = {k: entry[k] for k in ("status", "pr")}
            self.save({entry["id"]: self.entry_text(entry["about"], source, entry["link"], text, entry["added"] or _now(),
                                                    **extra)}, f"geheugen: {entry['id']} bewerkt ({who})", author)
            return 200, {"location": f"/memory/e/{entry['id']}"}
        except Exception as ex:
            return 400, {"error": str(ex)}

    def learned(self, body):
        mem, repos, changes, notes = self.memory, self.repo_list(), {}, []
        for kind, about, name, text in body["blocks"]:
            folder = "voorkeuren" if kind == "voorkeur" else "feiten"
            if about not in repos and about != ("algemeen" if kind == "voorkeur" else "box"):
                notes.append(f"{name}: onbekend onderwerp {about}")
            elif not text or len(text) > MEM_MAX:
                notes.append(f"{name}: leeg of langer dan {MEM_MAX} tekens")
            elif SECRET_RE.search(text):
                notes.append(f"{name}: niet bewaard, het lijkt een geheim te bevatten")
            else:
                old = mem.mem_get(f"{folder}/{about}/{name}")
                changes[f"{folder}/{about}/{name}"] = self.entry_text(about, body["source"], body["link"], text,
                                                                      old["added"] if old else _now())
        if changes:
            self.save(changes, f"geheugen: {', '.join(changes)} (Claude, {body['label_lower']}; bron: {body['source']})",
                      CLAUDE_AUTHOR)
            notes.insert(0, f"{len(changes)} item(s) in het geheugen bewaard")
        return 200, {"notes": notes}

    def history(self, b):
        mid = f"geschiedenis/{b['created'][:7]}/taak-{b['id']}"
        old = self.memory.mem_get(mid)
        if b["remembered"] and not old:
            return 200, {"saved": False}
        body = f"# {b['title']}\n\nGevraagd:\n{b['asked']}\n\nResultaat:\n{b['result']}"
        text = self.entry_text(b["repo"], b["label"], b["link"], SECRET_RE.sub("[verborgen]", body),
                               old["added"] if old else b["created"], status=b["status_nl"], pr=b["pr_url"], job=b["job"])
        self.save({mid: text}, f"geheugen: geschiedenis {b['label_lower']} ({b['status_nl']})")
        return 200, {"saved": True}

    def handle(self, path, body):
        self.calls.append((path, body))
        route = path.removeprefix("/internal/memory/")
        if route == "sync":
            self.sync()
            return 200, json.dumps({"ok": True, "owned": True}).encode()
        if route in ("add", "save", "delete"):
            status, data = self.page(route, body)
        elif route == "learned":
            status, data = self.learned(body)
        elif route == "history":
            status, data = self.history(body)
        else:
            status, data = 404, {"error": "no such route"}
        return status, json.dumps(data).encode()


def install(memory, repo_list, remote=None):
    """Make `memory` use the in-process store; returns it (its `calls` list records what was asked)."""
    fake = FakeBackend(memory, repo_list, remote)
    memory.transport = fake.handle
    return fake
