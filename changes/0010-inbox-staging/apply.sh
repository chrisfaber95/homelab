#!/usr/bin/env bash
# inbox staging (H-028 S1): run the Java inbox as unit homelab-inbox-staging on tailnet port 8125, with its own database and a copy of the data
#
# Why: the Java backend now serves everything the Python inbox does (H-028 W1 to W5). Before it takes over 8105 it runs
# beside the live inbox on a temporary port, against a copy of the data, so Chris can try the phone page and the checks
# in docs/inbox.md ("Staging the Java inbox") without anything live being at risk.
#
# What it makes:
#   - the unit homelab-inbox-staging (account inbox-runner, hardened like homelab-inbox-backend) with the front door on,
#     core.effects=shadow, the scheduler off, the kitt gate off. It listens on 127.0.0.1:8131 (BACKEND_PORT, loopback)
#     and, through inbox.public.port, on 8125 on the tailnet address only (the connector refuses any other address).
#     8125 is inside the existing ufw rule 8100:8199 on tailscale0, so ufw does not change and no rule is added.
#   - the PostgreSQL database inbox_staging (owner inbox_app, nobody else may connect) and one pg_hba line for it.
#   - /var/lib/homelab-inbox-staging (0700, inbox-runner): the data copy and everything staging writes. inbox.db is a
#     SQLite backup-API copy of the live one; memory-repo and jobs-repo are copies with their `origin` removed, so a
#     staging write can never reach GitHub. The unit may write only this directory (ReadWritePaths).
#   - the data: scripts/inbox_cutover.py imports the COPY of inbox.db into inbox_staging (own --database, --unit and
#     --backup-root), so the live inbox.db and the live database `inbox` are never opened for writing and the live
#     homelab-inbox unit is never stopped. The live inbox.db is only read, through the SQLite backup API in read-only mode.
#
# Refuses to copy while a task is running in the live inbox (the import would refuse it anyway, and a copy taken in the
# middle of a task is not a faithful copy). It waits up to 5 minutes for the task to end, then fails. The unit and the
# database are made before that, so a rerun by hand only repeats the copy: sudo bash /var/lib/homelab/repo/changes/0010-inbox-staging/apply.sh
# Rerun with the data already imported: nothing is copied or imported again (staging may hold test data by then). To
# refresh the copy, run revert.sh and then apply.sh.
#
# Needs: scripts/28-inbox-db.sh (account, database inbox, pg_ident map) and scripts/27-backend.sh (the jar, with the
# tailnet connector of W5b). If the merge reaches the box before the jar was rebuilt, this fails on the jar check and
# says so: rebuild with scripts/27-backend.sh and run this file again by hand.
#
# revert.sh removes the unit, the database, the pg_hba line, the data and the port again. It is a manual tool: this change
# touches no ufw, network or SSH file, so homelab-admin asks for no confirmation and never runs it by itself.
set -euo pipefail

UNIT=homelab-inbox-staging
UNIT_FILE=/etc/systemd/system/$UNIT.service
PUBLIC_PORT=8125     # on the tailnet address; inside 8100:8199 on tailscale0
LOOP_PORT=8131       # loopback only (BACKEND_PORT); the live backend has 8130
STAGE=/var/lib/homelab-inbox-staging
LIVE=/var/lib/homelab-inbox
DB=inbox_staging
ROLE=inbox_app
SERVICE_USER=inbox-runner
PG_MAP=homelab_inbox
JAR=/usr/local/lib/homelab/inbox-backend/inbox-backend.jar
BACKUP_ROOT=/var/backups/homelab-staging
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CUTOVER=$REPO/scripts/inbox_cutover.py

[[ $EUID -eq 0 ]] || { echo "Run as root." >&2; exit 1; }

as_postgres() { (cd / && runuser -u postgres -- "$@"); }
as_service() { (cd / && runuser -u "$SERVICE_USER" -- "$@"); }
listening() { ss -ltnH "sport = :$1"; }

# --- 0. Preconditions: fail before the box is touched
id "$SERVICE_USER" >/dev/null 2>&1 || { echo "account $SERVICE_USER missing: run scripts/28-inbox-db.sh first" >&2; exit 1; }
systemctl is-active --quiet postgresql || { echo "PostgreSQL is not running" >&2; exit 1; }
[[ -n $(as_postgres psql -AtXc "select 1 from pg_database where datname = 'inbox'") ]] \
  || { echo "the database inbox does not exist: run scripts/28-inbox-db.sh first" >&2; exit 1; }
grep -qx "$PG_MAP[[:space:]]\+$SERVICE_USER[[:space:]]\+$ROLE" "$(as_postgres psql -AtXc 'show ident_file')" \
  || { echo "pg_ident has no map $PG_MAP for $SERVICE_USER: run scripts/28-inbox-db.sh first" >&2; exit 1; }
[[ -f $LIVE/inbox.db ]] || { echo "$LIVE/inbox.db does not exist: nothing to copy" >&2; exit 1; }
[[ -f $CUTOVER ]] || { echo "$CUTOVER is missing" >&2; exit 1; }
# The port is inside the ufw rule that is already there (nothing may change in ufw).
ufw status | grep -qE '^8100:8199/tcp on tailscale0 +ALLOW' \
  || { echo "ufw has no rule for 8100:8199 on the tailnet: this change adds none, so it stops here" >&2; exit 1; }
[[ $PUBLIC_PORT -ge 8100 && $PUBLIC_PORT -le 8199 ]] || { echo "port $PUBLIC_PORT is outside 8100:8199" >&2; exit 1; }
case $PUBLIC_PORT in 8104|8105|8121) echo "port $PUBLIC_PORT is a live port" >&2; exit 1 ;; esac
# The ports must be free, unless it is this unit's own (a rerun).
if ! systemctl is-active --quiet "$UNIT"; then
  for p in $PUBLIC_PORT $LOOP_PORT; do
    [[ -z $(listening "$p") ]] || { echo "port $p is already in use: $(listening "$p")" >&2; exit 1; }
  done
fi
TSIP="$(tailscale ip -4 2>&1 | head -n1)" || true
[[ $TSIP =~ ^100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]+\.[0-9]+$ ]] \
  || { echo "tailscale has no tailnet IPv4 address ($TSIP)" >&2; exit 1; }
# The jar must have the tailnet connector (W5b) and the front door; an old jar would start without the port.
python3 -I - "$JAR" <<'PY' || { echo "$JAR is missing or too old (no tailnet connector): rebuild it with scripts/27-backend.sh, then run this again by hand" >&2; exit 1; }
import sys, zipfile
names = zipfile.ZipFile(sys.argv[1]).namelist()
need = ("dev/homelab/inbox/frontdoor/PublicConnector.class", "dev/homelab/inbox/frontdoor/FrontDoorFilter.class")
sys.exit(0 if all(any(n.endswith(c) for n in names) for c in need) else 1)
PY

# --- 1. The unit (not started yet)
tmp="$(mktemp "$UNIT_FILE.XXXXXX")"
trap 'rm -f "$tmp"' EXIT
cat > "$tmp" <<'UNIT'
# homelab: H-028 S1, the Java inbox on a staging port (changes/0010-inbox-staging). Not the live inbox: its own database
# (inbox_staging), its own data directory (a copy), shadow effects, no scheduler. Remove it with that change's revert.sh.
# Listens on 127.0.0.1:@LOOP_PORT@ and, only on the tailnet address (INBOX_PUBLIC_PORT, inside ufw's 8100:8199 on tailscale0), on @PUBLIC_PORT@.
[Unit]
Description=homelab inbox, Java staging (H-028 S1, tailnet port @PUBLIC_PORT@, shadow effects)
After=network.target postgresql.service tailscaled.service
Wants=postgresql.service

[Service]
User=inbox-runner
Group=inbox-runner
WorkingDirectory=/
Environment=BACKEND_HOST=127.0.0.1
Environment=BACKEND_PORT=@LOOP_PORT@
Environment=INBOX_PUBLIC_PORT=@PUBLIC_PORT@
Environment=INBOX_PUBLIC_INTERFACE=tailscale0
Environment=INBOX_FRONT_DOOR_ENABLED=true
Environment=CORE_EFFECTS=shadow
Environment=CORE_SCHEDULER_ENABLED=false
Environment=KITT_GATE_ENABLED=false
# Belt and braces on top of shadow effects: no notification leaves the box, and no GitHub token is read.
Environment=NTFY_FAKE=1
Environment=GH_TOKEN_FILE=/nonexistent/no-token
Environment="SPRING_DATASOURCE_URL=jdbc:postgresql://localhost/@DB@?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg&socketFactoryArg=/var/run/postgresql/.s.PGSQL.5432"
Environment=SPRING_DATASOURCE_USERNAME=inbox_app
Environment=BACKEND_SECRET_FILE=@STAGE@/backend-secret
# The copy of the data: inbox.db, logs, memory-repo, jobs-repo, csrf-secret (INBOX_RUNNER_HOME). Config is the live one, read only.
Environment=INBOX_RUNNER_HOME=@STAGE@
Environment=INBOX_CONFIG_DIR=/usr/local/lib/homelab/config
ExecStart=/usr/lib/jvm/java-21-openjdk-amd64/bin/java -Xms64m -Xmx256m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=64m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -jar /usr/local/lib/homelab/inbox-backend/inbox-backend.jar
Restart=on-failure
RestartSec=5
TimeoutStopSec=30
MemoryMax=640M
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
PrivateDevices=yes
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectKernelLogs=yes
ProtectControlGroups=yes
ProtectClock=yes
ProtectHostname=yes
RestrictNamespaces=yes
RestrictRealtime=yes
RestrictSUIDSGID=yes
LockPersonality=yes
SystemCallArchitectures=native
CapabilityBoundingSet=
AmbientCapabilities=
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
# Staging needs only the loopback and the tailnet (the owner's phone); nothing else, in particular not GitHub.
IPAddressDeny=any
IPAddressAllow=localhost 100.64.0.0/10
# The only place it may write; the live runner home, the live clones and the live inbox.db are read-only for it.
ReadWritePaths=@STAGE@

[Install]
WantedBy=multi-user.target
UNIT
sed -i -e "s|@PUBLIC_PORT@|$PUBLIC_PORT|g" -e "s|@LOOP_PORT@|$LOOP_PORT|g" -e "s|@DB@|$DB|g" -e "s|@STAGE@|$STAGE|g" "$tmp"
! grep -q '@[A-Z_]*@' "$tmp" || { echo "unit template still has a placeholder" >&2; exit 1; }
chmod 644 "$tmp"
mv "$tmp" "$UNIT_FILE"
trap - EXIT
systemctl daemon-reload

# --- 2. The database: its own, owned by the app role, closed to everybody else
as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c "select 1" >/dev/null
if [[ -z $(as_postgres psql -AtXc "select 1 from pg_database where datname = '$DB'") ]]; then
  as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c "CREATE DATABASE $DB OWNER $ROLE"
fi
as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c "ALTER DATABASE $DB OWNER TO $ROLE"
as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c "REVOKE ALL ON DATABASE $DB FROM PUBLIC"
HBA_FILE="$(as_postgres psql -AtXc 'show hba_file')"
HBA_LINE="local   $DB   $ROLE   peer map=$PG_MAP"
if ! grep -qxF "$HBA_LINE" "$HBA_FILE"; then
  # First match wins: the line goes before the first rule, like scripts/28-inbox-db.sh does it.
  tmp="$(mktemp "$HBA_FILE.XXXXXX")"
  trap 'rm -f "$tmp"' EXIT
  awk -v line="$HBA_LINE" '
    !done && $0 !~ /^[[:space:]]*(#|$)/ {
      print "# homelab (changes/0010-inbox-staging): the staging inbox, peer authentication through the same map."
      print line
      done = 1
    }
    { print }
    END { if (!done) { print line } }' "$HBA_FILE" > "$tmp"
  chown --reference="$HBA_FILE" "$tmp"
  chmod --reference="$HBA_FILE" "$tmp"
  mv "$tmp" "$HBA_FILE"
  trap - EXIT
  as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c 'select pg_reload_conf()' >/dev/null
  sleep 1
fi
bad="$(as_postgres psql -AtXc "select count(*) from pg_hba_file_rules where error is not null")"
[[ $bad == 0 ]] || { echo "pg_hba.conf has $bad rule(s) with an error: fix it by hand" >&2; exit 1; }
who="$(as_service psql -AtXc 'select current_user' -U "$ROLE" "$DB")" \
  || { echo "$SERVICE_USER cannot log in to $DB as $ROLE" >&2; exit 1; }
[[ $who == "$ROLE" ]] || { echo "logged in to $DB as '$who'" >&2; exit 1; }

# --- 3. The data directory and the copy (skipped on a rerun that already imported)
install -d -m 700 -o "$SERVICE_USER" -g "$SERVICE_USER" "$STAGE" "$STAGE/logs"
if [[ ! -s $STAGE/backend-secret ]]; then
  ( umask 077; openssl rand -hex 32 > "$STAGE/backend-secret" )
  chown "$SERVICE_USER:$SERVICE_USER" "$STAGE/backend-secret"
fi
if [[ -e $STAGE/cutover-import.json ]]; then
  echo "the data is already imported into $DB ($STAGE/cutover-import.json): not copied again (revert.sh, then apply.sh, refreshes it)"
else
  echo "== waiting for the live inbox to have no running task (at most 5 minutes)"
  running() {
    as_service python3 -I -c '
import sqlite3, sys
c = sqlite3.connect("file:" + sys.argv[1] + "?mode=ro", uri=True, timeout=10)
print(c.execute("select count(*) from tasks where status = \x27running\x27").fetchone()[0])' "$LIVE/inbox.db"
  }
  for _ in $(seq 60); do
    [[ $(running) == 0 ]] && break
    sleep 5
  done
  [[ $(running) == 0 ]] || { echo "a task is still running in the live inbox: wait for it, then run $0 again by hand" >&2; exit 1; }
  echo "== copying inbox.db (read-only on the live one, SQLite backup API)"
  rm -f "$STAGE/inbox.db" "$STAGE/inbox.db-wal" "$STAGE/inbox.db-shm"
  as_service python3 -I -c '
import sqlite3, sys
src = sqlite3.connect("file:" + sys.argv[1] + "?mode=ro", uri=True, timeout=10)
dst = sqlite3.connect(sys.argv[2])
src.backup(dst)
dst.close()
src.close()' "$LIVE/inbox.db" "$STAGE/inbox.db"
  chmod 600 "$STAGE/inbox.db"
  echo "== copying the memory and job clones, without their remote"
  for clone in memory-repo jobs-repo; do
    if [[ -d $LIVE/$clone ]]; then
      rm -rf "${STAGE:?}/$clone"
      as_service cp -a "$LIVE/$clone" "$STAGE/$clone"
      as_service git -c safe.directory='*' -C "$STAGE/$clone" remote remove origin 2>/dev/null || true
      # A clone without origin cannot push; leave nothing that could.
      [[ -z $(as_service git -c safe.directory='*' -C "$STAGE/$clone" remote) ]] \
        || { echo "$STAGE/$clone still has a remote" >&2; exit 1; }
    fi
  done
  # A failed earlier import may have left $DB half filled (the import refuses a non-empty target): start it empty.
  # Only this staging database, and only here, where nothing was imported yet (no marker).
  as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS $DB WITH (FORCE)" \
    -c "CREATE DATABASE $DB OWNER $ROLE" -c "REVOKE ALL ON DATABASE $DB FROM PUBLIC"
  echo "== importing the copy into $DB (scripts/inbox_cutover.py, own database, unit and backup directory)"
  # Exactly the line of a failed import: run revert.sh and then this file again.
  python3 "$CUTOVER" --inbox-db "$STAGE/inbox.db" --database "$DB" --unit "$UNIT" --backup-root "$BACKUP_ROOT"
fi

# --- 4. Start and check
systemctl enable "$UNIT" >/dev/null 2>&1
systemctl restart "$UNIT"
for _ in $(seq 40); do
  [[ -n $(listening "$PUBLIC_PORT") && -n $(listening "$LOOP_PORT") ]] && break
  sleep 1
done
systemctl is-active --quiet "$UNIT" || { echo "$UNIT is not running" >&2; exit 1; }
# $PUBLIC_PORT only on the tailnet address, the loopback port only on 127.0.0.1.
bad="$(listening "$PUBLIC_PORT" | awk -v ok="$TSIP:$PUBLIC_PORT" '$4 != ok')"
[[ -n $(listening "$PUBLIC_PORT") && -z $bad ]] || { echo "port $PUBLIC_PORT is not only on $TSIP: $(listening "$PUBLIC_PORT")" >&2; exit 1; }
bad="$(listening "$LOOP_PORT" | awk -v ok="127.0.0.1:$LOOP_PORT" '$4 != ok && $4 != "[::ffff:127.0.0.1]:'"$LOOP_PORT"'"')"
[[ -n $(listening "$LOOP_PORT") && -z $bad ]] || { echo "port $LOOP_PORT is not only on loopback: $(listening "$LOOP_PORT")" >&2; exit 1; }
# From the box itself the page is refused (the owner check: the box is not one of Chris's devices), the same check as scripts/21-inbox.sh.
code="$(curl -s -o /dev/null -w '%{http_code}' "http://$TSIP:$PUBLIC_PORT/")"
[[ $code == 403 ]] || { echo "http://$TSIP:$PUBLIC_PORT/ answered $code from the box (expected 403)" >&2; exit 1; }
code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$LOOP_PORT/api/backend/health")"
[[ $code == 200 ]] || { echo "the health check on 127.0.0.1:$LOOP_PORT answered $code (expected 200)" >&2; exit 1; }
# The live units were not touched by this change.
systemctl is-active --quiet homelab-inbox-backend || echo "note: homelab-inbox-backend is not active (not changed by this change)"
echo "staging is up: http://homelab.tail4eb878.ts.net:$PUBLIC_PORT/ (from Chris's own devices); checks: docs/inbox.md, 'Staging the Java inbox'"
