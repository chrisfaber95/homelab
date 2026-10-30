#!/usr/bin/env bash
# homelab: checklist stap 5 (SSH alleen met sleutel), 6 (firewall), 8 (swappiness) en 9 (klep, alleen laptop).
# Draai als: sudo bash ~/setup/05-09-base.sh
# homelab-rerun: manual
set -euo pipefail

LAN_SUBNET="192.168.1.0/24"

if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi

echo "== Stap 5: SSH alleen met sleutel"
cat > /etc/ssh/sshd_config.d/10-homelab.conf <<'EOF'
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
EOF
sshd -t
systemctl restart ssh
sshd -T | grep -Ei '^(passwordauthentication|kbdinteractiveauthentication|permitrootlogin) '

echo "== Stap 6: firewall"
# Standaard zet Tailscale een ACCEPT voor alles op tailscale0 vóór ufw; zonder die regel filtert ufw ook het tailnet.
tailscale set --netfilter-mode=nodivert
ufw default deny incoming
ufw default allow outgoing
ufw allow from "$LAN_SUBNET" to any port 22 proto tcp comment 'SSH LAN'
ufw allow in on tailscale0 to any port 22 proto tcp comment 'SSH tailnet'
ufw --force enable
ufw status verbose

echo "== Stap 8: swappiness"
echo 'vm.swappiness=10' > /etc/sysctl.d/99-homelab.conf
sysctl --system >/dev/null
sysctl vm.swappiness

echo "== Stap 9: klep dicht laat de box aan (alleen laptop; weghalen na de verhuizing)"
mkdir -p /etc/systemd/logind.conf.d
cat > /etc/systemd/logind.conf.d/laptop-lid.conf <<'EOF'
[Login]
HandleLidSwitch=ignore
HandleLidSwitchExternalPower=ignore
HandleLidSwitchDocked=ignore
EOF

echo "Klaar. Reboot zodat de klep-instelling ingaat: sudo reboot"
