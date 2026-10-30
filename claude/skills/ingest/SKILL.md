---
name: ingest
description: Add what this session learned to the second brain (/srv/obsidian/second-brain) from any session - write a note into its raw/inbox and have a vault session process it into the wiki. Use when Chris says /ingest, "add this to the second brain" or "remember this in the wiki". Optional argument - what to write down; otherwise the session's lasting facts and decisions.
---

# Ingest into the second brain

Installed for every session of `claude` from `claude/skills/ingest/` in the homelab repo.

## In the vault itself

If the working directory is `/srv/obsidian/second-brain`, this is the vault's own ingest: read
`/srv/obsidian/second-brain/.claude/skills/ingest/SKILL.md` and follow it with the given argument (a file in
`raw/inbox/`, or the whole inbox). Skip everything below.

## From any other session

1. **Write the note.** From the argument if Chris gave text, otherwise from this session: only what lasts.
   - decisions, with the why and the alternatives that were dropped;
   - facts about how a project or the box works now that the repos don't say plainly (verified behaviour, limits);
   - what changed, with sources: repo, PR links, commit hashes, file paths;
   - absolute dates (`2026-10-30`), never "today".
   Leave out secrets, tokens, passwords, personal data about others, and anything already obvious from the code.
   Plain Markdown: `# <title>`, a line `Source: Claude session in <working directory>, <date>`, then short sections.
2. **Put it in the inbox**: `/srv/obsidian/second-brain/raw/inbox/<YYYY-MM-DD>-<kebab-slug>.md` (a new file; never
   touch anything else in the vault).
3. **Process it**: `second-brain-ingest raw/inbox/<file name>` (Bash, timeout 15 minutes). It starts a Claude session
   inside the vault that runs the vault's own ingest on that one file, then commits. Don't edit wiki pages yourself.
4. **Report** in two or three lines: the note's name and the script's summary (pages created or changed,
   conflicts). If the script fails, say so with its last lines: the note stays in `raw/inbox/`, and the next
   `/ingest` in the vault picks it up.
