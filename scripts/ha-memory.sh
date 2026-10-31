#!/usr/bin/env bash
# homelab: H-008, prints the memory use of the Home Assistant container (docker stats --no-stream) for the
# "Measurements" table in docs/home-assistant.md. Run it when Home Assistant has been idle for a few minutes.
# Usage: bash scripts/ha-memory.sh [container]     (default: homeassistant)
# The container lives in the rootless Docker of `deploy`: run it as a user that can reach that daemon, or set
# DOCKER_HOST (for example unix:///run/user/<deploy uid>/docker.sock). Compare with `nvidia-smi` and `free -h`.
set -euo pipefail

NAME=${1:-homeassistant}

if ! out=$(docker stats --no-stream --format '{{.MemUsage}}|{{.MemPerc}}|{{.CPUPerc}}' "$NAME" 2>&1); then
  echo "Cannot read stats of container '$NAME': $out" >&2
  exit 1
fi
IFS='|' read -r mem perc cpu <<<"$out"
echo "container: $NAME"
echo "memory:    $mem ($perc of the limit shown by Docker)"
echo "cpu:       $cpu"
echo "box:       $(free -h | awk '/^Mem:/ {print $3 " used of " $2 ", " $7 " available"}')"
