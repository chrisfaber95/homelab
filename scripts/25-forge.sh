#!/usr/bin/env bash
# homelab: H-015, forge. Account `forge` (own HOME /var/lib/forge for its Claude CLI and gh logins), the state
# directory, and /etc/forge/env from config/forge.env (once, never overwritten). The unit itself comes from
# deploy/forge.service (scripts/18-projects.sh, which calls this script first so the account exists before the
# service starts) and the code from the `forge` line in config/repos.conf. Forge starts stopped after every restart.
# Not done here, by hand once (needs a login): docs/projects.md, "forge".
# Draai als: sudo bash <homelab-repo>/scripts/25-forge.sh
# homelab-rerun: root scripts/25-forge.sh config/forge.env
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi

echo "== Account forge"
id forge >/dev/null 2>&1 || useradd -r -m -d /var/lib/forge -s /usr/sbin/nologin -U forge
install -d -m 700 -o forge -g forge /var/lib/forge /var/lib/forge/work

echo "== /etc/forge/env"
install -d -m 755 -o root -g root /etc/forge
[[ -e /etc/forge/env ]] || install -m 644 -o root -g root "$REPO/config/forge.env" /etc/forge/env

echo "== Controle"
id -u forge >/dev/null
[[ $(stat -c %U:%a /var/lib/forge) == forge:700 ]]
[[ -s /etc/forge/env ]]
# Forge must never be told to bind a wildcard address, and never gets a key.
if grep -Eq '^(FORGE_BIND_HOST=(0\.0\.0\.0|::)|[A-Z_]*API_KEY=)' /etc/forge/env; then
  echo "/etc/forge/env binds a wildcard address or holds a key: fix it." >&2
  exit 1
fi
if sudo -u forge -H bash -c 'command -v claude' >/dev/null 2>&1; then
  echo "forge: claude CLI found."
else
  echo "forge: claude CLI not installed for the forge account yet (docs/projects.md, 'forge')."
fi
echo "forge account and config ready"
