---
when: every morning at 8:00
schedule: "0 8 * * *"
kind: repo-watch
model: local
sources: []   # fill in when switching on: the repos to watch, as github:owner/repo
deliver: conversation
minutes: 10
---
List what is new in each repo below since the day of the previous run: new commits, pull requests (opened, merged or
closed) and releases. One line per item, grouped per repo, with the PR number or release tag.

Nothing to report is: every repo says "geen activiteit" (no commits, pull requests or releases since that day) or none
could be loaded. Then answer NOTHING TO REPORT as the first line and nothing else.
