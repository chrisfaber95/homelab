#!/usr/bin/env bash
# inbox cut-over rollback: the Python inbox owns 8105 and 8121 again, the Java backend is the shadow backend on 127.0.0.1:8130 again
#
# Data written in Java since the cut-over (tasks, drafts, runs, jobs state) is NOT carried back to inbox.db: the old inbox starts on
# the last inbox.db, as it was when apply.sh stopped it (Q9, accepted for one release). The final inbox.db and the pg_dump stay in
# /var/backups/homelab/inbox-cutover-<date>/. Conversation, memory and job files are Java's since H-024 M4 to M6 and are not lost.
# Refuses while a task is running in the Java inbox (stopping it would cut the task off), unless FORCE=1 is set.
# Idempotent. Order for a real rollback (docs/inbox.md): run this file from root's clone, then merge the revert PR.
#   sudo bash /var/lib/homelab/repo/changes/0011-inbox-java-cutover/revert.sh
set -euo pipefail

BACKEND=homelab-inbox-backend
OLD=homelab-inbox
DROPIN_DIR=/etc/systemd/system/$BACKEND.service.d
DROPIN=$DROPIN_DIR/10-cutover.conf
MARKER=/var/lib/homelab-inbox/java-cutover
OLD_DB=/var/lib/homelab-inbox/inbox.db
PAGE_PORT=8105
GATE_PORT=8121
listening() { ss -ltnH "sport = :$1"; }

[[ $EUID -eq 0 ]] || { echo "Run as root." >&2; exit 1; }
[[ -f /etc/systemd/system/$OLD.service ]] || { echo "unit $OLD is not installed: nothing to go back to" >&2; exit 1; }

if [[ ${FORCE:-0} != 1 && -e $DROPIN ]]; then
  # The page refuses the box itself (403), so ask the database the Java inbox writes.
  n="$(cd / && runuser -u postgres -- psql -d inbox -AtXc "select count(*) from tasks where status = 'running'")" \
    || { echo "cannot count the running tasks in PostgreSQL (FORCE=1 skips this check)" >&2; exit 1; }
  [[ $n == 0 ]] || { echo "a task is running in the Java inbox: wait for it, or run with FORCE=1 to cut it off" >&2; exit 1; }
fi

systemctl stop "$BACKEND" || true
rm -f "$DROPIN" "$MARKER"
rmdir "$DROPIN_DIR" 2>/dev/null || true
systemctl daemon-reload
systemctl start "$BACKEND"
systemctl enable "$OLD" >/dev/null
systemctl restart "$OLD"

for _ in $(seq 30); do
  [[ -n $(listening "$PAGE_PORT") ]] && break
  sleep 1
done
systemctl is-active --quiet "$OLD" || { echo "$OLD is not running" >&2; exit 1; }
systemctl is-active --quiet "$BACKEND" || { echo "$BACKEND is not running" >&2; exit 1; }
code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PAGE_PORT/")"
[[ $code == 403 ]] || { echo "the old inbox answered $code on 127.0.0.1:$PAGE_PORT (expected 403)" >&2; exit 1; }
pid="$(systemctl show -p MainPID --value "$OLD")"
ss -ltnpH "sport = :$PAGE_PORT" | grep -q "pid=$pid," || { echo "port $PAGE_PORT is not held by $OLD" >&2; exit 1; }
[[ -n $(listening 8130) ]] || { echo "the backend is not back on 127.0.0.1:8130" >&2; exit 1; }
[[ -f $OLD_DB ]] || { echo "$OLD_DB is missing" >&2; exit 1; }
echo "rolled back: the old inbox ($OLD) is enabled and owns $PAGE_PORT and $GATE_PORT again; the backend is the shadow backend on 8130."
echo "Merge the revert PR of the cut-over now, so main and the box agree. The final inbox.db and pg_dump: /var/backups/homelab/"
