#!/usr/bin/env bash
# TEST (F-019): blokkeert SSH via Tailscale; NIET bevestigen, na 5 minuten moet het vanzelf terugdraaien
# Een deny-regel boven de allow-regel: de box ziet zelf geen probleem (de allow-regel staat er nog en SSH luistert),
# dus alleen het uitblijven van jouw bevestiging draait dit terug. SSH via het LAN blijft werken.
# Na de test haalt een volgende PR deze map weg, zodat een herbouw hem niet opnieuw uitvoert.
# (Met een bron erbij: zonder ziet ufw hem als dezelfde regel als de allow voor 22 en slaat hem over.)
set -euo pipefail
ufw prepend deny in on tailscale0 from 100.64.0.0/10 to any port 22 proto tcp comment 'TEST F-019 lockout'
ufw prepend deny in on tailscale0 from fd7a:115c:a1e0::/48 to any port 22 proto tcp comment 'TEST F-019 lockout'
[[ $(ufw status | grep -c 'TEST F-019 lockout') -ge 2 ]]
echo "SSH via tailscale0 geblokkeerd (test)"
