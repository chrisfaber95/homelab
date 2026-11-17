# backend

Spring Boot backend of the homelab inbox (brief H-023, plan `docs/architecture/inbox-split.md`). It runs beside the
Python inbox; nothing is switched over to it yet. For now it only has `GET /api/backend/health`.

- Java 21, Spring Boot 4.1.2 (the parent POM pins all plugin and dependency versions), Maven 3.9.16 through the
  wrapper (`mvnw`, version and checksum in `.mvn/wrapper/maven-wrapper.properties`).
- Dependencies: web, JDBC, Flyway (with its PostgreSQL module), pgjdbc, junixsocket (pgjdbc over the Unix socket; the
  database has no TCP listener), sqlite-jdbc (only for the one-off import) and the test starter. The datasource and Flyway auto-configuration are still switched
  off in `application.properties`: the running service has no database connection until the install and import tasks
  (B3) add it. The migrations below are exercised by the tests and by `import-sqlite`.

## Build and test

Needs a JDK 21 (on the box: `openjdk-21-jdk-headless`; CI uses setup-java).

```sh
cd backend
./mvnw verify
```

Tests that need a database (Q5, no Testcontainers) start a throwaway PostgreSQL cluster with the installed `initdb`
and `pg_ctl` in a temp directory, with its socket in that directory and no TCP listener (`ThrowawayPostgres`). The
binaries come from `PG_BIN`, else the newest `/usr/lib/postgresql/<n>/bin`; the test fails (does not skip) when they
are missing. On the box PostgreSQL is installed by `scripts/28-inbox-db.sh`; CI installs the `postgresql` package in
the `backend` job.

## Database migrations

Flyway migrations are in `src/main/resources/db/migration/`.

- `V1__baseline.sql` reproduces the 9 tables of the old SQLite `inbox.db` (`tasks`, `job_runs`, `job_state`, `drafts`,
  `skill_prs`, `kv`, `conversation`, `conversation_refusals`, `approvals`): same table and column names, matching types
  (TEXT to `text`, INTEGER to `integer`, REAL to `double precision`), the same primary keys and NOT NULL/DEFAULT rules.
  SQLite has no other indexes, so there are none. Integer ids that SQLite assigns itself are identity columns that also
  accept explicit values, so the import (B5) can keep the old ids; it must reset the sequences afterwards.
- **`V1` is never edited.** Every later schema change is a new migration (`V2__<what>.sql`, `V3__...`), also a fix to
  a mistake in an earlier one. Flyway refuses to start when an applied migration changed (checksum).
- `BaselineMigrationTest` runs the migrations on a throwaway cluster and checks the 9 tables and their columns.

## One-off import of `inbox.db` (B5)

`import-sqlite` loads the old SQLite database into the PostgreSQL database `inbox`. It is a command mode of the jar
(no Spring context, no web server), run by hand on the box. It does not run on its own and nothing calls it.

```sh
# as inbox-runner (the only account the database lets in as inbox_app, see docs/inbox-db.md)
sudo -u inbox-runner java -jar /usr/local/lib/homelab/inbox-backend/inbox-backend.jar import-sqlite --sqlite /var/lib/homelab-inbox/inbox.db
```

Options: `--socket-dir` (default `/var/run/postgresql`), `--port` (`5432`), `--database` (`inbox`), `--user`
(`inbox_app`), or `--url <jdbc url>` to replace the first three. Behaviour:

- Applies the Flyway migrations first (a no-op when `V1` is there), then opens `inbox.db` **read-only**
  (`SQLITE_OPEN_READONLY`) in one read transaction: one consistent snapshot, no write lock, the file is not changed.
  A database in WAL mode is read through the `-wal` and `-shm` files next to it, so the account needs access to them.
- Locks the nine tables in PostgreSQL and **refuses (exit 2, nothing written)** when any of them has a row.
- Copies all nine tables in **one PostgreSQL transaction**, keeping the old ids, then compares the row count of every
  table (SQLite snapshot against PostgreSQL). On any difference it prints `MISMATCH <table>: sqlite=<n> postgres=<m>`,
  rolls back and exits 1. Any other error (a value that does not fit its column, a SQLite table or column PostgreSQL
  lacks, a lost connection) also rolls back and exits 1.
- Resets every identity sequence to `max(id) + 1` just before the commit (a sequence change is not transactional, so
  this is the last step), then commits and prints the counts; exit 0.

After a failure the database is empty again, so re-run the command after fixing the cause. To start over after a
successful import, empty the nine tables by hand first (or drop and re-create the database with
`scripts/28-inbox-db.sh`); the command never deletes anything itself. Exit codes: 0 done, 1 failed or count mismatch,
2 target not empty or bad usage.

Tested by `SqliteImporterTest` on a small SQLite fixture and the throwaway cluster, including a forced count mismatch.

## Run

```sh
./mvnw spring-boot:run          # or: java -jar target/inbox-backend-0.1.0.jar
curl http://127.0.0.1:8130/api/backend/health
# {"status":"ok","service":"homelab-inbox-backend"}
```

It binds to loopback only. `BACKEND_HOST` (default `127.0.0.1`) and `BACKEND_PORT` (default `8130`) override this; the
port is also in `config/inbox.conf` (`BACKEND_PORT`). Never set `BACKEND_HOST` to anything but a loopback
address.

## On the box

`scripts/27-backend.sh` builds the jar as a throwaway user and runs it as `homelab-inbox-backend.service` on
`127.0.0.1:8130`; see `docs/inbox-backend.md` (install, hardening, how to measure the heap). The unit passes the
PostgreSQL socket URL (junixsocket, `junixsocket.version` in `pom.xml`).

## Facade secret

Every route but `GET /api/backend/health` needs the header `X-Inbox-Facade` with the shared secret, which the Python
server adds when it forwards (`BACKEND_ROUTES`, see `docs/inbox-backend.md`). The backend reads it from `BACKEND_SECRET`
or the file `BACKEND_SECRET_FILE`; with neither, it refuses everything but health. For a local run:
`BACKEND_SECRET=dev ./mvnw spring-boot:run` and `curl -H 'X-Inbox-Facade: dev' ...`.

## Changing versions

Raise the parent version in `pom.xml`, or the Maven version and its `distributionSha256Sum` in the wrapper properties,
in a PR of its own; `./mvnw verify` must stay green.
