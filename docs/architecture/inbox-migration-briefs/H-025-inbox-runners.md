---
id: H-025
title: "Inbox migration 6: Claude and local-model work as Python runners"
status: draft
priority: could
repos: [homelab]
build:
  kit:
    - {agent: backend-developer}
---
## Problem
`rounds.py`, `router.py`, parts of `jobs.py` and research mix orchestration with Claude calls. Java must only decide when and with what; Claude work stays in Python.

## Outcome
`runner/*.py` scripts behind the process contract (`runner/SCHEMA.md`), used by the Python inbox first, then called by a Java `RunnerService` the same way.

## Acceptance criteria
- [ ] `runner/round.py` wraps `homelab-inbox-task`, writes NDJSON with a final `{"event":"result","v":1,...}` line and keeps the exit codes; the Python inbox uses it and its tests pass.
- [ ] Route, draft and review runners exist the same way.
- [ ] Java's `RunnerService` starts a runner with an argument array (no shell), enforces deadline and cancel (`kill` action, then the process group) and is tested with the fake runner.
- [ ] No token or prompt goes in arguments; the sudoers line is unchanged.

## Tasks
- **R1** `runner/round.py` (H-020 C3; Q7). **R2** route, draft and review runners (R1). **R3** Java `RunnerService` (H-023 B1, H-020 C3). R3 can run parallel to R1 and R2.

## Out of scope
Changing what Claude is asked; moving `llm/` or `search/`.

## Open questions
Q7: runner shape.
