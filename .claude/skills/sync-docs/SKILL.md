---
name: sync-docs
description: "Procedure for keeping documentation in step with the code: read a diff or branch, find the docs affected (README, architecture overview, API docs, environment variables, commands, per-change records the project requires), update them, and check them against the code. Followed by the documentation-engineer agent; in a main session, delegate documentation updates after a change to that agent."
argument-hint: "<diff, branch or change to sync docs with>"
allowed-tools: [Read, Write, Edit, Glob, Grep, Bash]
---

Update the documentation for the change: $ARGUMENTS

Wherever a step says to ask the user: in a main session, ask. When running as the
documentation-engineer subagent, state the assumption and return the question. Change
documentation files only; never change code to make a document true.

## Steps

1. Get the change: `git diff <base>...HEAD`, `git diff`, a named branch or commits, or the
   files listed in the request. Read what the change does, not only its description. If there is
   no way to see the change, say so and ask for it.
2. Find what the change affects for a reader:
   - behavior, commands, scripts, options or defaults that people run
   - environment variables and configuration (added, renamed, removed, new defaults)
   - API endpoints, request and response shapes, errors, and any API spec file
   - setup and prerequisites (versions, dependencies, services)
   - architecture: new components, changed boundaries or data flow, new external services
   - behavior that existing docs describe differently now
3. Find where each of these is documented: search `README.md`, `ARCHITECTURE.md`, `docs/`,
   the API spec, comments that document public behavior, the changelog, and `.env.example`.
   Search for the old names and values to catch stale mentions.
4. Apply the project's documentation rules from `CLAUDE.md` and the project parameters (`.claude/project-params.md`, or a root `WORKFLOW.md`): for example
   "update `ARCHITECTURE.md` when architecture changes", or a required record per change in a
   given folder with a given template and a summary index. Follow naming, numbering and template
   exactly, use the next sequential number, and never reuse or delete historical entries. If a
   rule says the docs are kept local and not committed, say which files that applies to in your
   report.
5. Update each affected document with the smallest accurate change. Keep the existing structure
   and tone. Remove or fix statements the change made false; do not leave "later" markers.
6. Verify against the code: re-run the commands you documented where it is safe and local,
   confirm each name, path, option and default against the source, and check links and
   references. Mark anything you could not verify.
7. If the change introduces a code and documentation mismatch that needs a code decision (for
   example behavior that contradicts the documented intent), do not paper over it; report it.
8. Report: the documents updated with a one-line summary each, anything you intentionally left
   unchanged and why, what you verified and how, and open mismatches.
