"""Memory (F-023): preferences, facts and history on the memory branch of DATA_REPO.

Since H-024 M5 the Java backend owns the store: it is the only writer of the clone `memory-repo` (commits, pushes, the
checks of the page and of Claude's GEHEUGEN: blocks, the file format). This module only reads the files of the clone
(the cache MEMORY, the prompt of a task) and asks the backend to write, through /internal/memory/* on loopback with the
facade secret. When the backend cannot be reached nothing is written here: the caller gets the error."""
import json
import re
import shutil
import threading
from datetime import datetime

import yaml

from . import facade
from .config import CONF, HOME, JOBS, STATUS_NL, TZ
from .helpers import first_line, label, page_url, repo_list, title_of
from .storage import get, log, update


LEARN_HINT = ("Is iets hiervan een correctie die ook voor latere taken geldt, bewaar het dan als voorkeur "
              "(GEHEUGEN: voorkeur ...).")


MEMORY_DIR = HOME / "memory-repo"


MEM_KINDS = {"voorkeuren": "voorkeur", "feiten": "feit", "geschiedenis": "geschiedenis"}


MEM_ID_RE = re.compile(r"^((voorkeuren|feiten)/[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9-]{0,40}"
                       r"|geschiedenis/\d{4}-\d{2}/[a-z0-9][a-z0-9-]{0,40})$")


MEM_BLOCK_RE = re.compile(r"^GEHEUGEN:[ \t]*(voorkeur|feit)[ \t]+([a-z0-9][a-z0-9-]*)[ \t]+([a-z0-9][a-z0-9-]{0,40})"
                          r"[ \t]*\n(.*?)(?=^GEHEUGEN:|^SKILL-VOORSTEL:|\Z)", re.S | re.M)


MEM_FINAL = ("answered", "pr_open", "capped", "cancelled", "failed", "refused", "merged", "closed", "routed")


_mem_lock = threading.RLock()


MEMORY = []  # alle items, zoals op de branch


MEMORY_PROMPT = """
Geheugen (F-023). De gebruiker ziet, bewerkt en wist alles hiervan op de pagina (tab Geheugen).
Voorkeuren van de gebruiker (volg ze):
{prefs}
Feiten over de box en {repo}:
{facts}
Geschiedenis: één bestand per eerdere taak of Claude-job in {hist} (kop met datum, status en link). Vraagt de taak naar
iets van eerder ("wanneer faalde ... het laatst?"), zoek daar dan met grep en noem in je antwoord de datum en de link
van de taak die je vond. Recent voor {repo}:
{recent}
- Bewaren: leerde je iets blijvends, zet dan onder je laatste bericht (vóór een eventueel SKILL-VOORSTEL) één blok per
  item, precies zo:
  GEHEUGEN: voorkeur <algemeen|repo> <naam-met-streepjes>
  de voorkeur in één tot drie zinnen
  GEHEUGEN: feit <box|repo> <naam-met-streepjes>
  het feit in één tot drie zinnen
  Een voorkeur als de gebruiker je corrigeert of zegt hoe het anders moet (in PR-commentaar of een antwoord) en dat ook
  voor latere taken geldt. Een feit als je iets blijvends over de box of een repo ontdekt (een dienst, een build- of
  deploystap) of een bekend feit niet meer klopt. Gaat het over iets wat er al staat, gebruik dan precies die naam:
  dan wordt het bijgewerkt in plaats van verdubbeld. Niets nieuws: geen blok.
  Nooit tokens, wachtwoorden, sleutels of andere geheimen, en niets over andere mensen dan de gebruiker."""


class Unavailable(RuntimeError):
    """The Java backend did not answer (down, not imported yet, or no secret): nothing was written."""


def _http(path, body):
    """The transport: one POST to the backend, (status, body bytes). Tests replace `transport` with an in-process fake
    (tests/memory_fake.py); production always goes through facade.forward."""
    try:
        status, _, raw = facade.forward("POST", path, {"Content-Type": "application/json"}, json.dumps(body).encode(),
                                        "inbox", timeout=120)
        return status, raw
    except RuntimeError as e:
        raise Unavailable(f"memory store: {e}") from e


transport = _http


def backend(path, body):
    """(status, parsed JSON) of one call to /internal/memory/<path>; Unavailable when the backend does not answer."""
    status, raw = transport(f"/internal/memory/{path}", body)
    try:
        return status, json.loads(raw or b"{}")
    except ValueError as e:
        raise Unavailable(f"memory store: not JSON (HTTP {status})") from e


def page_write(action, form, who, author, mid=None):
    """The Geheugen page's add, save and delete (`/memory/add`, `/memory/e/<id>/save|delete`): the backend checks the
    form and commits. Returns (status, body): 200 with {"location"}, or the error status with {"error"}."""
    body = {"form": form, "who": who, "author": list(author)}
    if mid is not None:
        body["id"] = mid
    status, data = backend(action, body)
    if status == 200:
        load_memory(fetch=False)
    return status, data


def parse_entry(mid, text):
    meta, body = {}, text
    if text.startswith("---\n"):
        head, sep, rest = text[4:].partition("\n---")
        if sep:
            try:
                meta = yaml.safe_load(head) or {}
            except yaml.YAMLError:
                meta = {}
            body = rest.partition("\n")[2]
    if not isinstance(meta, dict):
        meta = {}
    kind = mid.split("/")[0]
    about = str(meta.get("over") or (mid.split("/")[1] if kind != "geschiedenis" else "algemeen"))
    return {"id": mid, "kind": kind, "about": about, "name": mid.rsplit("/", 1)[1], "body": body.strip(),
            "added": str(meta.get("toegevoegd") or ""), "changed": str(meta.get("gewijzigd") or meta.get("toegevoegd") or ""),
            "source": str(meta.get("bron") or ""), "link": str(meta.get("link") or ""),
            "status": str(meta.get("status") or ""), "pr": str(meta.get("pr") or "")}


def load_memory(fetch=True):
    """The entries of the clone into MEMORY. `fetch`: first ask the backend to bring the clone to GitHub (what the user
    changed there or on the page); when it does not answer, the clone as it is."""
    with _mem_lock:
        if fetch:
            try:
                status, data = backend("sync", {})
                if status != 200:
                    raise Unavailable(f"HTTP {status}: {data.get('error', '')}")
            except Exception as e:
                log(f"geheugen ophalen: {e}")  # dan het laatst opgehaalde
        found = []
        for f in sorted(MEMORY_DIR.glob("*/*/*.md")):
            mid = f.relative_to(MEMORY_DIR).with_suffix("").as_posix()
            if MEM_ID_RE.match(mid):
                found.append(parse_entry(mid, f.read_text()))
        MEMORY[:] = found


def mem_get(mid):
    return next((m for m in MEMORY if m["id"] == mid), None)


def mem_local(iso):
    """The date as the Geheugen page shows it (dd-mm hh:mm in the inbox time zone); a hand-edited value stays as is."""
    try:
        return datetime.fromisoformat(iso).astimezone(TZ).strftime("%d-%m %H:%M") if iso else "-"
    except ValueError:
        return iso


def mem_json(m):
    """One entry for the JSON API: the fields of the entry plus what the page derives from them."""
    title = first_line(m["body"]).lstrip("# ") if m["kind"] == "geschiedenis" else m["name"]
    return {**m, "title": title, "summary": "" if m["kind"] == "geschiedenis" else first_line(m["body"]),
            "kind_label": MEM_KINDS[m["kind"]], "added_local": mem_local(m["added"]),
            "changed_local": mem_local(m["changed"])}


def mem_matches(m, words):
    hay = " ".join((m["id"], m["about"], m["source"], m["status"], m["body"])).lower()
    return all(w in hay for w in words)


def memory_view(q=""):
    """What the Memory page (frontend/src/pages/memory.ts) renders: the three sections (history newest first, cut to 30 unless there is a query), the
    entries per section and the `about` choices of the add form."""
    words = q.lower().split()
    hits = [m for m in MEMORY if mem_matches(m, words)]
    sections = []
    for kind in ("voorkeuren", "feiten", "geschiedenis"):
        items = [m for m in hits if m["kind"] == kind]
        if kind == "geschiedenis":
            items.sort(key=lambda m: m["added"], reverse=True)
        shown = items if q or kind != "geschiedenis" else items[:30]
        sections.append({"kind": kind, "count": len(items), "truncated": len(shown) < len(items),
                         "entries": [mem_json(m) for m in shown]})
    return {"q": q, "sections": sections, "abouts": ["algemeen", "box", *sorted(repo_list())]}


def entry_view(m):
    """What the memory entry page renders: the entry and where it lives."""
    return {**mem_json(m), "branch": CONF.get("MEMORY_BRANCH", "memory"), "data_repo": CONF["DATA_REPO"]}


def split_memory(text):
    """Het antwoord van Claude zonder GEHEUGEN:-blokken, en die blokken als (soort, over, naam, tekst)."""
    blocks = [(k, a, n, b.strip()) for k, a, n, b in MEM_BLOCK_RE.findall(text)]
    return MEM_BLOCK_RE.sub("", text).strip(), blocks


def memory_for(t):
    """Het stuk geheugen voor de systeemprompt van een taak; de geschiedenis gaat als map naast de taak."""
    repo = t["repo"]
    share = HOME / "share" / str(t["id"])
    hist = share / "geschiedenis"
    shutil.rmtree(hist, ignore_errors=True)
    hist.mkdir(parents=True)
    entries = sorted((m for m in MEMORY if m["kind"] == "geschiedenis"), key=lambda m: m["changed"], reverse=True)
    for m in entries:
        shutil.copyfile(MEMORY_DIR / f"{m['id']}.md", hist / f"{m['id'].split('/', 1)[1].replace('/', '-')}.md")

    def lines(items):
        return "\n".join(f"- [{m['about']}/{m['name']}] {m['body']}" for m in items) or "- (nog niets)"
    prefs = [m for m in MEMORY if m["kind"] == "voorkeuren" and m["about"] in ("algemeen", repo)]
    facts = [m for m in MEMORY if m["kind"] == "feiten" and m["about"] in ("box", repo)]
    recent = [f"- {m['added'][:10]} {m['status']}: {first_line(m['body']).lstrip('# ')} ({m['link']})"
              for m in entries if m["about"] == repo][:int(CONF.get("MEMORY_RECENT", 10))]
    return MEMORY_PROMPT.format(prefs=lines(prefs), facts=lines(facts), repo=repo, hist=hist,
                                recent="\n".join(recent) or "- (nog niets)")


def save_learned(t, blocks):
    """Wat Claude onder zijn laatste bericht bewaarde (GEHEUGEN:): voorkeuren en feiten, nooit geheimen. The backend
    checks every block, writes the valid ones in one commit and answers the notes for the task."""
    source = t["mem_source"] or label(t)
    link = t["mem_link"] or page_url(t["id"])
    status, data = backend("learned", {"blocks": [list(b) for b in blocks], "source": source, "link": link,
                                       "label_lower": label(t).lower()})
    if status != 200:
        raise RuntimeError(data.get("error", f"HTTP {status}"))
    load_memory(fetch=False)
    return data["notes"]


def remember_task(task_id):
    """De geschiedenis van een afgeronde taak (of Claude-job) bijwerken; na een wis door de gebruiker niet opnieuw."""
    t = get(task_id)
    if not t or t["status"] not in MEM_FINAL:
        return
    asked = (JOBS.get(t["job"], {}).get("instruction") or f"job {t['job']}") if t["job"] else t["text"]
    result = t["answer"] or t["summary"] or t["question"] or t["note"] or ""
    status, data = backend("history", {
        "id": t["id"], "created": t["created"], "repo": t["repo"], "label": label(t), "label_lower": label(t).lower(),
        "link": page_url(t["id"]), "status_nl": STATUS_NL[t["status"]], "pr_url": t["pr_url"] or "", "job": t["job"] or "",
        "title": title_of(t), "asked": asked[:1000], "result": result[:2000], "remembered": bool(t["remembered"])})
    if status != 200:
        raise RuntimeError(data.get("error", f"HTTP {status}"))
    if data.get("saved"):
        load_memory(fetch=False)
        update(task_id, remembered=1)


def after_round(task_id, blocks):
    """Na elke ronde: geleerde items bewaren, de geschiedenis bijwerken, de kopie voor Claude opruimen. Faalt nooit."""
    notes = []
    try:
        if blocks:
            notes = save_learned(get(task_id), blocks)
    except Exception as e:
        log(f"taak {task_id}: geheugen bewaren: {e}")
        notes = [f"geheugen bewaren mislukt: {e}"[:200]]
    try:
        remember_task(task_id)
    except Exception as e:
        log(f"taak {task_id}: geschiedenis: {e}")
    shutil.rmtree(HOME / "share" / str(task_id) / "geschiedenis", ignore_errors=True)
    if notes:
        note = get(task_id)["note"]
        update(task_id, note="; ".join(([note] if note else []) + notes)[:1000])
