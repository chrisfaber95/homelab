#!/usr/bin/env bash
# homelab: F-018, het deel voor het account claude (zonder sudo). Na scripts/18-projects.sh.
# - Rootless Docker voor claude: Claude bouwt, draait en stopt containers zonder de groep docker of sudo.
# - Een globale pre-push-hook: Claude pusht niet naar main of master, alleen naar branches (en opent een PR).
#   GitHub kan dat voor privé-repo's zonder Pro niet afdwingen; dit vangt vergissingen af.
# - ~/dev voor Claudes dev-clones.
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
  esac
done
EOF
chmod +x ~/.config/git/hooks/pre-push
git config --global core.hooksPath ~/.config/git/hooks

mkdir -p ~/dev

echo "== Controle"
id -nG | grep -qw docker && { echo "claude zit in de groep docker." >&2; exit 1; }
node --version
echo "Klaar: rootless Docker en de pre-push-hook staan."
