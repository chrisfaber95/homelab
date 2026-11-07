#!/usr/bin/env bash
# Put pixel-quest back on plain HTTP (serve 8113) with COOKIE_SECURE=0, as changes/0006 left it.
# Order matters the other way round here: drop the Secure cookie first, then the TLS entry. A Secure cookie with an
# HTTP entry is the combination that breaks signing in without any error, so it must never exist in between.
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

# --- 1. Cookie back to 0, and recreate the app so it stops marking it Secure
if [ -s "$ENV_FILE" ] && ! grep -qxF 'COOKIE_SECURE=0' "$ENV_FILE"; then
  umask 077
  tmp=$(mktemp "$ENV_FILE.XXXXXX")
  trap 'rm -f "$tmp"' EXIT
  sed 's/^COOKIE_SECURE=.*/COOKIE_SECURE=0/' "$ENV_FILE" >"$tmp"
  grep -qE '^POSTGRES_PASSWORD=.{16,}$' "$tmp" \
    || { echo "refusing to install a $ENV_FILE without its password" >&2; exit 1; }
  chown deploy:deploy "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$ENV_FILE"
  trap - EXIT
  echo "set COOKIE_SECURE=0 in $ENV_FILE"
fi
if [ -d "$RELEASE" ]; then
  as_deploy docker compose -p pixel-quest -f "$RELEASE/docker-compose.yml" --env-file "$ENV_FILE" \
    up -d --remove-orphans --no-build --wait --wait-timeout 120
fi

# --- 2. Serve HTTP again instead of HTTPS
if tailscale serve status --json | jq -e --arg p "$SERVE_PORT" '.TCP[$p].HTTPS == true' >/dev/null; then
  tailscale serve --yes --https="$SERVE_PORT" off
fi
tailscale serve --bg --yes --http="$SERVE_PORT" "http://127.0.0.1:$APP_PORT"

status=$(tailscale serve status --json)
jq -e --arg p "$SERVE_PORT" '.TCP[$p].HTTP == true' >/dev/null <<<"$status" \
  || { echo "serve has no HTTP listener on $SERVE_PORT" >&2; exit 1; }
jq -e --arg p "$SERVE_PORT" '.TCP[$p].HTTPS != true' >/dev/null <<<"$status" \
  || { echo "$SERVE_PORT still has an HTTPS listener" >&2; exit 1; }
echo "pixel-quest is back on http://homelab:$SERVE_PORT with COOKIE_SECURE=0"
