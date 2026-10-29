#!/usr/bin/env bash
# homelab: F-018, het deel voor het account claude (zonder sudo). Na scripts/18-projects.sh.
# - Rootless Docker voor claude: Claude bouwt, draait en stopt containers zonder de groep docker of sudo.
# - Een globale pre-push-hook: Claude pusht niet naar main of master, alleen naar branches (en opent een PR), en
#   niet naar jobs: een job wordt alleen actief door jouw bevestiging op de pagina (F-022), en niet naar memory:
#   het geheugen schrijft alleen de inbox (F-023).
#   GitHub kan dat voor privé-repo's zonder Pro niet afdwingen; dit vangt vergissingen af.
# - ~/dev voor Claudes dev-clones.
# - Agents uit claude/agents/ en vastgepinde plugins (config/claude.conf) in een eigen lokale marketplace.
# - Skills (config/claude.conf): frontend-design, web-artifacts-builder (met pnpm) en webapp-testing (met Playwright
#   en een headless Chromium), plus gitleaks in ~/.local/bin. Hun systeempakketten staan in config/machine.conf (homelab-admin legt ze aan).
# - graphify (config/claude.conf) in its own venv, for the Obsidian code map.
# - claude/CLAUDE.md as ~/.claude/CLAUDE.md: instructions for every session of claude (second brain, code map).
# Draai als claude: bash /home/claude/homelab/scripts/18-claude.sh
set -euo pipefail

if [[ $(id -un) != claude ]]; then
  echo "Draai als claude, zonder sudo." >&2
  exit 1
fi

echo "== Rootless Docker voor claude"
if [[ ! -f ~/.config/systemd/user/docker.service ]]; then
  dockerd-rootless-setuptool.sh install
fi
systemctl --user enable --now docker.service
line='export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/docker.sock'
grep -qxF "$line" ~/.bashrc || echo "$line" >> ~/.bashrc
export DOCKER_HOST="unix://$XDG_RUNTIME_DIR/docker.sock"
for _ in $(seq 30); do docker info >/dev/null 2>&1 && break; sleep 1; done
docker info --format '{{.SecurityOptions}}' | grep -q rootless
docker run --rm hello-world | grep -q 'Hello from Docker'

echo "== Geen push naar main"
mkdir -p ~/.config/git/hooks
cat > ~/.config/git/hooks/pre-push <<'EOF'
#!/usr/bin/env bash
# homelab (F-018): Claude pusht naar branches en opent een PR; de gebruiker merget naar main.
# Lokale repo's (een pad of file://, bijvoorbeeld voor tests) vallen erbuiten.
case $2 in /*|file://*) exit 0 ;; esac
while read -r _ _ remote_ref _; do
  case $remote_ref in
    refs/heads/main|refs/heads/master)
      echo "pre-push: Claude pusht niet naar ${remote_ref#refs/heads/}; push een branch en open een PR." >&2
      exit 1 ;;
    refs/heads/jobs)
      echo "pre-push: alleen de pagina van de inbox (jouw bevestiging) zet jobs op de branch jobs (F-022)." >&2
      exit 1 ;;
    refs/heads/memory)
      echo "pre-push: alleen de inbox schrijft het geheugen op de branch memory (F-023)." >&2
      exit 1 ;;
  esac
done
EOF
chmod +x ~/.config/git/hooks/pre-push
git config --global core.hooksPath ~/.config/git/hooks

mkdir -p ~/dev

# shellcheck disable=SC1091
source "$(dirname "$(readlink -f "$0")")/../config/claude.conf"

echo "== Skills (${SKILLS_COMMIT:0:7})"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
git -C "$tmp" init --quiet
git -C "$tmp" fetch --quiet --depth 1 "$SKILLS_REPO" "$SKILLS_COMMIT"
git -C "$tmp" checkout --quiet FETCH_HEAD
mkdir -p ~/.claude/skills
for s in $SKILLS; do
  [[ -f "$tmp/skills/$s/SKILL.md" ]] || { echo "Skill $s staat niet in $SKILLS_COMMIT." >&2; exit 1; }
  rm -rf ~/.claude/skills/"$s"
  cp -r "$tmp/skills/$s" ~/.claude/skills/"$s"
done

echo "== Systeempakketten voor de skills"
missing=$(grep -E '^package ' "$(dirname "$(readlink -f "$0")")/../config/machine.conf" | awk '{print $2}' |
  while read -r p; do dpkg -s "$p" >/dev/null 2>&1 || echo "$p"; done)
if [[ -n $missing ]]; then
  echo "Ontbreekt: $(echo $missing). Na de merge legt homelab-admin ze aan; of nu:" >&2
  echo "  homelab-admin package install $(echo $missing) --reason \"skills van claude\"" >&2
  exit 1
fi

echo "== pnpm $PNPM_VERSION (web-artifacts-builder)"
npm config set prefix ~/.local
[[ $(pnpm -v 2>/dev/null) == "$PNPM_VERSION" ]] || npm install -g --silent "pnpm@$PNPM_VERSION"
[[ $(~/.local/bin/pnpm -v) == "$PNPM_VERSION" ]]

echo "== Playwright $PLAYWRIGHT_VERSION en headless Chromium (webapp-testing)"
[[ -x "$PLAYWRIGHT_VENV/bin/python" ]] || python3 -m venv "$PLAYWRIGHT_VENV"
"$PLAYWRIGHT_VENV/bin/pip" install --quiet "playwright==$PLAYWRIGHT_VERSION"
"$PLAYWRIGHT_VENV/bin/playwright" install chromium >/dev/null
# De skill roept `python` aan: dat wordt de venv (via ~/.local/bin). `python3` blijft die van Ubuntu (met python3-yaml).
# Een wrapper, geen symlink: via een symlink herkent Python de venv niet.
rm -f ~/.local/bin/python
printf '#!/bin/sh\nexec "%s/bin/python" "$@"\n' "$PLAYWRIGHT_VENV" > ~/.local/bin/python
chmod +x ~/.local/bin/python
"$PLAYWRIGHT_VENV/bin/python" - <<'PY'
from playwright.sync_api import sync_playwright
with sync_playwright() as p:
    b = p.chromium.launch(headless=True)
    pg = b.new_page()
    pg.set_content("<h1>ok</h1>")
    assert pg.inner_text("h1") == "ok"
    b.close()
PY

echo "== gitleaks $GITLEAKS_VERSION"
if [[ $(~/.local/bin/gitleaks version 2>/dev/null) != "$GITLEAKS_VERSION" ]]; then
  f="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"
  gl=$(mktemp -d)
  curl -fsSL -o "$gl/$f" "https://github.com/gitleaks/gitleaks/releases/download/v$GITLEAKS_VERSION/$f"
  echo "$GITLEAKS_SHA256  $gl/$f" | sha256sum -c --quiet -
  tar -xzf "$gl/$f" -C "$gl" gitleaks
  install -m 755 "$gl/gitleaks" ~/.local/bin/gitleaks
  rm -rf "$gl"
fi
[[ $(~/.local/bin/gitleaks version) == "$GITLEAKS_VERSION" ]]

echo "== graphify $GRAPHIFY_VERSION (code map)"
[[ -x "$GRAPHIFY_VENV/bin/python" ]] || python3 -m venv "$GRAPHIFY_VENV"
"$GRAPHIFY_VENV/bin/pip" install --quiet "graphifyy[pdf,sql]==$GRAPHIFY_VERSION"
[[ $("$GRAPHIFY_VENV/bin/pip" show graphifyy | sed -n 's/^Version: //p') == "$GRAPHIFY_VERSION" ]]

echo "== Global instructions (claude/CLAUDE.md -> ~/.claude/CLAUDE.md)"
install -m 644 "$(dirname "$(readlink -f "$0")")"/../claude/CLAUDE.md ~/.claude/CLAUDE.md

echo "== Agents (claude/agents)"
mkdir -p ~/.claude/agents
for f in "$(dirname "$(readlink -f "$0")")"/../claude/agents/*.md; do
  install -m 644 "$f" ~/.claude/agents/
done

echo "== Plugins (${PLUGINS_COMMIT:0:7}): $PLUGINS"
pl=$(mktemp -d)
git -C "$pl" init --quiet
git -C "$pl" fetch --quiet --depth 1 --filter=blob:none "$PLUGINS_REPO" "$PLUGINS_COMMIT"
# shellcheck disable=SC2086
git -C "$pl" sparse-checkout set --no-cone /.claude-plugin $(printf '/plugins/%s ' $PLUGINS)
git -C "$pl" checkout --quiet FETCH_HEAD
rm -rf "$PLUGINS_DIR"
mkdir -p "$PLUGINS_DIR/.claude-plugin" "$PLUGINS_DIR/plugins"
for p in $PLUGINS; do
  [[ -f "$pl/plugins/$p/.claude-plugin/plugin.json" ]] || { echo "Plugin $p staat niet in $PLUGINS_COMMIT." >&2; exit 1; }
  cp -r "$pl/plugins/$p" "$PLUGINS_DIR/plugins/$p"
done
# Een eigen marketplace met alleen deze plugins, de vermeldingen overgenomen uit die van Anthropic.
jq --arg names "$PLUGINS" '($names | split(" ")) as $n
  | {name: "homelab-pinned", owner: {name: "homelab"}, plugins: [.plugins[] | select(.name as $x | $n | index($x))]}' \
  "$pl/.claude-plugin/marketplace.json" > "$PLUGINS_DIR/.claude-plugin/marketplace.json"
rm -rf "$pl"
# Alleen plugins zonder hooks of scripts: niets dat buiten de sessie het netwerk op gaat (config/claude.conf).
for p in $PLUGINS; do
  d="$PLUGINS_DIR/plugins/$p"
  if [[ -e $d/hooks || -e $d/scripts || -e $d/bin || -e $d/.mcp.json || -e $d/.lsp.json ]] ||
    jq -e 'has("hooks") or has("mcpServers") or has("lspServers")' "$d/.claude-plugin/plugin.json" >/dev/null; then
    echo "Plugin $p heeft hooks, scripts of een server; eerst lezen en apart beslissen." >&2
    exit 1
  fi
done
[[ $(jq '.plugins | length' "$PLUGINS_DIR/.claude-plugin/marketplace.json") == $(wc -w <<<"$PLUGINS") ]]
if claude plugin marketplace list --json | jq -e '.[] | select(.name == "homelab-pinned")' >/dev/null; then
  claude plugin marketplace update homelab-pinned >/dev/null
else
  claude plugin marketplace add "$PLUGINS_DIR" >/dev/null
fi
for p in $PLUGINS; do
  claude plugin install "$p@homelab-pinned" >/dev/null
done

echo "== Controle"
id -nG | grep -qw docker && { echo "claude zit in de groep docker." >&2; exit 1; }
node --version
for f in "$(dirname "$(readlink -f "$0")")"/../claude/agents/*.md; do
  cmp -s "$f" ~/.claude/agents/"$(basename "$f")"
done
installed=$(claude plugin list --json)
for p in $PLUGINS; do
  jq -e --arg id "$p@homelab-pinned" '.[] | select(.id == $id and .enabled)' <<<"$installed" >/dev/null ||
    { echo "Plugin $p is niet geïnstalleerd of staat uit." >&2; exit 1; }
done
echo "Klaar: rootless Docker, de pre-push-hook, de skills ($SKILLS), gitleaks, graphify, de agents en de plugins ($PLUGINS) staan."
