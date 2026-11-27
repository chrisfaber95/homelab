#!/usr/bin/env bash
# homelab: H-023 (B3), install the Spring Boot backend of the inbox (backend/) beside the old Python inbox.
# OpenJDK 21 headless from Ubuntu; the jar is built from this repo's backend/ by Maven (the wrapper) as an unprivileged
# throwaway user (Q3, Chris 2026-11-16): `systemd-run` with DynamicUser, never as root, and never from root's clone
# directly (a copy of backend/ is made first). The build account has no rights beyond its own state directory; root only
# checks the jar and copies it to /usr/local/lib/homelab/inbox-backend/, where it is read-only for the service.
# This script runs no npm. (Q3: should one ever be added, it runs the same way, with `npm ci --ignore-scripts`.)
# Then it installs systemd/homelab-inbox-backend.service (account inbox-runner, loopback only, PostgreSQL over its unix
# socket, explicit max heap, hardened), starts it and checks it: health answers, the TCP listeners of the process
# are loopback only. It measures the heap (full GC, jcmd) and appends the numbers to /var/lib/homelab-inbox/backend-heap.log.
# Needs scripts/28-inbox-db.sh (B2) to have run: the account inbox-runner and the database `inbox`. Nothing is
# switched over to the backend; the old inbox (homelab-inbox, inbox.db) is not touched. Docs: docs/inbox-backend.md.
# Run as: sudo bash <homelab-repo>/scripts/27-backend.sh
# homelab-rerun: root backend systemd/homelab-inbox-backend.service config/inbox.conf scripts/conversation-import.sh scripts/memory-import.sh scripts/jobs-import.sh
set -euo pipefail

SERVICE=homelab-inbox-backend
SERVICE_USER=inbox-runner     # the account the PostgreSQL role inbox_app trusts (scripts/28-inbox-db.sh)
DEST=/usr/local/lib/homelab/inbox-backend
JAR=$DEST/inbox-backend.jar
SRC_COPY=/var/lib/homelab-backend-src       # root-owned copy of backend/, read by the build account
BUILD_STATE=/var/lib/private/homelab-backend-build   # StateDirectory of the DynamicUser build (Maven cache, output)
SECRET_FILE=/var/lib/homelab-inbox/backend-secret   # the facade secret (B6): the Python inbox sends it, the backend checks it
HEAP_LOG=/var/lib/homelab-inbox/backend-heap.log   # one line per install: the measured heap
JAVA_HOME_DIR=/usr/lib/jvm/java-21-openjdk-amd64      # the unit's ExecStart uses the same path
HOST=127.0.0.1
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PORT="$(sed -n 's/^BACKEND_PORT=//p' "$REPO/config/inbox.conf" | tr -d '"')"
PORT="${PORT:-8130}"
UNIT_PORT=$PORT     # the port in the unit file; the drop-in of the cut-over overrides it at run time
# H-028 X1b: after changes/0011-inbox-java-cutover the backend is the inbox: a drop-in moves it to 8105 (loopback and the tailnet
# address), with the front door and 8121. The marker file says so; the checks below then look for that shape.
CUTOVER_MARKER=/var/lib/homelab-inbox/java-cutover
CUTOVER=0
if [[ -e $CUTOVER_MARKER ]]; then
  CUTOVER=1
  PORT=8105
fi

if [[ $EUID -ne 0 ]]; then
  echo "Run with sudo." >&2
  exit 1
fi
[[ $(dpkg --print-architecture) == amd64 ]] || { echo "this script and the unit assume amd64 (JAVA_HOME $JAVA_HOME_DIR)" >&2; exit 1; }
[[ -f $REPO/backend/pom.xml && -x $REPO/backend/mvnw ]] || { echo "backend/ (task B1) is not in $REPO" >&2; exit 1; }
[[ -f $REPO/systemd/$SERVICE.service ]] || { echo "systemd/$SERVICE.service is missing" >&2; exit 1; }

echo "== Prerequisites (B2: account $SERVICE_USER, database inbox)"
id "$SERVICE_USER" >/dev/null 2>&1 || { echo "account $SERVICE_USER missing: run scripts/28-inbox-db.sh (and 21-inbox.sh) first" >&2; exit 1; }
systemctl is-active --quiet postgresql || { echo "PostgreSQL is not running: run scripts/28-inbox-db.sh first" >&2; exit 1; }
[[ -n $(cd / && runuser -u postgres -- psql -AtXc "select 1 from pg_database where datname = 'inbox'") ]] \
  || { echo "the database inbox does not exist: run scripts/28-inbox-db.sh first" >&2; exit 1; }

echo "== Packages: OpenJDK 21 headless (with jcmd/jstat), unzip and curl for the Maven wrapper"
missing=()
for pkg in openjdk-21-jdk-headless unzip curl ca-certificates; do
  dpkg -s "$pkg" >/dev/null 2>&1 || missing+=("$pkg")
done
if [[ ${#missing[@]} -gt 0 ]]; then
  apt-get update -q
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q "${missing[@]}"
fi
[[ -x $JAVA_HOME_DIR/bin/java && -x $JAVA_HOME_DIR/bin/javac ]] || { echo "no JDK at $JAVA_HOME_DIR" >&2; exit 1; }
"$JAVA_HOME_DIR/bin/java" -version 2>&1 | grep -q 'version "21\.' || { echo "$JAVA_HOME_DIR is not Java 21" >&2; exit 1; }

echo "== Build as an unprivileged throwaway user (systemd-run, DynamicUser)"
# A fresh copy of backend/ each run, readable by everybody and writable by root only: the build account cannot change
# what the next build reads, and root's clone (only root can change it) is never handed to it.
rm -rf "$SRC_COPY"
install -d -m 755 -o root -g root "$SRC_COPY"
tar -C "$REPO/backend" --exclude=./target -cf - . | tar -C "$SRC_COPY" --no-same-owner -xf -
chown -R root:root "$SRC_COPY"
chmod -R u=rwX,go=rX "$SRC_COPY"
# Stop a leftover of an earlier, interrupted build; a fresh output directory (the Maven cache stays for speed).
systemctl stop homelab-backend-build.service 2>/dev/null || true
rm -rf "$BUILD_STATE/out"
# The state directory belongs to the dynamic user: it is created (and chowned to the current dynamic uid) by systemd.
# Tests run in CI (job `backend`), not here: the box has no throwaway PostgreSQL for them yet (Q5, task B4).
# The inline script below must not use brace expansions (dollar-brace): systemd-run expands those itself before bash
# runs it (unknown names become empty), which broke the jar check. Plain $var and $1 pass through unchanged.
systemd-run --quiet --wait --pipe --collect --unit=homelab-backend-build \
  -p DynamicUser=yes -p StateDirectory=homelab-backend-build -p StateDirectoryMode=0700 \
  -p NoNewPrivileges=yes -p ProtectSystem=strict -p ProtectHome=yes -p PrivateTmp=yes -p PrivateDevices=yes \
  -p ProtectKernelTunables=yes -p ProtectKernelModules=yes -p ProtectControlGroups=yes \
  -p RestrictNamespaces=yes -p LockPersonality=yes -p CapabilityBoundingSet= \
  -p RestrictAddressFamilies="AF_INET AF_INET6 AF_UNIX AF_NETLINK" \
  -p MemoryMax=2G -p RuntimeMaxSec=1800 \
  -E JAVA_HOME="$JAVA_HOME_DIR" -E SRC_COPY="$SRC_COPY" \
  /bin/bash -c '
    set -euo pipefail
    state="$STATE_DIRECTORY"
    export HOME="$state" MAVEN_USER_HOME="$state/m2-home"
    work="$state/work"
    rm -rf "$work"; mkdir -p "$work" "$state/out"
    cp -a "$SRC_COPY"/. "$work"/
    cd "$work"
    ./mvnw -B -ntp -Dmaven.repo.local="$state/m2" package -DskipTests
    set -- target/inbox-backend-*.jar
    [[ $# -eq 1 && -f $1 ]] || { echo "expected exactly one target/inbox-backend-*.jar" >&2; exit 1; }
    cp "$1" "$state/out/inbox-backend.jar"
    rm -rf "$work"
  ' || { echo "the Maven build failed (output above)" >&2; exit 1; }
rm -rf "$SRC_COPY"

NEW_JAR="$BUILD_STATE/out/inbox-backend.jar"
# The build account wrote this file, so root only treats it as data: a plain file, a valid zip, then copy it over.
# Nothing runs it as root; the service runs it as $SERVICE_USER.
[[ -f $NEW_JAR && ! -L $NEW_JAR ]] || { echo "the build left no jar at $NEW_JAR" >&2; exit 1; }
unzip -tqq "$NEW_JAR" >/dev/null || { echo "$NEW_JAR is not a valid jar" >&2; exit 1; }
# No grep -q here: it stops at the first match, unzip then dies of SIGPIPE (141) and pipefail fails the whole check.
unzip -Z1 "$NEW_JAR" | grep '^BOOT-INF/classes/dev/homelab/inbox/' >/dev/null || { echo "$NEW_JAR is not the inbox backend" >&2; exit 1; }

echo "== Install the jar and the unit"
changed=0
install -d -m 755 -o root -g root "$DEST"
if [[ ! -f $JAR ]] || ! cmp -s "$NEW_JAR" "$JAR"; then
  install -m 644 -o root -g root "$NEW_JAR" "$JAR.new"
  mv "$JAR.new" "$JAR"
  changed=1
fi
UNIT=/etc/systemd/system/$SERVICE.service
if [[ ! -f $UNIT ]] || ! cmp -s "$REPO/systemd/$SERVICE.service" "$UNIT"; then
  install -m 644 -o root -g root "$REPO/systemd/$SERVICE.service" "$UNIT"
  systemctl daemon-reload
  changed=1
fi
# The port in inbox.conf and the unit must be the same one (the exposure check reads both).
grep -qx "Environment=BACKEND_PORT=$UNIT_PORT" "$UNIT" || { echo "BACKEND_PORT in config/inbox.conf ($UNIT_PORT) differs from the unit" >&2; exit 1; }
grep -qx "Environment=BACKEND_HOST=$HOST" "$UNIT" || { echo "the unit must set BACKEND_HOST=$HOST (loopback only)" >&2; exit 1; }
grep -qx "Environment=BACKEND_SECRET_FILE=$SECRET_FILE" "$UNIT" || { echo "the unit must set BACKEND_SECRET_FILE=$SECRET_FILE" >&2; exit 1; }
# The facade secret (B6): made once, never in the repo, readable by $SERVICE_USER only (both the inbox and the backend run as it).
if [[ ! -s $SECRET_FILE ]]; then
  (umask 077; head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$SECRET_FILE")
fi
chown "$SERVICE_USER:$SERVICE_USER" "$SECRET_FILE"
chmod 600 "$SECRET_FILE"
# H-024 M4: the conversation store is Java's. The import runs where nothing can write either store (inbox stopped, the M4
# Python code installed); scripts/conversation-import.sh skips until both the code and this jar are in place, so whichever of
# 21-inbox.sh and this script runs last does it.
bash "$REPO/scripts/conversation-import.sh"
# H-024 M5: the memory store is Java's too. The backend writes the clone of the memory branch (unit: ReadWritePaths), so the
# directory must exist before the unit starts; scripts/memory-import.sh does the import under the same rule as above.
[[ -d /var/lib/homelab-inbox/memory-repo ]] || install -d -m 755 -o "$SERVICE_USER" -g "$SERVICE_USER" /var/lib/homelab-inbox/memory-repo
bash "$REPO/scripts/memory-import.sh"
# H-024 M6: the job files are Java's too: the clone jobs-repo is a second ReadWritePath of the unit, created before it starts;
# scripts/jobs-import.sh does the import under the same rule.
[[ -d /var/lib/homelab-inbox/jobs-repo ]] || install -d -m 755 -o "$SERVICE_USER" -g "$SERVICE_USER" /var/lib/homelab-inbox/jobs-repo
bash "$REPO/scripts/jobs-import.sh"
systemctl enable -q "$SERVICE"
if [[ $changed -eq 1 ]] || ! systemctl is-active --quiet "$SERVICE"; then
  systemctl restart "$SERVICE"
fi

echo "== Check"
body=""
for _ in $(seq 1 45); do
  if body="$(curl -fsS -m 3 "http://$HOST:$PORT/api/backend/health" 2>/dev/null)"; then break; fi
  body=""
  sleep 2
done
[[ $body == *'"status":"ok"'* ]] || { echo "GET http://$HOST:$PORT/api/backend/health did not answer ok (systemctl status $SERVICE; its journal is not readable for claude)" >&2; exit 1; }
# The facade secret (B6): without the header a non-health route is refused, with it the request gets past the filter.
# After the cut-over the front door answers instead (no Python facade any more): the secret is not checked then.
if [[ $CUTOVER -eq 0 ]]; then
  code="$(curl -s -m 3 -o /dev/null -w '%{http_code}' "http://$HOST:$PORT/api/backend/none")"
  [[ $code == 403 ]] || { echo "a request without the facade secret got $code (expected 403)" >&2; exit 1; }
  code="$(curl -s -m 3 -o /dev/null -w '%{http_code}' -H "X-Inbox-Facade: $(cat "$SECRET_FILE")" "http://$HOST:$PORT/api/backend/none")"
  [[ $code != 403 ]] || { echo "the facade secret in $SECRET_FILE is not accepted" >&2; exit 1; }
fi
pid="$(systemctl show -p MainPID --value "$SERVICE")"
[[ $pid =~ ^[1-9][0-9]*$ ]] || { echo "$SERVICE has no main process" >&2; exit 1; }
[[ $(ps -o user= -p "$pid") == "$SERVICE_USER" ]] || { echo "$SERVICE does not run as $SERVICE_USER" >&2; exit 1; }
# Every TCP listener of the process is on loopback, and the port we expect is one of them.
listeners="$(ss -ltnpH)" || { echo "cannot list the listeners" >&2; exit 1; }
mine="$(grep -E "pid=$pid," <<<"$listeners" | awk '{print $4}' || true)"
[[ -n $mine ]] || { echo "the backend (pid $pid) listens on no TCP port" >&2; exit 1; }
# After the cut-over the listeners are exactly these three: loopback 8105, the tailnet address on 8105 (the page) and on 8121 (the kitt gate).
if [[ $CUTOVER -eq 1 ]]; then
  ts="$(tailscale ip -4 2>/dev/null | head -n1)"
  want="$(printf '%s\n' "127.0.0.1:$PORT" "$ts:$PORT" "$ts:8121" | sort)"
  [[ "$(sort <<<"$mine")" == "$want" ]] || { echo "the backend must listen only on: $(paste -sd' ' <<<"$want"); it listens on $(paste -sd' ' <<<"$mine")" >&2; exit 1; }
else
  wrong="$(grep -vE '^(127\.0\.0\.1|\[::1\]):[0-9]+$' <<<"$mine" || true)"
  [[ -z $wrong ]] || { echo "the backend listens beyond loopback: $(paste -sd' ' <<<"$wrong")" >&2; exit 1; }
fi
grep -qx "$HOST:$PORT" <<<"$mine" || { echo "the backend does not listen on $HOST:$PORT (it listens on $(paste -sd' ' <<<"$mine"))" >&2; exit 1; }
pg_listen="$(cd / && runuser -u postgres -- psql -AtXc 'show listen_addresses')"
[[ -z $pg_listen ]] || { echo "PostgreSQL listens on '$pg_listen': it must stay socket only (Q2)" >&2; exit 1; }

echo "== Heap measurement (recorded in $HEAP_LOG; copy it to docs/inbox-backend.md, Measured values)"
# Let the JVM settle, force a full GC (the live set is what sizes -Xmx), read the heap and the kernel's accounting.
# jcmd attaches only as the account of the service. A failed measurement is loud but does not undo the install.
sleep 10
JCMD="$JAVA_HOME_DIR/bin/jcmd"
as_service_cmd() { (cd / && runuser -u "$SERVICE_USER" -- "$@"); }
if as_service_cmd "$JCMD" "$pid" GC.run >/dev/null 2>&1 && info="$(as_service_cmd "$JCMD" "$pid" GC.heap_info 2>&1)"; then
  to_kb() { awk -v pat="$1" -v key="$2" '$0 ~ pat { for (i = 1; i < NF; i++) if ($i == key) { v = $(i + 1); gsub(/[^0-9]/, "", v); t += v } } END { print t + 0 }' <<<"$info"; }
  heap_used="$(to_kb generation used)"; heap_total="$(to_kb generation total)"; meta_used="$(to_kb Metaspace used)"
  mem_cur="$(systemctl show -p MemoryCurrent --value "$SERVICE")"; mem_peak="$(systemctl show -p MemoryPeak --value "$SERVICE")"
  line="$(date -u +%FT%TZ) pid=$pid state=just-started heap_used_after_gc_kb=$heap_used heap_committed_kb=$heap_total metaspace_used_kb=$meta_used MemoryCurrent=$mem_cur MemoryPeak=$mem_peak Xmx=256m MemoryMax=640M jar=$(sha256sum "$JAR" | cut -c1-12)"
  echo "$line" >> "$HEAP_LOG"
  echo "$line"
else
  echo "WARNING: could not measure the heap with jcmd as $SERVICE_USER; see docs/inbox-backend.md for the manual commands" >&2
fi
echo "inbox backend ready: http://$HOST:$PORT/api/backend/health (loopback; see above for the cut-over), jar $JAR"
