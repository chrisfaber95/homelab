"""Queue: the scheduler, run_round / finish_round, cancel, and recovery after a restart."""
import threading
import time
from collections import Counter

from .briefs import plan_task, review_pr, settle_briefs
from . import conversation
from .config import ACTIVE, AUTO, BRIEF, CONF, HOME, STATUS_NL, _cancel, _lock, _round_started
from .helpers import first_line, gh, git, kind_of, label, log_line, notify, page_url, pr_text, refresh_cache, repo_list, repo_lock, task_cmd, title_of
from .jobs import propose_skill, split_skill
from .memory import LEARN_HINT, after_round, load_memory, split_memory
from .rounds import push_branch, stop, stream_claude, write_prompt
from .router import revive_followups, route_task, settle_routed
from .storage import db, get, log, now, tasks, update


MERGE_MAIN_BY_HAND = ("    git -c safe.directory='*' fetch --upload-pack=\"git -c safe.directory='*' upload-pack\" "
                      "origin main\n    git merge origin/main")


def catch_up_main(t):
    """Merge main into the branch of a task that is about to continue without a PR.

    A task with a PR is covered: GitHub reports the conflict and github.on_conflict merges main in, with its own
    round limit. Without a PR nothing noticed, so a task that is retried or continued after its push failed carries
    on from the main it started with and conflicts with everything that landed since. The task itself cannot fix it:
    its origin is the service's copy, owned by another account, so a plain git fetch is refused.
    """
    if t["pr_number"]:
        return ""
    with repo_lock(t["repo"]):  # the copy of the service is read here and written by refresh_cache
        code, out = task_cmd("behind", t["id"])
        if code != 0:
            return ""  # 1: the branch already has main. 2: no working copy, so prepare made a fresh one from main.
        code, out = task_cmd("sync", t["id"])
    if code == 0:
        log_line(f"t{t['id']}", "info", f"merged main into inbox/{t['id']} before this round")
        return ("\n\nMain moved on since your last round and has been merged into your branch, so work of other "
                "tasks is in your working copy now. Build on it instead of doing it again.")
    if code == 4:
        files = [f for f in out.splitlines() if f.strip()]
        log_line(f"t{t['id']}", "info", f"main conflicts with inbox/{t['id']} in {len(files)} file(s)")
        return ("\n\nMain moved on since your last round, and `git merge origin/main` is in progress in your working "
                "copy with conflicts in:\n\n" + "\n".join(f"- {f}" for f in files)
                + "\n\nResolve them so that both your work and the work on main keep working, commit the merge on "
                  "the same branch, and then do what is asked above.")
    why = out.strip()[-200:] or f"exit {code}"
    log_line(f"t{t['id']}", "error", f"could not merge main into inbox/{t['id']}: {why}")
    return (f"\n\nMain moved on since your last round and merging it in here failed ({why}), so your working copy is "
            "older than main. Your origin is the service's copy and git refuses a repository of another account, so "
            "a plain git fetch does not work. Merge main yourself with:\n\n" + MERGE_MAIN_BY_HAND)


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
            extra = catch_up_main(t)
            if extra:
                prompt = (prompt or "") + extra
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
        notify(f"{label(t)} mislukt", str(e), page_url(task_id))
        return after_round(task_id, [])
    update(task_id, restarts=0)
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
            if not t["job"]:
                conversation.note("claude", "inbox", "answer", body, page_url(task_id), f"task:{task_id}")
            task_cmd("cleanup", task_id)
            if t["job"]:
                return  # een job meldt alleen wat de gebruiker nodig heeft; het resultaat staat op de pagina
            return notify(f"Inbox {task_id}: antwoord", body, page_url(task_id), store=False)
        if kind == "VRAAG" and not capped:
            update(task_id, status="waiting", question=body, note=note)
            conversation.note("claude", "inbox", "question", body, page_url(task_id), f"task:{task_id}")
            return notify(f"{label(t)}: vraag", body, page_url(task_id), store=False)
        if kind == "GEWEIGERD" and not capped:
            update(task_id, status="refused", summary=body, ended=now(), note=note)
            task_cmd("cleanup", task_id)
            return notify(f"{label(t)}: geweigerd", body, page_url(task_id))
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
            return notify(f"{label(t)} mislukt", (body or text), page_url(task_id))
        slug = repo_list()[t["repo"]]
        summary = body or text[:500]
        if t["pr_number"]:
            update(task_id, status="pr_open", summary=summary, ended=now(), note=note, review=None)
            if t["brief"]:  # H-001: the reviewer notifies (or sends it back once) instead
                return threading.Thread(target=review_pr, args=(task_id, cache), daemon=True).start()
            return notify(f"{label(t)}: PR #{t['pr_number']} bijgewerkt", summary, t["pr_url"])
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
        notify(f"{label(t)}: PR #{pr['number']}", summary, pr["html_url"])
    except Exception as e:
        log(f"taak {task_id} afronden: {e}")
        update(task_id, status="failed", ended=now(), note=f"afronden mislukt: {e}"[:500])
        notify(f"{label(t)} mislukt", str(e), page_url(task_id))


def answer_task(t, text, source=None):
    """Continue a task with the user's reply: an answer to Claude's question (waiting) or a reaction to its answer
    (answered). The page and chat clients (`source`, e.g. discord) both come through here. True when it was taken."""
    text = (text or "").strip()
    if not text or t["status"] not in ("waiting", "answered") or (t["status"] == "answered" and t["job"]):
        return False
    conversation.note("chris", source or "page", "message", text, page_url(t["id"]), f"task:{t['id']}")
    if t["status"] == "waiting":
        update(t["id"], status="queued", answer=None, pending_input=(
            f"Antwoord van de gebruiker op je vraag:\n\n{text[:8000]}\n\n"
            "Ga verder met de taak. Stel geen nieuwe vraag; neem waar nodig een redelijke aanname en noem die. "
            + LEARN_HINT), mem_source=f"jouw antwoord in {label(t).lower()}", mem_link=page_url(t["id"]))
    else:
        # Vervolg op een antwoord (bijvoorbeeld een plan met een vraag erin): zelfde sessie, nieuwe werkkopie.
        update(t["id"], status="queued", question=t["answer"], answer=None, ended=None, reprepare=1,
               pending_input=(f"Reactie van de gebruiker op je antwoord:\n\n{text[:8000]}\n\n"
                              "Ga verder met de taak. Neem waar nodig een redelijke aanname en noem die. "
                              + LEARN_HINT),
               mem_source=f"jouw reactie in {label(t).lower()}", mem_link=page_url(t["id"]))
    return True


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


RESTART_ROUNDS = 2  # rounds in a row a restart may cut off before the task fails (a task that crashes the inbox)
CONTINUE_HINT = ("Your working copy is as you left it: check `git status` and `git log` first, then continue where "
                 "you left off and end with KLAAR: (or VRAAG:/GEWEIGERD:).")


def recover():
    """After a restart: a Claude that still runs is stopped and its task goes back in the queue, where its own
    session continues; a brief task whose PR review was cut off is reviewed again."""
    for t in [t for t in tasks() if t["status"] == "running"]:
        stop(t["id"])
        if (t["restarts"] or 0) >= RESTART_ROUNDS:
            update(t["id"], status="failed", ended=now(), note="onderbroken door een herstart")
            continue
        # The router and planner start over; their pending_input would read as an answer to a question.
        update(t["id"], status="queued", restarts=(t["restarts"] or 0) + 1, pending_input=None if t["repo"] in (
            AUTO, BRIEF) else "The inbox restarted while you were working on this task, which cut off your last "
                              "turn. " + CONTINUE_HINT)
        log_line(f"t{t['id']}", "info", "interrupted by a restart of the inbox: back in the queue")
    for t in [t for t in tasks() if t["status"] == "pr_open" and t["brief"] and t["review"] is None]:
        threading.Thread(target=review_again, args=(t["id"],), daemon=True).start()


def review_again(task_id):
    t = get(task_id)
    try:
        review_pr(task_id, refresh_cache(t["repo"], repo_list()[t["repo"]]))
    except Exception as e:
        log(f"taak {task_id}: review after restart: {e}")


def pushed(t):
    """Whether an earlier round pushed commits to inbox/<id> (push_branch keeps them in the service's copy too)."""
    try:
        git(HOME / "repos" / t["repo"], "rev-parse", "--verify", "--quiet", f"refs/inbox/{t['id']}")
        return True
    except RuntimeError:
        return False


def retry_task(t, note=""):
    """Retry on the page: the task goes back in the queue (its own session, in its working copy if that's still
    there), with the tasks of its build or message that were cancelled because of it. note: what the user added for
    Claude on the page (optional), for example why a refused task is fine to do after all."""
    note = note.strip()[:8000]
    extra = f"\n\nNote from the user with this retry:\n\n{note}" if note else ""
    if t["status"] == "refused":
        extra = (f"\n\nYou refused it earlier with this reason:\n\n{t['summary'] or '(none given)'}\n\nDon't refuse "
                 "again for the same reason unless the user's note or the task gives you a new one." + extra)
    if t["rounds"]:
        code, _ = task_cmd("has", t["id"])
        fresh = code != 0
        why = t["note"] or STATUS_NL[t["status"]]
        if not fresh:
            hint = CONTINUE_HINT
        elif pushed(t):  # prepare restores the working copy from the commits pushed to inbox/<id>
            hint = (f"Your working copy was cleaned up and rebuilt from your commits on inbox/{t['id']}: uncommitted "
                    "work is gone. " + CONTINUE_HINT)
        else:
            hint = (f"Your working copy was cleaned up: this is a fresh copy of main on inbox/{t['id']}, so work "
                    "from earlier rounds that isn't on main is gone. Do the task again and end with KLAAR: (or "
                    "VRAAG:/GEWEIGERD:).")
        update(t["id"], status="queued", ended=None, note=None, restarts=0, accepted=0, reprepare=int(fresh),
               summary=None if t["status"] == "refused" else t["summary"],
               pending_input=f"This task stopped before it was finished ({why}) and the user asked to "
                             f"{'continue' if t['status'] == 'capped' else 'retry'} it. " + hint + extra)
    else:
        # never started: its first round reads the task text, not pending_input
        update(t["id"], status="queued", ended=None, note=None, accepted=0, text=t["text"] + extra)
    log_line(f"t{t['id']}", "info", "retried on the page: back in the queue")
    revive_followups(t)
