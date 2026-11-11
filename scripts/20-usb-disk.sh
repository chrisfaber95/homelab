#!/usr/bin/env bash
# homelab: F-020, de externe USB-HDD als backupschijf.
# - Mount een partitie van de HDD op BACKUP_MOUNT, in /etc/fstab op UUID met nofail: zonder de schijf start de box
#   gewoon (de health check meldt dan dat hij ontbreekt).
# - ext4 heeft de voorkeur; ntfs en exfat (een schijf van Windows, met data) gaan ook, dan alleen voor root leesbaar.
# - Formatteert nooit: een schijf zonder bestandssysteem, of met een ander, weigert het script.
# - Zet de oude backups van de SSD over naar BACKUP_DIR en draait daarna scripts/20-monitor.sh.
# Draai als: sudo bash /var/lib/homelab/repo/scripts/20-usb-disk.sh            (toont de USB-schijven)
#            sudo bash /var/lib/homelab/repo/scripts/20-usb-disk.sh <UUID>     (mount die partitie)
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
OLD_DIR=/var/backups/homelab

if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi
# shellcheck source=../config/backup.conf
source "$REPO/config/backup.conf"
if [[ -z ${BACKUP_MOUNT:-} || $BACKUP_DIR != "$BACKUP_MOUNT"/* ]]; then
  echo "config/backup.conf: BACKUP_DIR ($BACKUP_DIR) ligt niet op BACKUP_MOUNT (${BACKUP_MOUNT:-leeg})." >&2
  exit 1
fi

uuid=${1:-}
if [[ -z $uuid ]]; then
  echo "== USB-schijven"
  usb=$(lsblk -dno NAME,TRAN | awk '$2 == "usb" {print "/dev/" $1}')
  if [[ -z $usb ]]; then
    echo "Geen USB-schijf gevonden. Zit de kabel goed, heeft de schijf (indien nodig) eigen stroom, en draait hij?" >&2
    exit 1
  fi
  # shellcheck disable=SC2086
  lsblk -o NAME,SIZE,FSTYPE,LABEL,UUID,MOUNTPOINTS,MODEL $usb
  echo
  echo "Kies de partitie en draai opnieuw met haar UUID: sudo bash $0 <UUID>"
  exit 0
fi

dev=$(blkid -U "$uuid" || true)
if [[ -z $dev ]]; then
  echo "Geen partitie met UUID $uuid." >&2
  exit 1
fi
if [[ $(lsblk -no TRAN "/dev/$(lsblk -no PKNAME "$dev")") != usb ]]; then
  echo "$dev zit niet op USB; dit script is alleen voor de externe HDD." >&2
  exit 1
fi
fstype=$(blkid -s TYPE -o value "$dev")
case $fstype in
  ext4)  opts="defaults,nofail,noatime,x-systemd.device-timeout=10s"; pass=2 ;;
  ntfs)  fstype=ntfs3; opts="nofail,noatime,uid=0,gid=0,umask=077,x-systemd.device-timeout=10s"; pass=0 ;;
  exfat) opts="nofail,noatime,uid=0,gid=0,umask=077,x-systemd.device-timeout=10s"; pass=0 ;;
  *)
    echo "$dev heeft bestandssysteem '${fstype:-geen}'. Ondersteund: ext4, ntfs, exfat. Formatteren doe je zelf," >&2
    echo "bijvoorbeeld: sudo mkfs.ext4 -L homelab-hdd $dev (wist alles op die partitie)." >&2
    exit 1 ;;
esac

echo "== $dev ($fstype) op $BACKUP_MOUNT, in /etc/fstab"
line="UUID=$uuid $BACKUP_MOUNT $fstype $opts 0 $pass"
# Een eerdere regel voor dit mountpunt vervangen, zodat het script opnieuw kan draaien (ook met een andere schijf).
tmp=$(mktemp)
awk -v mp="$BACKUP_MOUNT" '$2 != mp' /etc/fstab > "$tmp"
comment="# homelab: backupschijf (F-020, scripts/20-usb-disk.sh)"
grep -qxF "$comment" "$tmp" || echo "$comment" >> "$tmp"
echo "$line" >> "$tmp"
cat "$tmp" > /etc/fstab
rm -f "$tmp"
systemctl daemon-reload
if mountpoint -q "$BACKUP_MOUNT" && [[ $(findmnt -no SOURCE "$BACKUP_MOUNT") != "$dev" ]]; then
  umount "$BACKUP_MOUNT"
fi
install -d -m 755 "$BACKUP_MOUNT"
mountpoint -q "$BACKUP_MOUNT" || mount "$BACKUP_MOUNT"
findmnt "$BACKUP_MOUNT"

echo "== $BACKUP_DIR, alleen voor root"
install -d -m 700 -o root -g root "$BACKUP_DIR"
if [[ -d $OLD_DIR && $OLD_DIR != "$BACKUP_DIR" ]]; then
  shopt -s nullglob
  old=("$OLD_DIR"/homelab-*.tar.gz)
  if (( ${#old[@]} )); then
    echo "Oude backups overzetten: ${#old[@]}"
    cp -n "${old[@]}" "$BACKUP_DIR"/
    chmod 600 "$BACKUP_DIR"/homelab-*.tar.gz 2>/dev/null || true
    for f in "${old[@]}"; do cmp -s "$f" "$BACKUP_DIR/$(basename "$f")" && rm -f "$f"; done
  fi
  rmdir "$OLD_DIR" 2>/dev/null || echo "Let op: $OLD_DIR is niet leeg, bekijk wat er nog staat." >&2
fi

echo "== Controle"
if sudo -u claude test -r "$BACKUP_DIR"; then echo "claude kan $BACKUP_DIR lezen." >&2; exit 1; fi
grep -q "^UUID=$uuid $BACKUP_MOUNT " /etc/fstab
df -h "$BACKUP_MOUNT" | tail -1

echo "== Bewaking en backup bijwerken (scripts/20-monitor.sh)"
bash "$REPO/scripts/20-monitor.sh"
echo "Klaar: de backups staan op de USB-HDD ($BACKUP_DIR)."
