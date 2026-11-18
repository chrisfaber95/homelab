#!/usr/bin/env bash
# homelab: H-024 M6, the switch of the job files (the clone `jobs-repo` of the `jobs` branch of DATA_REPO) from the Python inbox to
# the Java backend, done at the one moment when nothing can write it. Called by scripts/21-inbox.sh (after it installs the
# Python code) and by scripts/27-backend.sh (after it installs the jar); whichever runs last does the import, the other skips.
# It imports only when BOTH hold: the installed Python inbox code is the M6 one (it never writes the job files) and the installed
# jar has the import command. Then it stops homelab-inbox (no process of the old or the new code is running), runs
# `import-jobs --if-not-imported` (a fresh sync of the clone to GitHub, a count of the files, the marker that makes Java the
# only writer; a no-op once Java owns the store) and starts homelab-inbox again if it was running. Before the import the old
# code is the only writer, after it Java is; Java refuses every write until the marker is there.
# Run as: sudo bash <homelab-repo>/scripts/jobs-import.sh   (idempotent; docs/inbox-backend.md, "Job files (M6)")
# homelab-rerun: root scripts/jobs-import.sh
set -euo pipefail

DEST=/usr/local/lib/homelab
JAR=$DEST/inbox-backend/inbox-backend.jar
CODE=$DEST/bin/homelab_inbox/jobs.py
JAVA=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
RUNNER=inbox-runner
RUNNER_HOME=/var/lib/homelab-inbox

[[ $EUID -eq 0 ]] || { echo "Run with sudo." >&2; exit 1; }
if ! grep -q 'def pause_job(' "$CODE" 2>/dev/null; then
  echo "jobs import: skipped, the installed Python inbox is not the M6 code yet (scripts/21-inbox.sh imports after it installs it)"; exit 0
fi
if [[ ! -f $JAR ]] || ! { unzip -Z1 "$JAR" | grep 'dev/homelab/inbox/db/JobsCommand' >/dev/null; }; then
  echo "jobs import: skipped, the installed backend jar has no import-jobs yet (scripts/27-backend.sh imports after it installs it)"; exit 0
fi

was_active=0
systemctl is-active --quiet homelab-inbox && was_active=1
systemctl stop homelab-inbox      # nothing writes the clone while it moves
restart() { [[ $was_active -eq 0 ]] || systemctl start homelab-inbox; }
trap restart EXIT
(cd / && runuser -u "$RUNNER" -- env INBOX_CONFIG_DIR="$DEST/config" INBOX_RUNNER_HOME="$RUNNER_HOME" \
    "$JAVA" -Xmx256m -jar "$JAR" import-jobs --if-not-imported) \
  || { echo "the job files import failed; nothing was switched (docs/inbox-backend.md, M6)" >&2; exit 1; }
