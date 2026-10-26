#!/usr/bin/env bash
# Productfoto's van Coop Lubbers in de Docker-volume `public` van visualshopping-server (bestaande bestanden blijven)
# Bron: /home/claude/data/coop-lubbers/public, buiten git; herkomst en kopiëren: README van homelab-data/databases/coop-lubbers.
# De server serveert de volume op /api/back-images/ en schrijft er ook uploads in; tar slaat bestaande bestanden over.
set -euo pipefail
SRC=/home/claude/data/coop-lubbers/public
PROJECT=visualshopping-server
VOL=${PROJECT}_public
IMAGE=node:18.20.8-bookworm  # hetzelfde image als de Dockerfile van de server, met GNU tar
COUNT=9340

DEPLOY_UID=$(id -u deploy)
as_deploy() {
  (cd / && sudo -u deploy -H env XDG_RUNTIME_DIR="/run/user/$DEPLOY_UID" \
    DOCKER_HOST="unix:///run/user/$DEPLOY_UID/docker.sock" PATH=/usr/bin:/bin:/usr/sbin:/sbin "$@")
}

found=$(find "$SRC" -type f | wc -l)
if (( found < COUNT )); then
  echo "$SRC heeft $found bestanden, verwacht $COUNT: eerst opnieuw kopiëren (zie de README in homelab-data)." >&2
  exit 1
fi

# Met de labels van compose, zodat `docker compose up` de volume als de zijne overneemt (ook als die nog niet bestaat).
if ! as_deploy docker volume inspect "$VOL" >/dev/null 2>&1; then
  as_deploy docker volume create --label com.docker.compose.project="$PROJECT" \
    --label com.docker.compose.volume=public "$VOL" >/dev/null
fi
tar -C "$SRC" -cf - . | as_deploy docker run --rm -i -v "$VOL":/dst "$IMAGE" \
  tar -C /dst --no-same-owner --skip-old-files -xf -
in_vol=$(as_deploy docker run --rm -v "$VOL":/dst "$IMAGE" sh -c 'find /dst -type f | wc -l')
echo "$VOL: $in_vol bestanden"
(( in_vol >= COUNT ))
