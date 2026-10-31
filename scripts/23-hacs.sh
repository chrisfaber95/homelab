#!/usr/bin/env bash
# homelab: H-008. HACS and the integrations installed through it (Tuya Local), pinned in config/hacs.pins, into the
# custom_components of the Home Assistant config volume. Idempotent: an entry that is already installed with the same
# sha256 is skipped; a changed pin replaces the old copy. Run again after every change to config/hacs.pins.
# Home Assistant loads custom components at start: restart it once after a change (Settings > System > Restart).
# GitHub token (optional here, only to avoid the anonymous rate limit): the first line of $HACS_GITHUB_TOKEN_FILE
# (default /etc/homelab/hacs-github-token, root 0600, outside the repo). It goes to curl on stdin, is never printed
# and never put in an argument list. HACS' own GitHub login is a manual step in the UI (docs/projects.md).
# Draai als: sudo bash /var/lib/homelab/repo/scripts/23-hacs.sh
# homelab-rerun: root config/hacs.pins
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
CONFIG="${HA_CONFIG:-/srv/homelab/data/homeassistant/config}"
PINS="${HACS_PINS:-$REPO/config/hacs.pins}"
TOKEN_FILE="${HACS_GITHUB_TOKEN_FILE:-/etc/homelab/hacs-github-token}"

if [[ ! -d $CONFIG ]]; then
  echo "$CONFIG does not exist: run scripts/18-projects.sh first (it creates the volume)." >&2
  exit 1
fi

token=""
if [[ -r $TOKEN_FILE ]]; then
  IFS= read -r token < "$TOKEN_FILE" || true
  echo "GitHub token: read from $TOKEN_FILE"
else
  echo "GitHub token: none at $TOKEN_FILE (anonymous downloads)"
fi

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
dest="$CONFIG/custom_components"
mkdir -p "$dest"
changed=0

while read -r name domain url sha path; do
  [[ -z ${name:-} || $name == \#* ]] && continue
  marker="$dest/$domain/.pinned-sha256"
  if [[ -f $marker && $(< "$marker") == "$sha" ]]; then
    echo "== $name: already installed ($sha)"
    continue
  fi
  echo "== $name: downloading $url"
  # -K - reads the header from stdin: the token never appears in the process list or the output. GitHub drops the
  # Authorization header when it redirects to another host.
  if [[ -n $token ]]; then
    printf 'header = "Authorization: Bearer %s"\n' "$token" | curl -fsSL -K - -o "$tmp/$name.zip" "$url"
  else
    curl -fsSL -o "$tmp/$name.zip" "$url"
  fi
  echo "$sha  $tmp/$name.zip" | sha256sum -c - >/dev/null || { echo "$name: sha256 does not match config/hacs.pins" >&2; exit 1; }
  rm -rf "${tmp:?}/x-$name"
  mkdir "$tmp/x-$name"
  python3 -I -c 'import sys,zipfile;zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])' "$tmp/$name.zip" "$tmp/x-$name"
  src="$tmp/x-$name/$path"
  [[ -f $src/manifest.json ]] || { echo "$name: no manifest.json at $path in the zip" >&2; exit 1; }
  rm -rf "${dest:?}/$domain"
  cp -a "$src" "$dest/$domain"
  echo "$sha" > "$dest/$domain/.pinned-sha256"
  changed=1
  echo "$name: installed $(python3 -I -c 'import json,sys;print(json.load(open(sys.argv[1])).get("version","?"))' "$dest/$domain/manifest.json")"
done < "$PINS"

if [[ $EUID -eq 0 ]] && id deploy >/dev/null 2>&1; then
  chown -R deploy:deploy "$dest"
fi

# Self-check: every pinned entry is in place with its marker.
while read -r name domain _ sha _; do
  [[ -z ${name:-} || $name == \#* ]] && continue
  [[ -f $dest/$domain/manifest.json && $(< "$dest/$domain/.pinned-sha256") == "$sha" ]] || { echo "$name: check failed" >&2; exit 1; }
done < "$PINS"

if (( changed )); then
  echo "Done. Restart Home Assistant once (Settings > System > Restart), then add HACS under Settings > Devices > Add integration."
else
  echo "Done. Nothing changed."
fi
