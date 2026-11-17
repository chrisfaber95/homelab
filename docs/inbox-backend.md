# Inbox backend on the box (H-023, task B3)

The Spring Boot backend (`backend/`, task B1) runs on the box beside the old Python inbox, as the systemd service
`homelab-inbox-backend`. Nothing is switched over to it yet: it answers `GET /api/backend/health` and that is all
(plan: [`docs/architecture/inbox-split.md`](architecture/inbox-split.md)). The database it will use is described in
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
  every route (Java only has `/api/backend/health`), so a run prints only SKIP lines until the M slices land.
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
