#!/usr/bin/env bash
# forge: put back the memory that changes/0009-forge-reset-memory moved aside (manual tool, run it as root)
#
# What forge built since the reset is moved to /var/lib/forge/after-reset-0009/ first, so this loses nothing either.
# Forge is stopped after the restart: press Start on the homelab page.
set -euo pipefail

HOME_DIR=/var/lib/forge
STATE=$HOME_DIR/state.json
WORK=$HOME_DIR/work
KEEP=$HOME_DIR/before-reset-0009
LATER=$HOME_DIR/after-reset-0009
UNIT=homelab-app-forge.service

as_forge() { runuser -u forge -- "$@"; }

as_forge test -e "$KEEP/done" || { echo "nothing to put back: $KEEP/done is not there" >&2; exit 1; }
as_forge test -f "$KEEP/state.json" -a -d "$KEEP/work" || {
  echo "$KEEP holds no state.json and work: nothing to put back" >&2
  exit 1
}
as_forge test ! -e "$LATER" || { echo "$LATER exists already: move it away first" >&2; exit 1; }

trap 'systemctl start "$UNIT" || true' EXIT
systemctl stop "$UNIT"
as_forge mkdir -m 700 "$LATER"
if as_forge test -e "$STATE"; then as_forge mv -T -- "$STATE" "$LATER/state.json"; fi
if as_forge test -e "$WORK"; then as_forge mv -T -- "$WORK" "$LATER/work"; fi
as_forge mv -T -- "$KEEP/state.json" "$STATE"
as_forge mv -T -- "$KEEP/work" "$WORK"
as_forge rm -f "$KEEP/done"
trap - EXIT
systemctl start "$UNIT"
sleep 3
systemctl is-active --quiet "$UNIT"
echo "forge: the old memory is back; what came after the reset is in $LATER. Press Start on the homelab page."
