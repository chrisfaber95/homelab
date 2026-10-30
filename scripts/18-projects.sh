#!/usr/bin/env bash
# homelab: F-018, projecten op de box.
# - Docker (rootless; de gewone, root-draaiende Docker staat uit) en Node 24.
# - Account deploy: houdt de repo's uit config/repos.conf bij (dienst homelab-deploy) met een read-only GitHub-token,
#   en draait compose-projecten in zijn eigen rootless Docker.
# - Services uit deploy/<naam>.service als homelab-app-<naam>.service; deploy mag alleen die (her)starten.
# - ufw: poorten 8100-8199 alleen via tailscale0.
# - Linger voor claude, zodat Claude zijn eigen rootless Docker kan draaien (daarna: bash scripts/18-claude.sh als claude).
# Opnieuw draaien na een wijziging aan bin/homelab-deploy, config/deploy.conf of deploy/*.service.
# Draai als: sudo bash <homelab-repo>/scripts/18-projects.sh
# homelab-rerun: root bin/homelab-deploy config/deploy.conf deploy
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
DEST=/usr/local/lib/homelab

if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi
# shellcheck source=../config/deploy.conf
source "$REPO/config/deploy.conf"

echo "== Pakketten: Docker en Node 24"
install -m 755 -d /etc/apt/keyrings
if [[ ! -s /etc/apt/keyrings/docker.asc ]]; then
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
fi
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
  > /etc/apt/sources.list.d/docker.list
if [[ ! -s /etc/apt/keyrings/nodesource.gpg ]]; then
  curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor --yes -o /etc/apt/keyrings/nodesource.gpg
fi
echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_24.x nodistro main" \
  > /etc/apt/sources.list.d/nodesource.list
apt-get update -q
DEBIAN_FRONTEND=noninteractive apt-get install -y -q docker-ce docker-ce-cli containerd.io docker-buildx-plugin \
  docker-compose-plugin docker-ce-rootless-extras uidmap nodejs
node --version | grep -q '^v24\.'

echo "== Geen root-Docker: alleen rootless"
systemctl disable --now docker.service docker.socket >/dev/null 2>&1 || true
rm -f /var/run/docker.sock
# Ubuntu 24.04 beperkt user namespaces via AppArmor; rootlesskit heeft ze nodig.
cat > /etc/apparmor.d/usr.bin.rootlesskit <<'EOF'
abi <abi/4.0>,
include <tunables/global>

"/usr/bin/rootlesskit" flags=(unconfined) {
  userns,

  include if exists <local/usr.bin.rootlesskit>
}
EOF
apparmor_parser -r /etc/apparmor.d/usr.bin.rootlesskit

echo "== Account deploy"
id deploy >/dev/null 2>&1 || useradd -r -m -d /var/lib/deploy -s /usr/sbin/nologin -U deploy
grep -q '^deploy:' /etc/subuid || usermod --add-subuids 231072-296607 deploy
grep -q '^deploy:' /etc/subgid || usermod --add-subgids 231072-296607 deploy
DEPLOY_UID=$(id -u deploy)
loginctl enable-linger deploy claude
for _ in $(seq 30); do [[ -S /run/user/$DEPLOY_UID/bus ]] && break; sleep 1; done
as_deploy() {
  (cd / && sudo -u deploy -H env XDG_RUNTIME_DIR="/run/user/$DEPLOY_UID" \
    DBUS_SESSION_BUS_ADDRESS="unix:path=/run/user/$DEPLOY_UID/bus" DOCKER_HOST="unix:///run/user/$DEPLOY_UID/docker.sock" \
    PATH=/usr/bin:/bin:/usr/sbin:/sbin "$@")
}
if [[ ! -f /var/lib/deploy/.config/systemd/user/docker.service ]]; then
  as_deploy dockerd-rootless-setuptool.sh install
fi
as_deploy systemctl --user enable --now docker.service
for _ in $(seq 30); do as_deploy docker info >/dev/null 2>&1 && break; sleep 1; done
as_deploy docker info --format '{{.SecurityOptions}}' | grep -q rootless

echo "== Read-only GitHub-token voor deploy ($TOKEN_FILE)"
install -d -m 755 "$(dirname "$TOKEN_FILE")"
helper='!f() { test "$1" = get && echo username=x-access-token && echo "password=$(cat '"$TOKEN_FILE"')"; }; f'
token_works() {
  [[ -s "$TOKEN_FILE" ]] && as_deploy git -c credential.helper= -c "credential.helper=$helper" \
    ls-remote "https://github.com/$HOMELAB_REPO.git" refs/heads/main >/dev/null 2>&1
}
# Een bestaand token wordt getest; werkt het niet (verlopen, of verkeerd geplakt), dan vraagt het script opnieuw.
for attempt in 1 2 3; do
  if [[ -e "$TOKEN_FILE" ]]; then chown deploy:deploy "$TOKEN_FILE"; chmod 600 "$TOKEN_FILE"; fi
  if token_works; then echo "Het token kan $HOMELAB_REPO lezen."; break; fi
  if (( attempt == 3 )); then echo "Het token werkt na drie pogingen niet." >&2; exit 1; fi
  [[ -s "$TOKEN_FILE" ]] && echo "Het token in $TOKEN_FILE kan $HOMELAB_REPO niet lezen."
  echo "Maak op GitHub een fine-grained token (Settings > Developer settings > Fine-grained tokens):"
  echo "  Repository access: All repositories; Permissions: Contents = Read-only (Metadata wordt vanzelf Read-only)."
  read -r -s -p "Plak het token (je ziet het niet) en druk op Enter: " token </dev/tty
  echo
  token=$(tr -d '[:space:]' <<<"$token")
  echo "Ontvangen: ${#token} tekens, begint met '${token:0:11}'."
  [[ $token == github_pat_* ]] || echo "Dat lijkt geen fine-grained token (die beginnen met github_pat_)."
  (umask 077 && printf '%s\n' "$token" > "$TOKEN_FILE")
  unset token
done

echo "== homelab-deploy installeren"
install -d -m 755 "$DEST/bin" "$DEST/config"
install -m 755 "$REPO/bin/homelab-deploy" "$DEST/bin/homelab-deploy"
install -m 644 "$REPO/config/deploy.conf" "$DEST/config/deploy.conf"
ln -sfn "$DEST/bin/homelab-deploy" /usr/local/bin/homelab-deploy
install -d -m 755 -o deploy -g deploy "$DEPLOY_ROOT" "$DEPLOY_ROOT/repos" "$DEPLOY_ROOT/releases" "$DEPLOY_ROOT/state"

cat > /etc/systemd/system/homelab-deploy.service <<EOF
[Unit]
Description=homelab-deploy: repo's bijhouden en deployen (F-018)
After=network-online.target user@$DEPLOY_UID.service
Wants=network-online.target user@$DEPLOY_UID.service

[Service]
User=deploy
Environment=HOME=/var/lib/deploy
Environment=XDG_RUNTIME_DIR=/run/user/$DEPLOY_UID
Environment=DOCKER_HOST=unix:///run/user/$DEPLOY_UID/docker.sock
ExecStart=/usr/bin/python3 $DEST/bin/homelab-deploy run
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

echo "== Services uit deploy/"
installed=()
for f in "$REPO"/deploy/*.service; do
  [[ -e "$f" ]] || continue
  name=$(basename "$f" .service)
  install -m 644 "$f" "/etc/systemd/system/homelab-app-$name.service"
  installed+=("homelab-app-$name.service")
done
for f in /etc/systemd/system/homelab-app-*.service; do
  [[ -e "$f" ]] || continue
  u=$(basename "$f")
  if [[ ! " ${installed[*]} " == *" $u "* ]]; then
    systemctl disable --now "$u" || true
    rm -f "$f"
  fi
done
# deploy mag alleen de homelab-app-units starten, stoppen en herstarten; verder niets.
cat > /etc/polkit-1/rules.d/50-homelab-deploy.rules <<'EOF'
polkit.addRule(function(action, subject) {
  if (action.id == "org.freedesktop.systemd1.manage-units" && subject.user == "deploy") {
    var unit = action.lookup("unit"), verb = action.lookup("verb");
    if (unit && /^homelab-app-[a-z0-9-]+\.service$/.test(unit) &&
        (verb == "start" || verb == "stop" || verb == "restart")) {
      return polkit.Result.YES;
    }
  }
});
EOF
systemctl daemon-reload
# Alleen enablen: homelab-deploy start ze zodra er een release is.
for u in "${installed[@]}"; do systemctl enable "$u"; done
systemctl enable homelab-deploy.service
systemctl restart homelab-deploy.service

echo "== Firewall: 8100-8199 alleen via het tailnet"
ufw allow in on tailscale0 to any port 8100:8199 proto tcp comment 'homelab-diensten tailnet'

echo "== Controle"
systemctl is-active homelab-deploy
# (geen `! cmd`: dat breekt onder set -e nooit af)
if systemctl is-active --quiet docker.service; then echo "De root-Docker draait nog." >&2; exit 1; fi
if id -nG claude | grep -qw docker; then echo "claude zit in de groep docker." >&2; exit 1; fi
ufw status | grep -E '^8100:8199/tcp on tailscale0 +ALLOW'
for _ in $(seq 60); do [[ -d "$DEPLOY_ROOT/repos/homelab/.git" ]] && break; sleep 2; done
[[ -d "$DEPLOY_ROOT/repos/homelab/.git" ]] || { echo "De homelab-repo is niet gekloond: journalctl -u homelab-deploy" >&2; exit 1; }
# Het token mag niet kunnen pushen: GitHub moet de push-handshake weigeren.
if out=$(cd "$DEPLOY_ROOT/repos/homelab" && sudo -u deploy git -c credential.helper= -c "credential.helper=$helper" \
         push --dry-run "https://github.com/$HOMELAB_REPO.git" HEAD:refs/heads/homelab-push-test 2>&1); then
  echo "Het token kan pushen; maak een token met Contents = Read-only." >&2
  exit 1
fi
echo "Push geweigerd, zoals het hoort: $(tail -n1 <<<"$out")"
sleep 5
sudo -u "${SUDO_USER:-nobody}" -- homelab-deploy status || true
echo "Klaar. Volgende stap voor Claude: bash /home/claude/homelab/scripts/18-claude.sh (als claude, zonder sudo)."
