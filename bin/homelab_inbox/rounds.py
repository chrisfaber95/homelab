"""One Claude round of a task: the system prompt, streaming, watching, stopping and pushing the branch."""
import json
import os
import re
import subprocess
import tempfile
import threading
import time
from pathlib import Path

from .config import CONF, HOME, TASK_CMD, _cancel, _lock, _procs, _round_started
from .helpers import git, log_line, repo_lock, task_cmd
from .memory import memory_for
from .storage import get, update


SYSTEM_PROMPT = """Je werkt voor de homelab-inbox: de gebruiker heeft vanaf zijn telefoon een taak gestuurd. Je zit in een
eigen werkkopie van de repo {repo} op branch inbox/{id}; de gebruiker kijkt niet mee.
- Je kunt niet pushen en geen PR openen of mergen; de inbox pusht na afloop jouw commits op deze branch en opent de
  PR. Commit dus je werk op deze branch (kleine, duidelijke commits). Push niet en probeer het niet.
- Werk alleen in deze werkkopie. Raak geen andere repo's, dev-clones (~/dev) of deploy-clones (/srv/homelab) aan.
- Is de taak een vraag (opzoeken, uitleggen, "waarom ..."), dan commit je niets.
- Machinewijzigingen (alleen in de homelab-repo) volgen F-019 (docs/admin.md): wat op de vaste lijst staat doe je met
  `homelab-admin <actie> --reason "inbox-taak {id}: ..."`; al het andere als changes/NNNN-naam/apply.sh in een commit.
  Verbiedt F-019 iets (kernel, driver, schijven, reboot, SSH/LAN-regels), doe het dan niet en zoek geen omweg.
- Volg de CLAUDE.md van de repo, behalve waar die zegt dat je zelf pusht of een PR opent: dat doet de inbox.
- Je laatste bericht begint met precies één van deze woorden, gevolgd door een dubbele punt:
  KLAAR: wat je veranderd hebt, in 1 tot 3 zinnen (je hebt commits gemaakt)
  ANTWOORD: het antwoord op een vraag (geen commits)
  VRAAG: één vraag, als de taak te onduidelijk is om zonder te raden te beginnen (doe dan verder nog niets)
  GEWEIGERD: waarom dit niet mag of niet kan
- Stel hooguit één vraag per ronde. Heb je al een antwoord gekregen, neem dan een redelijke aanname en noem die.
- Skills: de gemergde skills van de gebruiker staan in je skills; gebruik ze waar ze passen.
- Ging de taak goed (KLAAR of ANTWOORD) en is jouw manier van werken herbruikbaar voor latere taken, en is er nog geen
  skill voor, dan mag je onder je laatste bericht één skill voorstellen. Meestal doe je dat niet. Precies zo:
  SKILL-VOORSTEL: naam-met-streepjes
  beschrijving: één regel: waarvoor en wanneer je hem gebruikt
  de instructies in markdown (stappen, valkuilen, commando's)
  De inbox maakt er een PR van; de gebruiker beslist. Een bestaande skill verander je nooit zelf: een verbetering
  stel je op dezelfde manier voor, onder dezelfde naam."""


def tool_brief(inp):
    for k in ("command", "file_path", "pattern", "path", "url", "description", "prompt"):
        if inp.get(k):
            return str(inp[k])[:240]
    return json.dumps(inp, ensure_ascii=False)[:240]


def stream_lines(ev):
    """Wat er in een stream-json-gebeurtenis van Claude Code gebeurt, als leesbare logregels."""
    kind = ev.get("type")
    if kind == "system" and ev.get("subtype") == "init":
        return [("info", f"sessie gestart, model {ev.get('model', '?')}")]
    if kind == "result":
        return [("info", f"ronde klaar na {ev.get('num_turns', '?')} beurten, "
                         f"{round((ev.get('duration_ms') or 0) / 1000)} s")]
    out = []
    for b in (ev.get("message") or {}).get("content") or [] if kind in ("assistant", "user") else []:
        if not isinstance(b, dict):
            continue
        if b.get("type") == "text" and b.get("text", "").strip():
            out.append(("claude", b["text"].strip()[:1500]))
        elif b.get("type") == "tool_use":
            out.append(("tool", f"{b.get('name')}: {tool_brief(b.get('input') or {})}"))
        elif b.get("type") == "tool_result":
            c = b.get("content")
            txt = (c if isinstance(c, str) else " ".join(x.get("text", "") for x in c or [] if isinstance(x, dict))).strip()
            n = len(txt.splitlines())
            first = txt.splitlines()[0][:200] if txt else "(leeg)"
            out.append(("error" if b.get("is_error") else "result", first + (f" (+{n - 1} regels)" if n > 1 else "")))
    return out


def write_prompt(t, text):
    share = HOME / "share" / str(t["id"])
    share.mkdir(parents=True, exist_ok=True)
    os.chmod(share, 0o2750)
    (share / "prompt.txt").write_text(text)
    (share / "system.txt").write_text(SYSTEM_PROMPT.format(repo=t["repo"], id=t["id"]) + memory_for(t))
    for f in ("prompt.txt", "system.txt"):
        os.chmod(share / f, 0o640)


def stream_claude(task_id, mode):
    """Claude laten lopen en bewaken: beurten tellen, tijd, annuleren. Geeft (resultaat-json, beurten, gestopt?)."""
    t = get(task_id)
    errfile = tempfile.TemporaryFile(mode="w+")  # niet als pipe: een volle stderr-pipe zou Claude laten hangen
    p = subprocess.Popen([*TASK_CMD, "run", str(task_id), t["session"], mode], stdout=subprocess.PIPE,
                         stderr=errfile, text=True)
    with _lock:
        _procs[task_id] = p
    max_turns, deadline = int(CONF["MAX_TURNS"]), time.monotonic() + 60 * int(CONF["MAX_MINUTES"])
    turns, result, capped = t["turns"] or 0, None, None
    watchdog = threading.Thread(target=watch, args=(task_id, p, deadline), daemon=True)
    watchdog.start()
    for line in p.stdout:
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        for kind, text in stream_lines(ev):
            log_line(f"t{task_id}", kind, text)
        if ev.get("type") == "system" and ev.get("model"):
            update(task_id, model=ev["model"])
        if ev.get("type") == "assistant":
            turns += 1
            if turns % 5 == 0:
                update(task_id, turns=turns)
            if turns - (t["turns"] or 0) >= max_turns and capped is None:
                capped = f"{max_turns} beurten"
                stop(task_id)
        elif ev.get("type") == "result":
            result = ev
    p.wait()
    _round_started.pop(task_id, None)
    with _lock:
        _procs.pop(task_id, None)
        if task_id in _cancel:
            capped = "geannuleerd"
        elif getattr(p, "timed_out", False):
            capped = f"{CONF['MAX_MINUTES']} minuten"
    update(task_id, turns=turns)
    errfile.seek(0)
    err = errfile.read()[-300:]
    errfile.close()
    if result is None and capped is None:
        raise RuntimeError(f"Claude stopte zonder resultaat: {err}")
    return result, turns, capped


def watch(task_id, p, deadline):
    while p.poll() is None:
        if time.monotonic() > deadline or task_id in _cancel:
            p.timed_out = task_id not in _cancel
            stop(task_id)
            return
        time.sleep(2)


def stop(task_id):
    task_cmd("kill", task_id, timeout=30)


def push_branch(task_id, cache):
    """De commits van de taak (als bundle, alleen data) in de kopie van de dienst halen en naar inbox/<id> pushen."""
    code, out = task_cmd("bundle", task_id)
    if code == 3:
        return []
    if code != 0:
        raise RuntimeError(f"bundle mislukt: {out[-300:]}")
    bundle = Path(os.environ.get("INBOX_TASK_HOME", CONF["TASK_HOME"])) / "out" / f"{task_id}.bundle"
    ref = f"refs/heads/inbox/{task_id}"
    with repo_lock(Path(cache).name):
        git(cache, "bundle", "verify", str(bundle))
        heads = git(cache, "bundle", "list-heads", str(bundle)).split()
        if heads[1::2] != [ref]:
            raise RuntimeError(f"de bundle bevat iets anders dan {ref}: {heads[1::2]}")
        git(cache, "fetch", "--quiet", "--force", str(bundle), f"{ref}:refs/inbox/{task_id}")
        # Alleen naar inbox/<id>, nooit naar main: de bestemming staat hier vast.
        git(cache, "push", "--quiet", "--force", "origin", f"refs/inbox/{task_id}:{ref}")
    return [l for l in out.splitlines() if re.match(r"^[0-9a-f]{7,} ", l)]
