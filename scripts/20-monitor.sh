#!/usr/bin/env bash
# homelab: F-020, bewaking, overzicht en backups.
# - homelab-monitor: elke 2 minuten de health check, meldingen bij falen en herstel, de overzichtspagina.
# - homelab-backup: elke nacht om 03:30 (of bij de eerste start daarna) een backup naar BACKUP_DIR, 14 dagen bewaard.
# - De overzichtspagina, alleen lezen, op poort OVERVIEW_PORT (ufw: 8100-8199 alleen via tailscale0).
# - Werkt homelab-health (nieuwe checks), homelab-deploy (compose-status) en homelab-admin (dagoverzicht) bij.
# Opnieuw draaien na een wijziging aan een van die bin/-bestanden of config/backup.*.
# Draai als: sudo bash /var/lib/homelab/repo/scripts/20-monitor.sh
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
DEST=/usr/local/lib/homelab

if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi
# shellcheck source=../config/backup.conf
source "$REPO/config/backup.conf"

echo "== Programma's en instellingen naar $DEST"
install -d -m 755 "$DEST/bin" "$DEST/config"
for f in homelab-health homelab-deploy homelab-admin homelab-backup homelab-monitor; do
  install -m 755 "$REPO/bin/$f" "$DEST/bin/$f"
done
for f in backup.conf backup.list health.conf; do
  install -m 644 "$REPO/config/$f" "$DEST/config/$f"
done
for f in homelab-backup homelab-monitor; do ln -sfn "$DEST/bin/$f" "/usr/local/bin/$f"; done
ln -sfn "$DEST/bin/homelab-monitor" /usr/local/bin/homelab-overview
install -d -m 755 "$MONITOR_DIR" "$OVERVIEW_DIR"
install -d -m 700 "$BACKUP_DIR"

echo "== Diensten en timers"
cat > /etc/systemd/system/homelab-monitor.service <<EOF
[Unit]
Description=homelab-monitor: health check en meldingen (F-020)
After=network-online.target ntfy.service

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 $DEST/bin/homelab-monitor check
EOF
cat > /etc/systemd/system/homelab-monitor.timer <<'EOF'
[Unit]
Description=homelab-monitor elke 2 minuten (F-020)

[Timer]
OnBootSec=2min
OnUnitActiveSec=2min
AccuracySec=10s

[Install]
WantedBy=timers.target
EOF
cat > /etc/systemd/system/homelab-backup.service <<EOF
[Unit]
Description=homelab-backup: dagelijkse backup (F-020)

[Service]
Type=oneshot
ExecStart=/usr/bin/python3 $DEST/bin/homelab-backup run
EOF
cat > /etc/systemd/system/homelab-backup.timer <<'EOF'
[Unit]
Description=homelab-backup elke nacht (F-020)

[Timer]
OnCalendar=*-*-* 03:30
RandomizedDelaySec=10min
Persistent=true

[Install]
WantedBy=timers.target
EOF
cat > /etc/systemd/system/homelab-overview-web.service <<EOF
[Unit]
Description=homelab: overzichtspagina, alleen lezen (F-020)
After=network-online.target

[Service]
ExecStart=/usr/bin/python3 -m http.server $OVERVIEW_PORT --bind :: --directory $OVERVIEW_DIR
DynamicUser=yes
ProtectSystem=strict
ProtectHome=yes
ReadOnlyPaths=$OVERVIEW_DIR
NoNewPrivileges=yes
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now homelab-monitor.timer homelab-backup.timer homelab-overview-web.service
systemctl restart homelab-overview-web.service
# Bijgewerkte programma's laden.
systemctl restart homelab-deploy.service homelab-admin.service

echo "== Eerste backup en eerste check"
systemctl start homelab-backup.service
ls -l "$BACKUP_DIR" | tail -n +2
stat -c '%a %U' "$BACKUP_DIR" | grep -qx '700 root'
systemctl start homelab-monitor.service
sleep 2

echo "== Controle"
systemctl is-active homelab-monitor.timer homelab-backup.timer homelab-overview-web.service
curl -fsS -o /dev/null "http://127.0.0.1:$OVERVIEW_PORT/" && echo "Overzichtspagina antwoordt."
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:$OVERVIEW_PORT/")
[[ $code == 501 ]] || { echo "De overzichtspagina accepteert POST (HTTP $code)." >&2; exit 1; }
if sudo -u claude test -r "$BACKUP_DIR"; then echo "claude kan $BACKUP_DIR lezen." >&2; exit 1; fi
sudo -u "${SUDO_USER:-nobody}" -- homelab-overview
echo "Klaar. Op je telefoon: http://homelab.tail4eb878.ts.net:$OVERVIEW_PORT"
