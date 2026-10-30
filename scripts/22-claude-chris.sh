#!/usr/bin/env bash
# homelab: Claude Code CLI voor de gebruiker chris (claude en inbox hebben een eigen installatie in hun ~/.local/bin).
# Zet `claude` in de PATH van chris bij elke login (SSH, lokaal en niet-interactief via ~/.profile).
# Draai als chris, zonder sudo: bash /var/lib/homelab/repo/scripts/22-claude-chris.sh
# homelab-rerun: manual
set -euo pipefail

if [[ $(id -un) != chris ]]; then
  echo "Draai als chris, zonder sudo." >&2
  exit 1
fi

BIN="$HOME/.local/bin"
export PATH="$BIN:$PATH"

echo "== Claude Code installeren (native installer, in ~/.local)"
if command -v claude >/dev/null 2>&1; then
  echo "claude staat al: $(command -v claude)"
else
  curl -fsSL https://claude.ai/install.sh | bash
fi

echo "== PATH in ~/.profile en ~/.bashrc"
LINE='export PATH="$HOME/.local/bin:$PATH"  # homelab: claude'
for f in "$HOME/.profile" "$HOME/.bashrc"; do
  touch "$f"
  grep -qF '# homelab: claude' "$f" || printf '\n%s\n' "$LINE" >> "$f"
done

echo "== Controle met een nieuwe login-shell"
bash -lc 'command -v claude && claude --version'
env -i HOME="$HOME" USER=chris bash -lc 'which claude && claude --version'
echo "Klaar. Eenmalig inloggen: claude → /login (open de URL op de Windows-pc)."
