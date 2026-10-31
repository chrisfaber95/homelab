#!/usr/bin/env bash
# homelab: H-008, times one colour change on a named light through the Home Assistant REST API and prints the elapsed
# time. Usage: bash scripts/ha-colour-latency.sh <light entity id> [R G B]     e.g. light.living_room 255 0 0
# The long-lived access token is read from a file outside the repo (default /etc/homelab/ha-token, first line;
# override with HA_TOKEN_FILE). It is handed to curl on stdin, so it is never on a command line, and never printed.
# HA_URL overrides the address (default http://100.85.70.34:8110).
# The time is until Home Assistant has the bulb's answer (the service call returns after the local command went
# out), not the light itself: film the bulb in slow motion or watch it for the "within 1 s" criterion.
set -euo pipefail

ENTITY=${1:?usage: ha-colour-latency.sh <light.entity_id> [R G B]}
R=${2:-255} G=${3:-0} B=${4:-0}
URL=${HA_URL:-http://100.85.70.34:8110}
TOKEN_FILE=${HA_TOKEN_FILE:-/etc/homelab/ha-token}

[[ $ENTITY =~ ^light\.[a-z0-9_]+$ ]] || { echo "Not a light entity id: $ENTITY" >&2; exit 2; }
for c in "$R" "$G" "$B"; do
  [[ $c =~ ^[0-9]+$ && $c -le 255 ]] || { echo "Colour values must be 0-255" >&2; exit 2; }
done
[[ -r $TOKEN_FILE ]] || { echo "Cannot read the token file $TOKEN_FILE (set HA_TOKEN_FILE)" >&2; exit 1; }
TOKEN=$(head -n1 "$TOKEN_FILE")
[[ -n $TOKEN ]] || { echo "Token file $TOKEN_FILE is empty" >&2; exit 1; }

body=$(printf '{"entity_id":"%s","rgb_color":[%d,%d,%d]}' "$ENTITY" "$R" "$G" "$B")
start=$(date +%s%N)
code=$(printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" |
  curl -sS -o /dev/null -w '%{http_code}' -K - --max-time 10 \
    -H 'Content-Type: application/json' -d "$body" "$URL/api/services/light/turn_on") || code=000
end=$(date +%s%N)
unset TOKEN

if [[ $code != 200 ]]; then
  echo "Home Assistant answered HTTP $code (000 = no connection; 401 = bad token; 400/404 = unknown light)" >&2
  exit 1
fi
ms=$(( (end - start) / 1000000 ))
echo "$ENTITY -> rgb($R,$G,$B): ${ms} ms until Home Assistant confirmed"
