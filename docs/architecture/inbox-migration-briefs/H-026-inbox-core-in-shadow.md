---
id: H-026
title: "Inbox migration 7: queue, GitHub polling and briefs in Java, in shadow"
status: draft
priority: could
repos: [homelab]
build:
  kit:
    - {agent: backend-developer}
    - {agent: api-database-engineer}
---
## Problem
The queue, auto merge and the gate are the risky parts: a bug merges or exposes the wrong thing. The `tasks` table has too many writers to move piecemeal.

## Outcome
The whole core exists in Java and passes ported tests with the scheduler off, so the cut-over is a switch and not a rewrite.

## Acceptance criteria
- [ ] Every behaviour covered by the existing 17 test files in `tests/` has an equivalent Java test (limits, restart recovery, follow-ups, conflict rounds, auto-merge rules, brief review).
- [ ] The Java core runs against a copy of the data with the scheduler off and never starts a real task, merge or notification.
- [ ] Access, CSRF, CSP, the gate on 8121 and kitt's peer check are implemented and tested; exposure is unchanged.
- [ ] Heap use of the running backend is measured on the box and recorded.

## Tasks
- **K1** task store, scheduler, recover (H-023 B4, H-025 R3). **K2** `finish_round` (K1). **K3** router and follow-ups (K1, R2). **K4** briefs (K2, R2). **K5** GitHub polling and auto merge (K2). **K6** jobs and research (K1, H-024 M6). **K7** chat actions, approvals, kitt door and gate (K1, H-024 M4). **K8** front door: access, CSRF, CSP, SSH tab (H-023 B1). **K9** memory measurement (H-023 B3).
- K2/K3/K6/K7 can run in parallel after K1; K4 and K5 after K2.

## Out of scope
Switching `tasks` or any route to Java (H-027).

## Open questions
None beyond those of H-023.
