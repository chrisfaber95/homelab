---
name: research-reports
description: Rules for research runs and their reports (H-017): a source for every claim, private search before the web, the quick and deep modes, how to say nothing was found, and the limits of a run. Use when building or changing the research feature, its prompts, its entry points (Discord command, homelab page, scheduled job) or how a report is shown and delivered.
---

Source: brief H-017 (`homelab/briefs/H-017-agents-research-delivery-catalogue.md` in the product repo) and `docs/jobs.md` in this repo for the daily cap. Facts in neither are open questions: do not invent them (the exact length of "a few minutes" and "a handful of sources", and the command's name, are for the build to decide and document).

## Where a question comes from, where the report goes
- A research question can be asked from the conversation of H-016 (a command in Discord), from the homelab page, and as a job on a schedule.
- The report is delivered to where the question came from, and is kept on the page with its sources.

## What a report must contain
- An answer to the question, with a source for every claim.
- A web source is cited with a link. A hit from private search is cited with its source, place and date.
- Private search is consulted before the web.
- The mode and the time taken are shown with the report.
- A claim without a source does not belong in the report: leave it out or mark it clearly as not found.

## Nothing found
- When nothing relevant is found, the report says so and lists what was searched. It does not guess.

## Two modes
- Chosen per question: quick (a few minutes, a handful of sources) or deep (up to the limit of an inbox task).
- The point of quick is that a small question does not cost a long Claude run.

## Limits of a run
- Every research run counts as one run against the daily cap for Claude jobs (`JOB_CLAUDE_CAP`, see `docs/jobs.md`). Above the cap the question waits or is refused with a clear message, as jobs are today.
- A research run changes nothing: no commit, branch or PR, and no machine change.
- No logging in to sites and no paywalled sources.
- No agent loops on the local model, and no new model or backend.
