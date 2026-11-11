#!/usr/bin/env bash
# Put pixel-quest back on plain HTTP (serve 8113) with COOKIE_SECURE=0, as changes/0006 left it.
#
# The order is the mirror of apply.sh: drop the Secure cookie first, then the TLS entry. A Secure cookie with an HTTP
# entry is the one combination that breaks signing in without any error, so it must never exist in between - and that
# is why the cookie is verified in the running container before the entry is switched.
# /etc/homelab/pixel-quest.env keeps its password: the pgdata volume was initialised with it.
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

[ -s "$ENV_FILE" ] || { echo "$ENV_FILE is missing: apply changes/0006-pixel-quest-env-and-serve first" >&2; exit 1; }

# --- 1. Cookie back to 0, including the comment above it, so the file reads as changes/0006 wrote it
if grep -qxF 'COOKIE_SECURE=0' "$ENV_FILE"; then
  echo "$ENV_FILE already has COOKIE_SECURE=0"
else
  umask 077
  tmp=$(mktemp "$ENV_FILE.XXXXXX")
  trap 'rm -f "$tmp"' EXIT
  sed -e 's/^COOKIE_SECURE=.*/COOKIE_SECURE=0/' \
      -e 's|^# 1 because the tailnet entry is HTTPS.*|# 0 while the tailnet entry is plain HTTP. changes/0007-pixel-quest-https sets this to 1.|' \
      "$ENV_FILE" >"$tmp"
  grep -qxF 'COOKIE_SECURE=0' "$tmp" \
    || { echo "$ENV_FILE has no COOKIE_SECURE line to set; look at it by hand" >&2; exit 1; }
  grep -qE '^POSTGRES_PASSWORD=.{16,}$' "$tmp" \
    || { echo "refusing to install a $ENV_FILE without its password" >&2; exit 1; }
  chown deploy:deploy "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$ENV_FILE"
  trap - EXIT
  echo "set COOKIE_SECURE=0 in $ENV_FILE"
fi

# --- 2. Recreate the app and prove it really dropped the Secure flag, before HTTP goes live
if [ -d "$RELEASE" ]; then
  as_deploy docker compose -p pixel-quest -f "$RELEASE/docker-compose.yml" --env-file "$ENV_FILE" \
    up -d --remove-orphans --no-build --wait --wait-timeout 120
  as_deploy docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' pixel-quest-app-1 \
    | grep -qx 'COOKIE_SECURE=0' \
    || { echo "the running app container still has a Secure cookie: refusing to switch $SERVE_PORT to HTTP, because that combination breaks signing in without any error" >&2; exit 1; }
  curl -fsS --max-time 30 "http://127.0.0.1:$APP_PORT/health" | jq -e '.status == "ok"' >/dev/null \
    || { echo "the app does not answer ok on http://127.0.0.1:$APP_PORT/health" >&2; exit 1; }
else
  echo "no release ($RELEASE): nothing to recreate, only switching the tailnet entry"
fi

# --- 3. Serve HTTP again instead of HTTPS
had_https=no
if serve_status | jq -e --arg p "$SERVE_PORT" '.TCP[$p].HTTPS == true' >/dev/null; then
  had_https=yes
fi
restore_https() {
  [ "$had_https" = yes ] || return 0
  echo "the HTTP entry did not come up: putting the HTTPS entry on $SERVE_PORT back" >&2
  tailscale serve --yes --http="$SERVE_PORT" off 2>/dev/null || true
  tailscale serve --bg --yes --https="$SERVE_PORT" "http://127.0.0.1:$APP_PORT" >&2 || true
}
trap restore_https ERR

[ "$had_https" = no ] || tailscale serve --yes --https="$SERVE_PORT" off
tailscale serve --bg --yes --http="$SERVE_PORT" "http://127.0.0.1:$APP_PORT"

status=$(serve_status)
jq -e --arg p "$SERVE_PORT" '.TCP[$p].HTTP == true' >/dev/null <<<"$status" \
  || { echo "serve has no HTTP listener on $SERVE_PORT" >&2; exit 1; }
jq -e --arg p "$SERVE_PORT" '.TCP[$p].HTTPS != true' >/dev/null <<<"$status" \
  || { echo "$SERVE_PORT still has an HTTPS listener" >&2; exit 1; }
trap - ERR
echo "pixel-quest is back on http://homelab:$SERVE_PORT with COOKIE_SECURE=0"
