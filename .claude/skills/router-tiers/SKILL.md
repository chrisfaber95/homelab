---
name: router-tiers
description: Rules for the LLM router (H-012): how tiers map to backends, escalation and fallback, the usage log, and how callers declare a tier. Use when building or changing the router, its config table, its log or a caller of it.
---

Source: brief H-012 (`homelab/briefs/H-012-llm-router-and-search.md` in the product repo). Facts not in the brief or the project docs are open questions: do not invent them.

## Tiers and backends
- Every request carries a caller name and a tier. Callers declare the tier; the router does not classify and does not look at remaining allowance.
- A config table maps each tier to a backend: the local model or the `claude` CLI. Changing the table changes routing without a code change.
- Claude is only reached through the subscription `claude` CLI. No Claude API key is used or stored anywhere.
- Tier names and which jobs start on which tier are an open question in the brief; take them from the planner's decision, not from guesses.

## Failure, timeout and escalation
- Per tier the config says what happens when the backend fails or times out: fail visibly, or escalate to the other backend.
- An escalation shows in the log.

## Usage log
- Log every request, including Claude runs: caller, tier, backend, duration, outcome, plus token counts when the backend reports them.
- A thumbs-down on an answer is stored with its caller, tier and backend and appears in the usage view.
- The usage view shows requests per caller and tier, the share served locally, and the number of Claude runs avoided.

## Callers
- Scheduled jobs, inbox tasks, apps on the box and Claude sessions (as callers of the router) can use it. Existing F-017 callers keep working during the move.
- Claude Code itself is not proxied through the router; sessions call it as a tool.
- Reachable over the tailnet only; no new LAN port.
