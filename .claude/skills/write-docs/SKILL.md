---
name: write-docs
description: "Procedure for writing a document from the code: pick the type and reader (README, how-to, reference, explanation), gather facts from the repo and from commands you run, structure it, give runnable examples, and verify every command and link. Followed by the documentation-engineer agent; in a main session, delegate documentation writing to that agent."
argument-hint: "<what to document and for whom>"
allowed-tools: [Read, Write, Edit, Glob, Grep, Bash]
---

Write documentation for: $ARGUMENTS

Wherever a step says to ask the user: in a main session, ask. When running as the
documentation-engineer subagent, state the assumption and return the question. Change
documentation files only.

## Steps

1. Decide the document type and the reader, and say them in the first lines of your notes:
   - **README**: what it is, how to run it, where to read more
   - **how-to guide**: a task with steps, for someone who knows the basics
   - **reference**: exact facts (endpoints, options, environment variables, commands)
   - **explanation**: why it is built this way (architecture, trade-offs)
   One document, one type; link between them instead of mixing.
2. Find what already exists on the topic and where the project keeps such documents. Update or
   extend instead of duplicating, and match the existing headings, tone and formatting.
3. Gather facts from the source: read the code, the config, `package.json` scripts, the
   environment variable usage, the routes or spec, and the tests (they show real usage). Do not
   rely on older documents as truth.
4. Verify what you will write by running it when it is safe and local: install, build, test and
   lint commands, `--help` output, and the app's start command. Note the versions and
   prerequisites that were actually needed. If a step failed or you could not run it, either fix
   the instruction or mark it unverified; never present it as working.
5. Structure for scanning: a clear title, a one or two line summary, prerequisites, numbered
   steps for procedures, tables for reference data, and short paragraphs. Put the most common
   task first. Use headings that name the task ("Run the tests"), not the concept.
6. Examples must be copy-paste runnable, with placeholders where a secret or an environment
   value belongs, and with the expected output when it helps. Use the same names and values the
   code uses.
7. Explain the why where a reader would otherwise guess wrong (a constraint, a trade-off, a
   gotcha), and leave out marketing language and restating what the code obviously does. Add
   a troubleshooting section only for problems that really occur and that you can describe
   precisely.
8. Architecture documents: describe the components, what each owns, how data flows, and the
   decisions with their reasons. A Mermaid diagram helps if the project already uses diagrams;
   keep it small and accurate to the code.
9. Check the result: every link and relative path resolves, every command was run or marked,
   names match the code, nothing contains secrets, and the document does not contradict others.
10. Report: files written, what each covers, what you verified by running it, what you could
    not verify, and mismatches between code and existing documents that need someone's decision.
