#!/usr/bin/env bash
# pixel-quest (H-014): switch the tailnet entry from HTTP to HTTPS (serve 8113) and mark the session cookie Secure.
#
# Follow-up to changes/0006-pixel-quest-env-and-serve, which deliberately set up plain HTTP so the game could run
# (Chris's choice, 2026-11-07). MERGE THIS ONLY AFTER two things are true, and check them in this order:
#
#   1. Tailscale admin console > DNS > HTTPS Certificates is on (https://login.tailscale.com/admin/dns).
#      `tailscale status --json | jq .CertDomains` must list homelab.tail4eb878.ts.net.
#      Enabling it publishes that name in the public Certificate Transparency logs: the name becomes visible, the
#      box does not become reachable.
#   2. `homelab-deploy status` shows pixel-quest running, so /srv/homelab/releases/pixel-quest/current exists.
#
# Not earlier: homelab-admin runs a failed change again only when apply.sh itself changes ("een mislukte wijziging
# alleen opnieuw als apply.sh verandert"), so a too-early merge costs a new PR instead of a retry.
#
# The two halves must move together. COOKIE_SECURE=1 with an HTTP entry means a browser never returns the session
# cookie and signing in fails with no error at all; COOKIE_SECURE=0 over HTTPS merely misses a hardening flag. So the
# order here is: prove TLS works, then the cookie. revert.sh walks back the other way.
#
# A failed apply gets no safety net from homelab-admin (it only restores snapshotted firewall/network/SSH files, and
# does not run revert.sh), so this script puts the working HTTP entry back itself if the HTTPS swap fails.
#
# Tailnet only: `serve` listens on the Tailscale addresses, never on the LAN or the internet, and funnel stays off
# (checked, and funnel is only possible on 443/8443/10000 anyway).
set -euo pipefail

ENV_FILE=/etc/homelab/pixel-quest.env
APP_PORT=8114
SERVE_PORT=8113
RELEASE=/srv/homelab/releases/pixel-quest/current

DEPLOY_UID=$(id -u deploy)
as_deploy() {
  (cd / && sudo -u deploy -H env XDG_RUNTIME_DIR="/run/user/$DEPLOY_UID" \
    DOCKER_HOST="unix:///run/user/$DEPLOY_UID/docker.sock" PATH=/usr/bin:/bin:/usr/sbin:/sbin "$@")
}
serve_status() {
  local out
  out=$(tailscale serve status --json) || { echo "tailscale serve status failed: is tailscaled running?" >&2; exit 1; }
  printf '%s' "$out"
}
no_funnel() {
  serve_status | jq -e '[(.AllowFunnel // {}) | to_entries[] | select(.value)] | length == 0' >/dev/null \
    || { echo "tailscale funnel is on for something: refusing (nothing on this box may reach the internet)" >&2; exit 1; }
}

# --- 0. Preconditions: change nothing unless all of this holds
no_funnel
domain=$(tailscale status --json | jq -r '.Self.DNSName | rtrimstr(".")')
[ -n "$domain" ] && [ "$domain" != "null" ] || { echo "cannot read this node's DNS name from tailscale status" >&2; exit 1; }
# Not just "any cert domain": this node's own name has to be one, or its certificate cannot be issued.
if ! tailscale status --json | jq -e --arg d "$domain" '[(.CertDomains // [])[]] | index($d)' >/dev/null; then
  echo "$domain is not in CertDomains, so 'tailscale serve --https=$SERVE_PORT' cannot get a certificate." >&2
  echo "turn on DNS > HTTPS Certificates at https://login.tailscale.com/admin/dns, then apply this change again" >&2
  echo "(check: tailscale status --json | jq .CertDomains)" >&2
  exit 1
fi
[ -s "$ENV_FILE" ] || { echo "$ENV_FILE is missing: apply changes/0006-pixel-quest-env-and-serve first" >&2; exit 1; }
[ -d "$RELEASE" ] || { echo "$RELEASE is missing: let homelab-deploy build pixel-quest first (homelab-deploy status)" >&2; exit 1; }
# serve proxies to a fixed port, so the app really has to be on it.
grep -qxF "PORT=$APP_PORT" "$ENV_FILE" \
  || { echo "$ENV_FILE does not set PORT=$APP_PORT, which serve proxies to (found: $(grep -E '^PORT=' "$ENV_FILE" || echo none))" >&2; exit 1; }
grep -qxF 'BIND_ADDR=127.0.0.1' "$ENV_FILE" \
  || { echo "$ENV_FILE does not bind the app to 127.0.0.1" >&2; exit 1; }

# --- 1. Serve HTTPS instead of HTTP on the same port, and prove TLS really works
had_http=no
if serve_status | jq -e --arg p "$SERVE_PORT" '.TCP[$p].HTTP == true' >/dev/null; then
  had_http=yes
fi
restore_http() {
  [ "$had_http" = yes ] || return 0
  echo "the HTTPS entry did not come up: putting the working HTTP entry on $SERVE_PORT back" >&2
  tailscale serve --yes --https="$SERVE_PORT" off 2>/dev/null || true
  tailscale serve --bg --yes --http="$SERVE_PORT" "http://127.0.0.1:$APP_PORT" >&2 || true
}
trap restore_http ERR

[ "$had_http" = no ] || tailscale serve --yes --http="$SERVE_PORT" off
tailscale serve --bg --yes --https="$SERVE_PORT" "http://127.0.0.1:$APP_PORT"

status=$(serve_status)
jq -e --arg p "$SERVE_PORT" '.TCP[$p].HTTPS == true' >/dev/null <<<"$status" \
  || { echo "serve has no HTTPS listener on $SERVE_PORT" >&2; exit 1; }
jq -e --arg p "$SERVE_PORT" '.TCP[$p].HTTP != true' >/dev/null <<<"$status" \
  || { echo "$SERVE_PORT still has an HTTP listener" >&2; exit 1; }
jq -e --arg p ":$SERVE_PORT" --arg target "127.0.0.1:$APP_PORT" \
  '[.Web // {} | to_entries[] | select(.key | endswith($p)) | .value.Handlers["/"].Proxy // ""
    | select(. == "http://" + $target)] | length == 1' >/dev/null <<<"$status" \
  || { echo "$SERVE_PORT does not proxy to 127.0.0.1:$APP_PORT: $(jq -c .Web <<<"$status")" >&2; exit 1; }

# tailscaled fetches this node's certificate lazily, on the first TLS handshake. serve's own config says nothing
# about whether that succeeds, so do a real request: it both proves TLS and warms the certificate. 60 s because
# issuing a fresh certificate takes a few seconds.
curl -fsS --max-time 60 "https://$domain:$SERVE_PORT/health" | jq -e '.status == "ok"' >/dev/null \
  || { echo "https://$domain:$SERVE_PORT/health does not answer ok: the certificate or the proxy is not working" >&2; exit 1; }
trap - ERR
echo "https://$domain:$SERVE_PORT answers over TLS"

# --- 2. Now the cookie may be Secure. Keep every other line (the password above all) untouched.
if grep -qxF 'COOKIE_SECURE=1' "$ENV_FILE"; then
  echo "$ENV_FILE already has COOKIE_SECURE=1"
else
  umask 077
  tmp=$(mktemp "$ENV_FILE.XXXXXX")
  trap 'rm -f "$tmp"' EXIT
  sed -e 's/^COOKIE_SECURE=.*/COOKIE_SECURE=1/' \
      -e 's|^# 0 while the tailnet entry is plain HTTP.*|# 1 because the tailnet entry is HTTPS (changes/0007-pixel-quest-https).|' \
      "$ENV_FILE" >"$tmp"
  grep -qxF 'COOKIE_SECURE=1' "$tmp" \
    || { echo "$ENV_FILE has no COOKIE_SECURE line to set; look at it by hand" >&2; exit 1; }
  grep -qE '^POSTGRES_PASSWORD=.{16,}$' "$tmp" \
    || { echo "refusing to install a $ENV_FILE without its password" >&2; exit 1; }
  chown deploy:deploy "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$ENV_FILE"
  trap - EXIT
  echo "set COOKIE_SECURE=1 in $ENV_FILE"
fi

# --- 3. Recreate the app so it reads the new value (interpolation happens at `up`, not at build)
as_deploy docker compose -p pixel-quest -f "$RELEASE/docker-compose.yml" --env-file "$ENV_FILE" \
  up -d --remove-orphans --no-build --wait --wait-timeout 120

as_deploy docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' pixel-quest-app-1 \
  | grep -qx 'COOKIE_SECURE=1' \
  || { echo "the running app container does not have COOKIE_SECURE=1" >&2; exit 1; }
curl -fsS --max-time 30 "https://$domain:$SERVE_PORT/health" | jq -e '.status == "ok"' >/dev/null \
  || { echo "https://$domain:$SERVE_PORT/health stopped answering after the container was recreated" >&2; exit 1; }

echo "pixel-quest is on https://$domain:$SERVE_PORT with a Secure session cookie"
echo "check by signing in on a tailnet device; plain HTTP on $SERVE_PORT no longer answers"
