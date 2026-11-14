---
when: every weekday at 7:00
schedule: "0 7 * * 1-5"
kind: box-report
model: local
deliver: conversation
minutes: 5
---
Report only what failed or went wrong on the box: one line per problem, with what and since when, at most five lines.

Nothing to report is: no check failed, no deploy failed and no backup is overdue. Then answer NOTHING TO REPORT as the
first line and nothing else.
