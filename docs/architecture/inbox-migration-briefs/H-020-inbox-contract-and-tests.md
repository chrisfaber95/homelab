---
id: H-020
title: "Inbox migration 1: write the contract down and pin it with tests"
status: draft
priority: should
repos: [homelab]
build:
  kit: []
---
## Problem
The inbox (`bin/homelab_inbox/`, about 5,500 lines of Python) is to move step by step to a TypeScript frontend, a Spring Boot backend and Python runners (`docs/architecture/inbox-split.md`). Nothing can be moved safely until its routes, data and process contract are written down and its behaviour is pinned by tests.

## Outcome
One exact description of what the inbox does today, plus tests that any replacement must pass. No runtime change.

## Acceptance criteria
- [ ] `backend/openapi.yaml` lists every route of `server.py` and `kitt.py` with method, access kind (page, chat door, kitt door), request, response and errors; the machine endpoints (`/api/chat/*`, kitt's door, `/api/forge*`, `/api/search/*`, `/api/live`, `/api/log/*`) match the code exactly.
- [ ] A test fails when a route in `server.py` or `kitt.py` is missing from the OpenAPI file.
- [ ] The plan lists every SQLite table and column and every file store under `/var/lib/homelab-inbox`, with its current writers.
- [ ] Contract tests for the machine endpoints run against the in-process Python server and take their base URL from an environment variable, so they can later be pointed at another server.
- [ ] `runner/SCHEMA.md` gives the actions of `homelab-inbox-task`, their arguments, stdout per action and one exit-code table; scrubbed recorded outputs are in `tests/fixtures/runner/` and a fake `homelab-inbox-task` replays them.
- [ ] `scripts/ci.sh` and the existing tests stay green; nothing on the box changes at runtime.

## Tasks (each its own PR, in homelab)
- **C1** inventory and `backend/openapi.yaml` (agent `api-database-engineer`). Depends on: nothing.
- **C2** contract tests for the machine endpoints (`test-automator`). Depends on: C1.
- **C3** runner contract, fixtures, fake task script (`documentation-engineer` with `test-automator`). Depends on: nothing; parallel with C1.

## Out of scope
Any change in behaviour; Java, TypeScript or PostgreSQL code.

## Open questions
None; this brief needs no decision from the user.
