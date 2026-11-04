#!/usr/bin/env bash
# Stop serving sdlc-studio on the tailnet (leaves any other serve config alone; nothing to do if it isn't served)
set -euo pipefail
if tailscale serve status --json | jq -e '.TCP["8111"] != null' >/dev/null; then
  tailscale serve --yes --http=8111 off
fi
tailscale serve status --json | jq -e '.TCP["8111"] == null' >/dev/null
echo "sdlc-studio no longer served on 8111"
