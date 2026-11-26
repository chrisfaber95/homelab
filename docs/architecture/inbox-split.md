# Inbox split: TypeScript frontend, Spring Boot backend, Python runners

Status: **plan, not built.** Written by the `architect` agent role for task 167 (message #166), made concrete enough to
cut into briefs in task 169. Nothing here changes the running inbox yet; nothing of `frontend/`, `backend/` or
`runner/` exists. Each slice in "Migration slices" is its own PR. Open decisions are at the end, each with the slices
it blocks.

## Goal

Today `homelab-inbox` is one Python process (`bin/homelab_inbox/`, 5,565 lines by `wc -l` on 2026-11-16) that serves
the API, renders every page as HTML strings (`pages.py`, 886 lines, plus `web/app.js` and `web/app.css`), runs the
queue and talks to GitHub. The frontend therefore lives inside the Python backend. Target:

| Part | Language | Owns |
|---|---|---|
| `frontend/` | TypeScript (Vite, no UI framework) | every page and all UI logic; talks to the backend only through the JSON API |
| `backend/` | Java 21, Spring Boot, Maven | HTTP and the REST API, auth and the gate, queue and scheduler, the PostgreSQL database, GitHub polling, memory and jobs bookkeeping; **starts** the Python runners |
| `runner/` | Python | everything that runs Claude Code, the local model, search or the router; one script per job, started by the backend |

Non-goals: changing what the inbox does for the user, the `inbox`/`inbox-runner` account split, the task states in
`docs/inbox.md`, the Discord bot or kitt's behaviour (they keep their contracts), or moving `llm/` and `search/` out
of Python. No schema redesign during the split.

Fixed rules that do not move (`CLAUDE.md`): nothing reachable from the internet; services only via Tailscale with a
ufw rule on `tailscale0`; secrets in `/etc/homelab`; everything rebuildable from the repo (scripts with a
`# homelab-rerun:` line, `changes/`, `config/machine.conf`); merges to `main` of homelab are the user's; inbox tasks run
in auto mode.

## What this plan covered before, and what it did not

Covered (task 167, kept): the target split and module map, the frontend and runner contracts in outline, PostgreSQL
with Flyway and a one-off import, one writer per table, Maven, Vite + plain TypeScript, the strangler order, deploy as
`inbox-runner`.

Not covered until now, added below:

- **How requests reach the new backend before cut-over.** The old text ran Java "on a spare port" but nothing sent
  traffic there. Added: a forwarding facade in the Python server (decision Q6).
- **How the loopback backend is protected** from other local accounts (`claude`, `deploy`, app units). Added: a
  facade secret plus loopback-only bind.
- **Stores that are not tables**: the git clones `memory-repo` and `jobs-repo`, `logs/`, `share/`, `repos/`,
  `csrf-secret`, `new-repos.json` (see "How the inbox runs now"). They need one writer each, like tables.
- **In-memory state** (`_procs`, `_cancel`, `_cancel_runs`, `_round_started` in `config.py`) cannot be handed over;
  the queue switch must happen with nothing running.
- **Building as root.** The rerun scripts run as root from root's clone; `npm ci` and Maven run third-party code. Where
  and as whom the builds run is an open decision (Q3).
- **What Claude can check by itself.** Claude (account `claude`) cannot read `/var/lib/homelab-inbox` or the journals,
  and the inbox answers 403 to the box itself. Each slice below therefore says how it is checked: tests in a dev clone
  (`INBOX_ALLOW_ALL=1`, temporary homes), the self-checks of the rerun script, a loopback health endpoint, or the user.
- **The runner layer is thin.** Every Claude call already goes through `bin/homelab-inbox-task` (bash, via sudo); the
  local model is HTTP (`LLM_URL`). `runner/` only adds the Claude-specific parsing that now lives in-process (see
  "Backend ↔ Python runners").
- **Corrections to the earlier text** (checked in the code): port 8104 is `homelab-overview-web`, a separate static unit
  from `scripts/20-monitor.sh`, not part of the inbox, so the cut-over does not "take" it. And `inbox-runner` cannot
  signal processes of `inbox`, so "kill the process group" is replaced by the `kill` action plus a signal to the
  `sudo` child, which sudo relays.
- **Docs that disagree with the code**: `docs/inbox.md` ("Beheer") says a task running during a restart ends as
  "mislukt"; `queue.recover` puts it back in the queue and fails it only after `RESTART_ROUNDS` (2) restarts in a row
  (the "Restarts" section of the same file is correct). To fix in a docs slice; this task does not touch that file.

## How the inbox runs now (facts from the code)

| What | Fact | Where |
|---|---|---|
| Service | `homelab-inbox.service`, `User=inbox-runner`, `SupplementaryGroups=inbox-share`, `UMask=0027`, `ExecStart=/usr/bin/python3 /usr/local/lib/homelab/bin/homelab-inbox serve`, `Restart=always`, `RestartSec=5` | `scripts/21-inbox.sh` |
| Threads in one process | HTTP server (`ThreadingHTTPServer`, binds `::` with V6ONLY off, port `INBOX_PORT`=8105), queue `scheduler` (every 3 s), `poll_github` (`GITHUB_POLL_SECONDS`=60), `job_scheduler`, the kitt gate (`gate.start`), one thread per running round | `server.serve`, `queue.scheduler` |
| Start order | create `RUNNER_HOME`, open the DB (schema created and upgraded by `storage.db()` with `ALTER TABLE ADD COLUMN`), `recover()`, `load_memory()`, `Access.init()` (`tailscale status`), gate, threads, serve | `server.serve` |
| Access to pages | `tailscale whois` per client IP (cached 300 s); only the tailnet owner's login; the box's own addresses and loopback get 403; `INBOX_ALLOW_ALL=1` for tests | `pages.Access` |
| Doors on loopback | chat door `/api/chat/entries|state|action` (bearer token per client, `config/clients.json`); kitt door `/api/clients/kitt`, `/api/conversation`, `/api/logs*`, `/api/approvals*`, `/api/jobs*`, `/api/health`, identified by header, the peer uid of the TCP connection read from `/proc/net/tcp*` (= kitt's DynamicUser `homelab-app-kitt`) and the `Tailscale-User-Login` header set by the gate | `server.chat_door`, `kitt.identify`, `kitt.route` |
| Kitt gate | in-process listener on `KITT_GATE_HOST:KITT_GATE_PORT` (100.85.70.34:8121), retries every 30 s until tailscaled is up, forwards to `KITT_UPSTREAM` (127.0.0.1:8122) | `gate.py`, `deploy/kitt.service` |
| Other clients | Discord bot (`INBOX_URL=http://127.0.0.1:8105`), kitt adapter (`KITT_BOX_URL=http://127.0.0.1:8105`) | `systemd/homelab-discord-bot.service`, `deploy/kitt.service` |
| Ports and ufw | 8105 inbox, 8121 kitt gate; ufw opens `8100:8199/tcp` on `tailscale0` as one range rule; 8104 is `homelab-overview-web` | `scripts/18-projects.sh`, `scripts/20-monitor.sh` |
| Accounts | `inbox-runner` (service; home `RUNNER_HOME`=`/var/lib/homelab-inbox`; GitHub and ntfy token) and `inbox` (tasks; home `TASK_HOME`=`/var/lib/inbox`; Claude token; no GitHub); both in group `inbox-share` | `scripts/21-inbox.sh` |
| Sudoers | `/etc/sudoers.d/homelab-inbox`: `inbox-runner ALL=(inbox) NOPASSWD: /usr/local/lib/homelab/bin/homelab-inbox-task`, nothing else | `scripts/21-inbox.sh` |
| Task commands | `homelab-inbox-task` actions `prepare`, `run`, `bundle` (exit 3 = no commits), `sync` (exit 4 + conflicted files), `behind`, `has`, `kill`, `cleanup`, `script` (exit 65 = bad repo or path), `route`, `draft`, `review`; ids match `^[djrv]?[0-9]{1,6}$` (else exit 2) | `bin/homelab-inbox-task` |
| Working copies | `/var/lib/inbox/work/<id>` (0700 `inbox`), bundles in `/var/lib/inbox/out`, branch `inbox/<id>`; origin is the service's copy in `RUNNER_HOME/repos/<name>` | `homelab-inbox-task prepare` |
| Data in `/var/lib/homelab-inbox` (2750 `inbox-runner:inbox-share`) | `inbox.db` (SQLite: tables `tasks`, `job_runs`, `job_state`, `drafts`, `skill_prs`, `kv`, `conversation`, `conversation_refusals`, `approvals`), `csrf-secret`, `new-repos.json`, `logs/` (live log per task or run), `share/<id>/prompt.txt`, `repos/` (service copies), `memory-repo` and `jobs-repo` (git clones of `DATA_REPO` branches `memory` and `jobs`), `fake_github.json` (tests only) | `config.py`, `storage.py`, `memory.py`, `jobs.py`, `chat.py`, `conversation.py` |
| Calls out | `git` (with the GitHub token via a credential helper), GitHub REST, ntfy, `tailscale`, the homelab-admin unix socket (`CONF["SOCKET"]`, SSH tab), `LLM_URL` (8017), `SEARCH_URL` (8019), `ROUTER_URL` (8018), `FORGE_URL` (8120) | `helpers.py`, `pages.admin_ask`, `jobs.py`, `search.py`, `forge.py` |
| Claude calls | only through `homelab-inbox-task` (`run`, `route`, `draft`, `review`); stream-json parsed in-process (`rounds.stream_claude`): turns counted, deadline watched by a thread, stop = `task_cmd kill` | `rounds.py`, `router.py`, `briefs.py`, `jobs.py` |
| Restart behaviour | systemd stops the whole cgroup, including the `sudo` children, so every running Claude ends. On start `recover()` requeues running tasks with a "you were cut off" input (router and planner start over), fails a task after `RESTART_ROUNDS`=2 restarts in a row, and re-reviews brief PRs whose review was cut off | `queue.recover` |
| Install and rerun | `scripts/21-inbox.sh` as root from root's clone; its `homelab-rerun` line lists `bin/homelab-inbox`, `bin/homelab_inbox`, `bin/homelab-inbox-task`, `web`, several `config/*`. A merge touching any of them makes `homelab-admin` rerun it, which **restarts the inbox**. `homelab-admin` is its own service, so a revert merged on GitHub is applied even when the inbox is broken | `scripts/21-inbox.sh`, `docs/admin.md` |
| Tests and CI | 17 files in `tests/` (`python3 -m unittest discover -s tests`), run by `scripts/ci.sh` (shellcheck, py_compile, tests) in `.github/workflows/ci.yml` | `scripts/ci.sh` |
| Tools on the box | Node 24 (NodeSource, `scripts/18-projects.sh`), PostgreSQL 16 socket-only (`scripts/25-forge.sh`); **no JDK, no Maven** (checked with `which` and `dpkg -l`) | |

## Components

```
phone ──tailnet──> :8105 front (Python until X1, then Java)    ── frontend/dist + /api/**
                      │  facade (until X1): BACKEND_ROUTES ──loopback + secret──> backend (Spring Boot, 127.0.0.1:BACKEND_PORT)
                      │                                                         queue, PostgreSQL, GitHub poll, gate, conversation, memory, jobs
                      │
backend ──ProcessBuilder── python3 -I runner/<job>.py ──> sudo -n -u inbox homelab-inbox-task <action> ...  (Claude)
        ──HTTP loopback─── llm/helper (8017), llm/router (8018), search API (8019), forge (8120)
        ──unix socket───── homelab-admin (SSH tab)
```

### Where today's modules go

| Today (`bin/homelab_inbox/`) | Goes to | Notes |
|---|---|---|
| `pages.py`, `web/app.js`, `web/app.css`, `web/fonts` | `frontend/` | rewritten as TypeScript pages; CSS and fonts move over unchanged first; `<form>` posts become API calls. `Access` and `csrf_token` move to `server.py` first (slice F12), later to Java |
| `server.py` (handler, routing, CSRF, Access) | `backend/` web layer (controllers, access and CSRF filter) | HTML routes disappear; every route becomes `/api/...` JSON; `/`, `/taken` etc. serve the SPA's `index.html` |
| `gate.py` (:8121) | `backend/` (second connector) | same check of Chris's devices; active only after cut-over |
| `config.py`, `storage.py` | `backend/` config properties and repositories | PostgreSQL, Flyway, one-off import |
| `queue.py`, `github.py`, `briefs.py`, `router.py` (bookkeeping) | `backend/` services | orchestration only: no Claude calls inside |
| `conversation.py`, `chat.py`, `kitt.py`, `forge.py`, `search.py` | `backend/` | same endpoints and client permissions; Discord bot and kitt do not notice |
| `memory.py`, `overview.py`, `jobs.py` (scheduling, file parsing, delivery) | `backend/` | job and memory files stay Markdown in `DATA_REPO`; parsing ported with tests on the existing fixtures |
| `rounds.py` (stream parsing, final-message parsing), result parsing of `route`/`draft`/`review` | `runner/` | the backend decides when and with what; the runner calls `homelab-inbox-task` and returns structured results |
| `bin/homelab-inbox-task` | stays (bash) | the single sudo entry; unchanged |
| `llm/`, `search/` | unchanged | still Python, still their own services |

## Contracts

### Frontend ↔ backend (JSON over HTTP)

- One origin, so no CORS. Static files from `frontend/dist`; unknown non-`/api` paths that are in the ported list
  return `index.html` (client-side routes `/taken`, `/task/<id>`, `/jobs`, `/memory`, `/search`, `/skills`, `/ssh`,
  `/briefs`, ...: current URLs, bookmarks and notification links stay valid).
- `GET /api/...` returns JSON; `POST`/`DELETE` take JSON. Errors: non-2xx with `{"error": "<message>"}`. The frontend
  shows the message and never ignores a failure.
- `GET /api/session` returns `{"owner", "device", "csrf"}`; every mutating request sends `X-CSRF-Token`. Same secret
  (`csrf-secret`) and check as `pages.csrf_token` today.
- The existing Content-Security-Policy stays (`script-src 'self'`, no inline script): the Vite build must emit none.
- Live data stays polling (`/api/live`, `/api/log/{key}`, both exist); server-sent events are later, not in the split.
- The contract is `backend/openapi.yaml`, checked in. `frontend/src/api/types.ts` is generated from it; the Python
  JSON endpoints (until X1) and the Java controllers are tested against it.
- Machine endpoints keep paths and bodies exactly: chat door, kitt door, `/api/forge*`, `/api/search/*`, `/api/live`,
  `/api/log/*`. They are written down first (slice C1) and pinned by tests (C2).

### Python facade → Java backend (until cut-over)

- `config/inbox.conf` gets `BACKEND_PORT` and `BACKEND_ROUTES` (path prefixes, empty by default). The Python server
  forwards a request whose path starts with a listed prefix to `http://127.0.0.1:BACKEND_PORT`, after its own access
  and CSRF check, adding `X-Inbox-Facade: <secret>` and `X-Inbox-Who: <device>`.
- The secret is a file in `RUNNER_HOME` (0600, `inbox-runner`), made on first start; both processes run as
  `inbox-runner`. Java answers 403 to any request without it, except `GET /api/backend/health` (counts and versions,
  no data) so Claude and the rerun script can check it on loopback.
- If Java fails or times out, the facade answers 502 `{"error": ...}`; it never falls back to the Python handler.
- Python code that writes a store Java owns calls Java's internal endpoints (`/internal/...`, same secret) instead.
- Rollback of a moved route = remove its prefix from `BACKEND_ROUTES` (one-line PR), as long as the store it writes
  still belongs to Python.

### Backend ↔ Python runners (the process contract)

The backend never imports Python and never builds a shell string; it starts a child with an argument array.

- **Command**: `["python3","-I","/usr/local/lib/homelab/runner/<job>.py", ...args]` as `inbox-runner`. A runner that
  needs a working copy or Claude calls `sudo -n -u inbox /usr/local/lib/homelab/bin/homelab-inbox-task <action> ...`
  itself (the existing sudoers line; no new root surface). Simple actions with no parsing (`prepare`, `bundle`,
  `sync`, `behind`, `has`, `kill`, `cleanup`) may be called by the backend directly.
- **Runners**: `round.py <id> <session> <new|resume> [research]` (wraps `run`), `route.py r<id>`, `draft.py d<id>`,
  `review.py v<id>` (wrap the no-tool actions and validate their JSON). Local-model jobs stay HTTP calls from the
  backend to `LLM_URL`; search and router stay HTTP services.
- **Input**: ids and modes as arguments; the prompt in `RUNNER_HOME/share/<id>/prompt.txt`, written by the backend as
  `rounds.write_prompt` does now. Nothing sensitive in arguments; tokens are read from `/etc/homelab` by
  `homelab-inbox-task`.
- **Output**: newline-delimited JSON on stdout, each line with `"v":1`: `{"event":"log","kind","text"}` (from
  `rounds.stream_lines`), `{"event":"model","model"}`, `{"event":"turn","turns"}`, and last
  `{"event":"result","outcome":"done|answer|question|refused|none","text","memory":[...],"skill":...,"raw":{...}}`.
  stderr is diagnostics for the task log. Schema in `runner/SCHEMA.md`.
- **Exit codes**: `0` ok, `2` bad id, `3` no commits, `4` merge conflict (files on stdout), `65` bad repo or path,
  anything else failed. One table in `runner/SCHEMA.md`.
- **Time and stopping**: the backend owns deadline and turn limit (it counts `turn` events) and the cancel button. To
  stop: `homelab-inbox-task kill <id>`, then SIGTERM to the runner, which forwards it to its `sudo` child. A runner
  leaves no children behind.
- **Restart safety**: the backend reconciles `running` tasks on start, as `queue.recover` does.
- **Concurrency**: the backend enforces `MAX_RUNNING` and `MAX_PER_REPO`; runners know nothing about the queue.
- **Testability**: `INBOX_*` overrides as today; a fake runner replays recorded output (`tests/fixtures/runner/`).

Why child processes and not an HTTP service: the boundary exists and is audited, a crashing runner cannot take the
backend down, and nothing new listens on a port.

## Database

Assumption: **PostgreSQL** (the review comment's "progress" read as PostgreSQL; PostgreSQL 16 is installed for forge,
socket-only). Say so if another database was meant.

- **Why**: a long-running multi-threaded backend with several writers; PostgreSQL gives concurrent writes and
  transactions where SQLite has one writer behind a file lock.
- **Schema**: the nine tables listed above, one to one, as Flyway migrations in
  `backend/src/main/resources/db/migration/` (`V1__baseline.sql` collapses the `ALTER TABLE` history of `storage.py`).
  Later changes are new migrations, never edits.
- **Access**: database `inbox`, role `inbox_app` (name to confirm in Q2) owning only it; no superuser; peer auth or a
  password from `/etc/homelab`. HikariCP, small pool.
- **Reachability**: the cluster is socket-only and must stay without a TCP listener unless Q2 says otherwise.
  (a) junixsocket for pgjdbc (no listener; a new dependency), (b) a `127.0.0.1` listener with scram-sha-256 and a
  `pg_hba` line for the role only (simple; a loopback port other local users can reach). Never tailnet or LAN.
- **Import**: in shadow mode the backend, on start and on `POST /internal/import`, takes a consistent copy of
  `inbox.db` (SQLite backup API to a temp file, read-only on the original) and loads every table it does not own yet
  in one transaction, compares row counts and refuses on a mismatch. Counts appear on `/api/backend/health`.
- **Backup**: `pg_dump` of `inbox` in `bin/homelab-backup` / `config/backup.list`, restore tested once before X1.
- **Python side**: runners do not open the database.
- **Rollback**: until a table switches, `inbox.db` stays the source of truth. After X1 keep the last `inbox.db` and
  `pg_dump` for one release; going back after X1 loses what was written since, unless Q9 asks for an export.
- **Install**: `scripts/28-inbox-db.sh` (with a `# homelab-rerun:` line), idempotent, checks a round trip and that
  `listen_addresses` is still what Q2 allows.

## Migration slices

Strangler: the Python inbox keeps working until Java can do everything behind it. Rules for every slice:

- One PR on a branch; on homelab the user merges. A merge that touches a path on `21-inbox.sh`'s rerun line restarts
  the inbox and cuts off running tasks; a task cut off twice in a row fails. **Merge inbox slices one at a time,
  not while long tasks run.** Slices that only add files under `frontend/`, `backend/`, `runner/` or `docs/` restart
  nothing until a rerun line lists them.
- Port tests, never drop them; keep `scripts/ci.sh` green; update `CHECKLIST.md` and the docs the slice touches.
- A new or changed script carries its `# homelab-rerun:` line; anything with a port or a root step gets
  `change-reviewer` and, for ports, `exposure-checker` before the PR.
- "Check alone" means what Claude can verify without the user: tests in a dev clone, the rerun script's own checks
  (its result in `/srv/homelab/state/`), and `GET /api/backend/health` on loopback. "User" means a step only the user
  can do: merge with a root script, a new package, a decision, or a look on the phone.

Legend: **Agent** = who builds it. Dependencies name slice ids or decisions (Qn, see the end).

### Phase C: write the contract down (no runtime change)

**C1 Route, data and store inventory.**
Goal: one exact list to build against. Built: `backend/openapi.yaml` with every route of `server.py` and
`kitt.route` (method, access: page, chat door or kitt door, request, response, errors), machine endpoints exact; a
section in `docs/architecture/inbox-data.md` listing every table and column and every file store under
`RUNNER_HOME` with its current writers. Check alone: `tests/test_inbox_openapi.py` fails when a route regex in `server.py` or `kitt.py` is missing from the OpenAPI file (`x-code`). Depends:
none. Rollback: revert (docs only). Risk: low. Agent: `api-database-engineer`. User: no.

**C2 Contract tests for the machine endpoints.**
Goal: pin what Discord, kitt, the search tab and forge rely on. Built: tests in `tests/` that run the Python server
in-process and check status codes and bodies of the chat door, kitt door (peer uid faked), `/api/live`,
`/api/log/*`, `/api/forge*`, `/api/search/*` against `backend/openapi.yaml`; written so the same cases can later be
pointed at Java (base URL from an environment variable). Check alone: `python3 -m unittest`. Depends: C1. Rollback:
revert. Risk: low. Agent: `test-automator`. User: no.

**C3 Runner contract and recorded fixtures.**
Goal: the process contract exact. Built: `runner/SCHEMA.md` (actions, arguments, stdout per action, exit-code table,
who kills what, timeouts used today), recorded stream-json samples in `tests/fixtures/runner/` (scrubbed; no tokens,
no personal data) and a fake `homelab-inbox-task` that replays them. Check alone: existing tests pass with `TASK_CMD`
pointed at the fake. Depends: none (parallel with C1). Rollback: revert. Risk: low. Agent: `documentation-engineer`
(schema) with `test-automator` (fixtures, fake). User: no.

### Phase F: frontend out of Python (no Java yet)

**F1 Frontend scaffold.**
Goal: a buildable empty frontend. Built: `frontend/` with Vite and TypeScript (no framework), `package-lock.json`,
`web/app.css` and fonts copied in, the API client (JSON, error shape, `X-CSRF-Token`), types generated from
`backend/openapi.yaml`, unit tests; `scripts/ci.sh` runs `npm ci --ignore-scripts`, typecheck, tests and build when
`frontend/` exists; `ci.yml` installs Node. Check alone: `bash scripts/ci.sh` in a dev clone. Depends: C1 (types), Q4.
Rollback: revert (nothing installed). Risk: low. Agent: `frontend-developer`. User: approve the dev-dependency list (Q4).

**F2 Session and JSON conventions in Python.**
Goal: the Python server speaks the frontend contract. Built: `GET /api/session`, `X-CSRF-Token` accepted on JSON
POSTs, `{"error"}` on JSON failures, tests. Check alone: unittest. Depends: C1. Parallel with F1. Rollback: revert
(restarts the inbox). Risk: low (additive). Agent: `backend-developer`. User: merge.

**F3 Install and serve the build.**
Goal: `frontend/dist` reaches the box and can serve pages. Built: the build step chosen in Q3; `21-inbox.sh` installs
`dist` to `/usr/local/lib/homelab/frontend` and lists `frontend` on its rerun line; the Python server serves `/assets/*`
and, for paths in `PORTED_PAGES` (`config/inbox.conf`, empty), `index.html`. Nothing visible changes. Check alone:
tests (SPA fallback only for listed paths, CSP unchanged), and the rerun script's check that `index.html` is
installed and that the box itself still gets 403. Depends: F1, F2, Q3. Rollback: revert; the rerun removes nothing
harmful (old pages still served). Risk: medium (root script, build on the box). Agent: `backend-developer`, reviewed by
`change-reviewer`. User: merge runs a root script; Q3.

**F4–F11 One page at a time.** Each page is two slices: **a** = the JSON endpoint(s) in Python that return what
`pages.py` renders, with tests against `openapi.yaml` (`backend-developer`); **b** = the TypeScript page, its path added
to `PORTED_PAGES`, its code removed from `pages.py` and `web/app.js`, a Playwright screenshot from a test server
(`webapp-testing`, `INBOX_ALLOW_ALL=1`, temp home) (`frontend-developer`). Check alone: unittest, `ci.sh`, screenshot.
User: merge; a look on the phone (a `CHECKLIST.md` line per page). Rollback: revert b (the HTML page returns; a stays,
it is additive). Risk: low to medium as noted.

| Slice | Page | a depends on | b depends on | Risk |
|---|---|---|---|---|
| F4 | Skills (`/skills`) | F2 | F3, F4a | low |
| F5 | Memory (`/memory`, `/memory/e/<id>`, add, save, delete) | F2 | F3, F5a | low |
| F6 | Overview (`/`: status sentence, running now, timeline, week, health, switches) | F2 | F3, F6a | medium (switches) |
| F7 | Jobs (`/jobs`, `/job/<name>`, `/draft/<id>`, `/run/<id>`, catalogue `/agents/*`, research) | F2 | F3, F7a | medium |
| F8 | Search (`/search`; its API is already JSON) | none (no a) | F3 | low |
| F9 | SSH (`/ssh`, via the homelab-admin socket) | F2 | F3, F9a | medium (SSH key path) |
| F10 | Tasks (`/taken`, `/task/<id>`, live log, answer, cancel, retry, continue, accept, attended, new task form) | F2 | F3, F10a | high (most used) |
| F11 | Briefs (`/briefs`, `/briefs/<id>`, build, park) | F2 | F3, F11a | medium |

All a-slices can run in parallel (they touch `server.py`, so expect small conflicts the inbox resolves); keep at most
two in review at once because each merge restarts the inbox.

**F12 Remove the HTML layer.**
Goal: no HTML rendered in Python. Built: `pages.py` and `web/app.js` gone; `Access` and `csrf_token` moved into
`server.py` (or a small `access.py` placed in the layer list); `web/` removed from the rerun line; `CLAUDE.md` map and
`docs/dashboard.md` updated. Check alone: unittest, `ci.sh`, screenshots of every page. Depends: F4b–F11b. Rollback:
revert. Risk: medium. Agent: `frontend-developer` with `documentation-engineer`. User: merge. **Decision point Q8:
stop here or go on to Java.**

### Phase B: database and backend beside the old inbox

**B1 Backend skeleton.**
Goal: a Spring Boot app that builds and tests. Built: `backend/` with the Maven wrapper, pinned versions in
`pom.xml`, configuration read from `config/inbox.conf`, `GET /api/backend/health`, the facade-secret filter, bind
to `127.0.0.1` only, structured logs without secrets; `ci.sh` runs `./mvnw -B verify` when `backend/` exists, `ci.yml`
installs Java 21. Check alone: `./mvnw verify` in a dev clone (needs a JDK for `claude`: see Q1). Depends: Q1.
Parallel with phase F. Rollback: revert. Risk: low. Agent: `backend-developer`. User: Q1 (go-ahead and dependency list).

**B2 Database on the box.**
Goal: an empty `inbox` database the backend can reach. Built: `scripts/28-inbox-db.sh` per Q2 (role, database,
auth, round-trip check, no new TCP listener unless Q2 chose b). Check alone: the script's own checks in
`/srv/homelab/state/`. Depends: Q2. Parallel with B1. Rollback: revert; the empty database may stay (nothing uses
it). Risk: medium (shared cluster with forge). Agent: `api-database-engineer`, `change-reviewer`. User: merge (root).

**B3 Install the backend.**
Goal: the backend runs on the box, unreachable from outside. Built: `package openjdk-21-jdk-headless` (or the JDK
named in Q1) in `config/machine.conf`; `scripts/27-backend.sh` (build per Q3, install the jar to
`/usr/local/lib/homelab/backend`, unit `homelab-inbox-backend.service` as `inbox-runner`, `127.0.0.1:BACKEND_PORT`,
heap cap, systemd hardening, `Restart=always`), rerun line `backend config/inbox.conf`. `BACKEND_PORT` is a free port
checked with `ss` and recorded in `config/inbox.conf`. Check alone: `curl http://127.0.0.1:BACKEND_PORT/api/backend/health`
answers; any other path without the secret gives 403; `ss` shows no listener on another address. Depends: B1, B2, Q3.
Rollback: revert and `homelab-admin` stops the unit (`systemctl disable` in a revert script). Risk: medium (new
runtime, root). Agent: `backend-developer`, `exposure-checker`, `change-reviewer`. User: new package, root script.

**B4 Flyway baseline and read repositories.**
Goal: today's schema in PostgreSQL. Built: `V1__baseline.sql` for the nine tables; read repositories; tests on a
real PostgreSQL per Q5. Check alone: `./mvnw verify`. Depends: B1, Q5. Rollback: revert. Risk: low. Agent:
`api-database-engineer`. User: Q5.

**B5 Shadow import.**
Goal: Java holds a fresh copy of the data. Built: the import described under "Database", counts on the health
endpoint. Check alone: tests with a fixture `inbox.db`; on the box, health shows equal SQLite and PostgreSQL counts
per table after a restart (`homelab-admin` may restart the unit). Depends: B2, B3, B4. Rollback: revert (the copy is
unused). Risk: low (read-only on `inbox.db`). Agent: `api-database-engineer`. User: merge.

**B6 Facade in the Python server.**
Goal: traffic can move route by route. Built: `BACKEND_PORT`/`BACKEND_ROUTES`, forwarding with secret and identity,
502 on failure, `/internal/...` client helper, tests with a fake upstream. First listed route: `/api/backend/health`.
Check alone: unittest; on the box the rerun script checks that Java's health answers directly on loopback and that
the Python front still refuses the box itself (403). Depends: B3, decision Q6. Rollback: empty `BACKEND_ROUTES`.
Risk: medium (every request passes it). Agent: `backend-developer`. User: merge.

**B7 Parity harness.**
Goal: proof that Java answers like Python. Built: a test that starts Python (temp home, fixture db,
`INBOX_ALLOW_ALL=1`) and Java (temp database), imports the same data and diffs the JSON of every GET in
`openapi.yaml` that both implement; C2's cases pointed at Java. Check alone: runs in `ci.sh`. Depends: C2, B5, and the
F a-slices for the endpoints compared. Rollback: revert. Risk: low. Agent: `test-automator`. User: no.

**B8 Backup and restore.**
Goal: the new database is in the backups. Built: `pg_dump` of `inbox` in `bin/homelab-backup`, `config/backup.list`;
one restore into a scratch database, recorded in `CHECKLIST.md`. Check alone: the backup status file. Depends: B2.
Parallel with B3–B7. Rollback: revert. Risk: low. Agent: `api-database-engineer`. User: merge (root script); the restore
test.

### Phase M: move read paths and small stores

Each M slice: Java implements the endpoints, B7 parity passes, then a one-line PR adds the prefixes to
`BACKEND_ROUTES`. Store-owning slices also switch the writer: Python's writes go to Java's `/internal/...` in the same
PR, after a fresh import. Rollback: remove the prefixes; for a store switch also hand the store back (re-import the
other way is not built, so a store switch is rolled back only in its first day, by reverting before new writes
matter: say so in the PR).

| Slice | What moves | Store owner after | Depends | Risk | Agent |
|---|---|---|---|---|---|
| M1 | Overview, live and log reads (`/api/overview` from F6a, `/api/live`, `/api/log/*`; reads `logs/` and monitor state) | none (read-only) | B6, B7, F6a | low | `backend-developer` |
| M2 | Skills and memory reads (F4a, F5a endpoints; `memory-repo` read) | none | B6, B7, F4a, F5a | low | `backend-developer` |
| M3 | Forge and search proxies (`/api/forge*`, `/api/search/*`) | none | B6, C2 | low | `backend-developer` |
| M4 | Conversation store: entries, refusals, the `switch:*` keys of `kv`; chat door `entries` and `state` with client tokens (`config/clients.json`); Python's `notify(store=True)` writes through `/internal/conversation` | Java: `conversation`, `conversation_refusals`, `kv switch:*` | M1, C2 | medium (Discord, kitt) | `api-database-engineer` |
| M5 | Memory writes (page edits and `GEHEUGEN:` blocks via `/internal/memory`; git pushes to the `memory` branch) | Java: `memory-repo` | M2 | medium (git, scrubbing with `SECRET_RE`) | `backend-developer` |
| M6 | Job files: list, read, save, pause, resume, delete, change, catalogue (`jobs-repo`, `/agents/*`); scheduler and runs stay in Python | Java: `jobs-repo` | M2, F7a | medium | `backend-developer` |

**M4 as built (H-024):** Java owns `conversation`, `conversation_refusals` and `kv switch:*` (marker row `store:conversation`); Python's
`conversation.py` is a loopback client (`/internal/conversation/*`). `GET /api/chat/state` and `GET|POST /api/chat/entries` are answered by
Java; **the bearer-token check and `/api/chat/action` (queue, approvals) stay in Python and move to Java in K7**, where the token files
become Java's to read. The backend runs as `inbox-runner` (not a DynamicUser; only the build is one). See `docs/inbox-backend.md`, "Conversation store (M4)".

M1–M3 can run in parallel; M4, M5 and M6 one at a time (each changes a writer). User for each: merge, and for M4 a
Discord message and a kitt look on the phone.

### Phase R: runners

**R1 `runner/round.py`.**
Goal: Claude-specific parsing out of the Python inbox, behind the contract. Built: the runner wrapping
`homelab-inbox-task run`, emitting v1 events and the parsed result; `rounds.stream_claude` in the Python inbox switched
to call it (so the contract is proven while Python still owns the queue); installed to `/usr/local/lib/homelab/runner`
by `21-inbox.sh` (rerun line gains `runner`). Check alone: tests with C3's fixtures and fake. Depends: C3, Q7 (decision on
runner shape). Parallel with phase D and M. Rollback: revert (Python parses again). Risk: medium (every round). Agent:
`backend-developer`. User: merge.

**R2 `runner/route.py`, `draft.py`, `review.py`.**
Goal: the no-tool Claude calls behind the same contract. Built: three runners; `router.py`, `jobs.py`, `briefs.py`
call them. Check alone: tests. Depends: R1. Rollback: revert. Risk: medium. Agent: `backend-developer`. User: merge.

**R3 Java `RunnerService`.**
Goal: Java can start, watch and stop runners. Built: process start with argument arrays, event reader, turn and time
limits, cancel (kill action, then SIGTERM), stderr to the task log, exit-code table, tests with the fake runner.
Check alone: `./mvnw verify`. Depends: B1, C3. Parallel with R1. Rollback: revert (unused). Risk: low. Agent:
`backend-developer`. User: no.

### Phase K: the core in shadow, then cut-over

The `tasks` table is written by queue, rounds, router, briefs, github, chat actions, jobs runs and the kitt door, so
it switches once, at X1. Before that, each K slice builds and tests its part in Java with the fake runner and fake
GitHub, ported from the matching `tests/test_inbox_*.py`, with the scheduler **off** on the box (a property, default
off). Rollback for every K slice: revert (nothing runs).

| Slice | Part | Ported tests | Depends | Risk | Agent |
|---|---|---|---|---|---|
| K1 | Task store writes, scheduler (limits, `after`), `recover`, cancel, answer, retry, continue, accept | `test_inbox_followups`, `test_inbox_catch_up` | B4, R3 | high | `backend-developer` + `test-automator` |
| K2 | `finish_round`: outcomes, bundle, push `inbox/<id>`, PR, notifications, skill PRs | `test_inbox_events`, `test_inbox_agents` | K1 | high | `backend-developer` |
| K3 | Router ("auto") and follow-ups | `test_inbox_followups` | K1, R2 | medium | `backend-developer` |
| K4 | Briefs: planner, review, rework, handoff, build without me | `test_inbox_builds` | K2, R2 | high | `backend-developer` |
| K5 | GitHub polling, PR comments, conflicts and `sync`, auto merge, CI state | `test_inbox_auto_merge` | K2 | high (merges) | `backend-developer` |
| K6 | Job scheduler, local runs (`LLM_URL`), Claude job runs, research, delivery | `test_inbox_job_delivery`, `test_inbox_research` | K1, M6 | medium | `backend-developer` |
| K7 | Chat actions and approvals (`approvals` table, two-step confirm), kitt door with peer uid, the gate as a second connector (off until X1) | `test_inbox_chat`, `test_inbox_conversation`, `test_inbox_kitt` | K1, M4 | high (exposure) | `backend-developer`, `exposure-checker` |
| K8 | Front door in Java: `tailscale whois` access, CSRF, SPA serving, security headers and CSP, SSH tab via the homelab-admin socket | `test_inbox_forge`, `test_inbox_search` | B1 | high (exposure) | `backend-developer`, `exposure-checker` |
| K9 | Measure: heap and RSS of the backend next to Ollama on the box over a day, recorded in the PR | none | B3 | low | `backend-developer` |

K2, K3, K6 and K8 can run in parallel once their dependencies are in; K4 and K5 after K2.

> **Plan change (H-028).** H-027 went straight to the switch, and its X1b and X2b were refused: nothing in `backend/` was
> wired up (no routes for tasks, drafts, runs, jobs and briefs, no live effects, nothing serving `frontend/dist`).
> **H-028 replaces H-027 X1b and X2b.** It first wires the Java inbox up (W1 to W5: live effects, every route, the
> doors, the SPA, the tailnet connector and the gate), then adds a **staging-port step (S1)** before the cut-over: a
> second unit `homelab-inbox-staging` on a free port inside the existing `8100:8199` ufw range, on its own database
> filled from a copy of the data, with `core.effects=shadow` and the scheduler off. Only after S1 do X1b (the cut-over
> described below, with the same rollback and Q9) and X2 (the clean-up, one release later) follow. The per-route test
> map is `docs/inbox-test-coverage.md`.

**X1 Cut-over.**
Goal: Java is the inbox. Built: a PR that switches `homelab-inbox-backend` to bind `INBOX_PORT` (8105, all
addresses, access as K8) and the gate on 8121, turns its scheduler on, disables `homelab-inbox.service` (kept
installed), and a final import. Done by the user at a moment with no running task (in-memory state does not move).
Check alone after: health, C2 contract cases against 8105 through loopback doors, the rerun script's 403-from-the-box
check; user: a task, a Discord message and kitt on the phone. Depends: all of M, R, K, B8, Q9. Rollback: re-enable
the Python unit and stop Java; data written in between is lost unless Q9 chose an export. Risk: high. Agent:
`backend-developer`, `exposure-checker`, `change-reviewer`. User: timing, merge, phone checks.

**X2 Clean-up.**
Goal: one inbox. Built: delete `bin/homelab_inbox/`, the facade and the old unit; `CLAUDE.md` map and layer list,
`docs/inbox.md` (including the restart sentence), `docs/dashboard.md`, rerun lines updated; contract tests kept.
Depends: X1 plus one release without rollback. Risk: medium. Agent: `documentation-engineer` with `backend-developer`.
User: merge.

### Dependency summary

| Slice | Depends on |
|---|---|
| C1, C3 | none |
| C2 | C1 |
| F1 | C1, Q4 |
| F2 | C1 |
| F3 | F1, F2, Q3 |
| F4a–F11a | F2 (F8 has no a) |
| F4b–F11b | F3 and their own a |
| F12 | F4b–F11b; then Q8 |
| B1 | Q1 (and Q8 if asked after F12) |
| B2 | Q2 |
| B3 | B1, B2, Q3 |
| B4 | B1, Q5 |
| B5 | B2, B3, B4 |
| B6 | B3, Q6 |
| B7 | C2, B5, the F a-slices compared |
| B8 | B2 |
| M1–M3 | B6, B7 (+ F6a; F4a, F5a; C2) |
| M4, M5, M6 | M1; M2; M2 and F7a (one at a time) |
| R1, R2 | C3, Q7; R1 |
| R3 | B1, C3 |
| K1 | B4, R3 |
| K2, K3, K6, K7 | K1 (+ R2 for K3; M6 for K6; M4 for K7) |
| K4, K5 | K2 (+ R2 for K4) |
| K8, K9 | B1; B3 |
| X1 | every M, R and K slice, B8, Q9 |
| X2 | X1 plus one release |

Can start at once: C1 and C3; B1 and B2 as soon as Q1 and Q2 are answered (they do not need phase F). Phase F and
phase B can run side by side; R1 and R3 can run beside both.

## Deploy and security

- Accounts: none new. The backend runs as `inbox-runner`; runners as `inbox-runner`, Claude as `inbox` via the
  existing sudoers line.
- Ports: until X1 the backend binds `127.0.0.1:BACKEND_PORT` only, behind the facade secret, so no ufw rule is needed;
  at X1 it takes 8105 and 8121, already covered by the `8100:8199` rule on `tailscale0`. PostgreSQL per Q2.
- Build: per Q3; dependencies pinned (Maven wrapper and `pom.xml`, `package-lock.json`), listed with versions in the PR.
- RAM and disk: measured in K9 before X1, not assumed.
- The frontend never holds a token. Machine clients keep their tokens in `/etc/homelab`.

## Risks

- **Half-way stall.** Stopping after F12 still leaves a useful result; every slice leaves one working inbox.
- **Two writers on one store.** One owner per table and per file store, switched in one PR after a fresh import.
- **Restart churn.** Each inbox slice merge restarts the inbox; merge one at a time.
- **Builds as root** (npm, Maven plugins): Q3.
- **Cut-over loses in-memory state and has a one-way data step**: X1 at a quiet moment; Q9.
- **Subtle behaviour** (auto merge, conflict rounds, recovery, scrubbing): ported with the existing tests first.
- **The runner stays the sensitive part**: Claude with write access to a working copy; the sandbox (`inbox`, no
  GitHub token, no `/home/claude`) is unchanged.

## Open decisions for the user

| # | Decision | Proposal (assumption) | Blocks |
|---|---|---|---|
| Q1 | Go-ahead for Java 21 and Spring Boot as a new framework on the box, the JDK package, and the dependency list (Spring Boot web, JDBC, Flyway, pgjdbc, test libraries) | OpenJDK 21 headless from Ubuntu; Maven via the wrapper (decided) | B1, B3, and everything after |
| Q2 | PostgreSQL confirmed? Socket via junixsocket, or a loopback listener with a password? Role name | junixsocket, socket-only | B2, B4 on the box, B5 |
| Q3 | Where and as whom `npm ci` and Maven run when the rerun script installs: (a) as root in root's clone, (b) as an unprivileged throwaway user (`systemd-run` with `DynamicUser`), (c) build in GitHub Actions and commit or download the artefact | (b), with `npm ci --ignore-scripts` | F3, B3 |
| Q4 | Frontend dev dependencies: Vite, TypeScript, a test runner (Vitest), a generator for types from OpenAPI | that list, pinned | F1 |
| Q5 | How Java tests get PostgreSQL: Testcontainers (needs Docker in CI and for the test account), or a throwaway cluster started with the installed `pg_ctl` in a temp dir | throwaway cluster: works on the box without Docker access and in CI | B4, B7, K slices |
| Q6 | During the migration, the Python server fronts and forwards to Java (this plan), or Java fronts and proxies the rest to Python | Python fronts: the access check stays where it is until X1, Java needs no tailnet port | B6, M |
| Q7 | Runner shape: Python runners wrap `homelab-inbox-task` and own the Claude-specific parsing (this plan), or Java calls `homelab-inbox-task` directly and parses stream-json itself | Python runners (the user's split: Claude work in Python) | R1, R2, R3 |
| Q8 | Stop after F12, or continue to Java? | decide after F12 | all of B, M, R, K, X |
| Q9 | After X1, is losing the data written since cut-over acceptable on rollback, or must there be an export back to SQLite? | acceptable for one release, with the last `inbox.db` and `pg_dump` kept | X1 |
| Q10 | Data repo and secrets stay where they are (`DATA_REPO`, `/etc/homelab`) | assumed; only the database moves | none |
