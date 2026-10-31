"""GitHub polling: PR state, review comments, conflicts, CI and the inbox merging its own PRs."""
import os
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

from .config import CONF
from .helpers import gh, label, log_line, merges_itself, notify, refresh_cache, repo_list, repo_lock, task_cmd, title_of
from .memory import LEARN_HINT, remember_task
from .rounds import push_branch
from .storage import log, now, tasks, update


def poll_github():
    while True:
        time.sleep(int(CONF["GITHUB_POLL_SECONDS"]))
        merged = set()  # repos with a merge this cycle: one per repo, so GitHub rechecks the other PRs against main
        for t in [t for t in tasks() if t["status"] == "pr_open" and t["pr_number"]]:
            try:
                check_pr(t, merged)
            except Exception as e:
                log(f"PR van taak {t['id']}: {e}")


CI_WAIT = timedelta(minutes=30)


def ci_state(slug, sha, since):
    """'ok' (green), 'none' (the repo has no CI), 'pending' or 'failed' for a PR's head commit."""
    if not sha:
        return "none"
    runs = (gh("GET", f"/repos/{slug}/commits/{sha}/check-runs?per_page=100") or {}).get("check_runs") or []
    status = gh("GET", f"/repos/{slug}/commits/{sha}/status") or {}
    if not runs and (gh("GET", f"/repos/{slug}/actions/workflows") or {}).get("total_count"):
        runs = [{"status": "queued"}]  # the repo has CI that hasn't started for this commit yet
    pending = any(r.get("status") != "completed" for r in runs) or \
        (status.get("total_count") and status.get("state") == "pending")
    if pending:
        return "failed" if datetime.now(timezone.utc) - datetime.fromisoformat(since) > CI_WAIT else "pending"
    if any(r.get("conclusion") not in ("success", "neutral", "skipped") for r in runs) or \
            (status.get("total_count") and status.get("state") != "success"):
        return "failed"
    return "ok" if runs or status.get("total_count") else "none"


def pr_closed(t, merged):
    update(t["id"], status="merged" if merged else "closed", ended=now())
    task_cmd("cleanup", t["id"])
    remember_task(t["id"])


def auto_merge(t, slug, pr):
    sha = (pr.get("head") or {}).get("sha")
    try:
        state = ci_state(slug, sha, t["ended"] or t["created"])
    except urllib.error.HTTPError as e:  # e.g. a token without permission to read checks: say so, don't stay silent
        state = "unreadable"
        unreadable = f"GitHub refused to show the CI status ({e.code} on {e.url.split('api.github.com')[-1][:80]})"
    if state == "pending":
        return
    why = unreadable if state == "unreadable" else \
        "CI failed or didn't finish in 30 minutes" if state == "failed" else \
        "a criterion can only be confirmed by CI (🧪) and this repo has none" if state == "none" and t["review_ok"] == 2 \
        else "the PR is a draft" if pr.get("draft") else None
    if not why:
        try:
            gh("PUT", f"/repos/{slug}/pulls/{t['pr_number']}/merge",
               {"merge_method": "merge", **({"sha": sha} if sha else {}),
                "commit_title": f"Merge PR #{t['pr_number']}: {title_of(t)} (build without me)"})
        except urllib.error.HTTPError as e:
            why = f"GitHub refused the merge ({e.code}: {e.read().decode(errors='replace')[:150]})"
    if why:
        update(t["id"], review_ok=0, review=f"{t['review'] or ''}. Not merged: {why}"[:2000])
        log_line(f"t{t['id']}", "info", f"build without me: not merged: {why}")
        return notify(f"{label(t)}: PR #{t['pr_number']} waits for you", f"Build without me: {why}.", t["pr_url"])
    log_line(f"t{t['id']}", "info", f"build without me: merged PR #{t['pr_number']}")
    pr_closed(t, merged=True)
    notify(f"{label(t)}: PR #{t['pr_number']} merged",
           f"Build without me ({t['brief']}): every criterion met" + (", CI green" if state == "ok" else "")
           + ". To undo: revert the PR.", t["pr_url"])
    return True


def check_pr(t, merged=None):
    merged = set() if merged is None else merged
    slug = repo_list().get(t["repo"])
    if not slug:
        return
    pr = gh("GET", f"/repos/{slug}/pulls/{t['pr_number']}")
    if isinstance(pr, dict) and pr.get("state") == "closed":
        return pr_closed(t, bool(pr.get("merged")))
    since = t["last_comment_at"] or t["ended"] or t["created"]
    owner = os.environ.get("OWNER_LOGIN", CONF["OWNER_GITHUB"])
    comments = []
    for path in (f"/repos/{slug}/issues/{t['pr_number']}/comments", f"/repos/{slug}/pulls/{t['pr_number']}/comments",
                 f"/repos/{slug}/pulls/{t['pr_number']}/reviews"):
        for c in gh("GET", path + "?per_page=100") or []:
            when = (c.get("created_at") or c.get("submitted_at") or "").replace("Z", "+00:00")
            body = (c.get("body") or "").strip()
            # Alleen jouw commentaar telt; andere accounts worden genegeerd.
            if (c.get("user") or {}).get("login") == owner and when > since and body \
                    and "<!-- homelab-inbox" not in body:
                where = f" (bij {c['path']}:{c.get('line') or c.get('original_line')})" if c.get("path") else ""
                comments.append((when, f"{body}{where}", c.get("html_url") or t["pr_url"]))
    if comments:
        comments.sort()
        text = "Review-commentaar op de PR:\n\n" + "\n\n".join(f"- {b}" for _, b, _ in comments) + \
               "\n\nVerwerk dit op dezelfde branch en eindig weer met KLAAR: (of VRAAG:/GEWEIGERD:). " + LEARN_HINT
        update(t["id"], status="queued", pending_input=text, last_comment_at=comments[-1][0], ended=None,
               mem_source=f"jouw PR-commentaar op PR #{t['pr_number']} ({label(t).lower()})", mem_link=comments[0][2],
               review_ok=None)
        log(f"taak {t['id']}: {len(comments)} review-commentaar, terug in de rij")
    elif isinstance(pr, dict) and pr.get("mergeable") is False:
        on_conflict(t, slug, pr)
    elif isinstance(pr, dict) and merges_itself(t) and t["repo"] not in merged and pr.get("mergeable") is not None:
        if auto_merge(t, slug, pr):  # mergeable None: GitHub is still checking the PR against main
            merged.add(t["repo"])


def on_conflict(t, slug, pr):
    """The PR conflicts with main: merge main into the task's branch. A clean merge is pushed right away; conflicts
    send the task back to its own session with the conflicted files, at most CONFLICT_ROUNDS times."""
    sha = (pr.get("head") or {}).get("sha")
    if sha and sha == t["conflict_sha"]:
        return  # this head was already handled (or handed to you)
    update(t["id"], conflict_sha=sha)
    limit = int(CONF.get("CONFLICT_ROUNDS", 2))
    if (t["conflicts"] or 0) >= limit:
        log_line(f"t{t['id']}", "info", f"PR #{t['pr_number']} still conflicts after {limit} conflict round(s)")
        return notify(f"{label(t)}: PR #{t['pr_number']} conflicts with main",
                      f"Still conflicting after {limit} automatic round(s): resolve it yourself.", t["pr_url"])
    try:
        cache = refresh_cache(t["repo"], slug)
        with repo_lock(t["repo"]):
            code, out = task_cmd("sync", t["id"])
        if code == 0:
            push_branch(t["id"], cache)
            update(t["id"], ended=now())  # CI runs again on the new head: its 30-minute wait starts now
            return log_line(f"t{t['id']}", "info", f"PR #{t['pr_number']}: merged main into inbox/{t['id']}, "
                                                   "no conflicts")
        if code != 4:
            raise RuntimeError(out.strip()[-300:] or f"exit {code}")
    except Exception as e:
        log(f"taak {t['id']}: conflict: {e}")
        return notify(f"{label(t)}: PR #{t['pr_number']} conflicts with main",
                      f"Couldn't merge main into the branch ({e}): resolve it yourself."[:300], t["pr_url"])
    files = [f for f in out.splitlines() if f.strip()]
    update(t["id"], status="queued", ended=None, conflicts=(t["conflicts"] or 0) + 1, review_ok=None,
           mem_source=None, mem_link=None,
           pending_input=(f"PR #{t['pr_number']} conflicts with main: other work was merged into main after you made "
                          "it. `git merge origin/main` is already in progress in your working copy, with conflicts "
                          "in:\n\n" + "\n".join(f"- {f}" for f in files)
                          + "\n\nResolve them so that both your change and the one on main keep working, check that "
                          "it still builds and the tests pass, commit the merge on the same branch and end with KLAAR: "
                          "again (or VRAAG:/GEWEIGERD:)."))
    log_line(f"t{t['id']}", "info", f"PR #{t['pr_number']} conflicts with main in {len(files)} file(s): back in "
                                    "the queue")
