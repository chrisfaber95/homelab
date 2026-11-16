---
id: H-024
title: "Inbox migration 5: move reads and small stores to Java, one table at a time"
status: draft
priority: could
repos: [homelab]
build:
  kit:
    - {agent: backend-developer}
---
## Problem
With the backend running (H-023), the low-risk parts can move first to prove the pattern before the queue does.

## Outcome
Overview, memory, skills, forge and search proxies, the conversation store and job files are served by Java; Discord and kitt notice nothing.

## Acceptance criteria
- [ ] Each moved route answers the same as the Python one on the parity harness and passes the contract tests against the Java base URL.
- [ ] A table or file store has exactly one writer at any time; its ownership switches in one PR, after a fresh import.
- [ ] The conversation store (`/api/chat/*`) keeps its paths, tokens and permissions (`config/clients.json`); the Discord bot works unchanged.
- [ ] Each switch has a documented rollback (revert plus re-import).

## Tasks
- **M1** overview, live and log reads. **M2** skills and memory reads. **M3** forge and search proxies. These three run in parallel (need H-023 B6, B7).
- **M4** conversation store (after M1). **M5** memory writes (after M2). **M6** job files (after M2 and H-022 F7a). Do M4 to M6 one at a time.

## Out of scope
The task queue, GitHub polling, briefs.

## Open questions
None beyond those of H-023.
