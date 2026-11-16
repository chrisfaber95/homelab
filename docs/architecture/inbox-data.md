# Inbox data inventory (slice C1)

Part of `docs/architecture/inbox-split.md`. This is what the Python inbox stores today, read from the code on
2026-11-16 (`bin/homelab_inbox/`), before anything moves. Nothing here is a proposal: types and constraints are
exactly those the code creates. The route side of the contract is `backend/openapi.yaml`.

`RUNNER_HOME` = `/var/lib/homelab-inbox` (`config/inbox.conf`, overridable with `INBOX_RUNNER_HOME`; directory
`2750 inbox-runner:inbox-share`). Everything below, unless it says otherwise, lives there and is written by the inbox
service (account `inbox-runner`, one process, many threads). "Writer" means the module and function that executes the
write; `storage.update` and `jobs.update_run` are the generic `UPDATE` helpers, so the writers below name their
callers.

## SQLite: `inbox.db`

One file, opened per call by `storage.db()` (`sqlite3.connect(DB, timeout=10)`, `row_factory = sqlite3.Row`, default
journal mode, no `PRAGMA`, **no foreign keys, no indexes besides the primary keys**). `db()` creates and upgrades the
schema on every call: `CREATE TABLE IF NOT EXISTS`, then `ALTER TABLE ... ADD COLUMN` for each column missing from
`PRAGMA table_info`. The tables `conversation`, `conversation_refusals` (`conversation._init`) and `approvals`
(`chat._init`) are created lazily by their own module the first time it touches them. Writers of one process
serialise through the re-entrant `config._lock` where they hold it (`storage.update`, `new_task`, `jobs.kv_set`,
`jobs.dispatch`, `jobs.start_draft`, ...); some writers (`chat.approval_*`, `conversation.append`,
`conversation.set_switch`, `jobs.start_claude`) use only SQLite's own locking.

Timestamps are ISO 8601 UTC strings from `storage.now()` (`2026-11-16T09:30:00+00:00`, seconds) unless a column says
otherwise. Booleans are `0/1` integers.

### `tasks`

One row per task, brief build, routed message, Claude job run and research run. No foreign keys: `parent`, `after_task`
and `run_id` are plain integers.

| Column | Type / constraints | Meaning | Written by |
|---|---|---|---|
| `id` | `INTEGER PRIMARY KEY` | task id (also the working copy `work/<id>` and branch `inbox/<id>`) | SQLite |
| `repo` | `TEXT NOT NULL` | repo name, or the pseudo-repos `auto` (router splits the message) and `brief` (planner splits a build) | INSERT: `storage.new_task`, `briefs.start_build`, `briefs.plan_task`, `router.route_task`, `jobs.start_claude` |
| `text` | `TEXT NOT NULL` | the task text (cut to 8000 on create) | INSERT as above; `queue.retry_task` appends a retry note |
| `status` | `TEXT NOT NULL` | `queued running waiting pr_open answered capped cancelled failed refused merged closed routed` (`config.STATUS_NL`) | INSERT `queued`; `queue.run_round`, `queue.finish_round`, `queue.finish_research`, `queue.answer_task`, `queue.cancel_task`, `queue.scheduler`, `queue.recover`, `queue.retry_task`, `briefs.plan_task`, `briefs.review_pr`, `router.route_task`, `router.revive_followups`, `router.settle_routed`, `github.pr_closed`, `github.check_pr`, `github.on_conflict`, `server.Handler.do_POST` (cancel) |
| `created` | `TEXT NOT NULL` | creation time | INSERT only |
| `started` | `TEXT` | first start | `queue.run_round`, `briefs.plan_task`, `router.route_task` |
| `ended` | `TEXT` | end of the last round, or cleared (NULL) when it comes back | `queue.*`, `briefs.*`, `router.*`, `github.*` (same callers as `status`) |
| `session` | `TEXT NOT NULL` | Claude session id, `uuid4` | INSERT only |
| `rounds` | `INTEGER DEFAULT 0` | rounds run | `queue.run_round`, `briefs.plan_task`, `router.route_task` |
| `pending_input` | `TEXT` | input for the next round (answer, PR comment, "you were cut off") | `queue.run_round` (clears), `queue.answer_task`, `queue.recover`, `queue.retry_task`, `briefs.review_pr`, `router.revive_followups`, `github.check_pr`, `github.on_conflict` |
| `question` | `TEXT` | the question while `waiting` | `queue.finish_round`, `queue.answer_task`, `briefs.plan_task`, `router.route_task` |
| `answer` | `TEXT` | the answer (a question task) or the user's reply | `queue.finish_round`, `queue.finish_research`, `queue.answer_task` |
| `summary` | `TEXT` | outcome text / PR summary | `queue.finish_round`, `queue.finish_research`, `queue.retry_task`, `router.settle_routed` |
| `pr_number` | `INTEGER` | PR number | `queue.finish_round` |
| `pr_url` | `TEXT` | PR URL | `queue.finish_round` |
| `last_comment_at` | `TEXT` | newest PR comment seen (GitHub's timestamp) | `queue.finish_round`, `github.check_pr` |
| `turns` | `INTEGER DEFAULT 0` | Claude turns of the last round | `rounds.stream_claude` |
| `note` | `TEXT` | short status note | `queue.run_round`, `queue.finish_round`, `queue.finish_research`, `queue.recover`, `queue.retry_task`, `briefs.plan_task`, `router.*`, `memory.after_round`; one-off upgrade in `storage.db` |
| `job` | `TEXT` (added) | job name for a Claude job run | INSERT in `jobs.start_claude` |
| `run_id` | `INTEGER` (added) | the `job_runs.id` of that run | INSERT in `jobs.start_claude` |
| `mem_source` | `TEXT` (added) | where what Claude learns this round comes from | `queue.answer_task`, `briefs.review_pr` (clears), `github.check_pr`, `github.on_conflict` |
| `mem_link` | `TEXT` (added) | link for that source | same as `mem_source` |
| `remembered` | `INTEGER DEFAULT 0` (added) | history entry written | `memory.remember_task` |
| `model` | `TEXT` (added) | model of the last round | `rounds.stream_claude` |
| `reprepare` | `INTEGER DEFAULT 0` (added) | next round starts with a fresh working copy | `queue.run_round`, `queue.answer_task`, `queue.sweep_capped`, `queue.retry_task`, `router.revive_followups` |
| `parent` | `INTEGER` (added) | build or routed message this is a child of | INSERT in `briefs.plan_task`, `router.route_task` |
| `after_task` | `INTEGER` (added) | task that must be merged first | INSERT in `briefs.plan_task`, `router.route_task` |
| `reason` | `TEXT` (added) | why a child exists | INSERT in `briefs.plan_task`, `router.route_task` |
| `brief` | `TEXT` (added) | brief id of a build and its children | INSERT in `briefs.start_build`, `briefs.plan_task` |
| `criteria` | `TEXT` (added) | JSON list of acceptance criteria of a child | INSERT in `briefs.plan_task` |
| `reviews` | `INTEGER DEFAULT 0` (added) | automatic rework rounds used | `briefs.review_pr` |
| `review` | `TEXT` (added) | the reviewer's last verdict (also "Not merged: ...") | `queue.finish_round`, `briefs.review_pr`, `github.auto_merge` |
| `handoff` | `TEXT` (added) | handed-off PR URL, or `failed: ...` | `briefs.handoff`, `briefs.settle_briefs` |
| `unattended` | `INTEGER DEFAULT 0` (added) | build without me: the inbox merges the PRs | INSERT in `briefs.start_build`; `server.Handler.do_POST` (`attended`) |
| `review_ok` | `INTEGER` (added) | `1` all criteria pass, `2` pass but CI needed, `0` not, NULL unknown | `briefs.review_pr`, `github.auto_merge`, `github.check_pr`, `github.on_conflict` |
| `conflicts` | `INTEGER DEFAULT 0` (added) | automatic conflict rounds used | `github.on_conflict` |
| `conflict_sha` | `TEXT` (added) | PR head last handled for a conflict | `github.on_conflict` |
| `restarts` | `INTEGER DEFAULT 0` (added) | rounds in a row cut off by an inbox restart | `queue.run_round` (reset), `queue.recover`, `queue.retry_task` |
| `accepted` | `INTEGER DEFAULT 0` (added) | the user marked it fine, follow-ups may run | `router.accept_task`, `queue.retry_task` (reset) |
| `no_auto_revive` | `INTEGER DEFAULT 0` (added) | cancelled before follow-ups could come back by themselves | only the one-off `UPDATE` in `storage.db` when the column is first added; nothing sets it afterwards |
| `source` | `TEXT` (added) | client the task came from (`discord`, `kitt`); NULL = the homelab page | INSERT: `storage.new_task` (from `chat.start_task`), `briefs.start_build` |

### `job_runs`

One row per run of a job (scheduled, manual, research).

| Column | Type / constraints | Meaning | Written by |
|---|---|---|---|
| `id` | `INTEGER PRIMARY KEY` | run id (log key `r<id>`) | SQLite |
| `job` | `TEXT NOT NULL` | job name (`research` for research) | INSERT: `jobs.dispatch`, `jobs.ask_research` |
| `kind` | `TEXT` | job kind (`script`, `local`, `claude`, `research`, ...) | INSERT, same |
| `model` | `TEXT` | model name | INSERT, same |
| `due` | `TEXT` | the moment it was due | INSERT, same |
| `created` | `TEXT NOT NULL` | creation | INSERT, same |
| `started` | `TEXT` | start of a local run | `jobs.run_local` |
| `ended` | `TEXT` | end | `jobs.run_local`, `jobs.run_job`, `jobs.release_held`, `server.Handler.cancel_run` |
| `dispatched` | `TEXT` | when it was handed to Claude | `jobs.start_claude` |
| `status` | `TEXT NOT NULL` | `queued running ok failed cancelled claude held` | INSERT `queued`; `jobs.run_local`, `jobs.run_job`, `jobs.start_claude`, `jobs.release_held`, `server.Handler.cancel_run` |
| `summary` | `TEXT` | result text | `jobs.run_local` |
| `note` | `TEXT` | note or error | `jobs.run_local`, `jobs.run_job`, `jobs.start_claude`, `jobs.release_held`, `server.Handler.cancel_run` |
| `task_id` | `INTEGER` | the `tasks.id` of a Claude run | `jobs.start_claude` |
| `manual` | `INTEGER DEFAULT 0` | started by hand | INSERT: `jobs.dispatch`, `jobs.ask_research` |
| `minutes` | `INTEGER` (added by `ALTER`) | minutes saved, set when it starts | INSERT in `jobs.dispatch` |
| `mode` | `TEXT` (added) | research mode (`quick`/`deep`) | INSERT: `jobs.dispatch`, `jobs.ask_research` |
| `deliver` | `TEXT` (added) | where the report goes (`page`, `conversation`) | INSERT in `jobs.ask_research` |
| `source` | `TEXT` (added) | client it came from, NULL = page or schedule | INSERT in `jobs.ask_research` |

`jobs.start_claude` also `DELETE`s the new run row when the daily Claude cap is reached and an older run of the same job is already `held` (at most one held run per job).

### `job_state`

| Column | Type / constraints | Meaning | Written by |
|---|---|---|---|
| `name` | `TEXT PRIMARY KEY` | job name | `jobs.tick` (`INSERT OR REPLACE`) |
| `last_due` | `TEXT NOT NULL` | last due moment handled | `jobs.tick` |

### `drafts`

| Column | Type / constraints | Meaning | Written by |
|---|---|---|---|
| `id` | `INTEGER PRIMARY KEY` | draft id (share dir `d<id>`) | SQLite |
| `created` | `TEXT NOT NULL` | | INSERT: `jobs.start_draft`, `jobs.switch_on_agent` |
| `request` | `TEXT NOT NULL` | what the user asked | INSERT, same |
| `base` | `TEXT` | job a change is based on | INSERT in `jobs.start_draft` |
| `status` | `TEXT NOT NULL` | `drafting ready failed confirmed cancelled` | INSERT; `jobs.make_draft`, `server.Handler.post_jobs` via `jobs.update_draft` |
| `text` | `TEXT` | the job file text | INSERT in `jobs.switch_on_agent`; `jobs.make_draft`, `server.Handler.post_jobs` |
| `error` | `TEXT` | failure message | `jobs.make_draft` |

### `skill_prs`

| Column | Type / constraints | Meaning | Written by |
|---|---|---|---|
| `id` | `INTEGER PRIMARY KEY` | | SQLite |
| `name` | `TEXT NOT NULL` | skill name | `jobs.propose_skill` (INSERT) |
| `task_id` | `INTEGER` | task that proposed it | same |
| `pr_url` | `TEXT` | the PR | same |
| `created` | `TEXT NOT NULL` | | same |

### `kv`

| Column | Type / constraints | Meaning | Written by |
|---|---|---|---|
| `key` | `TEXT PRIMARY KEY` | | |
| `value` | `TEXT` | | |

Keys in use: `switch:discord`, `switch:discord_approvals`, `switch:kitt` (`on`/`off`; a missing row means on;
`conversation.set_switch`, from `POST /discord/<name>/<on|off>`) and `cap_notified` (start of the local day on which
the Claude daily cap was announced; `jobs.kv_set` from `jobs.start_claude`).

### `conversation`

| Column | Type / constraints | Meaning | Written by |
|---|---|---|---|
| `id` | `INTEGER PRIMARY KEY AUTOINCREMENT` | entry id, the clients' cursor | |
| `time` | `TEXT NOT NULL` | | `conversation.append` (INSERT only; entries are never changed or deleted) |
| `author` | `TEXT NOT NULL` | `chris claude box system` | same |
| `client` | `TEXT NOT NULL` | `discord kitt page inbox ...` | same |
| `kind` | `TEXT NOT NULL` | `message question answer notification approval_request approval` | same |
| `text` | `TEXT NOT NULL` | scrubbed with `SECRET_RE`, cut to 20000 | same |
| `link` | `TEXT` | | same |
| `ref` | `TEXT` | `task:<id>`, `job:<name>`, `brief:<id>`, `job:research:<run>` | same |

Callers of `conversation.append/note`: `chat.*`, `kitt.send`, `kitt.answer_question`, `kitt.job_action`,
`server.Handler.do_POST` (tasks, briefs), `helpers.notify`, `queue`, `jobs` (delivery) and others; the only SQL is in
`conversation.append`.

### `conversation_refusals`

| Column | Type / constraints | Meaning | Written by |
|---|---|---|---|
| `id` | `INTEGER PRIMARY KEY AUTOINCREMENT` | | |
| `time` | `TEXT NOT NULL` | | `conversation.allowed` (INSERT only) |
| `client` | `TEXT NOT NULL` | cut to 40 | same |
| `action` | `TEXT NOT NULL` | cut to 40 | same |
| `why` | `TEXT NOT NULL` | | same |

### `approvals`

Two-step approval tokens of the chat door and kitt (`chat.py`).

| Column | Type / constraints | Meaning | Written by |
|---|---|---|---|
| `token` | `TEXT PRIMARY KEY` | `secrets.token_urlsafe(18)`, given only to the asking client | `chat.approval_request` (INSERT) |
| `client` | `TEXT NOT NULL` | | same |
| `brief` | `TEXT NOT NULL` | brief id | same |
| `mode` | `TEXT NOT NULL` | `build` or `without-me` | same |
| `created` | `TEXT NOT NULL` | | same |
| `expires` | `REAL NOT NULL` | Unix time (`time.time() + CONFIRM_TTL_SECONDS`), **not** an ISO string | same |
| `status` | `TEXT NOT NULL` | `pending used failed` | INSERT `pending`; `chat.approval_confirm` |
| `task_id` | `INTEGER` | the build started | `chat.approval_confirm` |

## File stores under `/var/lib/homelab-inbox`

| Path | What | Format | Writers | Notes |
|---|---|---|---|---|
| `inbox.db` | the database above | SQLite | `storage.db` and the writers listed per table | the only store that is read by all modules |
| `csrf-secret` | secret of the page's CSRF token | 64 hex chars, mode `0600`, created on first use | `pages.secret` (the function behind `csrf_token`) | read by every page render and every POST check |
| `new-repos.json` | repos of new projects added when a brief is approved: `{name: "owner/name"}` | JSON, `indent=1` | `briefs.add_new_repo` (via `start_build`) | read by `helpers.repo_list` |
| `logs/<key>.log` | live log per task (`t<id>`) and per job run (`r<id>`) | JSON lines `{"at","kind","text"}`, text cut to 600, secrets replaced by `[verborgen]` | `helpers.log_line`, called from `queue`, `rounds.stream_claude`, `briefs`, `router`, `github`, `jobs` (`run_local`, `deliver_result`), `server` | append only; **never deleted or rotated**; read by `helpers.read_log` (page, `/api/log/*`, kitt) |
| `share/<id>/prompt.txt`, `system.txt` | prompt and system prompt of a Claude round, readable by `inbox` through group `inbox-share` (dir `2750`, files `0640`) | text | `rounds.write_prompt` (task `<id>`), `memory.memory_for` (`share/<id>/geschiedenis/*.md`, copies of the history entries; removed again by `memory.after_round`) | `share/<id>/` is **not removed** after a task |
| `share/r<id>/`, `share/v<id>/`, `share/d<id>/` | the same for the router, the planner or build reviewer, and the job drafter | text | `router.route_task`, `briefs.plan_task` (`r<id>`), `briefs.review_pr` (`v<id>`), `jobs.make_draft` (`d<id>`) | removed after the call (`shutil.rmtree`, `jobs.make_draft` removes its files) |
| `repos/<name>/` | the service's own clone of each repo (origin of the working copies; refs `refs/inbox/<id>` hold pushed task commits) | git | `helpers.refresh_cache` (clone, `fetch`, `checkout -B main`), `rounds.push_branch` (`fetch` of the bundle into `refs/inbox/<id>`), `helpers.repo_lock` serialises | the `homelab` clone also supplies `skills/` and `agents/` (read by `jobs.skill_list`, `jobs.load_agents`) |
| `memory-repo/` | git clone of branch `memory` of `DATA_REPO` (entries `voorkeuren/`, `feiten/`, `geschiedenis/`, `README.md`) | markdown files with a JSON-quoted front matter | `memory.save_memory` (called by `memory.save_learned`, `memory.remember_task`, `server.Handler.post_memory`), `helpers.sync_branch` (reset to GitHub) | each change is one commit pushed with the service's token |
| `jobs-repo/` | git clone of branch `jobs` of `DATA_REPO` (`jobs/<name>.md`, `README.md`) | markdown | `jobs.save_job` (called by `server.Handler.post_jobs`, `kitt.job_action`), `helpers.sync_branch` | read by `jobs.load_jobs` |
| `tmp*/` (temporary) | scratch clone for a skill proposal | git | `jobs.propose_skill` (`tempfile.TemporaryDirectory(dir=HOME)`) | removed at the end of the call |
| `fake_github.json` | canned GitHub answers | JSON | tests only (`INBOX_FAKE_GITHUB=1`); read by `helpers.gh` | does not exist on the box |

Also written by the inbox, but not under `RUNNER_HOME`:

| Path | Writer | Notes |
|---|---|---|
| product repo clone `repos/product/` (a normal `repos/<name>` clone) and its branch `handoff/<brief id>` | `briefs.set_brief_status` (approve/park commit to `main`), `briefs.handoff` (handoff branch and PR) | the brief files are the source of `briefs.load_briefs`, cached 60 s in memory |
| GitHub (PRs, merges, comments), ntfy | `helpers.gh`, `helpers.notify`, `github.*`, `briefs.*`, `jobs.propose_skill` | outward only |

Adjacent stores that belong to the other account (`inbox`, home `TASK_HOME` = `/var/lib/inbox`), written by
`bin/homelab-inbox-task`, not by Python modules: working copies `work/<id>` (mode `0700`), bundles `out/<id>.bundle`
(read by `rounds.push_branch`, which runs as `inbox-runner`), the Claude token.

Not stored, and lost on a restart: `config._procs`, `_cancel`, `_cancel_runs`, `_round_started`, the in-memory caches
(`briefs._briefs_cache`, `forge._last`, `pages.Access.cache`, `memory.MEMORY`, `config.JOBS`). `queue.recover` rebuilds
what matters from `tasks`.

Config files read from the repo checkout (`config/*.conf`, `config/clients.json`, `config/services.conf`) and the chat
tokens in `/etc/homelab/chat-<client>-token` are inputs, not data of the inbox.
