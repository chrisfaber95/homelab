#!/usr/bin/env bash
# homelab: F-019, machinewijzigingen door Claude.
# - ntfy (pushmeldingen naar je telefoon) op poort NTFY_PORT, met login; ufw laat 8100-8199 alleen via tailscale0 toe.
# - Een eigen clone van de homelab-repo voor root (REPO_DIR), die alleen root kan wijzigen.
# - De dienst homelab-admin (root): het directe pad voor Claude, changes/ uit main toepassen met vangnet, meldingen.
# Opnieuw draaien na een wijziging aan bin/homelab-admin of config/admin.conf.
# Draai als: sudo bash /var/lib/homelab/repo/scripts/19-admin.sh   (de eerste keer: /home/claude/homelab/scripts/...)
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
DEST=/usr/local/lib/homelab

if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi
# shellcheck source=../config/deploy.conf
source "$REPO/config/deploy.conf"
# shellcheck source=../config/admin.conf
source "$REPO/config/admin.conf"

echo "== ntfy $NTFY_VERSION"
if [[ "$(/usr/local/bin/ntfy --version 2>/dev/null || true)" != "ntfy version $NTFY_VERSION"* ]]; then
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  f="ntfy_${NTFY_VERSION}_linux_amd64"
  curl -fsSL -o "$tmp/$f.tar.gz" "https://github.com/binwiederhier/ntfy/releases/download/v$NTFY_VERSION/$f.tar.gz"
  echo "$NTFY_SHA256  $tmp/$f.tar.gz" | sha256sum -c -
  tar -xzf "$tmp/$f.tar.gz" -C "$tmp"
  install -m 755 "$tmp/$f/ntfy" /usr/local/bin/ntfy
fi
id ntfy >/dev/null 2>&1 || useradd -r -s /usr/sbin/nologin -d /var/lib/ntfy -U ntfy
install -d -m 750 -o ntfy -g ntfy /var/lib/ntfy /var/cache/ntfy
install -d -m 755 /etc/ntfy
cat > /etc/ntfy/server.yml <<EOF
# homelab (F-019): alleen via het tailnet (ufw), iedereen moet inloggen.
base-url: "http://homelab:$NTFY_PORT"
listen-http: ":$NTFY_PORT"
cache-file: "/var/cache/ntfy/cache.db"
auth-file: "/var/lib/ntfy/user.db"
auth-default-access: "deny-all"
# iOS: Apple-push loopt via ntfy.sh; daarheen gaat alleen een ID, niet de inhoud.
upstream-base-url: "https://ntfy.sh"
attachment-cache-dir: ""
enable-signup: false
enable-login: true
EOF
chown root:ntfy /etc/ntfy/server.yml
chmod 640 /etc/ntfy/server.yml
cat > /etc/systemd/system/ntfy.service <<'EOF'
[Unit]
Description=ntfy (homelab, F-019)
After=network-online.target

[Service]
User=ntfy
Group=ntfy
ExecStart=/usr/local/bin/ntfy serve --config /etc/ntfy/server.yml
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable ntfy
systemctl restart ntfy
for _ in $(seq 20); do curl -fsS "http://127.0.0.1:$NTFY_PORT/v1/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "http://127.0.0.1:$NTFY_PORT/v1/health" | grep -q '"healthy":true'

nt() { runuser -u ntfy -- /usr/local/bin/ntfy "$1" --config /etc/ntfy/server.yml "${@:2}"; }
user_exists() { nt user list 2>&1 | grep -q "^user $1 "; }
phone_user=${SUDO_USER:-chris}
if ! user_exists "$phone_user"; then
  echo "Een ntfy-login voor je telefoon (gebruiker $phone_user)."
  while true; do
    read -r -s -p "Kies een wachtwoord: " pw1 </dev/tty; echo
    read -r -s -p "Nog een keer: " pw2 </dev/tty; echo
    [[ -n "$pw1" && "$pw1" == "$pw2" ]] && break
    echo "Leeg of niet gelijk; opnieuw."
  done
  NTFY_PASSWORD="$pw1" nt user add --role=user "$phone_user"
  unset pw1 pw2
fi
nt access "$phone_user" "$NTFY_TOPIC" read-only >/dev/null
if ! user_exists homelab-bot; then
  NTFY_PASSWORD="$(openssl rand -hex 24)" nt user add --role=user homelab-bot
fi
nt access homelab-bot "$NTFY_TOPIC" write-only >/dev/null
install -d -m 755 /etc/homelab
if [[ ! -s /etc/homelab/ntfy-token ]]; then
  token=$(nt token add homelab-bot 2>&1 | grep -o 'tk_[a-z0-9]*')
  [[ -n "$token" ]] || { echo "Geen ntfy-token gekregen." >&2; exit 1; }
  (umask 077 && printf '%s\n' "$token" > /etc/homelab/ntfy-token)
  unset token
fi
chown root:root /etc/homelab/ntfy-token
chmod 600 /etc/homelab/ntfy-token

echo "== Clone van root: $REPO_DIR"
helper='!f() { test "$1" = get && echo username=x-access-token && echo "password=$(cat '"$TOKEN_FILE"')"; }; f'
install -d -m 755 "$(dirname "$REPO_DIR")"
if [[ ! -d "$REPO_DIR/.git" ]]; then
  git -c credential.helper= -c "credential.helper=$helper" clone --quiet --branch main --single-branch \
    "https://github.com/$HOMELAB_REPO.git" "$REPO_DIR"
fi
chown -R root:root "$REPO_DIR"
chmod 755 "$REPO_DIR"

echo "== homelab-admin installeren"
install -d -m 755 "$DEST/bin" "$DEST/config"
install -m 755 "$REPO/bin/homelab-admin" "$DEST/bin/homelab-admin"
install -m 644 "$REPO/config/admin.conf" "$DEST/config/admin.conf"
install -m 644 "$REPO/config/deploy.conf" "$DEST/config/deploy.conf"
ln -sfn "$DEST/bin/homelab-admin" /usr/local/bin/homelab-admin
install -d -m 700 "$STATE_DIR"
install -d -m 755 "$(dirname "$LOG_FILE")"
cat > /etc/systemd/system/homelab-admin.service <<EOF
[Unit]
Description=homelab-admin: machinewijzigingen (F-019)
After=network-online.target tailscaled.service ntfy.service
Wants=network-online.target

[Service]
ExecStart=/usr/bin/python3 $DEST/bin/homelab-admin serve
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable homelab-admin
systemctl restart homelab-admin

echo "== Controle"
systemctl is-active ntfy homelab-admin
for _ in $(seq 20); do [[ -S "$SOCKET" ]] && break; sleep 1; done
[[ -S "$SOCKET" ]] || { echo "De socket $SOCKET ontbreekt: journalctl -u homelab-admin" >&2; exit 1; }
# Lezen zonder login moet geweigerd worden.
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$NTFY_PORT/$NTFY_TOPIC/json?poll=1")
[[ $code == 401 || $code == 403 ]] || { echo "ntfy is zonder login leesbaar (HTTP $code)." >&2; exit 1; }
curl -fsS -o /dev/null -H "Authorization: Bearer $(cat /etc/homelab/ntfy-token)" \
  -H "Title: homelab-admin is geïnstalleerd" -d "Deze melding komt van je box. Zie je hem op je telefoon, dan werkt het." \
  "http://127.0.0.1:$NTFY_PORT/$NTFY_TOPIC"
pr=$(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(cat "$TOKEN_FILE")" \
  "https://api.github.com/repos/$HOMELAB_REPO/pulls?state=open&per_page=1")
if [[ $pr != 200 ]]; then
  echo "LET OP: het token van deploy kan geen PR's lezen (HTTP $pr). Geef het op GitHub ook"
  echo "  'Pull requests: Read-only'; anders komen er geen meldingen over nieuwe PR's."
fi
sleep 3
sudo -u "$phone_user" -- homelab-admin status || true
echo "Klaar. Op je telefoon: de ntfy-app, server http://homelab:$NTFY_PORT, gebruiker $phone_user, topic $NTFY_TOPIC."
