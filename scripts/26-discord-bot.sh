#!/usr/bin/env bash
# homelab: H-016, the Discord bot (docs/discord.md). Account `discord-bot`, code and its own venv in
# /usr/local/lib/homelab-discord-bot (discord.py installed only from discord-bot/requirements.txt, with hashes), the
# inbox token for the bot's client, the unit. The bot only connects outward: no ufw rule, no port.
# Not done here, by hand once: the Discord token in /etc/homelab/discord-bot-token and your Discord id in
# /etc/homelab/discord-bot.env (docs/discord.md). Until both exist the bot stays off; this script starts it when they do.
# Draai als: sudo bash /var/lib/homelab/repo/scripts/26-discord-bot.sh
# homelab-rerun: root discord-bot systemd/homelab-discord-bot.service scripts/26-discord-bot.sh
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
DEST=/usr/local/lib/homelab-discord-bot
if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi

echo "== Account discord-bot"
id discord-bot >/dev/null 2>&1 || useradd -r -M -d /var/lib/homelab-discord-bot -s /usr/sbin/nologin -U discord-bot

echo "== Code and venv in $DEST"
install -d -m 755 "$DEST"
for f in "$REPO"/discord-bot/*.py; do install -m 644 "$f" "$DEST/$(basename "$f")"; done
install -m 644 "$REPO/discord-bot/requirements.txt" "$DEST/requirements.txt"
[[ -x $DEST/venv/bin/python ]] || python3 -m venv "$DEST/venv"
# --require-hashes: every package (and nothing else) from the pinned, hashed list; wheels only, nothing is built as root.
"$DEST/venv/bin/pip" install --quiet --require-hashes --only-binary :all: --no-input -r "$DEST/requirements.txt"

echo "== Tokens and settings in /etc/homelab"
install -d -m 755 /etc/homelab
# The inbox's token for the client `discord` (config/clients.json): the inbox reads it, the bot gets it as a credential.
[[ -s /etc/homelab/chat-discord-token ]] || ( umask 077; openssl rand -hex 32 > /etc/homelab/chat-discord-token )
chown root:inbox /etc/homelab/chat-discord-token
chmod 640 /etc/homelab/chat-discord-token
if [[ ! -e /etc/homelab/discord-bot.env ]]; then
  install -m 600 -o root -g root /dev/null /etc/homelab/discord-bot.env
  printf '# Your own Discord user id (Discord: Settings > Advanced > Developer Mode, then right-click yourself > Copy User ID).\nDISCORD_OWNER_ID=\n# Optional: the id of one channel in your own private server. Set: the bot lives there instead of in the DM (docs/discord.md).\n#DISCORD_CHANNEL_ID=\n' \
    > /etc/homelab/discord-bot.env
fi

echo "== Unit"
install -m 644 "$REPO/systemd/homelab-discord-bot.service" /etc/systemd/system/homelab-discord-bot.service
systemctl daemon-reload
systemctl enable homelab-discord-bot.service

echo "== Check"
"$DEST/venv/bin/python" -c "import discord, sys; print('discord.py', discord.__version__)"
[[ $(stat -c %a:%U /etc/homelab/chat-discord-token) == 640:root ]]
if grep -Eq '^DISCORD_OWNER_ID=[0-9]{5,}$' /etc/homelab/discord-bot.env && [[ -s /etc/homelab/discord-bot-token ]]; then
  systemctl restart homelab-discord-bot.service
  sleep 3
  systemctl is-active --quiet homelab-discord-bot.service
  echo "discord bot running"
else
  echo "Discord bot installed but off: put the token in /etc/homelab/discord-bot-token (mode 600) and your Discord id in"
  echo "/etc/homelab/discord-bot.env, then run this script again (docs/discord.md)."
fi
