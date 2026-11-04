#!/usr/bin/env bash
# homelab: H-012, the search index (index side; docs/search.md).
# Installs bin/homelab-search and search/ next to the other homelab programs, pulls the embedding model into Ollama,
# installs the nightly sync (systemd/homelab-search-sync.{service,timer}) and builds the index from the sources if
# there is none yet. Re-running never rebuilds an existing index: that is `homelab-search rebuild` (below).
# Rebuild the whole index from its sources (also after a rebuilt box, or after changing the model, the chunking rules
# or the scrub patterns):  sudo bash /var/lib/homelab/repo/scripts/24-search.sh --rebuild
# Draai als: sudo bash /var/lib/homelab/repo/scripts/24-search.sh [--rebuild]
# homelab-rerun: root bin/homelab-search search config/search.json systemd/homelab-search-sync.service systemd/homelab-search-sync.timer
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
DEST=/usr/local/lib/homelab
SEARCH_USER=claude   # reads ~/.claude and ~/dev; must match User= in systemd/homelab-search-sync.service

if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi

echo "== Programma's naar $DEST"
install -d -m 755 "$DEST/bin" "$DEST/search" "$DEST/config"
for f in "$REPO"/search/*.py; do install -m 644 "$f" "$DEST/search/$(basename "$f")"; done
install -m 755 "$REPO/bin/homelab-search" "$DEST/bin/homelab-search"
install -m 644 "$REPO/config/search.json" "$DEST/config/search.json"
ln -sfn "$DEST/bin/homelab-search" /usr/local/bin/homelab-search

echo "== Embeddingmodel"
MODEL=$(python3 -I -c "import json,sys;print(json.load(open(sys.argv[1]))['embedding']['model'])" "$REPO/config/search.json")
systemctl is-active --quiet ollama
if ! /usr/local/bin/ollama list | awk 'NR>1{print $1}' | grep -qx "$MODEL:latest\|$MODEL"; then
  /usr/local/bin/ollama pull "$MODEL"
fi

echo "== Nachtelijke sync"
install -m 644 "$REPO/systemd/homelab-search-sync.service" /etc/systemd/system/homelab-search-sync.service
install -m 644 "$REPO/systemd/homelab-search-sync.timer" /etc/systemd/system/homelab-search-sync.timer
systemctl daemon-reload
systemctl enable --now homelab-search-sync.timer

echo "== Index"
install -d -m 750 -o "$SEARCH_USER" -g "$SEARCH_USER" /var/lib/homelab-search
if [[ ${1:-} == --rebuild ]]; then
  runuser -u "$SEARCH_USER" -- /usr/local/bin/homelab-search rebuild
elif [[ ! -s /var/lib/homelab-search/index.db ]]; then
  runuser -u "$SEARCH_USER" -- /usr/local/bin/homelab-search rebuild
else
  echo "Er is al een index: niet opnieuw opgebouwd (gebruik --rebuild)."
fi
runuser -u "$SEARCH_USER" -- /usr/local/bin/homelab-search status
systemctl is-enabled homelab-search-sync.timer
