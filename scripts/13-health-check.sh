#!/usr/bin/env bash
# homelab: checklist stap 13, de health check (F-016) installeren als `homelab-health`.
# Kopieert bin/homelab-health en config/health.conf uit deze repo naar /usr/local/lib/homelab.
# Opnieuw draaien na elke wijziging aan een van die twee (bijvoorbeeld EXPECTED_GPU na de verhuizing).
# Draai als: sudo bash /home/claude/homelab/scripts/13-health-check.sh
# homelab-rerun: root bin/homelab-health config/health.conf
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
DEST=/usr/local/lib/homelab

if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi

echo "== Health check installeren naar $DEST"
install -d -m 755 "$DEST/bin" "$DEST/config"
install -m 755 "$REPO/bin/homelab-health" "$DEST/bin/homelab-health"
install -m 644 "$REPO/config/health.conf" "$DEST/config/health.conf"
ln -sfn "$DEST/bin/homelab-health" /usr/local/bin/homelab-health

echo "== Controle"
cmp "$REPO/bin/homelab-health" "$DEST/bin/homelab-health"
cmp "$REPO/config/health.conf" "$DEST/config/health.conf"
grep '^EXPECTED_GPU=' "$DEST/config/health.conf"

# Draai hem als de gebruiker die sudo aanriep, zoals hij straks ook gebruikt wordt.
rc=0
sudo -u "${SUDO_USER:-nobody}" -- homelab-health || rc=$?
case $rc in
  0) echo "Klaar: homelab-health is geïnstalleerd en alles is in orde." ;;
  1) echo "Klaar: homelab-health is geïnstalleerd, maar er faalt een check (zie FAIL hierboven)." ;;
  *) echo "homelab-health kan niet draaien (exit $rc)." >&2; exit 1 ;;
esac
