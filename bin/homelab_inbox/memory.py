"""Memory (F-023): preferences, facts and history on the memory branch of DATA_REPO."""
import json
import re
import shutil
import threading

import yaml

from .config import CONF, HOME, JOBS, SECRET_RE, STATUS_NL
from .helpers import first_line, git, label, page_url, repo_list, sync_branch, title_of
from .storage import get, log, now, update


LEARN_HINT = ("Is iets hiervan een correctie die ook voor latere taken geldt, bewaar het dan als voorkeur "
              "(GEHEUGEN: voorkeur ...).")


MEMORY_DIR = HOME / "memory-repo"


MEM_KINDS = {"voorkeuren": "voorkeur", "feiten": "feit", "geschiedenis": "geschiedenis"}


MEM_ID_RE = re.compile(r"^((voorkeuren|feiten)/[a-z0-9][a-z0-9-]*/[a-z0-9][a-z0-9-]{0,40}"
                       r"|geschiedenis/\d{4}-\d{2}/[a-z0-9][a-z0-9-]{0,40})$")


MEM_BLOCK_RE = re.compile(r"^GEHEUGEN:[ \t]*(voorkeur|feit)[ \t]+([a-z0-9][a-z0-9-]*)[ \t]+([a-z0-9][a-z0-9-]{0,40})"
                          r"[ \t]*\n(.*?)(?=^GEHEUGEN:|^SKILL-VOORSTEL:|\Z)", re.S | re.M)


MEM_MAX = 1500        # tekens per voorkeur of feit van Claude


MEM_FINAL = ("answered", "pr_open", "capped", "cancelled", "failed", "refused", "merged", "closed", "routed")


CLAUDE_AUTHOR = ("Claude (homelab-inbox)", "noreply@anthropic.com")


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


def memory_sync():
    return sync_branch(MEMORY_DIR, CONF.get("MEMORY_BRANCH", "memory"))


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
    with _mem_lock:
        if fetch:
            try:
                memory_sync()
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


def entry_text(about, source, link, body, added, **extra):
    head = {"over": about, "toegevoegd": added, "gewijzigd": now(), "bron": source}
    head.update({k: v for k, v in {"link": link, **extra}.items() if v})
    return ("---\n" + "".join(f"{k}: {json.dumps(v, ensure_ascii=False)}\n" for k, v in head.items())
            + f"---\n{body.strip()}\n")


def save_memory(changes, message, author=("homelab-inbox", "homelab-inbox@homelab")):
    """Eén commit op de geheugen-branch: {id: tekst, of None om te wissen}, gepusht met het token van de dienst."""
    branch = CONF.get("MEMORY_BRANCH", "memory")
    with _mem_lock:
        d = memory_sync()
        for mid, text in changes.items():
            f = d / f"{mid}.md"
            if text is None:
                f.unlink(missing_ok=True)
            else:
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(text)
        readme = d / "README.md"
        if not readme.exists():
            readme.write_text("# memory\n\nHet geheugen van Claude in de homelab-inbox (F-023): `voorkeuren/`, `feiten/` en "
                              "`geschiedenis/`, één bestand per item. Uitleg: `docs/memory.md` op `main`.\nDeze branch "
                              "wordt nooit gemerged; alleen de homelab-inbox schrijft erop (voor Claude en voor jou).\n")
        git(d, "add", "-A")
        if not git(d, "status", "--porcelain").strip():
            return
        git(d, "-c", f"user.name={author[0]}", "-c", f"user.email={author[1]}", "commit", "--quiet", "-m", message)
        # Alleen naar de geheugen-branch: de bestemming staat hier vast.
        git(d, "push", "--quiet", "origin", f"HEAD:refs/heads/{branch}")
        load_memory(fetch=False)


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
    """Wat Claude onder zijn laatste bericht bewaarde (GEHEUGEN:): voorkeuren en feiten, nooit geheimen."""
    repos, changes, notes = repo_list(), {}, []
    source = t["mem_source"] or label(t)
    link = t["mem_link"] or page_url(t["id"])
    for kind, about, name, body in blocks:
        folder = "voorkeuren" if kind == "voorkeur" else "feiten"
        if about not in repos and about != ("algemeen" if kind == "voorkeur" else "box"):
            notes.append(f"{name}: onbekend onderwerp {about}")
        elif not body or len(body) > MEM_MAX:
            notes.append(f"{name}: leeg of langer dan {MEM_MAX} tekens")
        elif SECRET_RE.search(body):
            notes.append(f"{name}: niet bewaard, het lijkt een geheim te bevatten")
        else:
            old = mem_get(f"{folder}/{about}/{name}")
            changes[f"{folder}/{about}/{name}"] = entry_text(about, source, link, body, old["added"] if old else now())
    if changes:
        save_memory(changes, f"geheugen: {', '.join(changes)} (Claude, {label(t).lower()}; bron: {source})",
                    CLAUDE_AUTHOR)
        notes.insert(0, f"{len(changes)} item(s) in het geheugen bewaard")
    return notes


def remember_task(task_id):
    """De geschiedenis van een afgeronde taak (of Claude-job) bijwerken; na een wis door de gebruiker niet opnieuw."""
    t = get(task_id)
    if not t or t["status"] not in MEM_FINAL:
        return
    mid = f"geschiedenis/{t['created'][:7]}/taak-{t['id']}"
    old = mem_get(mid)
    if t["remembered"] and not old:
        return
    asked = (JOBS.get(t["job"], {}).get("instruction") or f"job {t['job']}") if t["job"] else t["text"]
    result = t["answer"] or t["summary"] or t["question"] or t["note"] or ""
    body = f"# {title_of(t)}\n\nGevraagd:\n{asked[:1000]}\n\nResultaat:\n{result[:2000]}"
    text = entry_text(t["repo"], label(t), page_url(t["id"]), SECRET_RE.sub("[verborgen]", body),
                      old["added"] if old else t["created"], status=STATUS_NL[t["status"]], pr=t["pr_url"],
                      job=t["job"])
    save_memory({mid: text}, f"geheugen: geschiedenis {label(t).lower()} ({STATUS_NL[t['status']]})")
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
