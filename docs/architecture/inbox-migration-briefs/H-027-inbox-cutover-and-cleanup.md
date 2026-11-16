---
id: H-027
title: "Inbox migration 8: cut over to the Java backend, then clean up"
status: draft
priority: could
repos: [homelab]
build:
  kit: []
---
## Problem
Until the Java backend owns port 8105 and the `tasks` table, the migration changes nothing for the user.

## Outcome
The Java backend serves the inbox; the old Python inbox is disabled, then deleted one release later.

## Acceptance criteria
- [ ] Done only when no task is running; the final import runs just before the switch and row counts match.
- [ ] The backend owns 8105 (the existing ufw rule stays); port 8104 stays with `homelab-overview-web`; the old unit is disabled but installed, so rollback is a revert plus enabling it.
- [ ] Discord, kitt, forge and search work, and the phone page works, checked by the user.
- [ ] A final `inbox.db` copy and the last `pg_dump` are kept for one release.
- [ ] One release later: `bin/homelab_inbox/`, `web/` and obsolete tests are removed; CLAUDE.md map, `docs/inbox.md` (including the "task running during restart" text that disagrees with `recover()`), `docs/dashboard.md` and the rerun lines are updated; the contract tests stay.

## Tasks
- **X1** cut-over (all of H-023 to H-026, B8, Q9; the user merges, needs the user's look). **X2** clean-up (X1 plus one release).

## Out of scope
New features.

## Open questions
Q9: is losing data written after cut-over acceptable on rollback.
