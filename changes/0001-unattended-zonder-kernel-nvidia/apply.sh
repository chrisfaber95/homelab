#!/usr/bin/env bash
# unattended-upgrades slaat kernel- en NVIDIA-pakketten over (die blijven handwerk, F-016/F-019)
# Het dagoverzicht van homelab-admin meldt welke kernel- en NVIDIA-updates wachten; installeer ze met de checklist.
set -euo pipefail
cat > /etc/apt/apt.conf.d/51homelab-unattended <<'CONF'
// homelab (changes/0001): kernel en NVIDIA niet automatisch; zie docs/admin.md.
Unattended-Upgrade::Package-Blacklist {
    "^linux-";
    "^(lib)?nvidia-";
    "^xserver-xorg-video-nvidia";
};
CONF
apt-config dump | grep -A4 '^Unattended-Upgrade::Package-Blacklist' | grep -q '\^linux-'
echo "Blacklist actief: $(apt-config dump | grep -c 'Package-Blacklist::')"
