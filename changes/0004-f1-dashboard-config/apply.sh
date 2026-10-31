#!/usr/bin/env bash
# f1-dashboard: /etc/f1-dashboard with an env file (F1_SOURCE=replay) and a token file (empty until you add the real one)
set -euo pipefail
install -d -m 700 -o root -g root /etc/f1-dashboard
# Never overwrite: the real F1TV token and later edits stay.
[[ -e /etc/f1-dashboard/env ]] || printf 'F1_SOURCE=replay\n' > /etc/f1-dashboard/env
[[ -e /etc/f1-dashboard/token ]] || : > /etc/f1-dashboard/token
chmod 600 /etc/f1-dashboard/env /etc/f1-dashboard/token
chown root:root /etc/f1-dashboard/env /etc/f1-dashboard/token
test -f /etc/f1-dashboard/token && grep -q '^F1_SOURCE=' /etc/f1-dashboard/env
echo "f1-dashboard config ready (token file size: $(stat -c %s /etc/f1-dashboard/token) bytes)"
