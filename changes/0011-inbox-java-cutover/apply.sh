#!/usr/bin/env bash
# inbox cut-over (H-028 X1b): the Java backend takes over ports 8105 and 8121 from the Python inbox, with live effects and the scheduler
#
# Why: the Java backend serves everything the Python inbox does (H-028 W1 to W5) and has run on the staging port (S1). This is the
# switch. It replaces H-027 X1b. Chris merges it at a moment when no task is running (a task that runs is cut off by the switch).
#
# What it does, in this order (docs/inbox.md, "Cut-over to the Java inbox"):
#   0. checks everything it needs before it touches anything: the jar (with the shared-port connector), PostgreSQL, the ufw rule
#      that is already there, the tailnet address, the old unit and the unit of port 8104.
#   1. refuses to run while a task is running (status 'running' in inbox.db): exit 1, nothing changed. Run it again by hand later.
#   2. the final import: scripts/inbox_cutover.py checks again, stops homelab-inbox, imports inbox.db into PostgreSQL, compares the
#      row counts per table (any difference: it starts the old inbox again and this script stops, nothing switched) and keeps the
#      final inbox.db and a pg_dump in /var/backups/homelab/inbox-cutover-<date>/.
#   3. the switch: a drop-in for homelab-inbox-backend (BACKEND_PORT=8105 on loopback, INBOX_PUBLIC_PORT=8105 on the tailnet address
#      only, front door on, core.effects=live, scheduler on, kitt gate on 8121), the marker /var/lib/homelab-inbox/java-cutover (so
#      scripts/21-inbox.sh and scripts/27-backend.sh leave the old unit alone and check the new shape), homelab-inbox disabled
#      (its unit stays installed), the backend restarted.
#   4. the checks: /api/backend/health on 8105 answers 200; the listeners of the backend are only 127.0.0.1:8105, <tailnet>:8105 and
#      <tailnet>:8121; the page from the box is refused (403); the chat door is refused on the tailnet; 8104 is still held by
#      homelab-overview-web; ufw is as it was; homelab-inbox is disabled, inactive and installed. If one fails after the switch,
#      the switch is undone on the spot (the old unit is enabled and started again) and this script fails.
#   5. the staging unit is removed (changes/0010-inbox-staging/revert.sh: unit, database, data, port 8125 and 8131).
#
# ufw is not touched: 8105 and 8121 are inside the existing rule 8100:8199 on tailscale0. 8104 is not touched.
# Rerun: with the marker present it skips the import and only makes the drop-in and the checks again.
# Rollback: revert.sh (docs/inbox.md). Data written after this switch is lost on a rollback (Q9): the last inbox.db and pg_dump are kept.
# Needs scripts/27-backend.sh to have installed a jar of this version (the shared-port connector); if the merge reaches the box
# before the jar was rebuilt, this fails on the jar check, says so, and a rerun by hand after scripts/27-backend.sh does it:
#   sudo bash /var/lib/homelab/repo/changes/0011-inbox-java-cutover/apply.sh
set -euo pipefail

BACKEND=homelab-inbox-backend
OLD=homelab-inbox
STAGING=homelab-inbox-staging
DROPIN_DIR=/etc/systemd/system/$BACKEND.service.d
DROPIN=$DROPIN_DIR/10-cutover.conf
MARKER=/var/lib/homelab-inbox/java-cutover
LIVE=/var/lib/homelab-inbox
SERVICE_USER=inbox-runner
PAGE_PORT=8105
GATE_PORT=8121
OVERVIEW_PORT=8104
JAR=/usr/local/lib/homelab/inbox-backend/inbox-backend.jar
SCHEDULER_DROPIN=$DROPIN_DIR/20-scheduler.conf
# What sudo needs to run homelab-inbox-task as the account inbox (setuid, setgid, audit, rlimits); the drop-in and the check use the same list.
CAPS="CAP_SETUID CAP_SETGID CAP_AUDIT_WRITE CAP_SYS_RESOURCE"
TASK_BIN=/usr/local/lib/homelab/bin/homelab-inbox-task
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CUTOVER=$REPO/scripts/inbox_cutover.py

[[ $EUID -eq 0 ]] || { echo "Run as root." >&2; exit 1; }
as_service() { (cd / && runuser -u "$SERVICE_USER" -- "$@"); }
listening() { ss -ltnH "sport = :$1"; }

# --- 0. Preconditions: fail before the box is touched
id "$SERVICE_USER" >/dev/null 2>&1 || { echo "account $SERVICE_USER missing: run scripts/28-inbox-db.sh first" >&2; exit 1; }
systemctl is-active --quiet postgresql || { echo "PostgreSQL is not running" >&2; exit 1; }
[[ -f $LIVE/inbox.db ]] || { echo "$LIVE/inbox.db does not exist: nothing to cut over" >&2; exit 1; }
[[ -f $CUTOVER ]] || { echo "$CUTOVER is missing" >&2; exit 1; }
[[ -f /etc/systemd/system/$BACKEND.service ]] || { echo "unit $BACKEND is not installed: run scripts/27-backend.sh first" >&2; exit 1; }
[[ -f /etc/systemd/system/$OLD.service ]] || { echo "unit $OLD is not installed: nothing to disable, and no rollback possible" >&2; exit 1; }
# The existing ufw rule must be there: 8105 and 8121 are inside it, and this change adds no rule.
ufw status | grep -qE '^8100:8199/tcp on tailscale0 +ALLOW' \
  || { echo "ufw has no rule for 8100:8199 on the tailnet: this change adds none, so it stops here" >&2; exit 1; }
UFW_BEFORE="$(ufw status | sort)"
TSIP="$(tailscale ip -4 2>&1 | head -n1)" || true
[[ $TSIP =~ ^100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]+\.[0-9]+$ ]] \
  || { echo "tailscale has no tailnet IPv4 address ($TSIP)" >&2; exit 1; }
# The jar must have the shared-port connector (8105 on loopback and on the tailnet address); an old jar would refuse to start.
python3 -I - "$JAR" <<'PY' || { echo "$JAR is missing or too old (no shared-port connector): rebuild it with scripts/27-backend.sh, then run this again by hand" >&2; exit 1; }
import sys, zipfile
z = zipfile.ZipFile(sys.argv[1])
names = z.namelist()
need = ("dev/homelab/inbox/frontdoor/PublicConnector.class", "dev/homelab/inbox/frontdoor/PublicPortGuard.class",
        "dev/homelab/inbox/door/KittGateStarter.class", "dev/homelab/inbox/core/LiveEffects.class")
if not all(any(n.endswith(c) for n in names) for c in need):
    sys.exit(1)
connector = next(n for n in names if n.endswith("frontdoor/PublicConnector.class"))
sys.exit(0 if b"only when server.address is a loopback address" in z.read(connector) else 1)
PY
# 8104 belongs to homelab-overview-web and stays so.
overview_pid() { systemctl show -p MainPID --value homelab-overview-web; }
check_overview() {
  local pid; pid="$(overview_pid)"
  [[ $pid =~ ^[1-9][0-9]*$ ]] && listening "$OVERVIEW_PORT" >/dev/null && ss -ltnpH "sport = :$OVERVIEW_PORT" | grep -q "pid=$pid,"
}
check_overview || { echo "port $OVERVIEW_PORT is not held by homelab-overview-web: this change does not touch it, so it stops here" >&2; exit 1; }

# --- 1. No task may be running
running() {
  as_service python3 -I -c '
import sqlite3, sys
c = sqlite3.connect("file:" + sys.argv[1] + "?mode=ro", uri=True, timeout=10)
print(c.execute("select count(*) from tasks where status = \x27running\x27").fetchone()[0])' "$LIVE/inbox.db"
}
if [[ ! -e $MARKER ]]; then
  [[ $(running) == 0 ]] || { echo "REFUSED: a task is running in the old inbox. Wait for it to finish (or cancel it), then run this again by hand: sudo bash $0" >&2; exit 1; }
fi

undo() {
  set +e
  echo "== the switch failed: putting the old inbox back" >&2
  rm -f "$DROPIN" "$SCHEDULER_DROPIN" "$MARKER"
  rmdir "$DROPIN_DIR" 2>/dev/null
  systemctl daemon-reload || echo "WARNING: daemon-reload failed" >&2
  systemctl stop "$BACKEND" || echo "WARNING: could not stop $BACKEND" >&2
  systemctl enable "$OLD" >/dev/null || echo "WARNING: could not enable $OLD" >&2
  systemctl start "$BACKEND" || echo "WARNING: could not start $BACKEND" >&2
  systemctl restart "$OLD" || echo "WARNING: could not start $OLD" >&2
  if systemctl is-active --quiet "$OLD"; then
    echo "the old inbox is running again (tasks that were queued run there; none ran in Java: the scheduler is off until the checks pass)" >&2
  else
    echo "ALERT: THE OLD INBOX IS NOT RUNNING. Look at port $PAGE_PORT ($(listening "$PAGE_PORT" | tr '\n' ' ')) and run: sudo systemctl start $OLD" >&2
  fi
}
fresh=0; [[ -e $MARKER ]] || fresh=1     # only a switch made by this run is undone by this run
switched=0
on_exit() {
  local rc=$?
  if [[ $rc -ne 0 && $switched -eq 1 && $fresh -eq 1 ]]; then undo; fi
}
trap on_exit EXIT

# --- 2. The final import (stops the old inbox, imports, compares the row counts, keeps inbox.db and pg_dump)
if [[ -e $MARKER ]]; then
  echo "the cut-over marker exists ($MARKER): the final import is not repeated"
else
  echo "== final import (scripts/inbox_cutover.py)"
  switched=1      # the import stops the old inbox: from here a failure puts it back
  python3 "$CUTOVER"      # exit 1 on a running task or on different row counts; then the old inbox runs again and nothing is switched
fi

# --- 3. The switch
systemctl disable "$OLD" >/dev/null 2>&1 || true
systemctl stop "$OLD" 2>/dev/null || true
if [[ $fresh -eq 1 ]]; then
  # The backend still listens on 8130 only; anything on 8105 or 8121 now is somebody else's.
  for p in $PAGE_PORT $GATE_PORT; do
    [[ -z $(listening "$p") ]] || { echo "port $p is still in use: $(listening "$p")" >&2; exit 1; }
  done
fi

install -d -m 755 "$DROPIN_DIR"
tmp="$(mktemp "$DROPIN.XXXXXX")"
cat > "$tmp" <<'UNIT'
# homelab: H-028 X1b, the Java backend is the inbox (changes/0011-inbox-java-cutover). Remove this file (revert.sh) and the
# backend is the shadow backend on 127.0.0.1:8130 again.
# 8105: 127.0.0.1 for the doors (Discord bot, kitt adapter, chat) and, through INBOX_PUBLIC_PORT, the tailnet address only for
# the page. 8121: the kitt gate on the tailnet address (KITT_GATE_HOST, KITT_GATE_PORT in config/inbox.conf). Both ports are inside
# the ufw rule 8100:8199 on tailscale0 that was already there.
[Unit]
After=network-online.target tailscaled.service ntfy.service
Wants=network-online.target

[Service]
Environment=BACKEND_HOST=127.0.0.1
Environment=BACKEND_PORT=8105
Environment=INBOX_PUBLIC_PORT=8105
Environment=INBOX_PUBLIC_INTERFACE=tailscale0
Environment=INBOX_FRONT_DOOR_ENABLED=true
Environment=CORE_EFFECTS=live
Environment=KITT_GATE_ENABLED=true
# As the old unit: the group that shares the task files, and files readable by that group.
SupplementaryGroups=inbox-share
UMask=0027
# Live effects start the runner through `sudo -n -u inbox homelab-inbox-task`, run git, gh and python3 and write the task logs, the
# share directories and the clones in /var/lib/homelab-inbox, as the old unit did. The sandbox of the shadow unit blocks that
# (no new privileges, no setuid, a read-only file system), so this drop-in lifts exactly those parts and keeps the rest.
NoNewPrivileges=no
RestrictSUIDSGID=no
ProtectSystem=no
ProtectHome=no
PrivateTmp=no
PrivateDevices=no
RestrictNamespaces=no
CapabilityBoundingSet=
CapabilityBoundingSet=@CAPS@
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
# Tasks run as children of this process, in this cgroup: the 640M of the shadow unit would kill the JVM or a task (Claude, git, npm).
MemoryMax=infinity
# After a reboot the tailnet address may come a moment later than the unit: wait for it, or the second connector cannot bind.
ExecStartPre=/bin/bash -c 'for i in $$(seq 90); do ip -4 -o addr show tailscale0 2>/dev/null | grep -q "inet 100\\." && exit 0; sleep 1; done; exit 1'
# The scheduler is NOT switched on here: 20-scheduler.conf does it after the checks have passed (apply.sh), so a failed
# check can never leave tasks or jobs started by Java behind an undo. After the first run both files stay.
Environment=CORE_SCHEDULER_ENABLED=false
UNIT
sed -i "s|@CAPS@|$CAPS|" "$tmp"
chmod 644 "$tmp"
mv "$tmp" "$DROPIN"
printf '{"at": "%s", "by": "changes/0011-inbox-java-cutover"}\n' "$(date -u +%FT%TZ)" > "$MARKER"
chmod 644 "$MARKER"
systemctl daemon-reload
systemctl restart "$BACKEND"

# --- 4. The checks
for _ in $(seq 60); do
  [[ -n $(listening "$PAGE_PORT") && -n $(listening "$GATE_PORT") ]] && break
  sleep 1
done
systemctl is-active --quiet "$BACKEND" || { echo "$BACKEND is not running" >&2; exit 1; }
code=""
for _ in $(seq 30); do
  code="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PAGE_PORT/api/backend/health" || true)"; [[ $code == 200 ]] && break
  sleep 1
done
[[ $code == 200 ]] || { echo "/api/backend/health on 127.0.0.1:$PAGE_PORT answered $code (expected 200)" >&2; exit 1; }
pid="$(systemctl show -p MainPID --value "$BACKEND")"
[[ $pid =~ ^[1-9][0-9]*$ ]] || { echo "$BACKEND has no main process" >&2; exit 1; }
[[ $(ps -o user= -p "$pid") == "$SERVICE_USER" ]] || { echo "$BACKEND does not run as $SERVICE_USER" >&2; exit 1; }
mine="$(ss -ltnpH | grep -E "pid=$pid," | awk '{print $4}' | sort)"
want="$(printf '%s\n' "127.0.0.1:$PAGE_PORT" "$TSIP:$PAGE_PORT" "$TSIP:$GATE_PORT" | sort)"
[[ $mine == "$want" ]] || { echo "the backend listens on:$(echo; echo "$mine") but must listen only on:$(echo; echo "$want")" >&2; exit 1; }
code="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "http://$TSIP:$PAGE_PORT/" || true)"
[[ $code == 403 ]] || { echo "http://$TSIP:$PAGE_PORT/ answered $code from the box (expected 403)" >&2; exit 1; }
code="$(curl -s -m 10 -o /dev/null -w '%{http_code}' -X POST "http://$TSIP:$PAGE_PORT/api/chat/action" || true)"
[[ $code == 403 ]] || { echo "the chat door on the tailnet address answered $code (expected 403)" >&2; exit 1; }
code="$(curl -s -m 5 -o /dev/null -w '%{http_code}' "http://$TSIP:$GATE_PORT/" || true)"
[[ $code != 000 ]] || { echo "the kitt gate on $TSIP:$GATE_PORT does not answer" >&2; exit 1; }
check_overview || { echo "port $OVERVIEW_PORT is no longer held by homelab-overview-web" >&2; exit 1; }
[[ "$(ufw status | sort)" == "$UFW_BEFORE" ]] || { echo "ufw changed during the cut-over" >&2; exit 1; }
[[ -f /etc/systemd/system/$OLD.service ]] || { echo "the old unit is no longer installed" >&2; exit 1; }
! systemctl is-enabled --quiet "$OLD" || { echo "$OLD is still enabled" >&2; exit 1; }
! systemctl is-active --quiet "$OLD" || { echo "$OLD is still running" >&2; exit 1; }
# A runner task must be able to start from inside this sandbox: the same sudo line the backend uses, as inbox-runner, with the
# unit's own relaxations (no new privileges off, setuid allowed, the capability list above). Fails here, before any task runs in Java.
systemd-run --quiet --wait --pipe --collect -p User="$SERVICE_USER" -p Group="$SERVICE_USER" -p SupplementaryGroups=inbox-share \
  -p NoNewPrivileges=no -p RestrictSUIDSGID=no -p CapabilityBoundingSet="$CAPS" \
  -p RestrictAddressFamilies="AF_INET AF_INET6 AF_UNIX AF_NETLINK" -p ProtectKernelTunables=yes -p ProtectKernelModules=yes \
  -p ProtectControlGroups=yes -p LockPersonality=yes -p RuntimeMaxSec=60 \
  sudo -n -u inbox "$TASK_BIN" cleanup 0 >/dev/null \
  || { echo "sudo -u inbox homelab-inbox-task does not work inside the unit's sandbox: runner tasks would fail (see the capability list in $DROPIN)" >&2; exit 1; }

# --- 4b. Only now the scheduler: Java starts queued tasks and due jobs from this moment on.
cat > "$SCHEDULER_DROPIN" <<'UNIT'
# homelab: H-028 X1b, written by changes/0011-inbox-java-cutover after its checks passed: the scheduler runs (live effects).
[Service]
Environment=CORE_SCHEDULER_ENABLED=true
UNIT
chmod 644 "$SCHEDULER_DROPIN"
# From the restart on Java may start tasks: no automatic undo any more (a rollback is revert.sh; data written since is lost, Q9).
trap - EXIT
systemctl daemon-reload
systemctl restart "$BACKEND"
code=""
for _ in $(seq 40); do
  code="$(curl -s -m 10 -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PAGE_PORT/api/backend/health" || true)"; [[ $code == 200 ]] && break
  sleep 1
done
[[ $code == 200 ]] || { echo "after switching the scheduler on, /api/backend/health answered $code (expected 200)" >&2; exit 1; }

# --- 5. The staging unit is not needed any more
if [[ -f /etc/systemd/system/$STAGING.service || -d /var/lib/homelab-inbox-staging ]]; then
  echo "== removing the staging unit (changes/0010-inbox-staging/revert.sh)"
  bash "$REPO/changes/0010-inbox-staging/revert.sh" \
    || echo "WARNING: removing the staging unit failed; the cut-over itself is done. Run changes/0010-inbox-staging/revert.sh by hand." >&2
fi
echo "the Java inbox is live: http://homelab.tail4eb878.ts.net:$PAGE_PORT/ and the kitt gate on :$GATE_PORT (effects live, scheduler on); $OLD is disabled but installed"
echo "kept for a rollback: the final inbox.db and the pg_dump in /var/backups/homelab/ (docs/inbox.md, 'Cut-over to the Java inbox')"
