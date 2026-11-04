#!/usr/bin/env bash
# sdlc-studio (H-013): publish the app (127.0.0.1:8112, deploy/sdlc-studio.service) on the tailnet as http://homelab:8111
# with tailscale serve. Serve adds the Tailscale-User-Login header the app uses to find the owner.
# Tailnet only: serve listens on the Tailscale addresses, never on the LAN, and funnel must stay off (checked).
# The serve config lives in tailscaled's state (in config/backup.list): a restore brings it back; rerun this change
# only after a rebuild without a restore.
set -euo pipefail
no_funnel() {
  tailscale serve status --json | jq -e '[(.AllowFunnel // {}) | to_entries[] | select(.value)] | length == 0' >/dev/null \
    || { echo "tailscale funnel is on for something: refusing (nothing on this box may reach the internet)" >&2; exit 1; }
}
no_funnel
tailscale serve --bg --yes --http=8111 http://127.0.0.1:8112
no_funnel
status=$(tailscale serve status --json)
jq -e '.TCP["8111"].HTTP == true' >/dev/null <<<"$status" \
  || { echo "serve has no HTTP listener on 8111" >&2; exit 1; }
jq -e '[.Web // {} | to_entries[] | select(.key | endswith(":8111")) | .value.Handlers["/"].Proxy // ""
        | select(test("127\\.0\\.0\\.1:8112"))] | length == 1' >/dev/null <<<"$status" \
  || { echo "8111 does not proxy to 127.0.0.1:8112: $(jq -c .Web <<<"$status")" >&2; exit 1; }
echo "sdlc-studio served on the tailnet at http://homelab:8111"
