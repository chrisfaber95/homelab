#!/usr/bin/env bash
# forge: clear its memory (state file and clones) so it starts again with no projects and all five repo slots free
#
# Why (Chris, 2026-11-14): forge's first three projects (pulsecheck, feedstash, snaptrail) are small generic tools,
# built before it had a direction. Forge now gets one (forge PR "Give forge a direction: it builds its own world"),
# and it should start on that with a clean list instead of growing the old projects first: its scout always grows a
# project it has before it starts a new one.
#
# What it does: stops forge, moves /var/lib/forge/state.json and /var/lib/forge/work into
# /var/lib/forge/before-reset-0009/ and starts forge again, which then writes a fresh, empty state. Nothing is
# deleted: the old state and clones stay in that directory, and the three repos stay on GitHub (private, untouched).
# Forge no longer counts them as its own, so it never pushes to them again.
#
# The logins of the `forge` account (Claude CLI, gh) live elsewhere in /var/lib/forge and are not touched.
#
# Everything inside /var/lib/forge is done as the account `forge`, never as root: that directory belongs to an
# account that runs generated code, so root does not follow paths in it.
#
# Runs once: before-reset-0009/done marks it, so a re-run never clears what forge built afterwards. On a rebuilt box
# without a restore there is nothing to clear and it only sets the mark.
#
# A restart always leaves forge *stopped*: press Start on the homelab page afterwards.
#
# revert.sh is a manual tool here: the change touches no firewall, network or SSH file, so homelab-admin asks for no
# confirmation and never runs it by itself.
set -euo pipefail

HOME_DIR=/var/lib/forge
STATE=$HOME_DIR/state.json
WORK=$HOME_DIR/work
KEEP=$HOME_DIR/before-reset-0009
ENV_FILE=/etc/forge/env
UNIT=homelab-app-forge.service

as_forge() { runuser -u forge -- "$@"; }

# --- 0. Preconditions: fail before the box is touched
id forge >/dev/null 2>&1 || { echo "no account forge: run scripts/25-forge.sh first" >&2; exit 1; }
[[ $(stat -c %U "$HOME_DIR") == forge ]] || { echo "$HOME_DIR is not forge's" >&2; exit 1; }
# This change knows the default places only. A box where they were moved needs its own look.
if [[ -r $ENV_FILE ]] && grep -Eq '^FORGE_(STATE_DIR|STATE_FILE|WORK_DIR|CONFIG)=' "$ENV_FILE"; then
  if grep -E '^FORGE_(STATE_DIR|STATE_FILE|WORK_DIR|CONFIG)=' "$ENV_FILE" \
      | grep -Evxq "FORGE_STATE_DIR=$HOME_DIR|FORGE_STATE_FILE=$STATE|FORGE_WORK_DIR=$WORK"; then
    echo "$ENV_FILE moves forge's state or work directory: clear it by hand." >&2
    exit 1
  fi
fi
if as_forge test -e "$KEEP/done"; then
  echo "forge: memory was already cleared by this change ($KEEP), nothing to do"
  exit 0
fi

# --- 1. Stop forge, and never leave it down, whatever happens below
# Stopping can take a while on purpose (TimeoutStopSec=330: forge ends its children and saves its state first).
trap 'systemctl start "$UNIT" || true' EXIT
systemctl stop "$UNIT"

# --- 2. Move the memory aside (as forge; a leftover of an earlier, broken run keeps its own name)
as_forge mkdir -p -m 700 "$KEEP"
as_forge test -d "$KEEP" -a ! -L "$KEEP" || { echo "$KEEP is not a plain directory" >&2; exit 1; }
aside() {  # aside <path>: move it into $KEEP under its own name, or a numbered one when that is taken
  local src="$1" dest n=1
  as_forge test -e "$src" -o -L "$src" || return 0
  dest="$KEEP/$(basename "$src")"
  while as_forge test -e "$dest" -o -L "$dest"; do dest="$KEEP/$(basename "$src").$n"; n=$((n + 1)); done
  as_forge mv -T -- "$src" "$dest"
  echo "forge: moved $src to $dest"
}
aside "$STATE"
aside "$WORK"
as_forge mkdir -m 700 "$WORK"
as_forge touch "$KEEP/done"

# --- 3. Start forge again and check that it came back empty
trap - EXIT
systemctl start "$UNIT"
empty=no
for _ in $(seq 1 15); do
  sleep 2
  # Forge writes a fresh state file when it starts. Read as forge: the file is its own (the directory is 700).
  if as_forge python3 -c '
import json, sys
s = json.load(open(sys.argv[1], encoding="utf-8"))
sys.exit(0 if s.get("repos") == [] and s.get("ideas") == [] and s.get("merged") == [] else 1)
' "$STATE" 2>/dev/null; then
    empty=yes
    break
  fi
done
systemctl is-active --quiet "$UNIT"
[[ $empty == yes ]] || { echo "forge did not come back with an empty state: look at $STATE" >&2; exit 1; }
echo "forge: memory cleared, the old state and clones are in $KEEP. $UNIT active."
echo "forge is stopped after a restart: press Start on the homelab page."
