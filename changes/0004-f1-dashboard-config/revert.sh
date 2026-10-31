#!/usr/bin/env bash
# Remove the f1-dashboard env file; the token file is kept (it may hold a real secret)
set -euo pipefail
rm -f /etc/f1-dashboard/env
