---
paths:
  - "bin/homelab-monitor"
  - "bin/homelab-backup"
  - "bin/homelab-health"
  - "config/backup.conf"
  - "config/backup.list"
  - "config/health.conf"
  - "scripts/13-health-check.sh"
  - "scripts/20-monitor.sh"
---

# Testing monitor, backup and health (F-016, F-020)

- `homelab-monitor`: `MONITOR_DIR`/`OVERVIEW_DIR`/`HEALTH_CMD`/`NTFY_FAKE=1`.
- `homelab-backup`: `BACKUP_LIST`/`BACKUP_DIR`/`BACKUP_STATUS`/`RESTORE_ROOT`/`NO_SERVICES=1`.
- `homelab-health` runs straight from the repo; the expected GPU is `EXPECTED_GPU` in `config/health.conf`.
