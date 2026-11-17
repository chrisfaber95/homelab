#!/usr/bin/env bash
# homelab: H-024 M4, the switch of the conversation store from the Python inbox (SQLite) to the Java backend (PostgreSQL),
# done at the one moment when nothing can write either store. Called by scripts/21-inbox.sh (after it installs the Python
# code) and by scripts/27-backend.sh (after it installs the jar); whichever runs last does the import, the other skips.
# It imports only when ALL of these hold: the installed Python inbox code is the M4 one (it writes nothing to SQLite),
# the installed jar has the import command, and inbox.db exists. Then it stops homelab-inbox (no process of the old or the
# new code is running), imports with --if-not-imported (a no-op once Java owns the store: never overwrites Java's data)
# and starts homelab-inbox again if it was running. Before the import the old code is the only writer, after it Java is.
# Run as: sudo bash <homelab-repo>/scripts/conversation-import.sh   (idempotent; docs/inbox-backend.md, "Conversation store (M4)")
# homelab-rerun: root scripts/conversation-import.sh
set -euo pipefail

DEST=/usr/local/lib/homelab
JAR=$DEST/inbox-backend/inbox-backend.jar
CODE=$DEST/bin/homelab_inbox/conversation.py
INBOX_DB=/var/lib/homelab-inbox/inbox.db
JAVA=/usr/lib/jvm/java-21-openjdk-amd64/bin/java
RUNNER=inbox-runner

[[ $EUID -eq 0 ]] || { echo "Run with sudo." >&2; exit 1; }
if ! grep -q 'def door(' "$CODE" 2>/dev/null; then
  echo "conversation import: skipped, the installed Python inbox is not the M4 code yet (scripts/21-inbox.sh imports after it installs it)"; exit 0
fi
if [[ ! -f $JAR ]] || ! { unzip -Z1 "$JAR" | grep 'dev/homelab/inbox/db/ConversationCommand' >/dev/null; }; then
  echo "conversation import: skipped, the installed backend jar has no import-conversation yet (scripts/27-backend.sh imports after it installs it)"; exit 0
fi
if [[ ! -f $INBOX_DB ]]; then
  echo "conversation import: skipped, $INBOX_DB does not exist yet"; exit 0
fi

was_active=0
systemctl is-active --quiet homelab-inbox && was_active=1
systemctl stop homelab-inbox      # nothing writes SQLite or the store while the data moves
restart() { [[ $was_active -eq 0 ]] || systemctl start homelab-inbox; }
trap restart EXIT
(cd / && runuser -u "$RUNNER" -- "$JAVA" -Xmx256m -jar "$JAR" import-conversation --sqlite "$INBOX_DB" --if-not-imported) \
  || { echo "the conversation import failed; nothing was switched (docs/inbox-backend.md, M4)" >&2; exit 1; }
