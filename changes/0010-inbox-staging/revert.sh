#!/usr/bin/env bash
# inbox staging: remove the unit, its database, its pg_hba line, its data copy and free port 8125 again (changes/0010-inbox-staging)
#
# Removes only what apply.sh made: the unit homelab-inbox-staging, the database inbox_staging, its pg_hba line, the
# directory /var/lib/homelab-inbox-staging and /var/backups/homelab-staging. The live inbox (unit homelab-inbox, its
# inbox.db), the live backend (unit homelab-inbox-backend) and the database `inbox` are not touched. ufw is not touched
# either: apply.sh added no rule. Idempotent: a second run finds nothing to remove and still checks the port.
set -euo pipefail

UNIT=homelab-inbox-staging
UNIT_FILE=/etc/systemd/system/$UNIT.service
PUBLIC_PORT=8125
LOOP_PORT=8131
STAGE=/var/lib/homelab-inbox-staging
BACKUP_ROOT=/var/backups/homelab-staging
DB=inbox_staging
ROLE=inbox_app

[[ $EUID -eq 0 ]] || { echo "Run as root." >&2; exit 1; }
[[ $DB == inbox_staging && $STAGE == /var/lib/homelab-inbox-staging ]] || exit 1   # never anything live

as_postgres() { (cd / && runuser -u postgres -- "$@"); }

systemctl disable --now "$UNIT" >/dev/null 2>&1 || true
rm -f "$UNIT_FILE"
systemctl daemon-reload
systemctl reset-failed "$UNIT" >/dev/null 2>&1 || true

if systemctl is-active --quiet postgresql; then
  if [[ -n $(as_postgres psql -AtXc "select 1 from pg_database where datname = '$DB'") ]]; then
    as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c "DROP DATABASE $DB WITH (FORCE)"
  fi
  HBA_FILE="$(as_postgres psql -AtXc 'show hba_file')"
  if grep -qF "$DB   $ROLE" "$HBA_FILE"; then
    tmp="$(mktemp "$HBA_FILE.XXXXXX")"
    trap 'rm -f "$tmp"' EXIT
    grep -vF -e "local   $DB   $ROLE   peer" -e "changes/0010-inbox-staging" "$HBA_FILE" > "$tmp" || true
    chown --reference="$HBA_FILE" "$tmp"
    chmod --reference="$HBA_FILE" "$tmp"
    mv "$tmp" "$HBA_FILE"
    trap - EXIT
    as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c 'select pg_reload_conf()' >/dev/null
  fi
fi

rm -rf "${STAGE:?}" "${BACKUP_ROOT:?}"

# The port is free again, the database and the unit are gone, the live inbox is as it was.
for p in $PUBLIC_PORT $LOOP_PORT; do
  [[ -z $(ss -ltnH "sport = :$p") ]] || { echo "port $p is still in use: $(ss -ltnH "sport = :$p")" >&2; exit 1; }
done
[[ ! -e $UNIT_FILE ]] || { echo "$UNIT_FILE is still there" >&2; exit 1; }
if systemctl is-active --quiet postgresql; then
  [[ -z $(as_postgres psql -AtXc "select 1 from pg_database where datname = '$DB'") ]] || { echo "database $DB is still there" >&2; exit 1; }
  [[ -n $(as_postgres psql -AtXc "select 1 from pg_database where datname = 'inbox'") ]] || { echo "the live database inbox is gone: look at it by hand" >&2; exit 1; }
fi
echo "inbox staging removed: no unit, no database $DB, ports $PUBLIC_PORT and $LOOP_PORT free"
