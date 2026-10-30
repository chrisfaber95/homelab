"""Helpers shared by all parts: GitHub API, git, notifications, live-log files, task text helpers."""
import json
import os
import re
import subprocess
import threading
import urllib.request
from pathlib import Path

from .config import CONF, FAKE_GH, GH_TOKEN_FILE, HOME, JOBS, NEW_REPOS, NTFY_TOKEN_FILE, REPOS_CONF, SECRET_RE, TASK_CMD, _lock
from .storage import get, log, now


def repo_list():
    repos = {"homelab": CONF["HOMELAB_REPO"]}
    try:
        for line in REPOS_CONF.read_text().splitlines():
            parts = line.split("#", 1)[0].split()
            if len(parts) == 3 and re.match(r"^[a-z0-9][a-z0-9-]*$", parts[0]):
                repos[parts[0]] = parts[1]
    except OSError:
        pass
    for name, slug in read_json(NEW_REPOS, {}).items():
        repos.setdefault(name, slug)
    return repos


def notify(title, message, click=None):
    if os.environ.get("NTFY_FAKE") == "1":
        log(f"MELDING: {title} | {message} | {click or ''}")
        return
    try:
        body = {"topic": CONF.get("NTFY_TOPIC", "homelab"), "title": title, "message": message, "priority": 4,
                "tags": ["robot"]}
        if click:
            body["click"] = click
            body["actions"] = [{"action": "view", "label": "Openen", "url": click}]
        req = urllib.request.Request(f"http://127.0.0.1:{CONF.get('NTFY_PORT', '8102')}/", method="POST",
                                     data=json.dumps(body).encode(), headers={
                                         "Authorization": f"Bearer {NTFY_TOKEN_FILE.read_text().strip()}",
                                         "Content-Type": "application/json"})
        urllib.request.urlopen(req, timeout=10).close()
    except (OSError, urllib.error.URLError) as e:
        log(f"melding niet verstuurd ({e}): {title}")


def page_url(task_id=None):
    base = f"http://{CONF.get('TAILNET_NAME', 'homelab')}:{CONF['INBOX_PORT']}"
    return f"{base}/task/{task_id}" if task_id else base


def gh(method, path, body=None):
    if FAKE_GH:
        log(f"FAKE GITHUB: {method} {path} {json.dumps(body) if body else ''}")
        if method == "POST" and path.endswith("/pulls"):
            return {"number": 1, "html_url": f"https://github.com/{path.split('/')[2]}/{path.split('/')[3]}/pull/1"}
        if method != "GET":
            return {}
        fake = HOME / "fake_github.json"  # tests: {"<pad zonder query>": antwoord}
        answers = json.loads(fake.read_text()) if fake.exists() else {}
        return answers.get(path.split("?")[0], [] if path.split("?")[0].endswith(("comments", "reviews")) else {})
    req = urllib.request.Request(f"https://api.github.com{path}", method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": f"Bearer {GH_TOKEN_FILE.read_text().strip()}",
                                          "Accept": "application/vnd.github+json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def git(repo_dir, *args, timeout=600):
    """git in een clone van de dienst zelf (nooit in een map van Claude), met het token via een helper."""
    helper = ('!f() { test "$1" = get && echo username=x-access-token && '
              f'echo "password=$(cat {GH_TOKEN_FILE})"; }}; f')
    p = subprocess.run(["git", "-c", "credential.helper=", "-c", f"credential.helper={helper}", "-C", str(repo_dir),
                        *args], capture_output=True, text=True, timeout=timeout,
                       env={**os.environ, "GIT_TERMINAL_PROMPT": "0"})
    if p.returncode != 0:
        raise RuntimeError(f"git {args[0]}: {(p.stderr or p.stdout).strip()[-300:]}")
    return p.stdout


def remote_url(name, slug):
    fake = os.environ.get(f"REMOTE_{name.upper().replace('-', '_')}")
    return fake or f"https://github.com/{slug}.git"


_repo_locks = {}  # repo name -> lock on the service's copy of it: parallel tasks on one repo share that copy


def repo_lock(name):
    with _lock:
        return _repo_locks.setdefault(name, threading.RLock())


def refresh_cache(name, slug):
    """De kopie van de dienst (waar de werkkopie van inbox uit clonet), op origin/main."""
    with repo_lock(name):
        return _refresh_cache(name, slug)


def _refresh_cache(name, slug):
    d = HOME / "repos" / name
    if not (d / ".git").is_dir():
        d.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "--quiet", remote_url(name, slug), str(d)], check=True, capture_output=True,
                       env={**os.environ, "GIT_TERMINAL_PROMPT": "0",
                            "GIT_CONFIG_COUNT": "2", "GIT_CONFIG_KEY_0": "credential.helper", "GIT_CONFIG_VALUE_0": "",
                            "GIT_CONFIG_KEY_1": "credential.helper",
                            "GIT_CONFIG_VALUE_1": ('!f() { test "$1" = get && echo username=x-access-token && '
                                                   f'echo "password=$(cat {GH_TOKEN_FILE})"; }}; f')})
    git(d, "fetch", "--quiet", "origin", "main")
    git(d, "checkout", "--quiet", "-B", "main", "origin/main")
    return d


def task_cmd(*args, timeout=600):
    p = subprocess.run([*TASK_CMD, *map(str, args)], capture_output=True, text=True, timeout=timeout)
    return p.returncode, (p.stdout + p.stderr).strip()


LOGS = HOME / "logs"


def log_line(key, kind, text):
    """kind: info, claude, tool, result, error, out. Wat op een geheim lijkt, wordt verborgen."""
    LOGS.mkdir(exist_ok=True)
    text = SECRET_RE.sub("[verborgen]", str(text))
    lines = text.splitlines() if kind == "out" else [" ".join(text.split())]
    with open(LOGS / f"{key}.log", "a") as f:
        for line in lines or [""]:
            f.write(json.dumps({"at": now(), "kind": kind, "text": line[:600]}, ensure_ascii=False) + "\n")


def read_log(key, start=0, limit=500):
    try:
        with open(LOGS / f"{key}.log") as f:
            lines = f.readlines()
    except OSError:
        return [], 0
    return [json.loads(l) for l in lines[start:start + limit]], min(len(lines), start + limit)


def label(t):
    return f"Job {t['job']}" if t["job"] else f"Inbox {t['id']}"


def pr_text(t):
    """The task as shown in its PR: for a brief task without the brief itself (the product repo is private, the
    target repo may be public)."""
    if t["brief"]:
        return t["text"].split("\n\nThis task is part of brief", 1)[0] + f"\n\n(Part of brief {t['brief']}.)"
    return t["text"]


def title_of(t):
    if t["job"]:
        return f"job {t['job']}: {first_line(JOBS.get(t['job'], {}).get('instruction', ''))}"
    return f"inbox {t['id']}: {first_line(t['text'])}"


def kind_of(text):
    return text.strip().partition(":")[0].strip().upper()


def first_line(text):
    return text.strip().splitlines()[0][:70] if text.strip() else "taak"


NEVER_SELF_MERGE = {"homelab"}  # a merge there runs root scripts on the box: always the user


def merges_itself(t):
    """Does the inbox merge this PR itself? Only a brief task of a build without me, on a product repo, whose last
    review passed every criterion."""
    if not t or not t["brief"] or not t["parent"] or t["repo"] in NEVER_SELF_MERGE or t["repo"] not in repo_list():
        return False
    parent = get(t["parent"])
    return bool(parent and parent["unattended"]) and t["review_ok"] in (1, 2)


def as_list(value):
    if value is None or value == "":
        return []
    return [str(v).strip() for v in (value if isinstance(value, list) else str(value).split(",")) if str(v).strip()]


def sync_branch(d, branch):
    """Een clone van één branch van DATA_REPO, gelijk aan GitHub (of leeg, als de branch er (nog) niet is)."""
    url = remote_url("homelab-data", CONF["DATA_REPO"])
    if not (d / ".git").is_dir():
        d.mkdir(parents=True, exist_ok=True)
        git(d, "init", "--quiet")
        git(d, "remote", "add", "origin", url)
        git(d, "config", "user.name", "homelab-inbox")
        git(d, "config", "user.email", "homelab-inbox@homelab")
    else:
        git(d, "remote", "set-url", "origin", url)  # een clone van voor DATA_REPO wees nog naar de homelab-repo
    if git(d, "ls-remote", "--heads", "origin", branch).strip():
        git(d, "fetch", "--quiet", "origin", branch)
        git(d, "checkout", "--quiet", "--force", "-B", branch, "FETCH_HEAD")
        git(d, "clean", "-fdq")
    else:
        # Weg op GitHub (nog nooit opgeslagen, of gewist): hier ook leeg, zodat er niets ouds blijft hangen.
        git(d, "symbolic-ref", "HEAD", f"refs/heads/{branch}")
        git(d, "update-ref", "-d", f"refs/heads/{branch}")
        git(d, "read-tree", "--empty")
        git(d, "clean", "-fdxq")
    return d


def read_json(path, default):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return default
