#!/usr/bin/env bash
# Stop serving pixel-quest on the tailnet (leaves any other serve config alone; nothing to do if it isn't served).
# /etc/homelab/pixel-quest.env stays on purpose: the pgdata volume was initialised with that password, so throwing it
# away would make the players' profiles, progress and shared world unreachable. Remove it by hand only together with
# the volume (`docker compose -p pixel-quest down -v` as the account deploy).
set -euo pipefail
SERVE_PORT=8113
if tailscale serve status --json | jq -e --arg p "$SERVE_PORT" '.TCP[$p] != null' >/dev/null; then
  tailscale serve --yes --http="$SERVE_PORT" off
fi
tailscale serve status --json | jq -e --arg p "$SERVE_PORT" '.TCP[$p] == null' >/dev/null
echo "pixel-quest no longer served on $SERVE_PORT (/etc/homelab/pixel-quest.env kept: the pgdata volume needs it)"
