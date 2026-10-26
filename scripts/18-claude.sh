#!/usr/bin/env bash
# homelab: F-018, het deel voor het account claude (zonder sudo). Na scripts/18-projects.sh.
# - Rootless Docker voor claude: Claude bouwt, draait en stopt containers zonder de groep docker of sudo.
# - Een globale pre-push-hook: Claude pusht niet naar main of master, alleen naar branches (en opent een PR), en
#   niet naar jobs: een job wordt alleen actief door jouw bevestiging op de pagina (F-022), en niet naar memory:
#   het geheugen schrijft alleen de inbox (F-023).
#   GitHub kan dat voor privé-repo's zonder Pro niet afdwingen; dit vangt vergissingen af.
# - ~/dev voor Claudes dev-clones.
# - Skills (config/claude.conf): frontend-design, web-artifacts-builder (met pnpm) en webapp-testing (met Playwright
#   en een headless Chromium). Hun systeempakketten staan in config/machine.conf (homelab-admin legt ze aan).
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

echo "== Controle"
id -nG | grep -qw docker && { echo "claude zit in de groep docker." >&2; exit 1; }
node --version
echo "Klaar: rootless Docker, de pre-push-hook en de skills ($SKILLS) staan."
