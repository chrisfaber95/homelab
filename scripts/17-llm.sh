#!/usr/bin/env bash
# homelab: checklist stap 13, F-017. Ollama (vastgepind) en de LLM-helper `llm-helper` op poort LLM_PORT.
# Ollama luistert alleen op 127.0.0.1; de helper is via ufw alleen bereikbaar via tailscale0.
# Niet via ollama.com/install.sh: dat installeert zelf cuda-drivers als het de GPU niet herkent.
# Opnieuw draaien na elke wijziging aan llm/, bin/llm of config/llm.conf.
# Draai als: sudo bash /home/claude/homelab/scripts/17-llm.sh
# homelab-rerun: root bin/llm llm config/llm.conf
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
DEST=/usr/local/lib/homelab

if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi
# shellcheck source=../config/llm.conf
source "$REPO/config/llm.conf"

echo "== Ollama $OLLAMA_VERSION"
if [[ "$(/usr/local/bin/ollama --version 2>/dev/null | grep -o '[0-9][0-9.]*$' || true)" == "$OLLAMA_VERSION" ]]; then
  echo "Staat er al."
else
  command -v zstd >/dev/null || apt-get install -y zstd
  tmp=$(mktemp -d)
  trap 'rm -rf "$tmp"' EXIT
  curl -fL --progress-bar -o "$tmp/ollama.tar.zst" \
    "https://github.com/ollama/ollama/releases/download/v$OLLAMA_VERSION/ollama-linux-amd64.tar.zst"
  echo "$OLLAMA_SHA256  $tmp/ollama.tar.zst" | sha256sum -c -
  systemctl stop ollama 2>/dev/null || true
  rm -rf /usr/local/lib/ollama
  zstd -dc "$tmp/ollama.tar.zst" | tar -xf - -C /usr/local
fi
/usr/local/bin/ollama --version 2>/dev/null | grep -q "$OLLAMA_VERSION"

id ollama >/dev/null 2>&1 || useradd -r -s /bin/false -U -m -d /usr/share/ollama ollama
for g in render video; do getent group "$g" >/dev/null && usermod -a -G "$g" ollama; done

cat > /etc/systemd/system/ollama.service <<'UNIT'
[Unit]
Description=Ollama (homelab, F-017)
After=network-online.target

[Service]
ExecStart=/usr/local/bin/ollama serve
User=ollama
Group=ollama
Environment=OLLAMA_HOST=127.0.0.1:11434
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT

echo "== LLM-helper installeren naar $DEST"
install -d -m 755 "$DEST/bin" "$DEST/llm" "$DEST/config"
install -m 755 "$REPO/bin/llm" "$DEST/bin/llm"
install -m 644 "$REPO/llm/helper.py" "$DEST/llm/helper.py"
install -m 644 "$REPO/config/llm.conf" "$DEST/config/llm.conf"
ln -sfn "$DEST/bin/llm" /usr/local/bin/llm

cat > /etc/systemd/system/llm-helper.service <<UNIT
[Unit]
Description=LLM-helper (homelab, F-017)
After=ollama.service tailscaled.service
Wants=ollama.service

[Service]
ExecStart=/usr/bin/python3 $DEST/llm/helper.py
DynamicUser=yes
StateDirectory=llm-helper
Restart=always
RestartSec=3
NoNewPrivileges=yes
ProtectSystem=strict
ProtectHome=yes
PrivateTmp=yes
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable ollama llm-helper
systemctl restart ollama llm-helper

echo "== Firewall: helper alleen via het tailnet"
ufw allow in on tailscale0 to any port "$LLM_PORT" proto tcp comment 'llm-helper tailnet'

echo "== Model $LLM_MODEL"
for _ in $(seq 30); do curl -fsS http://127.0.0.1:11434/api/version >/dev/null 2>&1 && break; sleep 1; done
/usr/local/bin/ollama pull "$LLM_MODEL"

echo "== Controle"
systemctl is-active ollama llm-helper
ufw status | grep -E "^$LLM_PORT/tcp on tailscale0 +ALLOW"
ss -Htln "sport = :11434" | grep -q '127.0.0.1:11434' || { echo "Ollama luistert niet (alleen) op 127.0.0.1." >&2; exit 1; }
for _ in $(seq 15); do curl -fsS "http://127.0.0.1:$LLM_PORT/health" >/dev/null 2>&1 && break; sleep 1; done
curl -fsS "http://127.0.0.1:$LLM_PORT/health"; echo
echo "Zeg dit is een test." | sudo -u "${SUDO_USER:-nobody}" -- llm --caller install --url "http://127.0.0.1:$LLM_PORT" summarize
/usr/local/bin/ollama ps
echo "Klaar: llm en llm-helper draaien. In de PROCESSOR-kolom hierboven hoort 100% GPU te staan."
