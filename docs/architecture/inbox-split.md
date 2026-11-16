# Inbox split: TypeScript frontend, Spring Boot backend, Python runners

Status: **plan, not built.** Written by the `architect` agent role for task 167 (message #166). Nothing here changes
the running inbox yet; each migration step below is its own PR. Open decisions are listed at the end and need the
user's answer before step 1.

## Goal

Today `homelab-inbox` is one Python process (`bin/homelab_inbox/`, about 5,500 lines) that serves the API, renders
every page as HTML strings (`pages.py`, 886 lines, plus `web/app.js` and `web/app.css`), runs the queue and talks to
GitHub. The frontend therefore lives inside the Python backend. Target:

| Part | Language | Owns |
|---|---|---|
| `frontend/` | TypeScript | every page and all UI logic; talks to the backend only through the JSON API |
| `backend/` | Java 21, Spring Boot | HTTP and the REST API, auth and the gate, queue and scheduler, the PostgreSQL database, GitHub polling, memory and jobs bookkeeping; **starts** the Python runners |
| `runner/` | Python | everything that runs Claude Code, the local model, search or the router; one script per job, started by the backend |

Non-goals: changing what the inbox does for the user, the `inbox`/`inbox-runner` account split, the task states in
`docs/inbox.md`, the Discord bot or kitt's behaviour (they keep their contracts), or moving `llm/` and `search/` out
of Python.

Fixed rules that do not move (`CLAUDE.md`): nothing reachable from the internet, tailnet only with its own ufw rule,
secrets in `/etc/homelab`, everything rebuildable from the repo, merges to `main` are the user's.

## Components

```
phone ──tailnet──> backend (Spring Boot, :8105)  ── serves frontend/dist (static) + /api/** (JSON)
                      │  queue, PostgreSQL, GitHub poll, gate, conversation, memory, jobs
                      │
                      ├─ ProcessBuilder ─ sudo -n -u inbox homelab-inbox-task <action> ...   (existing, kept)
                      │                      └─ runner/*.py   Claude Code, git bundle, stream-json on stdout
                      ├─ ProcessBuilder ─ runner/*.py          local model, router, search (no sudo)
                      └─ HTTP localhost ─ llm/router (8018), llm/helper (8017), search API (8019)
```

### Where today's modules go

| Today (`bin/homelab_inbox/`) | Goes to | Notes |
|---|---|---|
| `pages.py`, `web/app.js`, `web/app.css`, `web/fonts` | `frontend/` | rewritten as TypeScript pages; the CSS and fonts move over unchanged first. The `<form>` posts become API calls |
| `server.py` (handler, routing, CSRF, Access) | `backend/` web layer (controllers, filter for access and CSRF) | HTML routes disappear; every route becomes `/api/...` JSON; `/` and `/taken` etc. serve the SPA's `index.html` |
| `gate.py` (the tailnet gate in front of kitt, :8121) | `backend/` (second connector or a servlet filter) | same allow-list of Chris' devices |
| `config.py`, `storage.py` | `backend/` config properties and a repository layer | **SQLite is replaced by PostgreSQL** (see "Database"): schema in Flyway migrations, repositories in Spring Data JDBC or JPA, one-time import from `inbox.db` |
| `queue.py`, `github.py`, `briefs.py` | `backend/` services (scheduler, queue, PR polling, auto merge, briefs) | pure orchestration and bookkeeping: no Claude calls inside |
| `conversation.py`, `chat.py`, `kitt.py`, `forge.py`, `search.py` (proxy) | `backend/` | same endpoints and client permissions (`config/clients.json`); Discord bot and kitt do not notice |
| `memory.py`, `overview.py`, `jobs.py` (scheduling, file parsing, delivery) | `backend/` | job files stay Markdown in the data repo; parsing is ported with tests against the existing fixtures |
| `rounds.py` (stream parsing, prompt writing), the Claude-invoking parts of `jobs.py`, `router.py` (message routing), research | `runner/` + backend services | the backend decides *when* and *with what*; the runner does the Claude call. See the contract below |
| `bin/homelab-inbox-task` | stays (bash), moved logic called by the backend | already the process boundary today (`sudo -n -u inbox`); keep it as the single sudo entry, its actions become the runner contract |
| `llm/`, `search/`, `bin/llm`, `bin/homelab-search*` | unchanged | still Python, still their own services |

## Contracts

### Frontend ↔ backend (JSON over HTTP)

- One origin (the backend), so no CORS. Static files from `frontend/dist`; unknown non-`/api` paths return
  `index.html` (client-side routes `/taken`, `/jobs`, `/memory`, `/search`, `/skills`, `/ssh`, `/briefs`, ...: the
  current URLs stay valid, including bookmarks and notification links).
- `GET /api/...` returns JSON, `POST`/`DELETE` take JSON. Errors: non-2xx with `{"error": "<message>"}`. The frontend
  shows the message and never ignores a failure.
- CSRF: the backend sets the token in a response header on `GET /api/session`; every mutating request sends it back
  as `X-CSRF-Token`. Same secret and check as `pages.csrf_token` today.
- Live data (the running-now list and logs) stays polling at the current cadence first (`/api/live`, `/api/log/{key}`,
  both already exist); server-sent events are a later improvement, not part of the split.
- The contract is written as an OpenAPI file, `backend/openapi.yaml`, checked in. The TypeScript types
  (`frontend/src/api/types.ts`) are generated from it; the Java controllers are tested against it.
- The existing machine endpoints keep their paths and bodies exactly: `/api/chat/*` (Discord bot), kitt's door,
  `/api/forge`, `/api/search/*`. They are the first part of the contract, written down from the current code.

### Backend ↔ Python runners (the process contract)

The backend never imports Python and never builds a shell string. It starts a child process with an argument array.

- **Command**: `["sudo","-n","-u","inbox","/usr/local/lib/homelab/bin/homelab-inbox-task", action, ...args]` for
  anything that touches a task's working copy or runs Claude (today's `TASK_CMD`); `["python3","-I", "<runner>/<name>.py", ...]`
  for work with no sudo (local model, search, router). The sudoers line stays as it is; no new root surface.
- **Input**: arguments for short values (ids, mode), files for long ones. The prompt is written by the backend to
  `RUNNER_HOME/share/<id>/prompt.txt` exactly as `rounds.write_prompt` does now. Nothing sensitive in arguments
  (visible in `ps`); tokens are read by the script from `/etc/homelab`, never passed.
- **Output**: newline-delimited JSON on stdout, one event per line (today Claude's stream-json is passed through;
  the runners add `{"event":"result", ...}` as the last line). stderr is diagnostics only and goes to the task log.
  The runner's result schema is versioned (`"v":1`) and defined in `runner/SCHEMA.md`.
- **Exit codes**: keep the current ones (`0` ok, `3` no commits, `4` merge conflict with the conflicted files listed,
  `65` bad repo or path) and document them in one table; anything else is "failed".
- **Time and stopping**: the backend owns the deadline (80 turns / 30 minutes per round) and the cancel button. It stops a
  run with `homelab-inbox-task kill <id>` (existing action), then kills the process group. A runner must not leave
  children behind.
- **Restart safety**: on start, the backend reconciles `running` tasks against live processes, as `queue.recover` does now.
- **Concurrency**: the backend enforces `MAX_RUNNING` and `MAX_PER_REPO`; runners know nothing about the queue.
- **Testability**: runners accept `INBOX_*` environment overrides as `homelab-inbox-task` does today, and the Java
  tests use a fake runner script that replays recorded output.

Why child processes and not an HTTP service for the runners: the boundary already exists and is audited (sudo as the
unprivileged `inbox`), a crash of a runner cannot take the backend down, and nothing new listens on a port.

## Database

The user asked to replace SQLite with a database server. Assumption: **PostgreSQL** (the review comment said
"progress"; read as a typo for PostgreSQL, which is already installed on the box for forge, `scripts/25-forge.sh`).
Say so if another database was meant.

- **Why it fits**: the backend is a long-running multi-threaded service with several writers (scheduler, GitHub poll,
  chat clients); PostgreSQL gives real concurrent writes and transactions, where SQLite is one writer behind a file lock.
- **Schema**: today's tables (`tasks`, jobs, runs, memory and conversation tables from `storage.py` and the modules)
  are carried over one to one first, as Flyway migrations in `backend/src/main/resources/db/migration/` (`V1__baseline.sql`
  and onwards). The many `ALTER TABLE ADD COLUMN` steps in `storage.py` collapse into the baseline; later changes are
  new numbered migrations, never edits of old ones. No schema redesign in the split itself.
- **Access**: a database `inbox` and a role `inbox` that owns only it; no superuser from the application; the password
  or the peer-auth identity comes from `/etc/homelab`, never the repo. Connection pool: HikariCP, small (5 connections).
- **Reachability**: the existing cluster listens on its unix socket only and nothing may listen on TCP. pgjdbc cannot
  talk over a unix socket by itself. Options: (a) add the junixsocket library for pgjdbc and keep the cluster socket-only
  (preferred: no listener, but a new dependency); (b) a second listener on `127.0.0.1` with scram-sha-256 (simple, but
  a loopback port that other local users can reach, so it needs a password and a `pg_hba` line for `inbox` only). Open
  decision below. Never a tailnet or LAN listener.
- **Import**: a one-off tool `backend` command (`import-sqlite <path>`) reads `inbox.db` read-only and loads it in one
  transaction, checks row counts per table, and fails loudly on a mismatch. It is re-runnable on an empty database.
- **Backup**: add `pg_dump` of the `inbox` database to `bin/homelab-backup` / `config/backup.list` (today's SQLite file
  is covered by copying it); a restore is tested once before cut-over. Record it in `CHECKLIST.md`.
- **Python side**: runners do **not** open the database. They get what they need through files and arguments from the
  backend and report through stdout, so the database has one client (the backend).
- **Rollback**: until cut-over `inbox.db` is untouched and remains the source of truth for the Python inbox; after
  cut-over keep a final `inbox.db` copy and the last `pg_dump` for one release.
- **Install**: a new `scripts/28-inbox-db.sh` (with a `# homelab-rerun:` line), idempotent, creating role and database
  and checking a round trip; the package `postgresql` is already on the list from forge.

## Migration

Strangler pattern: the Python inbox keeps working until the Java backend can do everything behind it. Each step is
one PR, the box works after each, and rolling back is reverting the PR (plus, for steps 2 onward, switching the
port back).

0. **Write the contract down** (docs and tests only). `backend/openapi.yaml` from the current routes, the runner
   contract in `runner/SCHEMA.md`, and contract tests against the running Python inbox (`tests/`). Needs no new tools.
1. **Frontend off the backend, same API.** Add `frontend/` (TypeScript, built to `frontend/dist`). The Python server
   gets JSON twins of what `pages.py` renders and serves `frontend/dist` for pages already ported; unported pages
   still come from `pages.py`. Port in this order, easiest to riskiest: overview and Skills, Memory, Jobs and
   catalogue, Search, SSH, Taken (task page, live log), Briefs. Each page = one PR that deletes its code in `pages.py`
   and `web/app.js`. The `frontend-developer` agent builds these. *Result: the frontend is out of Python; no Java yet.*
2. **Backend skeleton (Spring Boot) beside the old inbox.** `backend/` with config, the PostgreSQL repository on the
   PostgreSQL schema (Flyway) and the one-time import of `inbox.db` (see "Database"), `GET /api/...` read endpoints, static serving. New install script
   `scripts/27-backend.sh` (JDK, build, unit `homelab-inbox-backend.service`, own port, own ufw rule through
   `config/machine.conf`), reviewed with `exposure-checker`. The new backend runs on a spare port; the old inbox
   still owns 8105. Compare responses of both on the same data.
3. **Move the read side, then the write side, module by module**, in the order that keeps each step small:
   `memory`, `overview`, `forge`/`search` proxies, `conversation`/`chat` (Discord bot and kitt as the test), `gate`
   and `kitt`, `jobs` bookkeeping, `briefs`, `github`, and last `queue`. While both run, **only one of them may
   write** to a given table: ownership is switched per table in one PR, never shared. Until a table is switched, the new backend only reads its imported copy
   and the import is re-run just before the switch.
4. **Runners.** Extract the Claude-calling code out of `rounds.py`, `jobs.py`, `router.py` and research into
   `runner/*.py`, behind the contract above, still called from the Python inbox first (so the contract is proven
   before Java uses it); then Java's `RunnerService` calls them the same way.
5. **Cut over.** The backend takes port 8105 and 8104's redirect; the old inbox unit is disabled but stays installed
   for one release. Then delete `bin/homelab_inbox/` and `web/`, update `CLAUDE.md`'s map, `docs/inbox.md`,
   `docs/dashboard.md` and the rerun lines of the install scripts.
6. **Clean up**: remove the old unit and tests that no longer apply; keep the contract tests.

Each step's PR must: pass the tests that exist for the part it moves (port them, don't drop them: the 17 test files
in `tests/` describe behaviour to keep), update `CHECKLIST.md` and the docs, and carry a `# homelab-rerun:` line on
any new script. A merge that restarts the inbox cuts running tasks; that is already handled (`RESTART_ROUNDS`), but
do cut-over steps when no task is running.

### Order of risk

Frontend ports and read-only endpoints are low risk. The queue, auto merge and the gate are the risky parts (a bug
merges or exposes the wrong thing); they move last, with the Python behaviour pinned by contract tests first.

## Deploy and security

- New accounts: none. The backend runs as the same `inbox-runner` account as the Python inbox does today; runners
  keep running as `inbox` via the existing sudoers line.
- Ports: the backend takes 8105 at cut-over (the existing ufw rule on `tailscale0` stays); on a spare port during the
  migration, with its own rule, bound to the Tailscale address or loopback only.
- Build: `scripts/27-backend.sh` installs a pinned JDK from Ubuntu packages (`homelab-admin package`, so it needs
  `config/machine.conf`), builds with Maven (the Maven wrapper, `mvnw`, with pinned plugin and dependency versions in `backend/pom.xml`), and installs `frontend/dist`
  and the jar. Node is needed to build the frontend; use the version Ubuntu or the project's deploy path already has.
  Dependencies are pinned and listed with versions in the PR.
- RAM and disk on the laptop phase (24 GB, 250 GB): a Spring Boot service with a capped heap (`-Xmx256m` as the
  starting point, to be measured) next to Ollama is fine; measure before step 5 and record the numbers in the PR.
- The frontend never holds a token. Machine clients (Discord, kitt) keep their bearer tokens in `/etc/homelab`.

## Risks

- **Big-bang temptation.** A rewrite of 5,500 lines in a new language can stall half-way. The order above keeps one
  working inbox at every step; stop after step 1 and the result is still useful.
- **Two writers on one dataset.** Controlled by per-table ownership (step 3): a table has exactly one writer, and the import is re-run right before its switch.
- **PostgreSQL is a second moving part to back up and keep running** (see "Database").
- **New framework, new runtime on the box.** Spring Boot, a JDK and a Node build add update work and attack surface;
  `harden-service` and `exposure-checker` apply to the backend like any service.
- **Port of subtle behaviour** (auto-merge rules, conflict rounds, restart recovery, memory scrubbing). Pin it with
  tests derived from the existing Python tests before porting each part.
- **The Python runner is still the sensitive part**: it runs Claude with write access to a working copy. The split
  does not weaken that: the sandbox boundary (`inbox` account, no GitHub token, no `/home/claude`) is unchanged.

## Open decisions for the user

0. **Database**: confirm PostgreSQL, and choose how Java reaches it: unix socket via junixsocket (preferred) or a
   loopback listener with a password.

1. **Go-ahead for Java 21 and Spring Boot** as a new framework on the box (CLAUDE.md asks before new ones).
   Decided in review: the build tool is **Maven**.
2. **Frontend tooling**: Vite + plain TypeScript (no UI framework), confirmed in review. A framework such as React or Lit is
   possible; the `frontend-developer` agent will not add one without being told.
3. **Stop after step 1?** Step 1 alone already takes the frontend out of Python. Whether to continue to Java after
   seeing it is the user's call; the plan is built so that stopping there is fine.
4. **Data repo and secrets** stay where they are (`DATA_REPO`, `/etc/homelab`); assumed nothing moves. The database is the only data that moves (SQLite to PostgreSQL).
