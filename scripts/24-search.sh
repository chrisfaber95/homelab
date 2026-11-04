#!/usr/bin/env bash
# homelab: H-012, the search index (index side; docs/search.md).
# Installs bin/homelab-search and search/ next to the other homelab programs, pulls the embedding model into Ollama,
# installs the nightly sync (systemd/homelab-search-sync.{service,timer}) and builds the index from the sources if
# there is none yet. Re-running never rebuilds an existing index: that is `homelab-search rebuild` (below).
# Rebuild the whole index from its sources (also after a rebuilt box, or after changing the model, the chunking rules
# or the scrub patterns):  sudo bash /var/lib/homelab/repo/scripts/24-search.sh --rebuild
# Draai als: sudo bash /var/lib/homelab/repo/scripts/24-search.sh [--rebuild]
# Also the query side: the API service `homelab-search-api` (port 8019, ufw: only tailscale0), the CLI (`homelab-search
# query|ask`) and the MCP tool for Claude sessions (`homelab-search-mcp`, registered for the user claude).
# homelab-rerun: root bin/homelab-search bin/homelab-search-mcp search config/search.json systemd/homelab-search-sync.service systemd/homelab-search-sync.timer systemd/homelab-search-api.service
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
install -m 755 "$REPO/bin/homelab-search-mcp" "$DEST/bin/homelab-search-mcp"
install -m 644 "$REPO/config/search.json" "$DEST/config/search.json"
ln -sfn "$DEST/bin/homelab-search" /usr/local/bin/homelab-search
ln -sfn "$DEST/bin/homelab-search-mcp" /usr/local/bin/homelab-search-mcp
SEARCH_PORT=$(python3 -I -c "import json,sys;print(json.load(open(sys.argv[1])).get('query',{}).get('port',8019))" "$REPO/config/search.json")

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

echo "== Search API (port $SEARCH_PORT, tailnet only)"
install -m 644 "$REPO/systemd/homelab-search-api.service" /etc/systemd/system/homelab-search-api.service
systemctl daemon-reload
systemctl enable homelab-search-api.service
systemctl restart homelab-search-api.service
ufw allow in on tailscale0 to any port "$SEARCH_PORT" proto tcp comment 'homelab-search-api tailnet'
ufw status | grep -E "^$SEARCH_PORT/tcp on tailscale0 +ALLOW" >/dev/null
for _ in $(seq 15); do curl -fsS "http://127.0.0.1:$SEARCH_PORT/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "http://127.0.0.1:$SEARCH_PORT/health"; echo
echo "== MCP tool for Claude sessions of $SEARCH_USER"
CLAUDE_BIN=$(runuser -u "$SEARCH_USER" -- bash -lc 'command -v claude' || true)
if [[ -n $CLAUDE_BIN ]]; then
  runuser -u "$SEARCH_USER" -- "$CLAUDE_BIN" mcp remove --scope user homelab-search >/dev/null 2>&1 || true
  runuser -u "$SEARCH_USER" -- "$CLAUDE_BIN" mcp add --scope user homelab-search -- /usr/local/bin/homelab-search-mcp
else
  echo "WAARSCHUWING: claude niet gevonden voor $SEARCH_USER; registreer de MCP-tool later met:" >&2
  echo "  claude mcp add --scope user homelab-search -- /usr/local/bin/homelab-search-mcp" >&2
fi
echo "The instruction for sessions is in claude/CLAUDE.md; install it with scripts/18-claude.sh (as claude)."
