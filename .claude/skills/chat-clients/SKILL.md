---
name: chat-clients
description: Rules for the conversation between Chris and the box (H-016): what it stores, how a client such as the Discord bot (later Jarvis) reads and writes it, per-client permissions, and the two-step approval with confirm. Use when building or changing the conversation store in the inbox, the client interface, a client's permission list or an approval that comes from a client.
---

Source: brief H-016 (`homelab/briefs/H-016-chat-and-discord.md` in the product repo) and its stack decision (`homelab/research/stack-decision.md` there). Facts in neither, nor in this repo's docs, are open questions: do not invent them (the shape of the client interface, permission names, config file and expiry times are for the build to decide and document).

## One conversation
- The box keeps one conversation with Chris. It holds every message, answer, question from Claude, notification and approval request, each with its time, who wrote it and through which client.
- It lives in the inbox and survives a restart. A client keeps no history of its own.
- Text is scrubbed with the secret filter the memory uses before it is stored. Test: a planted fake token in a task answer appears in no client.
- Open question in the brief: whether scrubbing and storing slows a notification down. Measure it in the build.

## Clients
- A client reads the conversation from a given point onward (a cursor) and writes to it.
- The inbox refuses requests from the box itself, so a local client gets a new, explicit and narrow door; do not widen an existing one.
- Each client has its own set of permissions, set in this repo's config. An action without the permission is refused and the refusal is logged.
- The Discord bot is the first client; the Jarvis app (H-018) is the second. Build the interface so a second client needs no change to the first.
- The inbox stays stdlib-only and must work as before when a client is stopped or unreachable; messages written meanwhile reach the client once it is back.
- Rule from the stack decision: code that holds a permission to approve lives in this repo (merged by Chris), or does not hold it.

## What a client can start
- A task from a client goes into the same queue, with the same limits, auto mode and PR as one sent from the homelab page, and its page shows which client it came from.
- A reply to a question from Claude continues that task, as an answer on the page does.
- No model sits between a message and an action: nothing classifies free text as a task or an approval.
- SSH keys, memory edits and merges on `homelab` cannot be done from a client.

## Two-step approval
- Approve & build and Approve & build without me are offered on a draft brief only as a command or button, each followed by a confirm button that expires.
- Free text never approves: a message such as "approve H-016" starts nothing.
- Every approval from a client is recorded as such on the brief's commit and on the build's page, and also sends an ntfy notification.
- The homelab page has a switch for approvals from Discord (and one for Discord as a whole). They work without a restart or rebuild; with approvals off, the request is answered with "approvals are off" and nothing starts.
