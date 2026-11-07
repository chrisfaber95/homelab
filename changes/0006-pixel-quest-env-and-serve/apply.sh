#!/usr/bin/env bash
# pixel-quest (H-014): give the compose project its database password (/etc/homelab/pixel-quest.env) and serve the app
# on the tailnet as https://homelab.tail4eb878.ts.net:8113 -> http://127.0.0.1:8114.
#
# PRECONDITION, a web action this script cannot do: DNS > HTTPS Certificates must be on in the Tailscale admin console
# (https://login.tailscale.com/admin/dns). Without it `tailscale serve --https` is refused. The check below runs before
# anything is written, so merging this too early changes nothing on the box and says what to click. Note the side
# effect of enabling it: the name homelab.tail4eb878.ts.net ends up in the public Certificate Transparency logs. That
# makes the name visible, not the box reachable.
#
# Why the env file lives outside the repo: homelab-deploy unpacks a release again for every commit, so a `.env` next to
# docker-compose.yml is gone after the next deploy. ENV_DIR/<name>.env (config/deploy.conf) survives, and homelab-deploy
# hands it to compose as --env-file, which fills both ${POSTGRES_PASSWORD} in the compose file and the Postgres
# container's own password. Only the account deploy may read it (mode 600).
#
# The password is generated once and then left alone: Postgres applies POSTGRES_PASSWORD only when it initialises the
# pgdata volume, so writing a new one later would lock the box out of the players' data. Rerunning this change is
# therefore safe; after a rebuild without a restore of /etc/homelab it makes a new password, but then the volume is
# gone as well.
#
# Tailnet only: `serve` listens on the Tailscale addresses, never on the LAN or the internet, and funnel must stay off
# (checked before and after; funnel is only possible on 443/8443/10000 anyway). HTTPS and not HTTP because the app sets
# COOKIE_SECURE=1, so the browser only sends the session cookie over TLS. The serve config lives in tailscaled's state
# (in config/backup.list): a restore brings it back, so rerun this change only after a rebuild without a restore.
set -euo pipefail

ENV_FILE=/etc/homelab/pixel-quest.env
APP_PORT=8114    # the app itself, published on loopback only (compose: BIND_ADDR:PORT)
SERVE_PORT=8113  # what the tailnet sees, over HTTPS

no_funnel() {
  tailscale serve status --json | jq -e '[(.AllowFunnel // {}) | to_entries[] | select(.value)] | length == 0' >/dev/null \
    || { echo "tailscale funnel is on for something: refusing (nothing on this box may reach the internet)" >&2; exit 1; }
}

# --- 0. Preconditions: fail before the box is touched
no_funnel
if ! tailscale status --json | jq -e '[(.CertDomains // [])[]] | length > 0' >/dev/null; then
  echo "this tailnet cannot issue certificates, so 'tailscale serve --https=$SERVE_PORT' would be refused." >&2
  echo "turn on DNS > HTTPS Certificates at https://login.tailscale.com/admin/dns, then apply this change again" >&2
  echo "(check: tailscale status --json | jq .CertDomains  must list homelab.tail4eb878.ts.net)" >&2
  exit 1
fi

# --- 1. The env file compose reads (the password is created once, never overwritten)
if [ -e "$ENV_FILE" ]; then
  # -e and not -s on purpose: an empty file from an interrupted write must not quietly earn a new password while the
  # pgdata volume may already hold the old one. The checks below reject it instead.
  echo "$ENV_FILE is already there: keeping the password the pgdata volume was initialised with"
  # Repair ownership and mode of a file that came from somewhere else; this never touches the password.
  chown deploy:deploy "$ENV_FILE"
  chmod 600 "$ENV_FILE"
else
  umask 077
  # In /etc/homelab and then mv, so homelab-deploy (which polls every 20 s) never reads a half-written file.
  tmp=$(mktemp "$ENV_FILE.XXXXXX")
  trap 'rm -f "$tmp"' EXIT
  {
    echo "# pixel-quest (H-014). homelab-deploy passes this to compose as --env-file (config/deploy.conf, ENV_DIR)."
    echo "# Made by changes/0006-pixel-quest-env-and-serve. Never change POSTGRES_PASSWORD: the pgdata volume keeps it."
    echo "POSTGRES_PASSWORD=$(openssl rand -hex 24)"
    echo "PORT=$APP_PORT"
    echo "BIND_ADDR=127.0.0.1"
  } >"$tmp"
  chown deploy:deploy "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$ENV_FILE"
  trap - EXIT
  echo "wrote $ENV_FILE"
fi

owner_mode=$(stat -c '%U:%G %a' "$ENV_FILE")
[ "$owner_mode" = "deploy:deploy 600" ] \
  || { echo "$ENV_FILE is $owner_mode, want deploy:deploy 600" >&2; exit 1; }
grep -qE '^POSTGRES_PASSWORD=.{16,}$' "$ENV_FILE" \
  || { echo "$ENV_FILE has no usable POSTGRES_PASSWORD; look at it by hand (do not guess: the pgdata volume holds the old one)" >&2; exit 1; }
grep -qxF "PORT=$APP_PORT" "$ENV_FILE" \
  || { echo "$ENV_FILE does not set PORT=$APP_PORT (found: $(grep -E '^PORT=' "$ENV_FILE" || echo none)); look at it by hand" >&2; exit 1; }
grep -qxF 'BIND_ADDR=127.0.0.1' "$ENV_FILE" \
  || { echo "$ENV_FILE does not bind the app to 127.0.0.1; look at it by hand" >&2; exit 1; }

# Prove the thing this change exists for: compose can interpolate now. `config` reads files only, no daemon, no build.
release=/srv/homelab/releases/pixel-quest/current
if [ -d "$release" ]; then
  runuser -u deploy -- docker compose -p pixel-quest -f "$release/docker-compose.yml" --env-file "$ENV_FILE" config -q \
    || { echo "compose still cannot read $release/docker-compose.yml with $ENV_FILE" >&2; exit 1; }
  echo "compose interpolates the release with $ENV_FILE"
else
  echo "no release yet ($release): homelab-deploy builds one in its next round"
fi

# --- 2. Serve it on the tailnet over HTTPS
tailscale serve --bg --yes --https="$SERVE_PORT" "http://127.0.0.1:$APP_PORT"
no_funnel

status=$(tailscale serve status --json)
jq -e --arg p "$SERVE_PORT" '.TCP[$p].HTTPS == true' >/dev/null <<<"$status" \
  || { echo "serve has no HTTPS listener on $SERVE_PORT" >&2; exit 1; }
jq -e --arg p ":$SERVE_PORT" --arg target "127.0.0.1:$APP_PORT" \
  '[.Web // {} | to_entries[] | select(.key | endswith($p)) | .value.Handlers["/"].Proxy // ""
    | select(. == "http://" + $target)] | length == 1' >/dev/null <<<"$status" \
  || { echo "$SERVE_PORT does not proxy to 127.0.0.1:$APP_PORT: $(jq -c .Web <<<"$status")" >&2; exit 1; }

echo "pixel-quest: env file in place, served on the tailnet at https://homelab.tail4eb878.ts.net:$SERVE_PORT"
echo "the deploy itself follows: a failed build is retried once per 30 minutes, so give it that long (homelab-deploy status)"
