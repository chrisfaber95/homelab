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
| Network | binds `127.0.0.1:8130` (`BACKEND_HOST`, `BACKEND_PORT`; the port is also in `config/inbox.conf`). Listens on loopback only; since M5 outgoing traffic is not limited (git push to GitHub, see "Memory writes (M5)"). No ufw rule, nothing on the tailnet |
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
`config/inbox.conf`, `ReadWritePaths` is only the memory clone, `server.address` defaults to loopback, the script touches no ufw) and, on the
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

### Measuring with one command (H-026 K9)

`bin/homelab-backend-heap [--gc] [unit-or-pid]` prints heap used, heap committed, the max heap setting, the JVM flags and
the scheduler switch of the running backend (default unit `homelab-inbox-backend`). `--gc` forces a full GC first, so
*used* is the live set. It re-runs itself as the owner of the process through `sudo -u inbox-runner` (jcmd only attaches
as the same user), reads only, and changes no port, config or service. The first line to record is the one with `--gc`.

```sh
sudo /var/lib/homelab/repo/bin/homelab-backend-heap --gc      # path of root's clone; or run it from a checkout of main
```

**Shadow mode, against a copy of the data.** The backend is in shadow when `CORE_SCHEDULER_ENABLED` is unset or `false`
(the default: no scheduler, no real task, merge or notification). To measure it against a copy and not the live data, do
the import into a scratch database and start a second instance on another loopback port, then measure that one by pid:

```sh
sudo -u postgres createdb -O inbox_app inbox_copy      # scratch copy; drop it afterwards: dropdb inbox_copy
sudo -u inbox-runner env BACKEND_PORT=8131 CORE_SCHEDULER_ENABLED=false \
  SPRING_DATASOURCE_URL='jdbc:postgresql://localhost/inbox_copy?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg=/var/run/postgresql/.s.PGSQL.5432' \
  SPRING_DATASOURCE_USERNAME=inbox_app BACKEND_SECRET_FILE=/var/lib/homelab-inbox/backend-secret \
  INBOX_CONFIG_DIR=/usr/local/lib/homelab/config \
  /usr/lib/jvm/java-21-openjdk-amd64/bin/java -Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=64m \
  -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -jar /usr/local/lib/homelab/inbox-backend/inbox-backend.jar &
# import the data into inbox_copy as in "The import" of the sections above, then:
sudo /var/lib/homelab/repo/bin/homelab-backend-heap --gc <pid of that java>
```

Use the same flags as `ExecStart` in the unit, so the number is the one of the service. Stop the copy by its pid and
drop `inbox_copy` when done. Nothing here opens a port beyond loopback or switches a route to Java.

### Heap record (H-026 K9)

One row per measurement: paste the output of `homelab-backend-heap --gc`. Fill it in on the box; leave older rows.
Empty until someone measures: no value is claimed here.

| Date | Machine | JVM flags | State (shadow, scheduler off; data copy or live) | Heap used after full GC (MiB) | Heap committed (MiB) | Max heap (MiB) |
| --- | --- | --- | --- | --- | --- | --- |
| *not measured yet* | | | | | | |

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
Java reaches forge on the box's own tailnet address (since M5 the unit no longer limits outgoing traffic). Job files are read on each request (Python keeps them in memory between syncs).

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

## Conversation store (M4, H-024)

The store behind `/api/chat/*` moved to Java: PostgreSQL tables `conversation` and `conversation_refusals`, and the `switch:*`
keys of `kv`. **From the merge on the Java backend is the only writer.** Code: `backend/src/main/java/dev/homelab/inbox/conversation/`
(`ConversationStore`, `ConversationController`, `Clients`), the import in `.../db/ConversationImporter.java` and
`ConversationCommand.java`. The Python side is a client: `bin/homelab_inbox/conversation.py` keeps its function names
(`append`, `note`, `read`, `allowed`, `switch`, ...) and calls the backend on loopback with the facade secret, so the queue, the kitt
door, notifications and the overview did not change.

**Account (checked on the box, 2026-11-17).** The backend runs as `inbox-runner` (`User=` in the unit; `ps` shows it), like the Python
inbox; only the Maven *build* uses a `DynamicUser`. The plan in `inbox-split.md` was right; the PostgreSQL role `inbox_app` trusts that name.

### Routes

| Route | Who answers | Notes |
| --- | --- | --- |
| `GET /api/chat/state` | Java | same body; non-GET is 405 `GET only` |
| `GET /api/chat/entries?after=&limit=` | Java | cursor, limit 1 to 1000, `next`; 403 `refused: no permission 'read'`, 400 for non-numbers |
| `POST /api/chat/entries` | Java | always written as Chris; kind needs `write`/`ask`/`answer`; scrubbing, 20000 characters, `ref` 80 |
| `POST /api/chat/action` | **Python** | task queue and approvals; ported in shadow (K7, `door/`), still Python's until H-027 |

The paths, bodies and status codes are unchanged. **Tokens:** the bearer token (`/etc/homelab/chat-<name>-token`) is still checked by
the Python door (`conversation.authenticate`), which also checks loopback; it then calls Java with `X-Inbox-Client: <name>`. Java trusts
that header only together with the facade secret. **This moves to Java in K7** (see `docs/architecture/inbox-split.md`). **Permissions**
come from `config/clients.json`, read by Java on every request (`INBOX_CONFIG_DIR`), including the Discord and kitt switches. The Discord
bot is not changed.

Internal routes for the rest of the Python inbox (secret required, not reachable from outside): `POST /internal/conversation/append`,
`GET /internal/conversation/entries`, `GET /internal/conversation/refusals`, `POST /internal/conversation/allowed`,
`GET|POST /internal/conversation/switch/<name>`.

### Failure behaviour (fail closed)

The Java store answers 503 until the import has run (marker row `store:conversation` in `kv`) or while PostgreSQL is down. Python then:
the chat door answers 503; `note()` (notifications, task rounds) logs and gives up; `allowed()` says no; `switch()` reads as on; `read()` and
`append()` raise. Nothing is written to SQLite as a fallback: that would be a second writer.

### The import (the switch)

`scripts/conversation-import.sh` does it, called by `scripts/21-inbox.sh` (after it installs the Python code) and by
`scripts/27-backend.sh` (after it installs the jar). It **skips unless both are installed** (the Python code has `conversation.door`, the
jar has `ConversationCommand`) and `inbox.db` exists, so whichever of the two scripts runs last does the import, in either order.
It then stops `homelab-inbox` (no process of the old or the new code runs), runs, as `inbox-runner`:

```sh
sudo -u inbox-runner /usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar /usr/local/lib/homelab/inbox-backend/inbox-backend.jar \
  import-conversation --sqlite /var/lib/homelab-inbox/inbox.db --if-not-imported
```

and starts `homelab-inbox` again. One PostgreSQL transaction: the old Java rows are replaced by the SQLite `conversation`,
`conversation_refusals` and `kv switch:*` rows (ids kept, sequences reset, counts compared), and the marker is written last.
`--if-not-imported` makes a re-run a no-op once Java owns the store, so it never overwrites what Java wrote after the switch. The other
`kv` keys stay in SQLite. Without the flag a second import refuses (exit 2); `--replace` redoes it and is only for before the switch.

**One writer at every moment.** Until the import the old Python code is the only writer. The import only runs when the Python code on
disk is the M4 code, and it stops the service first, so the old code is no longer running when SQLite is read, and the M4 code never writes
SQLite. From the marker on Java is the only writer. What can be lost is only availability, not data from the old store: while the new
Python code runs but the import has not happened yet (a few minutes while the backend builds), the chat door answers 503 and
notifications are not stored (logged); nothing is written anywhere else.

### Rollback (revert the PR, then re-import into the Python store)

Java must hand its data back to SQLite **with the jar of this PR, before the revert is installed** (the revert removes the command). With the
Python inbox stopped, nothing writes either store:

```sh
sudo systemctl stop homelab-inbox
sudo -u inbox-runner /usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar /usr/local/lib/homelab/inbox-backend/inbox-backend.jar \
  export-conversation --sqlite /var/lib/homelab-inbox/inbox.db      # prints: export ok: conversation=N conversation_refusals=M switches=K (counts equal)
# then merge the revert PR of this change; homelab-admin reruns 21-inbox.sh and 27-backend.sh (old Python code, old jar)
sudo systemctl start homelab-inbox     # if the rerun did not already start it
```

`export-conversation` replaces `conversation`, `conversation_refusals` and the `switch:*` keys in `inbox.db` in one transaction (ids kept)
and leaves every other table alone. Afterwards check `sqlite3 /var/lib/homelab-inbox/inbox.db 'select count(*), max(id) from conversation'`
against the export line. The PostgreSQL copy stays as it is. If the revert was already installed, build this PR's jar from its merge commit
and run the command above with it.

### After the merge: what to check

1. `systemctl is-active homelab-inbox homelab-inbox-backend` and `curl -s http://127.0.0.1:8130/api/backend/health`.
2. Send a Discord message to the bot: it must answer and `curl -H "Authorization: Bearer <discord token>" 'http://127.0.0.1:8105/api/chat/entries?after=0'` shows it.
3. Open kitt on the phone: the chat thread shows the same message, and sending a question from kitt appears in it.
4. The homelab page: the Discord and kitt switches still toggle (Overview).

### Tests

`python3 -m unittest tests.test_inbox_m4_parity` (needs the jar and PostgreSQL binaries): the reference inbox (old in-process store) and
the inbox in front of Java get the same scripted requests with the tokens and permissions of `config/clients.json`, and must answer the same;
then the same checks run against the Java base URL with the facade secret and `X-Inbox-Client` (per client of `clients.json`, plus the
no-secret cases). Known difference: the wording of a JSON parse error (`bad request: ...`). Java: `ConversationStoreTest`,
`ConversationImporterTest` (throwaway PostgreSQL). The other Python tests use `tests/conversation_fake.py`, an in-process stand-in for the store.

## Memory writes (M5, H-024)

The memory store is the clone `memory-repo` of the `memory` branch of DATA_REPO (Markdown files, not a table). **From the merge on the Java
backend is its only writer**: it syncs the clone to GitHub, writes the files, makes one commit per change and pushes to the `memory`
branch with the token of the inbox. Code: `backend/src/main/java/dev/homelab/inbox/memory/` (`MemoryRepo` git and ownership, `MemoryWriter`
the rules and the file format, `MemoryController` the routes) and `.../db/MemoryCommand.java` (import and export). The Python side
(`bin/homelab_inbox/memory.py`) only reads the files (the `MEMORY` cache, the prompt of a task) and asks the backend to write.

### Routes

The public routes keep their paths, forms, CSRF/owner check, status codes, `{"error": ...}` bodies and the 303 redirects: they are still
answered by `server.py`, which passes the form on. Java answers the routes below (facade secret required, loopback only):

| Public route (unchanged) | Internal route (Java) | Answer |
| --- | --- | --- |
| `POST /memory/add` | `POST /internal/memory/add` | `{"location"}` (Python sends 303), 400 `{"error"}` with the old Dutch messages |
| `POST /memory/e/<id>/save` | `POST /internal/memory/save` | same; 404 `Niet gevonden.` for an unknown entry |
| `POST /memory/e/<id>/delete` | `POST /internal/memory/delete` | `{"location": "/memory"}` |
| `GEHEUGEN:` blocks of Claude (queue) | `POST /internal/memory/learned` | `{"notes": [...]}` |
| history of a finished task (`remember_task`) | `POST /internal/memory/history` | `{"saved": bool}` |
| start of the inbox, before each round, every minute (`load_memory`) | `POST /internal/memory/sync` | `{"ok", "owned"}` |

If the backend does not answer, the page gets 502 `{"error"}` and nothing is written (no fallback writer in Python). `GET /api/memory*`
(M2) still reads the same clone. The commit author and message of each change are the ones Python used.

### The import (the switch)

`scripts/memory-import.sh`, called by `21-inbox.sh` and `27-backend.sh` (whichever runs last), skips unless the installed Python code is the M5
code and the installed jar has `MemoryCommand`. It stops `homelab-inbox`, and runs as `inbox-runner`:

```sh
sudo -u inbox-runner env INBOX_CONFIG_DIR=/usr/local/lib/homelab/config INBOX_RUNNER_HOME=/var/lib/homelab-inbox \
  /usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar /usr/local/lib/homelab/inbox-backend/inbox-backend.jar import-memory --if-not-imported
```

`import-memory` brings the clone exactly to GitHub (the fresh import: what the Python store held is on the branch, because Python pushed
every change), counts the entries, and writes the marker `memory-repo/.git/inbox-memory-owner`. **Java refuses every write until that
marker exists**, and the Python code never writes, so there is exactly one writer at any moment: the old code before the import, Java after.
`--if-not-imported` makes a re-run a no-op; without it a second import refuses (`--replace` redoes it, only before the switch).

The unit got `ReadWritePaths=/var/lib/homelab-inbox/memory-repo` (the only place it may write) and **lost `IPAddressDeny=any`**, because it
now pushes to GitHub and the addresses of GitHub change; it still listens only on `127.0.0.1`. `bin/homelab-exposure` checks the
`ReadWritePaths` instead.

### Rollback (revert the PR, then re-import into the Python store)

Hand the store back **with the jar of this PR, before the revert is installed** (the revert removes the command):

```sh
sudo systemctl stop homelab-inbox
sudo -u inbox-runner env INBOX_CONFIG_DIR=/usr/local/lib/homelab/config INBOX_RUNNER_HOME=/var/lib/homelab-inbox \
  /usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar /usr/local/lib/homelab/inbox-backend/inbox-backend.jar export-memory
# prints: export ok: memory entries=N (equal to GitHub), the marker is removed; refuses (exit 1) while the clone holds
# a commit or file that GitHub does not have
# then merge the revert PR; homelab-admin reruns 21-inbox.sh and 27-backend.sh (old Python code, old jar)
sudo systemctl start homelab-inbox     # if the rerun did not already start it
```

The old Python code re-imports by itself at its start: `load_memory()` runs `sync_branch`, which resets `memory-repo` to the branch that Java
pushed. To do it by hand: `sudo -u inbox-runner git -C /var/lib/homelab-inbox/memory-repo fetch origin memory && sudo -u inbox-runner git -C
/var/lib/homelab-inbox/memory-repo checkout --force -B memory FETCH_HEAD` (needs the token helper; the restart is easier). Check afterwards that
`homelab-inbox memory | wc -l` equals the export line. If the revert was already installed, build this PR's jar from its merge commit.

### After the merge: what to check

1. `ls /var/lib/homelab-inbox/memory-repo/.git/inbox-memory-owner` exists; `systemctl is-active homelab-inbox homelab-inbox-backend`.
2. On the Memory page add an entry, edit it and delete it; `git log` of the `memory` branch on GitHub shows three commits in your name.
3. Finish a task: its history entry appears; a `GEHEUGEN:` block of Claude is stored.

### Tests

`mvn test` (`MemoryWriterTest`: local bare repository as DATA_REPO, rules, commits, import and export); `python3 -m unittest tests.test_inbox_m5_parity`
(reference inbox with the old write code against the inbox in front of Java: every page post, then the same files and commits on the branch;
the direct `/internal/memory/*` contract against the Java base URL); `tests.test_inbox_memory_writes` (Python side); the M1/M2 parity run
(`tests.test_inbox_m1_parity`, harness `backend/parity/parity.py` and contract tests) covers the memory reads after the switch.

## Job files (M6, H-024)

The job files are the clone `jobs-repo` of the `jobs` branch of DATA_REPO: one `jobs/<name>.md` per job, plus a README. **From the merge on
the Java backend is their only writer**: it syncs the clone to GitHub, checks the file with the rules of `parse_job`, writes it, makes one
commit and pushes to the `jobs` branch with the token of the inbox. Code: `backend/src/main/java/dev/homelab/inbox/jobs/` (`JobsRepo` the
clone and the one-commit save, `JobsWriter` the rules and the pause/resume edit, `JobsController` the routes), `.../gitstore/BranchRepo.java`
(the git work and the owner marker, shared with `MemoryRepo`) and `.../db/JobsCommand.java` (import and export). The Python side
(`bin/homelab_inbox/jobs.py`) only reads the files (the `JOBS` cache, for the scheduler and the pages) and asks the backend to write.
**Not moved**: the task queue, the scheduler, runs and run history, drafts (SQLite), research, delivery, GitHub polling and briefs.

### Routes

The public routes keep their paths, forms, CSRF/owner check, drafts, status codes, `{"error": ...}` bodies and 303 redirects: they are still
answered by `server.py` (and kitt's pause/resume by `kitt.py`), which checks the owner and the draft, validates the file with `parse_job` and
then sends the file here. No read route moved: the job pages (`/api/jobs-page`, `/api/job/<name>`, the catalogue) join the files with the
runs and drafts of the Python queue, so they are still Python's and read the clone; `BACKEND_ROUTES` is unchanged. Java answers (facade
secret required, loopback only):

| Public route (unchanged) | Internal route (Java) | Answer |
| --- | --- | --- |
| `POST /draft/<id>/confirm`, `POST /job/<name>/save` | `POST /internal/jobs/save` `{name, text, message}` | `{"saved": bool}`, 400 `{"error"}` (the `parse_job` messages) |
| `POST /job/<name>/delete` | `POST /internal/jobs/delete` `{name, message}` | `{"saved": bool}` |
| `POST /job/<name>/pause`, kitt pause | `POST /internal/jobs/pause` `{name, message}` | `{"saved": bool}`, 404 `Niet gevonden.` for an unknown job |
| `POST /job/<name>/resume`, kitt resume | `POST /internal/jobs/resume` `{name, message}` | same |
| start of the inbox and the scheduler's reload (`load_jobs`) | `POST /internal/jobs/sync` | `{"ok", "owned"}` |

If the backend does not answer, the page gets 502 `{"error"}` and nothing is written (no fallback writer in Python). Commit author and
messages (`jobs: <name> nieuw|gewijzigd|bewerkt|gepauzeerd|hervat|verwijderd (<who>)`) are the ones Python used.

### The import (the switch)

`scripts/jobs-import.sh`, called by `21-inbox.sh` and `27-backend.sh` (whichever runs last), skips unless the installed Python code is the M6
code (`pause_job` exists) and the installed jar has `JobsCommand`. It stops `homelab-inbox` and runs as `inbox-runner`:

```sh
sudo -u inbox-runner env INBOX_CONFIG_DIR=/usr/local/lib/homelab/config INBOX_RUNNER_HOME=/var/lib/homelab-inbox \
  /usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar /usr/local/lib/homelab/inbox-backend/inbox-backend.jar import-jobs --if-not-imported
```

`import-jobs` brings the clone exactly to GitHub (the fresh import: Python pushed every change), counts the job files and writes the marker
`jobs-repo/.git/inbox-jobs-owner`. **Java refuses every write until that marker exists**, and the Python code never writes, so there is
exactly one writer at any moment: the old code before the import, Java after. `--if-not-imported` makes a re-run a no-op; without it a
second import refuses (`--replace` redoes it, only before the switch). The unit's `ReadWritePaths` now lists `memory-repo` and `jobs-repo`
(`bin/homelab-exposure` checks both).

### Rollback (revert the PR, then re-import into the Python store)

Hand the store back **with the jar of this PR, before the revert is installed** (the revert removes the command):

```sh
sudo systemctl stop homelab-inbox
sudo -u inbox-runner env INBOX_CONFIG_DIR=/usr/local/lib/homelab/config INBOX_RUNNER_HOME=/var/lib/homelab-inbox \
  /usr/lib/jvm/java-21-openjdk-amd64/bin/java -jar /usr/local/lib/homelab/inbox-backend/inbox-backend.jar export-jobs
# prints: export ok: job files=N (equal to GitHub), the marker is removed; refuses (exit 1) while the clone holds
# a commit that GitHub does not have
# then merge the revert PR; homelab-admin reruns 21-inbox.sh and 27-backend.sh (old Python code, old jar)
sudo systemctl start homelab-inbox     # if the rerun did not already start it
```

The old Python code re-imports by itself at its start: `load_jobs()` runs `sync_branch`, which resets `jobs-repo` to the branch that Java
pushed. By hand: `sudo -u inbox-runner git -C /var/lib/homelab-inbox/jobs-repo fetch origin jobs && sudo -u inbox-runner git -C
/var/lib/homelab-inbox/jobs-repo checkout --force -B jobs FETCH_HEAD` (needs the token helper; the restart is easier). Check afterwards that
`ls /var/lib/homelab-inbox/jobs-repo/jobs | wc -l` equals the export line. If the revert was already installed, build this PR's jar from its merge commit.

### After the merge: what to check

1. `ls /var/lib/homelab-inbox/jobs-repo/.git/inbox-jobs-owner` exists; `systemctl is-active homelab-inbox homelab-inbox-backend`.
2. On the Jobs page pause, resume and change a job (confirm the draft), and delete a test job; the `jobs` branch on GitHub shows the commits.
3. Ask kitt to pause a job; the scheduler still runs the jobs that are due.

### Tests

`mvn verify` (`JobsWriterTest`: local bare repository as DATA_REPO, rules, commits, import and export); `python3 -m unittest
tests.test_inbox_m6_parity` (reference inbox with the old write code against the inbox in front of Java: every job post, then the same
files and commits on the branch and the same page reads; the `/internal/jobs/*` contract against the Java base URL; export and
re-import); `tests.test_inbox_jobs_writes` and `tests.test_inbox_kitt` (Python side); `tests.test_inbox_contract`.

## RunnerService (H-025 R3)

`backend/.../runner/RunnerService.java` starts a runner of `runner/` as the process contract says
(`docs/architecture/inbox-split.md`, "Backend ↔ Python runners"). Nothing calls it yet; the queue slices (K) will.

- The command is an argument array, put behind `setsid` (also an argument) so the runner leads its own process group.
  No shell. The prompt is a file in `share/<id>/`, the token is read by `homelab-inbox-task`; `RunnerRequest` only
  has `stdin` and `env` for what the contract allows, never arguments for either.
- stdout is NDJSON: non-JSON lines are ignored, the last `{"event":"result","v":1,...}` line plus the exit code is the
  `RunnerResult`, every other event goes to the listener untouched (Java does not parse stream-json, Q7). stderr: the last
  4000 characters.
- Deadline or `cancel()`: first the request's `killCommand` (the `homelab-inbox-task kill <id>` action, as an array), then,
  if the runner is still there after `killGrace`, `kill -KILL -- -<pid>` on the group and its descendants.
- Test: `RunnerServiceTest` with `src/test/resources/runner/fake_runner.py` (result, exit code, deadline, cancel, group
  kill of a runner that ignores SIGTERM, no shell). `./mvnw verify`. The sudoers line is unchanged.

## Front door: access, CSRF, CSP and the SSH tab (H-026 K8)

`backend/src/main/java/dev/homelab/inbox/frontdoor/` is the Python front door (`access.py`, the guard and `csrf_ok` of
`server.py`, `ssh_state`, `POST /api/ssh`) in Java, with the same behaviour. It is **in shadow**: nothing is in front of
it yet, no port, bind address, unit, proxy, `services.conf` entry or `BACKEND_ROUTES` changed, and the whole package is
off unless `inbox.front-door.enabled=true` (nothing sets it; H-027 does when it switches the routes).
`FrontDoorOffTest` checks that with the default there is no front-door bean, no `/api/session` or `/api/ssh` route and
no CSP header, and that the facade secret still guards every route.

| Class | Python | What it does |
|---|---|---|
| `TailnetAccess` | `access.Access` | Owner from `tailscale status --json`; a peer is allowed only when `tailscale whois` names the owner. The box's own addresses and an unknown owner are refused, a failed whois is refused, answers are kept 5 minutes. `INBOX_ALLOW_ALL=1` opens it, for tests only. |
| `Csrf` | `access.csrf_token`, `csrf_ok` | Token = HMAC-SHA256 of `inbox-form` with `<INBOX_RUNNER_HOME>/csrf-secret` (same file, so tokens are interchangeable with Python's), constant-time compare; Origin netloc must equal Host. |
| `FrontDoorFilter` | `Handler.guard`, `csrf_ok`, `handle_mutating` | GET: owner check. POST: owner check, then Origin and token (`X-CSRF-Token` on `/api/*`, the `csrf` form field on pages). PUT/PATCH/DELETE: same checks then 405 on `/api/*`, 501 elsewhere. Errors are `{"error": ...}` on `/api/*`, a small HTML page elsewhere. |
| `SecurityHeaders` | `Handler.send` | CSP, `X-Frame-Options: DENY`, `Referrer-Policy: same-origin`, `Cache-Control: no-store` on every answer. The CSP string is Python's, byte for byte (a test compares it with `server.py`). |
| `SessionController` | `GET /api/session` | `{"csrf_token": ...}`. |
| `SshController`, `AdminSocket` | `ssh_state`, `admin_ask`, `POST /api/ssh` | History (last 15 `sshkey` lines of the changes log, newest first), and a key request to `homelab-admin` over its unix socket. |

Java tests (`backend/src/test/java/dev/homelab/inbox/frontdoor/`): `CsrfTest`, `TailnetAccessTest`, `FrontDoorTest`
(ports `tests/test_inbox_session.py` and `tests/test_inbox_ssh_api.py`, with negative tests for no access, a missing or
wrong token, a wrong origin and the CSP), `FrontDoorOffTest`.

Not ported, on purpose: `flat_error` and the frontend-source check of `test_inbox_session.py` (they belong to the
Python proxy and the TypeScript client), and the chat and kitt doors and the gate on 8121 (K7). Tomcat writes
`Content-Type: application/json;charset=utf-8` without the space Python has; the tests compare it that way.

## Core in shadow: task store, scheduler, recovery (H-026 K1)

`backend/.../core/` is the start of the inbox core in Java. **Nothing is switched**: Python owns the `tasks` table and the
queue; no route calls this code; the running service never opens a task connection at start.

- `TaskStore` (`get`, `update`, `tasks`, `newTask`, `now`) over the `tasks` table, same columns and ISO-8601 `+00:00`
  strings as the SQLite database. `update` only takes known column names.
- `Scheduler.tick()` (the pass of `queue.scheduler`): oldest queued first; `MAX_RUNNING` in total, `MAX_PER_REPO` per repo
  (`auto` and `brief` are exempt); waits for `after_task` (a brief task also while its predecessor is `pr_open`).
- `Recovery.recover()` (`queue.recover`): a running task goes back in the queue with a restart note and `restarts+1`; after
  `RESTART_ROUNDS` (2) cut-offs in a row it fails; router and planner start over without a note; a brief task whose review
  was cut off is reviewed again.
- `RoundLimits`: the turn limit (`MAX_TURNS`, counted on top of the task's turns, every 5th turn stored), the time limit
  (`MAX_MINUTES`) and cancel of one round, with the reason strings of Python (`N beurten`, `N minuten`, `geannuleerd`).
- `TaskActions`: `answer`, `cancel`, `retry` (Continue for a capped task) and `sweepCapped` of `queue.py`.
- `Effects`: everything with a side effect is an interface (`RoundRunner`, `WorkingCopies`, `Notifier`, `GitHubWrites`,
  `TaskLog`, `ConversationNotes`, `Followups`). In shadow they are all `ShadowEffects`, which only records the call.

**The switch:** `core.scheduler.enabled` (env `CORE_SCHEDULER_ENABLED`), default **off**. Off: the `SchedulerLoop` bean does not
exist, and `tick`, `recover` and `sweepCapped` return without reading or writing anything. On: `SchedulerLoop` refuses to
start while the effects are the shadow ones, so until the real effects exist (K2 and later) switching it on is a start
error, not a way to run tasks. Limits come from the same `*.conf` keys as Python (`MAX_RUNNING`, `MAX_PER_REPO`, `MAX_TURNS`,
`MAX_MINUTES`, `CAPPED_KEEP_DAYS`).

Left for later tasks (K4 to K7): (the router and follow-up chain are K3, below; `Followups` is the hook they implement),
`settle_routed`/`settle_briefs`, the real effects, and the loop calling the K2+ pieces. K2 is below.

### Which Python test each Java test covers

The Python tests of `tests/` hardly cover the scheduler, limits and recovery directly: no test calls `scheduler()` or
`recover()`, and the turn/time limits are only exercised for the turn count. Where a rule has no Python test, the Java test is
written from the Python code and says so in its comment ("no Python test").

| Java test (`backend/src/test/java/dev/homelab/inbox/core/`) | Python test it ports |
|---|---|
| `TaskStoreTest.aNewTaskIsQueuedAndRemembersWhereItCameFrom` | `test_inbox_chat.StartTask.test_task_is_queued_like_a_page_task_and_marked_discord` |
| `TaskStoreTest.anUnknownTaskIsEmpty` | `test_inbox_tasks_api.test_unknown_task_is_a_json_404` (store half) |
| `TaskStoreTest.tasksComeNewestFirstAndAreLimited` | `test_inbox_tasks_api.test_list_has_the_form_repos_queue_and_history` (order) |
| `TaskStoreTest.updateChangesOnlyTheGivenFieldsOfThatTask` | `test_inbox_chat.Lists.test_state_lists_running_waiting_and_queued` (the rows) |
| `TaskStoreTest.updateCanSetAColumnToNull` | `test_inbox_followups.test_retry_a_refused_task` (summary to NULL), `test_inbox_chat.Answer` |
| `TaskStoreTest.theTextIsCutAt8000Characters`, `updateRefusesAColumnThatIsNotOnTheTable`, `nowIsIsoSecondsWithAnOffset` | none (written from `storage.py`) |
| `RoundLimitsTest.turnsAreCountedOnTopOfTheTurnsTheTaskAlreadyHad` | `test_runner_round.InboxUsesRunner.test_stream_claude_reads_the_runner_events` (turns 3 + 1 = 4, no cap) |
| `RoundLimitsTest` other 5 cases (every 5th turn, turn limit once, deadline, cancel vs time-out, time-out over turn limit) | none (from `rounds.stream_claude` and `rounds.watch`) |
| `SchedulerTest` (10 cases: oldest first, `MAX_RUNNING`, `MAX_PER_REPO`, router/planner exempt, predecessor, brief and `pr_open`, window of 200, failing round, scheduler off) | none (from `queue.scheduler`) |
| `RecoveryTest` (6 cases: requeue with note, fail after 2, router/planner, other statuses untouched, review again, scheduler off) | none (from `queue.recover`) |
| `TaskActionsTest.anAnswerQueuesTheWaitingTaskAgain...` | `test_inbox_chat.Answer.test_answer_continues_the_task_like_the_page` |
| `TaskActionsTest.anAnswerToATaskThatIsNotWaiting...` | `test_inbox_chat.Answer.test_not_waiting_unknown_and_forbidden` (not-waiting half; 404/403 belong to the route, H-027) |
| `TaskActionsTest.retryOfARefusedTaskQuotes...` | `test_inbox_followups.test_retry_a_refused_task` (the follow-ups queueing is K3: only the `Followups` hook is checked) |
| `TaskActionsTest.continueOfACappedTaskKeepsItsWorkingCopy` | `test_inbox_followups.test_capped_round_keeps_its_working_copy_for_continue` (retry half; the capped `finish_round` half is `RoundFinishTest.aCappedRoundKeepsItsWorkingCopy...`, K2) |
| `TaskActionsTest.retryClearsTheAcceptedMark...` | `test_inbox_followups.test_retry_clears_the_mark` |
| `TaskActionsTest.theNoteOfATaskThatNeverRan...` | `test_inbox_followups.test_retry_note_of_a_task_that_never_ran_goes_in_its_text` |
| `TaskActionsTest.theSweepRemovesOnlyOldCappedWorkingCopies...` | `test_inbox_followups.test_sweep_removes_only_old_capped_working_copies` |
| `TaskActionsTest` cancel, reaction-to-answer, rebuilt-from-commits, sweep edge cases | none |
| `ShadowModeTest` | the acceptance criterion (scheduler off on a copy of the data: no change, no effect) |

Not ported in K1 because they test later slices: the follow-up chain of
`test_inbox_followups` (K3), `test_inbox_auto_merge` (K5), `test_inbox_builds` (K4), `test_inbox_followups.test_migration_flags_only_existing_followups`
(a SQLite `ALTER TABLE`; V1 has `no_auto_revive` with default 0 and the import copies the value).

Test: `cd backend && ./mvnw verify` (`core/` tests use a throwaway PostgreSQL, like the other database tests).

## Jobs and research in shadow (H-026 K6)

`backend/.../core/` also holds the run side of `jobs.py`. As in K1 **nothing is switched**: Python still runs the job scheduler
and owns `job_runs`, `job_state` and the `kv` keys of jobs; no route calls this code; the file writes were already Java's
(H-024 M6, `jobs/`).

- `JobEngine`: `tick()` (a job that was due since its previous run runs once, also after downtime; a new or paused job counts
  from now), `dispatch`, `runJob`, `startClaude` (the daily cap `JOB_CLAUDE_CAP`, one held run per job, one notice a day),
  `releaseHeld`, `runLocal` (gather, local model, deliver; cancel and failure paths), `askResearch` (page or chat client, the
  cap, 400/429), `deliverResult`
  (page / ntfy / conversation, "NOTHING TO REPORT"), `jobOfTask`, `runView`.
- `JobText`: the research prompt (Dutch text word for word), limits, report header and check, `isNothing`, `sinceDay`, the
  prompt of a Claude job, `htmlText`. `SourceReader`: `watchRepo`, `fetchSource`, `feedText`. `JobRuns`: the SQL over
  `job_runs`, `job_state`, `kv` and the one `tasks` insert of a Claude job. `JobSettings`: the cap and the research limits.
- `AgentCatalogue`: the `agents/` catalogue (broken files kept apart, `NeedsInput` for empty sources, switching on only makes a
  ready draft). `JobPages`: the data of the Jobs, job, draft, run and Research pages (same keys and Dutch texts as `overview.py`).
- `Jobs.Job` (the parsed job file) now keeps `repo`, `script`, `args`, `sources`, `deliver`, `mode` and `instruction`;
  `Jobs.NeedsInput` is `jobs.NeedsInput`.
- `Effects.JobIo` is what a run reads from outside (box overview, web page, GitHub GET, a script of a repo, the local model).
  In shadow it is `ShadowEffects`: it records the call and answers empty. A Claude job only becomes a `queued` row in `tasks`;
  starting it is the K1 scheduler, which is off.

**Switch:** the same `core.scheduler.enabled`. Off: `tick` and `releaseHeld` return without reading or writing anything.
`askResearch`, `startClaude` and `dispatch` are not called by anything while no route exists (K8/H-027 will).

Left out of K6: the HTTP side of the job pages and `/api/research` (routes, CSRF, JSON errors: K8), the chat permission for
`research` (K7), the end of a research task (`RoundFinisher.finishResearch`, K2; `JobResearch` is the K6 side of its `Research` hook), the shell script `bin/homelab-inbox-task`
(read-only tools of a research run, stays as it is), the real `JobIo` (a later task), the job drafts via Claude (`make_draft`,
a runner process since H-025 R2).

### Which Python test each Java test covers

Java tests live in `backend/src/test/java/dev/homelab/inbox/core/`. "none" means Python has no test for the rule: the Java test
is written from the Python code.

| Java test | Python test it ports |
|---|---|
| `JobFileTest` default mode, bad mode / local model / mode elsewhere, reserved name, no marker for research | `test_inbox_research.Format` (5 tests) |
| `JobFileTest` default delivery, values, unknown value, marker needed | `test_inbox_job_delivery.Format` (default, values, unknown, must define nothing) |
| `JobFileTest` digest needs input, watcher takes only GitHub | `test_inbox_agents.FirstAgents.test_sources_start_empty...`, `test_watcher_takes_only_github_repos` (parser half) |
| `JobFileTest` script defaults to homelab | none |
| `JobTextTest` prompt, quick smaller than deep, report problems | `test_inbox_research.Prompt` (3 tests) |
| `JobTextTest` report header | `test_inbox_research.Finish.test_report_has_mode_and_time...` (header line) |
| `JobTextTest` first-line marker, statement of nothing | `test_inbox_job_delivery.Delivery.test_marker_only_counts_as_first_line`, `Format.test_page_shows_the_statement` |
| `JobTextTest` since day, first run and cap | `test_inbox_agents.RepoWatcher` (the two `since_day` tests) |
| `JobTextTest` job prompt, html text | none |
| `SourceReaderTest` commits/PRs/releases since, nothing new | `test_inbox_agents.RepoWatcher.test_reports_commits_prs_and_releases_since`, `test_nothing_new_says_so` |
| `SourceReaderTest` failing read, 24-hour digest, feed, page | none |
| `JobEngineTest` ask: one run against the cap, above the cap, cap 0, scheduled research waits, bad input, chat source | `test_inbox_research.Ask` (6 tests; the 403 permission is K7) |
| `JobEngineTest` finish: report with mode and time, nothing extra on the page, Discord gets it, nothing found, no sources flagged, no report fails | `test_inbox_research.Finish` (6 of 8; `capped` is K2, `write_prompt` is `rounds.py`) |
| `JobEngineTest` delivery: page, ntfy, conversation, nothing to report | `test_inbox_job_delivery.Delivery` (4 tests; scrubbing is `ConversationStoreTest`) |
| `JobEngineTest` local run: nothing to report, failure notice | `test_inbox_job_delivery.LocalRun` (2 tests) |
| `JobEngineTest` tick (new, downtime, paused/broken, scheduler off), dispatch, hold and release, gather, run view, job of task, cancelled/ok/too-long local run, failure to start | none |
| `AgentCatalogueTest` examples load, broken file, switch on only makes a draft, broken/unknown, on | `test_inbox_agents.Catalogue` (5 tests) |
| `AgentCatalogueTest` the three first agents, sources asked for | `test_inbox_agents.FirstAgents` (3 tests) |
| `JobPagesTest` shape, sorted jobs, catalogue, on, drafts, runs | `test_inbox_jobs_api` (6 tests; the path/query test is the route) |
| `JobPagesTest` job detail, broken job, draft detail, run detail, research data, unknown | `test_inbox_job_pages_api` (6 data tests), `test_inbox_research.Page` (2 tests) |
| `ShadowModeTest` jobs with the scheduler off, application has the engine in shadow | the acceptance criterion (no run, no message, no change) |

Already Java before K6, with their own tests: `test_inbox_jobs_writes`, `test_inbox_m6_parity` (`JobsWriterTest`, M6). Not Java
(HTTP and shell): the route and page tests of `test_inbox_job_pages_api` (`test_pages_get_the_frontend_index`, `..._a_job_named_research...`,
`..._without_a_build...`, `..._failed_posts_...`, `..._cancelling_a_draft...`), `test_inbox_research.Permissions` (greps
`bin/homelab-inbox-task`) and `Finish.test_system_prompt_is_the_read_only_one` (`rounds.write_prompt`).

## Core in shadow: finish_round, conflict rounds (H-026 K2)

Still **nothing switched**: Python's `queue.finish_round` and `run_round` run the tasks; the Java ones are only called by tests
and (once the scheduler is on, which needs real effects) by K7/H-027.

- `RoundFinisher.finish` ports `finish_round` and `finish_research`: cancelled round, research report (mode and time on
  top, `report check:` note), answer, question, refusal, capped (research: nothing pushed; others: the working copy stays
  and the pushed branch is named), no commits = failed, PR opened or updated, brief tasks reviewed instead of notified,
  skill proposal split off and kept only for a `KLAAR`/`ANTWOORD` that was not capped. A failing push or PR fails the task
  with `afronden mislukt: ...`. Dutch notes and notification titles are exactly Python's.
- `CatchUp.catchUp` ports `catch_up_main`: a task with a PR is left to GitHub polling (K5); otherwise main is merged into the
  branch and the prompt says so, names the conflicted files, or hands over the `git fetch` command when the merge failed.
- `RoundFlow.run` ports `run_round` (prepare, catch-up, prompt, `rounds+1`, Claude run, memory split, `finish`). The router and
  planner (K3, K4) are behind `Sessions.route`/`plan`. One difference: a continued round without `pending_input` and without a
  catch-up note gets an empty prompt in the log line; Python crashes there on `first_line(None)` and fails the task.
- New effect interfaces in `Effects`: `TaskCommands` (prepare, behind, sync), `Pushes`, `PullRequests`, `Research`,
  `AfterEffects` (PR review and skill proposal, run on the background executor), `MemorySteps`, `Repos`, `Sessions`;
  `Notifier` got the `store` flag of `helpers.notify`. `ShadowEffects` implements all of them: it pushes nothing, opens no PR,
  merges nothing and notifies nobody, and only records the call. `CoreConfig` wires `CatchUp`, `RoundFinisher` and `RoundFlow`
  on those shadow effects.

| Java test (`RoundFinishTest`) | Python test it ports |
|---|---|
| `aQuestionIsKeptInFullInTheConversationAndNotNotifiedTwice` | `test_inbox_events.test_questions_of_a_task_are_stored_in_full` |
| `aCappedRoundKeepsItsWorkingCopyAndContinueCarriesOn` | `test_inbox_followups.test_capped_round_keeps_its_working_copy_for_continue` |
| `aResearchReportGetsModeAndTimeAndNothingIsPushed`, `theReportIsHandedToDeliveryOnceAndWithTheTaskLink` | `test_inbox_research.Finish.test_report_has_mode_and_time_and_sources_and_stays_on_the_page`, `test_page_question_delivers_nothing_extra`, `test_discord_question_gets_its_report_in_the_conversation` (the delivery itself is `jobs.deliver_result`, K6) |
| `nothingFoundIsAValidReportWithoutANote` | `test_inbox_research.Finish.test_nothing_found_is_delivered_as_the_report` |
| `aReportWithoutSourcesIsFlaggedInTheNote` | `test_inbox_research.Finish.test_report_without_sources_is_flagged` |
| `aResearchRunWithoutAReportFailsAndOpensNoPr` | `test_inbox_research.Finish.test_no_report_is_a_failure_not_a_pr` |
| `aResearchRunAtItsLimitPushesNothing` | `test_inbox_research.Finish.test_a_run_that_hits_its_limit_pushes_nothing` |
| `aPrIsLeftToTheGithubPoller` | `test_inbox_catch_up.CatchUpMain.test_a_pr_is_left_to_the_github_poller` |
| `aBranchThatAlreadyHasMainIsNotTouched` | `CatchUpMain.test_a_branch_that_already_has_main_is_not_touched` |
| `noWorkingCopyIsNotTouched` | `CatchUpMain.test_no_working_copy_is_not_touched` |
| `aCleanMergeIsToldToTheTask` | `CatchUpMain.test_a_clean_merge_is_told_to_the_task` |
| `conflictsNameTheFiles` | `CatchUpMain.test_conflicts_name_the_files` |
| `aFailedMergeHandsOverTheCommand` | `CatchUpMain.test_a_failed_merge_hands_over_the_command` |
| `aRetriedRoundMergesMainAndKeepsTheUsersNote` | `test_inbox_catch_up.RoundGetsMain.test_retried_round_merges_main_and_keeps_the_users_note` |
| `theFirstRoundDoesNotSync` | `RoundGetsMain.test_first_round_does_not_sync` |
| the other cases (answer, job answer, refusal, cancelled, no commits, PR opened, PR updated, brief review, brief hidden in PR body, failing push, skill proposal, denials note, round bookkeeping, failed prepare, unknown repo, router/planner, shadow no-effect) | none: written from `queue.finish_round` / `run_round` |

Not ported because they test the git side, not Java: `test_inbox_catch_up.BehindSubcommand` (the `behind` and `sync` commands of
`bin/homelab-inbox-task` against real git; unchanged and still run by Python), and `test_runner_contract`.

## Core in shadow: briefs (H-026 K4)

The briefs of `bin/homelab_inbox/briefs.py` exist in Java with the same behaviour, in `backend/.../core/`:

| Class | Python | What |
|---|---|---|
| `BriefParsing` | `split_frontmatter`, `load_briefs` (one file), `task_limit`, `parse_plan`, `parse_review`, `REVIEW_SKIP`, the status edits of `set_brief_status` and `handoff` | Pure text and JSON handling, no I/O |
| `Briefs` | `load_briefs`, `builds`, `build_left_nothing`, `live_build`, `set_brief_status`, `start_build`, `add_new_repo`, `merge_setup_pr`, `helpers.merges_itself` | Brief intake and the builds |
| `BriefRounds` | `plan_task`, `review_pr`, `review_diff`, `handoff`, `settle_briefs` | Planning into tasks or a question back to the user, the PR review with one rework round, the handoff PR |
| `BriefPages` | `overview.briefs_data`, `brief_data`, `brief_view`, `build_view` | The data of the Briefs page; no route uses it |

Everything outside the task store goes through new interfaces in `Effects` (`ProductRepo`, `BriefGitHub`, `BriefClaude`,
`RepoProfiles`, `NewRepos`, `Diffs`), plus the existing `Notifier`, `ConversationNotes`, `PullRequests` and `Repos`. The
`ShadowEffects` implement all of them and do nothing: no Claude run, commit, comment, PR, merge or notification (the shadow
test `withTheShadowEffectsNothingLeavesTheCore` checks this). `Sessions.plan` and `AfterEffects.reviewPr` are still the shadow
ones: wiring them to `BriefRounds.planTask` and `reviewPr` is part of the cut-over (H-027), together with the real effects.
No route is switched. `TaskStore` got `children`, `builds`, `buildsToHandOff`, `newBuild` and `newChild`.

Differences from Python, on purpose: the planner and reviewer get Claude's answer as a `ClaudeAnswer` (the runner owns the
parsing of Claude's JSON, Q7), so `helpers.claude_result` stays in Python; the criteria of a child task are written with
Jackson (non-ASCII is not escaped, as `json.dumps` does by default).

### Which Python test each Java test covers

All Java tests are in `BriefsTest`; "new" marks behaviour of `briefs.py` that no Python test covers.

| Java test | Python test |
|---|---|
| `aCancelledBuildDoesNotBlockANewOne` | `test_inbox_builds.Builds.test_a_cancelled_build_does_not_block_a_new_one` |
| `aPlannedBuildWhoseTasksWereAllCancelledCanBeStartedAgain` | `...test_a_planned_build_whose_tasks_were_all_cancelled_can_be_started_again` |
| `aBuildWithAMergedTaskStaysLive` | `...test_a_build_with_a_merged_task_stays_live` |
| `aBuildThatIsStillRunningStaysLive` | `...test_a_build_that_is_still_running_stays_live` |
| `aBuildWithoutTasksYetStaysLive` | `...test_a_build_without_tasks_yet_stays_live` |
| `aCancelledBuildDoesNotHideAnEarlierOneWithMergedWork` | `...test_a_cancelled_build_does_not_hide_an_earlier_one_with_merged_work` |
| `theSettingAppliesWhenTheBriefSaysNothing` | `test_inbox_builds.TaskLimit.test_the_setting_applies_when_the_brief_says_nothing` |
| `theBriefSetsItsOwnLimit` | `...test_the_brief_sets_its_own_limit` |
| `aPlanOverTheLimitIsRefused` | `...test_a_plan_over_the_limit_is_refused` |
| `limitZeroTakesAnyNumberOfTasks` | `...test_limit_zero_takes_any_number_of_tasks` |
| `theListGroupsBriefsByStatusWithTheirActions` | `test_inbox_briefs_api.BriefsApi.test_list` |
| `anUnreadableProductRepoGivesAnErrorAndEmptyLists` | `...test_unreadable_product_repo` |
| `oneBriefComesWithItsBodyPathAndZone` | `...test_one_brief`, `...test_times_come_with_their_zone` |
| `anUnknownBriefIsNotFound` | `...test_unknown_brief` (the 404 itself is the route's) |
| `aBuildThatCannotStartSaysWhy` | `...test_a_failed_build_is_400_with_the_reason` (the 400 itself is the route's) |
| `parkingADraftCommitsTheNewStatusAndKeepsTheComment` | `...test_park_redirects_to_the_briefs_page` (the redirect is the route's) |
| `theReviewReadsTheRunnersResult` | `test_runner_review.InboxUsesRunner.test_review_pr_reads_the_runner_result` |
| `withTheShadowEffectsNothingLeavesTheCore` | acceptance criterion 2 (no Python counterpart) |

Not ported, because they test Python-only parts: `test_inbox_builds.ClaudeResult` (`helpers.claude_result`), and the
runner process tests of `test_runner_review.Review` (`runner/review.py` stays Python; `RunnerServiceTest` covers the Java
side of the contract). The brief parts of `test_inbox_followups`, `test_inbox_auto_merge` and `test_inbox_tasks_api` belong
to K3 (follow-ups), K5 (auto merge) and the routes (K8). The other Java tests are new and cover the planner
(`thePlannerMakesChildTasks`, `aQuestionFromThePlannerWaits`, `theAnswerGoesBackToThePlanner`,
`whatGoesWrongFailsTheBuildWithTheReason`, `aCancelledPlannerMakesNoTasks`, `thePlanIsChecked`), the review
(`aPassedReviewIsCommentedAndNotified`, `aMissSendsTheTaskBackOnce`, `aBuildWithoutMeMergesProductRepoPrsItself`,
`mergesItselfNeedsEveryCondition`, `theReviewerDiffLeavesOutLockfilesAndPutsDocsLast`, `theReviewIsChecked`), the setup PR,
`start_build` and the handoff.

## Chat actions, approvals, kitt door and gate in shadow (H-026 K7)

`backend/src/main/java/dev/homelab/inbox/door/` ports `chat.py`, `kitt.py`, the chat door's action route and `gate.py`.
Exposure is unchanged: no route, no listener and no scheduled job uses it, the Python inbox still serves both doors and
the Python gate still listens on 8121.

| Class | Python | What |
|---|---|---|
| `ChatActions` | `chat.py` | start task (daily cap from local midnight), answer, state, jobs, run job, research, drafts, two-step approval (token in `approvals`, one use, expires) |
| `ChatDoor` | `server.py chat_door/chat_action` | `POST /api/chat/action`: loopback only, bearer token from `chat-<name>-token`, switches, forbidden actions. `entries` and `state` stay the conversation controller's |
| `KittDoor` | `kitt.py`, `server.py kitt_door` | the peer check (`identify`: client header, socket owner = kitt's account, owner login from the gate) and all kitt routes |
| `ProcPeers` | `peer_uid`, `account_uid` | owner of the loopback socket from `/proc/net/tcp(6)`, account from passwd |
| `KittGate` | `gate.py` | owner check, identity headers set, forged ones dropped, 502 when kitt is down. `listen` refuses a wildcard host, and in shadow every non-loopback host |
| `TailscaleOwner` | `access.py Access` | `tailscale status/whois`, cached 5 min; no allow-all switch |
| `ShadowPorts` | | briefs (K4), jobs and research (K6) and search are ports; the shadow versions start nothing |
| `DoorHttp` | | loopback listener for tests only; not a bean |

Deliberate differences: a gate request body over 1 MiB gets 413 (Python cut it and kept the length); the forwarded `Host`
header is the upstream's. Tests: `backend/src/test/java/dev/homelab/inbox/door/` port `tests/test_inbox_chat.py` and
`tests/test_inbox_kitt.py` (the page switch case is covered by the store's switch test), plus peer-check, gate-only-allows
what Python allows (`TailscaleOwnerTest`), and `ShadowDoorTest` (no round, merge or notification; no gate listener).
Real briefs, jobs and search ports arrive with K4 and K6; a bean that replaces `ShadowPorts` must provide all three.
`TailscaleOwner` overlaps the K8 `frontdoor` access check; unify them at the cut-over (H-027).

## Core in shadow: router and follow-ups (H-026 K3)

Still **nothing switched**: Python's `router.py` runs the router and the chain; nothing calls the Java ones (`RoundFlow` still
goes through `Sessions.route`, whose real implementation, a `Router.routeTask` call, comes with H-027).

- `Router` ports `ROUTE_SYSTEM`, `parse_route` and `route_task`: the message to `auto` goes to Claude without tools, the answer
  is a question (also when there are more tasks than `ROUTE_MAX_CHILDREN`) or the child tasks (queued, with their
  predecessor via `after`, the `Onderdeel van bericht #N` suffix and a fresh session). Dutch messages are Python's. The Claude run
  is the new effect `RouteClaude` (`runner/route.py`, `router.run_route`), the repo list `Repos`, the descriptions `RepoProfiles`.
  The children are inserted one by one, Python does it in one transaction.
- `FollowupChain` ports `pre_fine`, `blocked_by`, `blocked_followups`, `revive_followups`, `accept_task` and `settle_routed`, and
  implements the `Followups` hook that `TaskActions.retry` calls (wired in `CoreConfig`). `settleRouted` is the scheduler's pass:
  off with the scheduler. It needs the new effect `TaskHistory` (`memory.remember_task`).
- `ShadowEffects` implements `RouteClaude` (reports a failure, so a routed task fails and creates no child) and `TaskHistory`;
  nothing starts a task, merges or notifies.

| Java test | Python test it ports |
|---|---|
| `FollowupChainTest.anAutomaticReviveQueuesTheFollowupsInAChain...` | `test_inbox_followups.test_merged_pr_requeues_followups_in_a_chain` (the revive; the `check_blocking_pr` half is K5) |
| `FollowupChainTest.aFollowupFromBeforeTheFeatureIsNotRestartedAutomatically` | `test_merge_does_not_restart_a_followup_from_before_this_feature` (the `blocked_followups(t, True)` half; K5 for the poller) |
| `FollowupChainTest.markAsFineQueuesTheFollowups...` | `test_mark_as_fine_requeues_followups_and_not_the_task` |
| `FollowupChainTest.markAsFineNeedsABlockedFollowup` | `test_mark_as_fine_needs_a_blocked_followup` |
| `FollowupChainTest.anUnmarkedTaskStillStopsItsFollowups` | `test_unmarked_task_still_stops_followups` |
| `FollowupChainTest.aRetryClearsTheMark` | `test_retry_clears_the_mark` |
| `FollowupChainTest.retryingTheTaskQueuesItsFollowupsAgain` | `test_retry_a_refused_task` (the follow-ups half; the rest is `TaskActionsTest`, K1) |
| `FollowupChainTest.theColumnNoAutoReviveDefaultsToZero` | `test_migration_flags_only_existing_followups` (the SQLite `ALTER TABLE` is not Java's: V1 has the column with default 0) |
| `FollowupChainTest.aFollowupThatRanBeforeResumes...` | none (from `revive_followups`; `test_retry_note_of_a_task_that_never_ran...` is `TaskActionsTest`, K1) |
| `FollowupChainTest` settle cases (message closed, busy child, scheduler off, no routed parent) | none (from `settle_routed`; `test_inbox_builds` only reads its result) |
| `RouterTest.theShadowClaudeNeverRoutesAnything` | `test_runner_route.RouterUsesRunner.test_run_route_without_a_result_line_is_an_error` (no answer is an error) |
| other `RouterTest` cases (`parse_route`, `route_task`: children, question, limit, second round, failure, cancel) | none: written from `router.py` |
| `ShadowModeTest` (extended with `settleRouted`) | the acceptance criterion |

`tests/test_runner_route.py` (the `runner/route.py` process contract) stays Python's, like `test_runner_contract` in K2.
