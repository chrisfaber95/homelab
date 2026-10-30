"""Queue: the scheduler, run_round / finish_round, cancel, and recovery after a restart."""
import threading
import time
from collections import Counter

from .briefs import plan_task, review_pr, settle_briefs
from .config import ACTIVE, AUTO, BRIEF, CONF, _cancel, _lock, _round_started
from .helpers import first_line, gh, kind_of, label, log_line, notify, page_url, pr_text, refresh_cache, repo_list, repo_lock, task_cmd, title_of
from .jobs import propose_skill, split_skill
from .memory import after_round, load_memory, split_memory
from .rounds import push_branch, stop, stream_claude, write_prompt
from .router import route_task, settle_routed
from .storage import db, get, log, now, tasks, update


def run_round(task_id):
    t = get(task_id)
    repos = repo_list()
    if t["repo"] == AUTO:
        return route_task(task_id)
    if t["repo"] == BRIEF:
        return plan_task(task_id)
    if t["repo"] not in repos:
        return update(task_id, status="failed", ended=now(), note="repo staat niet (meer) in de lijst")
    try:
        cache = refresh_cache(t["repo"], repos[t["repo"]])
        if t["repo"] != "homelab":
            refresh_cache("homelab", repos["homelab"])  # de skills komen uit main van homelab
        load_memory()  # wat de gebruiker net wiste of veranderde, geldt al voor deze ronde
        first = t["rounds"] == 0
        if first or t["reprepare"]:
            with repo_lock(t["repo"]):
                code, out = task_cmd("prepare", task_id, t["repo"])
            if code != 0:
                raise RuntimeError(f"werkkopie maken mislukt: {out[-300:]}")
        if first:
            prompt = f"Taak voor de repo {t['repo']}:\n\n{t['text']}"
        else:
            prompt = t["pending_input"]
        write_prompt(t, prompt)
        update(task_id, status="running", started=t["started"] or now(), rounds=t["rounds"] + 1, pending_input=None,
               reprepare=0)
        _round_started[task_id] = now()
        log_line(f"t{task_id}", "info", f"ronde {t['rounds'] + 1} gestart" + (
            "" if first else ": " + first_line(prompt)))
        result, turns, capped = stream_claude(task_id, "new" if first else "resume")
    except Exception as e:
        log(f"taak {task_id}: {e}")
        update(task_id, status="failed", ended=now(), note=str(e)[:500])
        if not t["pr_number"]:
            task_cmd("cleanup", task_id)
        notify(f"{label(t)} mislukt", str(e)[:300], page_url(task_id))
        return after_round(task_id, [])
    text, blocks = split_memory((result or {}).get("result") or "")
    try:
        finish_round(task_id, cache, text, result, capped)
    finally:
        after_round(task_id, blocks if capped is None else [])


def finish_round(task_id, cache, text, result, capped):
    t = get(task_id)
    text, proposal = split_skill(text)
    if proposal and kind_of(text) in ("KLAAR", "ANTWOORD") and not capped:
        threading.Thread(target=propose_skill, args=(t, proposal), daemon=True).start()
    kind, _, body = text.strip().partition(":")
    kind, body = kind.strip().upper(), body.strip()
    denials = len((result or {}).get("permission_denials") or [])
    note = f"{denials} actie(s) geweigerd door auto mode" if denials else None
    try:
        if capped == "geannuleerd":
            update(task_id, status="cancelled", ended=now(), note=note)
            if not t["pr_number"]:
                task_cmd("cleanup", task_id)
            return
        if kind == "ANTWOORD" and not capped:
            # Een vraag maakt geen commit, branch of PR; eventuele commits blijven lokaal en worden weggegooid.
            update(task_id, status="answered", answer=body, ended=now(), note=note)
            task_cmd("cleanup", task_id)
            if t["job"]:
                return  # een job meldt alleen wat de gebruiker nodig heeft; het resultaat staat op de pagina
            return notify(f"Inbox {task_id}: antwoord", body[:300], page_url(task_id))
        if kind == "VRAAG" and not capped:
            update(task_id, status="waiting", question=body, note=note)
            return notify(f"{label(t)}: vraag", body[:300], page_url(task_id))
        if kind == "GEWEIGERD" and not capped:
            update(task_id, status="refused", summary=body, ended=now(), note=note)
            task_cmd("cleanup", task_id)
            return notify(f"{label(t)}: geweigerd", body[:300], page_url(task_id))
        commits = push_branch(task_id, cache)
        if capped:
            if not t["pr_number"]:
                task_cmd("cleanup", task_id)  # de commits staan (als die er zijn) op GitHub in inbox/<id>
            update(task_id, status="capped", ended=now(),
                   note=f"gestopt bij {capped}" + (f"; branch inbox/{task_id} gepusht" if commits else "")
                   + (f"; {note}" if note else ""))
            return notify(f"{label(t)}: gestopt bij {capped}",
                          f"{len(commits)} commit(s) op inbox/{task_id}" if commits else "geen commits",
                          page_url(task_id))
        if not commits:
            if not t["pr_number"]:
                task_cmd("cleanup", task_id)
            update(task_id, status="failed", ended=now(), summary=body or text[:500],
                   note="Claude gaf geen KLAAR/ANTWOORD/VRAAG en maakte geen commits" + (f"; {note}" if note else ""))
            return notify(f"{label(t)} mislukt", (body or text)[:300], page_url(task_id))
        slug = repo_list()[t["repo"]]
        summary = body or text[:500]
        if t["pr_number"]:
            update(task_id, status="pr_open", summary=summary, ended=now(), note=note, review=None)
            if t["brief"]:  # H-001: the reviewer notifies (or sends it back once) instead
                return threading.Thread(target=review_pr, args=(task_id, cache), daemon=True).start()
            return notify(f"{label(t)}: PR #{t['pr_number']} bijgewerkt", summary[:300], t["pr_url"])
        pr = gh("POST", f"/repos/{slug}/pulls", {
            "title": f"{title_of(t)}"[:100], "head": f"inbox/{task_id}", "base": "main",
            "body": f"{summary}\n\n**Taak** (via de homelab-inbox, #{task_id}):\n> " + pr_text(t).replace("\n", "\n> ")
                    + "\n\nCommits:\n" + "\n".join(f"- {c}" for c in commits)
                    + "\n\nReview-commentaar van jou stuurt de taak terug naar de inbox."
                    + f"\n\n<!-- homelab-inbox task={task_id} -->"})
        update(task_id, status="pr_open", summary=summary, pr_number=pr["number"], pr_url=pr["html_url"],
               ended=now(), last_comment_at=now(), note=note, review=None)
        if t["brief"]:
            return threading.Thread(target=review_pr, args=(task_id, cache), daemon=True).start()
        notify(f"{label(t)}: PR #{pr['number']}", summary[:300], pr["html_url"])
    except Exception as e:
        log(f"taak {task_id} afronden: {e}")
        update(task_id, status="failed", ended=now(), note=f"afronden mislukt: {e}"[:500])
        notify(f"{label(t)} mislukt", str(e)[:300], page_url(task_id))


def cancel_task(t):
    with _lock:
        if t["status"] == "running":
            _cancel.add(t["id"])
            threading.Thread(target=stop, args=(t["id"],), daemon=True).start()
        else:
            update(t["id"], status="cancelled", ended=now())
            if t["status"] == "waiting" and not t["pr_number"]:
                threading.Thread(target=task_cmd, args=("cleanup", t["id"]), daemon=True).start()
            threading.Thread(target=after_round, args=(t["id"], []), daemon=True).start()


def scheduler():
    while True:
        try:
            settle_routed()  # buiten het slot: het bewaart de geschiedenis (git)
            settle_briefs()
            with _lock:
                running = [t for t in tasks() if t["status"] == "running"]
                per_repo = Counter(t["repo"] for t in running)
                all_tasks = tasks()
                by_id = {t["id"]: t for t in all_tasks}
                for t in reversed([t for t in all_tasks if t["status"] == "queued"]):
                    if len(running) >= int(CONF["MAX_RUNNING"]):
                        break
                    if per_repo[t["repo"]] >= int(CONF.get("MAX_PER_REPO", 1)) and t["repo"] not in (AUTO, BRIEF):
                        continue  # each task has its own working copy; the router is short and touches no repo
                    pre = by_id.get(t["after_task"], {"status": "queued"})["status"] if t["after_task"] else None
                    if pre in ACTIVE + ("waiting",) or (t["brief"] and pre == "pr_open"):
                        continue  # wacht op zijn voorganger (H-001: a brief task starts only after its PR is merged)
                    update(t["id"], status="running")
                    running.append(t)
                    per_repo[t["repo"]] += 1
                    threading.Thread(target=run_round, args=(t["id"],), daemon=True).start()
        except Exception as e:
            log(f"wachtrij: {e}")
        time.sleep(3)


def recover():
    """Na een herstart: wat liep is onderbroken (en een Claude die nog loopt, wordt gestopt)."""
    for t in [t for t in tasks() if t["status"] == "running"]:
        stop(t["id"])
    with db() as c:
        c.execute("UPDATE tasks SET status='failed', ended=?, note='onderbroken door een herstart' "
                  "WHERE status='running'", (now(),))
