---
when: every weekday at 7:00
schedule: "0 7 * * 1-5"
kind: box-report
model: local
deliver: conversation
minutes: 5
---
Summarise the state of the box in at most eight lines: health checks, deploys, backups, LLM use and changes that were
applied. Start with what failed or needs attention, then one line saying everything else is fine.

Nothing to report is: no check failed, no deploy failed, no backup is overdue and no change is waiting. Then answer
NOTHING TO REPORT as the first line and nothing else.
