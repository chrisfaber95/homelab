# Inbox backend on the box (H-023, task B3)

The Spring Boot backend (`backend/`, task B1) runs on the box beside the old Python inbox, as the systemd service
`homelab-inbox-backend`. It answers `GET /api/backend/health` and, since H-024 M1 and M2, the overview, live, log, skills and memory reads
([M1](#overview-live-and-log-reads-m1-h-024), [M2](#skills-and-memory-reads-m2-h-024)); and, since H-024 M3, the [forge and search proxies](#moved-routes-forge-and-search-proxies-h-024-m3); nothing else is switched over (plan: [`docs/architecture/inbox-split.md`](architecture/inbox-split.md)). The database it will use is described in
[`docs/inbox-db.md`](inbox-db.md).

Installer: `scripts/27-backend.sh` (root, idempotent,
`# homelab-rerun: root backend systemd/homelab-inbox-backend.service config/inbox.conf`, so a merge that changes
`backend/` builds and installs the new jar by itself). Run it by hand from root's clone:
`sudo bash /var/lib/homelab/repo/scripts/27-backend.sh`. It needs `scripts/28-inbox-db.sh` (B2) first.

## What it sets up

| What | Value |
| --- | --- |
| Packages | `openjdk-21-jdk-headless` (JDK: `javac` for the build, `jcmd`/`jstat` for measuring), `unzip` and `curl` (the Maven wrapper needs them) |
| Build (Q3) | Maven through the wrapper (`backend/mvnw`, Maven 3.9.16 with checksum) as a **throwaway unprivileged user**: `systemd-run` with `DynamicUser=yes` (transient unit `homelab-backend-build`), state in `/var/lib/private/homelab-backend-build` (Maven cache and output only), hardened (`ProtectSystem=strict`, no capabilities, `NoNewPrivileges`). Never as root. Root makes a fresh copy of `backend/` first (`/var/lib/homelab-backend-src`, removed after the build), so the build account never reads root's clone. The build runs `package -DskipTests`: tests run in CI (job `backend`); a throwaway PostgreSQL for tests on the box is Q5/B4 |
| npm | none. Should a script here ever run `npm ci`, it runs the same way (throwaway user) with `--ignore-scripts` |
| Jar | root checks the build output (plain file, valid zip, contains the backend's classes) and copies it to `/usr/local/lib/homelab/inbox-backend/inbox-backend.jar` (root-owned, 644); root never runs it |
| Unit | `systemd/homelab-inbox-backend.service` → `/etc/systemd/system/`, enabled and started; restarted when the jar or the unit changed |
| Account | `inbox-runner` (the account of the old inbox): the PostgreSQL role `inbox_app` trusts that name through peer authentication, a `DynamicUser` would be refused |
| Network | binds `127.0.0.1:8130` (`BACKEND_HOST`, `BACKEND_PORT`; the port is also in `config/inbox.conf`). `IPAddressDeny=any` with only loopback allowed. No ufw rule, nothing on the tailnet |
| Database | `SPRING_DATASOURCE_URL=jdbc:postgresql://localhost/inbox?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg=/var/run/postgresql/.s.PGSQL.5432`, user `inbox_app`, no password. pgjdbc reaches the socket through junixsocket (`junixsocket-core` 2.11.1, pinned in `backend/pom.xml`). The datasource and Flyway stay switched off in `application.properties` until B4 adds the schema, so for now the service does not open a connection |
| Heap | `-Xms64m -Xmx256m`, `-XX:MaxMetaspaceSize=128m`, `-XX:MaxDirectMemorySize=64m`, serial GC (one small service, few threads), `-XX:+ExitOnOutOfMemoryError`; the unit has `MemoryMax=640M` as a ceiling for everything else (metaspace, code cache, thread stacks, junixsocket's native library) |
| Hardening | `NoNewPrivileges`, `ProtectSystem=strict`, `ProtectHome`, `PrivateTmp`, `PrivateDevices`, `ProtectKernel*`, `ProtectControlGroups`, `ProtectClock`, `RestrictNamespaces`, `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX`, empty capability set. No `MemoryDenyWriteExecute`: the JVM's JIT needs it |

## The facade: forwarding from the Python server (B6)

The old Python inbox stays in front (Q6). Routes listed in `BACKEND_ROUTES` are forwarded to the backend; everything
else is handled as before. **With `BACKEND_ROUTES` empty (the default) nothing is forwarded** and the server behaves
exactly as before; rollback of a moved route is removing its prefix.

| Setting | Where | Meaning |
| --- | --- | --- |
| `BACKEND_ROUTES` | env, or `config/inbox.conf` (empty by default) | Comma-separated path prefixes, each under `/api/` (`/api/backend` matches `/api/backend` and `/api/backend/...`, not `/api/backendx`). Others are ignored: pages and `/internal/...` are never forwarded. Set it in the inbox unit (`Environment=BACKEND_ROUTES=/api/backend`) or in `config/inbox.conf`, then run `scripts/21-inbox.sh` |
| `BACKEND_URL` | env | Default `http://127.0.0.1:<BACKEND_PORT>`. Must be `http` on loopback; anything else makes every forward fail with 502 |
| `BACKEND_SECRET_FILE` | env, both processes | File with the shared secret. Default for the inbox: `$RUNNER_HOME/backend-secret` (`/var/lib/homelab-inbox/backend-secret`); the backend unit sets the same path. Made by `scripts/27-backend.sh` (random, 0600, `inbox-runner`), never in the repo |
| `BACKEND_SECRET` | env, both processes | The secret itself instead of the file (it wins over the file). For tests and one-off use; keep it out of the repo |

Order of a forwarded request: Tailscale owner check (`guard`), then, for POST/PUT/PATCH/DELETE, the same Origin and
`X-CSRF-Token` check as every `/api/*` request, then the forward. The facade sends method, path with query and body
(`Content-Length` required, at most 8 MiB) and the headers `Content-Type`, `Accept`, `Accept-Language`, `If-None-Match`,
`If-Modified-Since`, plus `X-Inbox-Facade: <secret>` and `X-Inbox-Who: <device>`; cookies and anything the client put in
those two headers are dropped. Status, headers (not hop-by-hop ones) and body come back unchanged. Paths with `..`,
backslashes, `//` or `%2f` are refused (400). If the backend cannot be reached, times out (30 s) or no secret is
configured, the answer is 502 `{"error": ...}`; there is no fallback to a Python handler.

The backend (`FacadeSecretFilter`) answers 403 to every request without the right `X-Inbox-Facade` (constant-time
compare), except `GET /api/backend/health`. Without a configured secret it refuses everything but health.
Doors with their own token (chat, kitt) do not pass the owner check, so they cannot be forwarded.

## Moved routes: forge and search proxies (H-024, M3)

Java serves these routes now; `/api/forge` and `/api/search` in `BACKEND_ROUTES` (`config/inbox.conf`) makes the
Python server forward them (after its own access and CSRF checks). Code: `backend/src/main/java/dev/homelab/inbox/proxy/`
(`ForgeController`, `SearchController`, `Upstream`); the Python originals (`forge.py`, `search.py`, the
handlers in `server.py`) stay in place, unused for these paths, so the rollback is a plain revert.

| Route | Upstream |
| --- | --- |
| `GET /api/forge`, `POST /api/forge/start`, `POST /api/forge/stop` | forge `FORGE_URL` (`/api/state`, `/api/start`, `/api/stop`) |
| `GET /api/search/filters`, `GET /api/search/results` | `SEARCH_URL` `/v1/filters`, `/v1/search` |
| `POST /api/search/ask` | `SEARCH_URL` `/v1/ask` (waits up to 330 s) |
| `POST /api/search/feedback` | `ROUTER_URL` `/v1/feedback` |

Same paths, request fields, response bodies and error texts as before (errors flattened to `{"error": "text"}`, 502
`Forge is not reachable ...`, `The search service is unreachable: ...`, 404 `{"error": "Not found."}` for other paths under
these prefixes). Known small differences: the Content-Type is written `application/json;charset=utf-8` (no space; Tomcat),
the text after `unreachable:` is Java's exception text, and a Start/Stop does not drop the Python 2-second cache of
the "Nu bezig" row; the Java reads drop their own cache after a press. Discord and kitt do not use these routes
(they call the Python modules in process or their own doors).

- **Upstreams.** `FORGE_URL`, `SEARCH_URL`, `ROUTER_URL` come from the installed `config/inbox.conf` through
  `Settings` (as for the M1 reads; an environment variable wins). Forge's log, `GET /api/log/f1`, is the M1 route.
  Forge listens on the Tailscale address, which the unit already allows (`IPAddressAllow=100.85.70.34/32`).
- **No fallback.** When the backend is down these routes answer 502 (`the backend is not available`); check
  `systemctl status homelab-inbox-backend` first.
- **Order after the merge.** `27-backend.sh` (new jar) and `21-inbox.sh` (new conf) both rerun; until both are done
  the routes may answer 502 for a moment.

### Tests and parity (results of this change)

- `./mvnw verify` in `backend/`: `ProxyControllersTest` (fake upstream; 11 cases) and `DeadUpstreamTest`.
- Contract tests against Java: start the backend with the three URLs set to `http://127.0.0.1:<P>` and run
  `INBOX_CONTRACT_JAVA_URL=http://127.0.0.1:<port> INBOX_CONTRACT_JAVA_SECRET=<secret> INBOX_CONTRACT_UPSTREAM_PORT=<P>
  python3 -m unittest tests.test_inbox_contract.PageMachineEndpoints`. The in-process Python server forwards the moved
  routes to Java exactly as in production; all 13 cases pass (with a wrong secret they fail, so Java is really in the path).
- Parity: `python3 backend/parity/m3_fixture.py --java-url http://127.0.0.1:<port> --java-secret <secret> --upstream-port <P>`
  starts a fake upstream and the Python handler, then compares the GET routes (`/api/forge`, `/api/log/f1`, `/api/search/*`
  including query variants from `parity.json` `queries`): 7 same, 0 different.
- The whole Python suite passes (485 tests). The suites set `BACKEND_ROUTES=""` so the Python handlers stay tested.

### Rollback

No store is involved: these routes read and write nothing of their own. Revert this PR (it puts the two prefixes
out of `BACKEND_ROUTES` and removes the Java routes), let the merge rerun `21-inbox.sh` and `27-backend.sh`. For a quick
stop without a revert: remove `/api/forge,/api/search` from `BACKEND_ROUTES` in `config/inbox.conf` and run `sudo bash /var/lib/homelab/repo/scripts/21-inbox.sh`; the Python
handlers answer again at once.

## Checks (the script fails loudly when one fails)

1. B2 is in place: account `inbox-runner`, PostgreSQL running, database `inbox` exists.
2. JDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64`; the Maven build succeeds and leaves exactly one jar.
3. `GET http://127.0.0.1:8130/api/backend/health` answers `"status":"ok"` (45 tries, 2 s apart).
4. The main process runs as `inbox-runner`; every TCP listener of that process is on loopback, and `127.0.0.1:8130`
   is one of them.
5. PostgreSQL still has an empty `listen_addresses`.
6. The facade secret file exists (0600, `inbox-runner`); a request without it gets 403, with it not 403.

`bin/homelab-exposure` checks the same from the repo side (the unit sets `BACKEND_HOST=127.0.0.1`, the port agrees with
`config/inbox.conf`, `IPAddressDeny=any`, `server.address` defaults to loopback, the script touches no ufw) and, on the
box, that port 8130 listens on `127.0.0.1` only. The `exposure-checker` agent knows about it through that script.

## Measuring the heap on the box

**Automatic record.** Every run of `scripts/27-backend.sh` waits 10 s after the health check, forces a full GC with
`jcmd` as `inbox-runner` and appends one line to `/var/lib/homelab-inbox/backend-heap.log` (heap used after GC,
heap committed, metaspace, `MemoryCurrent`/`MemoryPeak`, limits, jar hash); it prints the same line. Read it with
`tail /var/lib/homelab-inbox/backend-heap.log`. The commands below are for measuring by hand, under load.

Do this after the first install, and again when the backend gets real work (B4/B5, import and parity runs). The
numbers go in the table below. `claude` has no sudo and cannot read system journals, so ask Chris to run the
commands with sudo, or use the output of `scripts/27-backend.sh` (it ends with a snapshot).

```sh
PID=$(systemctl show -p MainPID --value homelab-inbox-backend)
JCMD=/usr/lib/jvm/java-21-openjdk-amd64/bin/jcmd
JSTAT=/usr/lib/jvm/java-21-openjdk-amd64/bin/jstat

# Heap now (used / committed / reserved), as the account of the service (jcmd attaches only as the same user):
sudo -u inbox-runner $JCMD $PID GC.heap_info
# Same, after a full GC: what the application really holds on to (the "live set"):
sudo -u inbox-runner $JCMD $PID GC.run; sudo -u inbox-runner $JCMD $PID GC.heap_info
# Generations and metaspace, once per second, five samples (columns in KB; OU = old used, MU = metaspace used):
sudo -u inbox-runner $JSTAT -gc $PID 1000 5
# Native memory beyond the heap needs -XX:NativeMemoryTracking=summary on the ExecStart line first; then:
sudo -u inbox-runner $JCMD $PID VM.native_memory summary

# What the kernel counts for the whole service (heap + metaspace + stacks + code cache): the unit's accounting.
systemctl show -p MemoryCurrent -p MemoryPeak homelab-inbox-backend
systemctl status homelab-inbox-backend       # the Memory: line
ps -o rss=,vsz= -p $PID                       # resident set in KB
```

How to read it: *heap used after a full GC* is the number that matters for sizing `-Xmx` (leave at least 2× of it as
headroom); *MemoryPeak* is the number to compare with `MemoryMax=640M`. If `MemoryPeak` gets near the ceiling, or the
live set near 128 MiB, raise `-Xmx` and `MemoryMax` together in `systemd/homelab-inbox-backend.service` (a PR, the
script installs it).

### Measured values

Fill this in from a real run on the box (date, what the service was doing, the numbers); leave older rows.

| Date | State | Heap used after full GC | Heap committed | Metaspace used | `MemoryCurrent` / `MemoryPeak` | Measured by |
| --- | --- | --- | --- | --- | --- | --- |
| *no value yet* | idle, just started | see `/var/lib/homelab-inbox/backend-heap.log` after the first install | | | | `scripts/27-backend.sh` |

No number is written here yet: it appears on the box only once `scripts/27-backend.sh` has run (the box had no JDK on
2026-11-17 and an inbox task has no root). After the merge, copy the first log line into this table.

## Rollback

Revert the commit and run nothing; to remove what was installed:
`sudo systemctl disable --now homelab-inbox-backend && sudo rm /etc/systemd/system/homelab-inbox-backend.service
&& sudo systemctl daemon-reload && sudo rm -r /usr/local/lib/homelab/inbox-backend /var/lib/private/homelab-backend-build`.
The JDK package and the database may stay. The old inbox is not touched by any of this.

## Parity runs (B7)

`backend/parity/parity.py` sends the same GET requests to the Python inbox and to the Java backend and compares status,
media type and normalized body. It never writes: it only sends GET. Run it after `import-sqlite` (B5), so both sides
read the same data (Python on `inbox.db`, Java on PostgreSQL `inbox`); a parity run against a database that was not
freshly imported compares stale data and means nothing.

- **Routes.** The GET operations of `backend/openapi.yaml` (the H-020 C1 contract; needs `python3-yaml`).
  `backend/parity/parity.json` adds what the spec cannot say: `examples` (a real id for each `{param}` path; without one
  the route is skipped), `exclude` (`/static/*`, `/api/session`: differ on purpose), `skip`, and `ignore` (JSON keys
  that legitimately differ, for all routes under `*` or per spec path; `a.b` means the key `b` inside `a`).
- **Result per route.** `OK`, `DIFF` (with a unified diff of the normalized answers, `-` Python, `+` Java) or `SKIP`.
  Java answering 404 or 501 where Python does not means *not implemented yet*: skipped, not a failure. Today that is
  every route except the M1 and M2 ones (`/api/overview`, `/api/live`, `/api/log/*`, `/api/skills`, `/api/memory`,
  `/api/memory/e/*`), so a run prints SKIP lines for the rest until the other M slices land.
  A Java 403 is a difference: it usually means the facade secret is missing.
- **Exit code.** 0 no differences, 1 at least one `DIFF`, 2 the run could not start (a side is down, bad option).
- **Options.** `--python-url` (default `http://127.0.0.1:8105`), `--java-url` (`http://127.0.0.1:8130`),
  `--python-header` / `--java-header 'Name: value'` (repeatable), `--java-secret-file FILE` (sent as `X-Inbox-Facade`,
  the secret of B6), `--only PREFIX`.

```sh
python3 backend/parity/parity.py --only /api/ --java-secret-file /var/lib/homelab-inbox/<facade-secret-file>
python3 -m unittest tests.test_parity     # the comparison logic; no servers needed
```

Limits to know: the Python server answers 403 to the box itself on page routes (`x-access: page`), so on the box
the Python side only answers the chat door and kitt door routes (give their headers with `--python-header`; none of
those are GET routes in the spec that this harness covers well yet). For page routes run it from a tailnet device with
`--python-url http://homelab.tail4eb878.ts.net:8105` and an SSH tunnel to `127.0.0.1:8130` for Java, passing the
secret with `--java-header`. Do not start a second Python inbox for this: it would run the scheduler on the same data.

## Overview, live and log reads (M1, H-024)

Three read routes are served by Java; the Python server forwards them (`BACKEND_ROUTES="/api/overview,/api/live,/api/log"`
in `config/inbox.conf`) after its own tailnet owner check, so who may call them has not changed. Discord and kitt do not
use them (they use the doors with their own token, `/api/chat/*` and kitt's door, which are never forwarded).

| Route | Answer (as before) |
| --- | --- |
| `GET /api/overview` | everything the Overview page shows: status sentence, live items, the job timeline, the last five changes, services, switches, a week of usage, health. `application/json; charset=utf-8` |
| `GET /api/live` | `{items, queued, activity}`, plain `application/json` |
| `GET /api/log/t<id>`, `/api/log/r<id>` | lines of `logs/<key>.log` from `?from=` (at most 500), `{lines, next, done}`; an unknown id is empty and done |
| `GET /api/log/f1` | forge's log through forge's own endpoint; 502 `{"error": ...}` when forge does not answer |

Other keys under `/api/log/` are 404 `{"error": "Not found."}`; a `from` that is not a number is 400. The code is in
`backend/src/main/java/dev/homelab/inbox/read/`; it is a port of `overview.py`, the log branch of `server.py`, `forge.py`
(read half) and `jobs.parse_job`/`Cron`, with the same Dutch sentences.

**Where it reads (and why SQLite, not PostgreSQL).** The PostgreSQL `inbox` database is a one-off import and nothing writes to it
yet, so reading it would show stale running tasks, runs and logs. `InboxDb` is therefore a bridge: each table's queries move to
PostgreSQL when that table's writer moves to Java (M4, H-026), and the class goes when the last one has. No store is switched: Python is still the only writer of every table, so Java reads **the live files of
the Python inbox, read-only**, not the PostgreSQL copy (that one is a one-off import and is stale as soon as Python writes).
`inbox.db` is opened with sqlite-jdbc read-only (`InboxDb`), `logs/`, `jobs-repo/jobs/*.md`, `repos/homelab/skills/`,
`new-repos.json`, the monitor and deploy state, `changes.jsonl` and `backup.json` are read from the same paths as Python,
and forge (`FORGE_URL`) and the LLM helper (`LLM_URL`) are asked over HTTP with the same timeouts. Settings come from the installed
`*.conf` files (`INBOX_CONFIG_DIR`, set in the unit, default `/usr/local/lib/homelab/config`) with the same environment overrides.
The unit therefore also allows the box's own tailnet address (`IPAddressAllow=... 100.85.70.34/32`) so Java can reach forge; nothing
else outside loopback is allowed. Job files are read on each request (Python keeps them in memory between syncs).

**One known difference.** Python remembers in memory when the *running round* of a task started (`_round_started`) and shows that
as `started` of a running task; that is not in the database. Java shows the task's own `started`. For a task in its first round
they are equal; in a later round the page's "bezig sinds" shows the start of the task, not of the round. Parity runs report this as
a `DIFF` on `started` of a live item for such a task; it is not a bug in a route.

**Checks.** `python3 -m unittest tests.test_inbox_m1_parity` (CI runs it after `./mvnw verify`; skipped without a built jar) starts the
Python server and the built jar on the same fixture state (`tests/m1_fixture.py`: tasks, runs, logs, job files good and broken, paused and
deleted jobs, monitor files, a fake forge and LLM helper) and compares them with the parity harness, then runs
`tests.test_inbox_contract.MovedReads` against the Java base URL (`INBOX_CONTRACT_BASE_URL`, `INBOX_CONTRACT_FACADE_SECRET`). The
only fields left out of the comparison are the time-dependent ones (`x`, `status.at`, see `backend/parity/parity.json`). Java unit tests
(`CronTest`, `TextTest`) pin cron times, timeline positions and the sentence helpers to values from the Python code.
On the box, after the merge: `python3 backend/parity/parity.py --only /api/overview --only /api/live --only /api/log
--java-secret-file /var/lib/homelab-inbox/backend-secret` (see "Parity runs" for the limits).

**Order after the merge.** `homelab-admin` runs `21-inbox.sh` (restarts the inbox with the routes) before `27-backend.sh` (builds and
starts the new jar). In between, which can be a few minutes, the old jar does not have these routes and the Overview, live and log
calls fail with 404 or 502; they work again as soon as `27-backend.sh` has restarted the backend. Nothing else is affected.

### Rollback of the M1 switch

Revert the PR (or only remove the three prefixes from `BACKEND_ROUTES` in `config/inbox.conf`) and let `scripts/21-inbox.sh` run again
(a merge of the revert does that). The Python handlers for these routes are untouched in this PR, so they answer again at once.
**These routes are read-only and no store changes owner, so no re-import and no data move are needed:** Java wrote nothing,
and Python never stopped being the only writer. The extra files in the backend (`read/` package, unit settings) may stay; with the
prefixes gone nothing calls them.

## Skills and memory reads (M2, H-024)

Three more read routes are served by Java, forwarded by the Python server (`BACKEND_ROUTES` now also has `/api/skills` and
`/api/memory`) after its own tailnet owner check, so who may call them has not changed. Discord and kitt do not use them (their doors,
`/api/chat/*` and kitt's, are never forwarded). The pages (`/skills`, `/memory`, `/memory/e/<id>`) and every memory **write** stay
in Python: the add, save and delete forms post to `/memory/...`, which is not under a forwarded prefix, and the `GEHEUGEN:` blocks of
a task are saved by the Python queue. Python is the only writer of the memory store; the writes move in M5.

| Route | Answer (as before) |
| --- | --- |
| `GET /api/skills` | the merged skills of `repos/homelab/skills/*/SKILL.md` with the jobs that use each, the 20 newest skill proposals, the page's Dutch texts |
| `GET /api/memory[?q=]` | preferences, facts and history (newest first, the newest 30 unless `q` is given) filtered by every word of `q` (cut to 200 characters), and the `abouts` of the add form |
| `GET /api/memory/e/<id>` | one entry plus `branch` and `data_repo`; an unknown id is 404 `{"error": "No such memory entry."}` |

Other GET and POST paths under these prefixes are 404 `{"error": "Not found."}`, PUT, PATCH and DELETE are 405 `{"error": "<METHOD> is not supported"}`:
the same words as Python's. The code is in `read/` (`MemoryReader`, `SkillsService`, in `ReadController`); it ports `memory.py` (parse, view, entry),
`jobs.skill_list` and `overview.skills_data`.

**Where it reads.** Like M1, from the live files of the Python inbox, read-only, not from PostgreSQL (nothing writes there yet): the
`memory-repo` clone (`<RUNNER_HOME>/memory-repo`, the `memory` branch of `DATA_REPO`) on each request, where Python keeps the entries in memory
between writes; `repos/homelab/skills/`; the job files for the `jobs` of a skill; and `skill_prs` in `inbox.db` through `InboxDb`. Differences
that are known and not bugs: Java sees a change of the clone as soon as the files are written, Python after its reload (a few ms to seconds
later, once a write finished); a hand-edited front matter with an unquoted date **and time** with a `T` is shown as written, Python shows
`datetime` with a space (a plain date, and the quoted values the inbox writes, are the same).

**Checks.** `python3 -m unittest tests.test_inbox_m1_parity` (the M1 test, now also M2; needs a built jar) runs the parity harness on the
fixture state of `tests/m1_fixture.py` (skills with quoted, missing and multi-line descriptions, 23 skill proposals, a memory clone with
preferences, facts and 40 history entries, entries a hand edit leaves behind: no front matter, broken YAML, a list as front matter, a plain date,
no offset, a text that is no date, files that are no entries) for `/api/skills`, `/api/memory` with ten queries and every entry id plus the
not-found cases, and then `tests.test_inbox_contract.MovedReads` and `MovedMemoryAndSkills` against the Java base URL
(`INBOX_CONTRACT_BASE_URL`, `INBOX_CONTRACT_FACADE_SECRET`). Java unit tests: `MemoryReaderTest`. On the box, after the merge:
`python3 backend/parity/parity.py --only /api/skills --only /api/memory --java-secret-file /var/lib/homelab-inbox/backend-secret`.

### Rollback of the M2 switch

Revert the PR (or only remove `/api/skills` and `/api/memory` from `BACKEND_ROUTES` in `config/inbox.conf`) and let `scripts/21-inbox.sh`
run again (a merge of the revert does that). The Python handlers are untouched, so they answer again at once. **No re-import and no data
move are needed:** Java wrote nothing, and Python never stopped being the only writer of the memory clone, `skill_prs` and the job files.
