# backend

Spring Boot backend of the homelab inbox (brief H-023, plan `docs/architecture/inbox-split.md`). It runs beside the
Python inbox; nothing is switched over to it yet. For now it only has `GET /api/backend/health`.

- Java 21, Spring Boot 4.1.2 (the parent POM pins all plugin and dependency versions), Maven 3.9.16 through the
  wrapper (`mvnw`, version and checksum in `.mvn/wrapper/maven-wrapper.properties`).
- Dependencies: web, JDBC, Flyway (with its PostgreSQL module), pgjdbc, junixsocket (pgjdbc over the Unix socket; the
  database has no TCP listener) and the test starter. The datasource and Flyway auto-configuration are still switched
  off in `application.properties`: the running service has no database connection until the install and import tasks
  (B3, B5) add it. The migrations below are exercised by the tests only.

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
