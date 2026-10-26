#!/usr/bin/env bash
# Demo-werknemer (demo@example.com / demo1234) en logo-URL in de bestaande database van visualshopping-server
# Het SQL-bestand komt uit de server-repo (db/06_demo.sql) en is idempotent. Een nieuwe database krijgt het al bij de
# eerste start; deze change is voor de database die op de box al bestond.
# Opnieuw (2026-10-26): de eerste keer was db/06_demo.sql nog niet gemerged.
set -euo pipefail
SQL=/srv/homelab/repos/visualshopping-server/db/06_demo.sql
CONTAINER=visualshopping-server-db-1

DEPLOY_UID=$(id -u deploy)
as_deploy() {
  (cd / && sudo -u deploy -H env XDG_RUNTIME_DIR="/run/user/$DEPLOY_UID" \
    DOCKER_HOST="unix:///run/user/$DEPLOY_UID/docker.sock" PATH=/usr/bin:/bin:/usr/sbin:/sbin "$@")
}

if [[ ! -f $SQL ]]; then
  echo "$SQL ontbreekt: eerst de PR met db/06_demo.sql in Visualshopping-Server-portfolio mergen." >&2
  exit 1
fi
as_deploy docker exec -i "$CONTAINER" mysql -uvisualshopping -pvisualshopping-demo < "$SQL" 2>&1 | grep -v 'Using a password' || true
as_deploy docker exec "$CONTAINER" mysql -uvisualshopping -pvisualshopping-demo -N \
  -e "SELECT COUNT(*) FROM cooplubbers_test.User WHERE email = 'demo@example.com'" 2>/dev/null | grep -qx 1
echo "demo-werknemer staat in de database"
