---
name: job-delivery
description: Rules for delivering job results and for the agent catalogue (H-017): how a job file names where a successful result goes, what counts as nothing to report, and the file format of a catalogue agent. Use when building or changing job delivery, the catalogue on the Jobs tab, a catalogue agent file or the repo watcher.
---

Source: brief H-017 (`homelab/briefs/H-017-agents-research-delivery-catalogue.md` in the product repo) and `docs/jobs.md` in this repo (F-022) for how jobs work today. Facts in neither are open questions: do not invent them. Not fixed by the brief and for the build to decide and document in `docs/jobs.md`: the name and values of the delivery setting, how "nothing to report" is detected, and where in this repo the catalogue files live.

## Jobs today (docs/jobs.md)
- A job is a Markdown file with a frontmatter header (`when`, `schedule`, `kind`, `model`, and per kind `repo`, `skills`, `script`, `args`, `sources`, plus `minutes`, `paused`) and below it the instruction: what the job does and what the result looks like.
- Kinds: `box-report`, `repo-chore`, `digest`, `script`. Model: `local` or `claude`.
- Confirmed jobs live on the `jobs` branch of the private data repo; only Chris switches a job on, by confirming it on the page. A file with an error does not run and its error shows on the page.
- A job notifies only when it fails, opens or updates a PR, asks a question, or stops on a limit. A successful result is only in the job's history on the page.

## Where a result goes
- A job file can name where a successful result goes: the page only (as today), ntfy, or the conversation of H-016, which Discord shows.
- A job without that setting behaves exactly as before. Keep this backwards compatible: existing job files must not need a change.
- A delivered result arrives in full, scrubbed like every message in the conversation, with a link to the run.
- The existing notifications (failure, PR, question, limit) are unchanged; delivery is in addition to them, not a replacement.

## Nothing to report
- A delivering job whose result is "nothing to report" sends nothing. The run and its result are still in the job's history.
- What counts as nothing to report is stated in the job's own instruction, and is shown on the job's page. It is not a hidden rule in code.

## Catalogue
- The Jobs tab has a catalogue with at least: morning digest, box report and repo watcher. Each entry shows what it does, its schedule, its model and where it delivers.
- Catalogue agents are files in this repo: a rebuild brings the catalogue back. A broken file shows its error in the catalogue and does not hide the other entries.
- A catalogue file is a job file: the same header and instruction as above, including the delivery setting and, in the instruction, what counts as nothing to report. This repo is public: no private sources, names or secrets in a catalogue file.
- Switching an agent on shows the job file first, lets Chris change it (schedule, sources, repos to watch) and needs one confirmation, like a drafted job today. Nothing runs before that.
- Once switched on it is an ordinary job: it can be paused, changed, run now and deleted, and the catalogue shows it as on.
- The morning digest's news sources are an open question in the brief: Chris fills them in when he switches it on. Do not ship a guessed list as if it were his choice.

## Repo watcher
- Reports new commits, pull requests and releases of the GitHub repos named in its file since the day of its previous run, and delivers only when there are any.
- That "since the previous run" is the only memory an agent has; agents that remember what they reported last time are out of scope.

## Out of scope
- Agent loops on the local model, and any new model or backend.
- A dependency-update agent in the catalogue (the `repo-chore` job kind still exists).
- Agent cards in the Jarvis app (H-018).
