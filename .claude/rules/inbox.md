---
paths:
  - "bin/homelab-inbox"
  - "bin/homelab_inbox/**"
  - "bin/homelab-inbox-task"
  - "config/inbox.conf"
  - "scripts/21-inbox.sh"
  - "web/**"
---

# Testing homelab-inbox and the homelab page (F-021 to F-024)

- Test without root: `INBOX_FAKE_GITHUB=1`, `TASK_CMD`, `INBOX_RUNNER_HOME`/`INBOX_TASK_HOME`, `REPOS_CONF`,
  `REMOTE_<NAME>`, `INBOX_ALLOW_ALL=1`. For jobs also `OVERVIEW_CMD`, `LLM_URL` (a fake LLM) and `INBOX_SKILLS_DIR`
  (never point it at your own `~/.claude/skills`).
- Load functions in Python with `SourceFileLoader` (the script has no `.py` extension). It re-exports every
  public name of the modules in `bin/homelab_inbox/`; to patch a function for a test, patch it in the module that defines it.
- Job times are in `JOB_TIMEZONE`; the box runs on UTC.
- `--max-turns` of `claude -p` doesn't work; the inbox counts the turns itself.
- Logs per task or run: `/var/lib/homelab-inbox/logs/`.
- Page: take screenshots with Playwright (`python` is the Playwright venv). Style: dark and blue after the KITT skin,
  dark only; see `docs/dashboard.md`.
