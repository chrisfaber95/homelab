---
id: H-023
title: "Inbox migration 4: Spring Boot backend and PostgreSQL beside the old inbox"
status: draft
priority: could
max_tasks: 8
repos: [homelab]
build:
  kit:
    - {agent: api-database-engineer}
    - {agent: backend-developer}
---
## Problem
The queue and all bookkeeping are Python on SQLite with one writer. The target is Java 21 on Spring Boot with PostgreSQL, but it must arrive without ever breaking the running inbox.

## Outcome
A Java backend and a PostgreSQL database `inbox` run on the box next to the old inbox, loaded from `inbox.db`, reachable only on loopback, and compared with the old answers. Nothing is switched over yet.

## Acceptance criteria
- [ ] `backend/` builds with Maven (wrapper, pinned versions) and `GET /api/backend/health` answers on 127.0.0.1.
- [ ] `scripts/28-inbox-db.sh` creates the role and database without a superuser in the app, checks a round trip, and no TCP listener appears other than the one chosen in Q2.
- [ ] Flyway `V1__baseline.sql` reproduces today's 9 tables; later changes are new migrations only.
- [ ] A one-off `import-sqlite` loads `inbox.db` read-only in one transaction, compares row counts per table and fails loudly on a mismatch; it can re-run on an empty database.
- [ ] `scripts/27-backend.sh` installs JDK, builds and starts `homelab-inbox-backend.service` on loopback; `exposure-checker` passes; its heap use is measured and recorded.
- [ ] The Python server forwards listed routes to Java (`BACKEND_ROUTES`, secret header) and, with the list empty, behaves as before.
- [ ] A parity harness compares Python and Java answers on the same data.
- [ ] `pg_dump` of `inbox` is in the backup list and a restore was tested once.

## Tasks
- **B1** Spring Boot skeleton (Q1). **B2** database script (Q2). **B3** install the backend (needs B1, B2, Q3; user merges). **B4** Flyway baseline (B1, Q5). **B5** shadow import (B2, B3, B4). **B6** forwarding facade (B3, Q6). **B7** parity harness (H-020 C2, B5). **B8** backup and restore (B2).
- B1 and B2 can start together; B8 runs beside B3 to B7.

## Out of scope
Switching any route or table to Java (H-024, H-026); cut-over.

## Open questions
Q1, Q2, Q3, Q5, Q6 of `docs/architecture/inbox-split.md`; Q8 (whether to start at all).
