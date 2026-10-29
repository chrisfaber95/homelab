---
paths:
  - "bin/homelab-admin"
  - "changes/**"
  - "config/machine.conf"
  - "config/admin.conf"
  - "scripts/19-admin.sh"
---

# Testing homelab-admin and changes (F-019)

- Test without root: `HOMELAB_ADMIN_FAKE=1` with your own `REPO_DIR`/`STATE_DIR`/`LOG_FILE`/`SOCKET`/`SNAP_PATHS`
  and `NO_FETCH=1` (see the docstring of `bin/homelab-admin`).
- `ufw` treats a rule that only differs in allow/deny as the same one and skips it. In `apply.sh`, always check that
  the change is actually in place.
- Run the `change-reviewer` agent on every `changes/*/apply.sh` before opening the PR.
