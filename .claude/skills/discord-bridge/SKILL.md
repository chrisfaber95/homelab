---
name: discord-bridge
description: Rules the Discord bot follows (H-016): the owner-only check on every message and button, DM only, questions versus tasks, message length limits and slow answers, what is scrubbed before text leaves the box, the token and the switches. Use when building or changing the bot service, its commands and buttons, its install script or its docs.
---

Source: brief H-016 (`homelab/briefs/H-016-chat-and-discord.md` in the product repo) and its stack decision (`homelab/research/stack-decision.md` there). Facts in neither, nor in this repo's docs, are open questions: do not invent them. For the conversation, permissions and approvals, see the `chat-clients` skill.

## Shape of the service
- A separate small service in this repo, under its own account (precedent: forge has its own account, unit and install script). The Discord library and the token stay out of the inbox process.
- Library: discord.py in its own venv, pinned with hashes.
- The bot is one client of the conversation in the inbox and keeps no history of its own.
- It only connects outward: no new listening port, and the exposure checker must pass.
- With Discord unreachable or the bot stopped, the page, ntfy and the task queue work as before; messages written meanwhile arrive in the DM once the bot is back. ntfy stays as the short push channel.

## Owner only, DM only
- Check on every message, button press and command, not once per session: it must come from the owner's Discord account and in the DM.
- Anything from another account or place does nothing and is logged.

## Messages, commands and buttons
- A plain message is a question: answer from the private search, with citations, and say so when nothing relevant was found. Under the answer is a button that turns the message into an inbox task. No model decides whether something is a task.
- A command starts a task on a chosen repo or on "auto". Other commands show what is running and queued, list the jobs, and run a job now.
- A reply to a question from Claude continues that task.
- Approvals: only a command or button on a draft brief plus a confirm button that expires; free text never approves. With approvals switched off, the buttons answer that approvals are off.
- At most a configured number of tasks per day can start from Discord; above it, say so and start nothing.
- SSH keys, memory edits and merges on `homelab` are not available from Discord.

## Length limits and slow answers
- Every notification the inbox sends also arrives in the DM with its full text and its link. A text too long for one Discord message arrives complete: split, or as an attachment. Never truncate silently.
- The stack decision names Discord's limits as 2000 characters per message and three seconds to acknowledge an interaction, and marks both as not checked: verify them (and the current discord.py version and package count) in the build before relying on them.
- Design for answers slower than the acknowledge window (a search answer, a task start): acknowledge first, deliver the result afterwards.

## What leaves the box
- Notification and answer text leaves the tailnet and is stored by Discord: a deliberate, documented exception to "reached only over my own network". Send nothing beyond what is in the conversation.
- Text is scrubbed with the memory's secret filter before it enters the conversation, so the bot only ever sends scrubbed text; never send text that bypassed the conversation.
- The bot's token is only in `/etc/homelab`: never in a repo, a log or the conversation. A rebuild from the repo brings the bot back after the token is entered once.

## Switches and docs
- The homelab page has a switch for Discord as a whole and one for approvals from Discord; each works without a restart or a rebuild.
- The docs say how to create the Discord application, where the token goes, and how to switch the bot off.
