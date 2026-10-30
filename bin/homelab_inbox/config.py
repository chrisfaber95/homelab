"""Settings and shared state: config files, environment overrides, paths, and the locks shared by all parts."""
import os
import re
import shlex
import threading
from pathlib import Path
from zoneinfo import ZoneInfo


ROOT = Path(__file__).resolve().parent.parent.parent


def load_config():
    conf = {}
    for name in ("deploy.conf", "admin.conf", "llm.conf", "backup.conf", "inbox.conf"):
        f = ROOT / "config" / name
        if f.exists():
            for line in f.read_text().splitlines():
                line = line.strip()
                if line and not line.startswith("#") and "=" in line:
                    key, value = line.split("=", 1)
                    conf[key.strip()] = os.environ.get(key.strip(), value.strip().strip('"'))
    return conf


CONF = load_config()


def load_services():
    """The services on the box for the overview: config/services.conf, one `name | address | what it is` per line."""
    f = ROOT / "config" / "services.conf"
    services = []
    for line in (f.read_text().splitlines() if f.exists() else []):
        parts = [p.strip() for p in line.split("|")]
        if line.strip().startswith("#") or len(parts) != 3 or not all(parts):
            continue
        services.append(dict(zip(("name", "address", "note"), parts)))
    return services


SERVICES = load_services()


HOME = Path(os.environ.get("INBOX_RUNNER_HOME", CONF["RUNNER_HOME"]))


DB = HOME / "inbox.db"


FAKE_GH = os.environ.get("INBOX_FAKE_GITHUB") == "1"


TASK_CMD = shlex.split(os.environ.get("TASK_CMD", "sudo -n -u inbox /usr/local/lib/homelab/bin/homelab-inbox-task"))


REPOS_CONF = Path(os.environ.get("REPOS_CONF", "/srv/homelab/repos/homelab/config/repos.conf"))


GH_TOKEN_FILE = Path("/etc/homelab/inbox-github-token")


NTFY_TOKEN_FILE = Path("/etc/homelab/inbox-ntfy-token")


SECRET_FILE = HOME / "csrf-secret"


STATUS_NL = {"queued": "in de rij", "running": "bezig", "waiting": "wacht op jou", "pr_open": "PR open",
             "answered": "beantwoord", "capped": "gestopt bij limiet", "cancelled": "geannuleerd",
             "failed": "mislukt", "refused": "geweigerd", "merged": "gemerged", "closed": "PR gesloten",
             "routed": "doorgestuurd"}


AUTO = "auto"  # pseudo-repo: een router verdeelt het bericht over de repo's (kindtaken)


BRIEF = "brief"  # pseudo-repo (H-001): the planner splits an approved brief into child tasks


# Repos of new projects (`build: {new_repo: owner/name}` in a brief), added when Chris approves that brief: name -> slug.
NEW_REPOS = HOME / "new-repos.json"


# What the designer's setup PR (branch setup/<brief id>) may change; the inbox merges it when the build starts.
SETUP_PATHS = re.compile(r"(CLAUDE\.md|\.claude/kit\.md|\.claude/agents/[\w.-]+\.md|\.claude/skills/[\w.-]+/.+)")


ACTIVE = ("queued", "running")


_lock = threading.RLock()


_procs = {}      # taak-id -> Popen


_cancel = set()  # taken die gestopt moeten worden


_cancel_runs = set()  # lokale job-runs die gestopt moeten worden (F-024)


_round_started = {}   # taak-id -> begin van de lopende ronde (voor "bezig sinds")


TZ = ZoneInfo(CONF.get("JOB_TIMEZONE", "Europe/Amsterdam"))


JOBS = {}  # naam -> job, of {"name", "error", "text"} voor een bestand met een fout


# Wat op een token, wachtwoord of sleutel lijkt; zoiets komt nooit in het geheugen.
SECRET_RE = re.compile(
    r"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_\w{20,}|sk-ant-[\w-]{10,}|sk-[A-Za-z0-9]{20,}|xox[abprs]-[\w-]{10,}"
    r"|AKIA[0-9A-Z]{16}|tskey-[\w-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|eyJ[\w-]{10,}\.[\w-]{10,}\.[\w-]{5,}"
    r"|(?i:bearer)\s+[\w.~+/-]{16,}=*|(?i:token|password|passwd|secret|api[_-]?key|wachtwoord)\s*[:=]\s*\S{8,}")
