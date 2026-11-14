# The Discord bot (H-016)

A small separate service that lets Chris read and write the conversation (`docs/conversation.md`) from a Discord DM, or
from one channel in his own server ("In your own server instead of the DM" below).
Code: `discord-bot/` (`bot_core.py` the behaviour, no Discord import; `bot_run.py` the discord.py glue; `bot_client.py` the loopback
calls; `bot_scrub.py` the secret filter), installer `scripts/26-discord-bot.sh`, unit `systemd/homelab-discord-bot.service`.
Tests: `tests/test_discord_bot.py` (a fake Discord layer, no network).

## Shape

- Own account `discord-bot`, own venv in `/usr/local/lib/homelab-discord-bot/venv`. discord.py is installed only from
  `discord-bot/requirements.txt` (pinned, with hashes, `pip --require-hashes --only-binary :all:`). To update: change the
  pin in a scratch venv with `pip-compile --generate-hashes --strip-extras` and replace the file.
- It only connects outward (Discord, and the inbox and search API on loopback). No listening port, no ufw rule;
  `bin/homelab-exposure` checks that the code has no server and the script opens no port.
- It keeps no history. The conversation lives in the inbox. The bot's only state is `cursor.json` in its state directory
  (`/var/lib/homelab-discord-bot`): the id of the last entry delivered to the DM. Entries written while the bot was down
  arrive once it is back, each once (the cursor moves after each delivery; at worst a crash between sending and saving
  repeats one). On the very first start the history is not replayed.
- Its permissions and daily cap are the `discord` client in `config/clients.json`; the switches are on the homelab page.

## Rules the bot follows

- **Owner and one place only**, checked on every message, command and button press: the sender must be the Discord id in
  `DISCORD_OWNER_ID`, in the bot's place. That place is the DM, or, when `DISCORD_CHANNEL_ID` is set, that one server
  channel and then not the DM. Anything else does nothing and is logged (who and where, never the text).
- **A plain message is a question**: answered from the private search (`/v1/ask`) with sources, or "Nothing relevant
  found". Under it is a **Make this a task** button (a task on `auto`). Nothing decides by itself that text is a task. The
  question is also appended to the conversation as a `question` entry.
- **Commands** (`!` prefix or slash): `task <repo|auto> <text>`, `status`, `jobs`, `run <job>` (slash: `/runjob`),
  `drafts`, `approve <brief> [without-me]`, `research [quick|deep] <question>` (slash: `/research` with a mode
  choice; quick is the default). It starts one read-only Claude run (counts against the daily cap for Claude jobs); the
  report with its sources arrives later as a relayed conversation entry. A reply to a message from Claude's question continues that task. A message
  like "approve H-016" is a question and starts nothing.
- **Approvals**: `!drafts` shows Approve & build and Approve & build without me as buttons; each (or the command) only asks
  the inbox for a confirmation, then shows a **Confirm** button that expires (`CONFIRM_TTL_SECONDS`, default 5 min). Only
  Confirm approves. With approvals off, the daily cap reached or Discord off, the bot says so plainly.
- **Relay**: every new conversation entry (notifications, questions, answers, approvals, tasks from the page) goes to the DM
  with its full text and link. Over 2000 characters it is split; over five messages it is an attachment. The bot's own
  commands are not echoed. A question carries "(task N)": reply to that message to answer it.
- **Slow answers**: slash commands and buttons are deferred first (Discord wants an acknowledgement in 3 s), plain
  messages show typing; the result follows.
- **What leaves the box**: everything sent to Discord is scrubbed with the memory's secret filter (`bot_scrub.py`; a test fails
  when it differs from `config.SECRET_RE`). Notification and answer text is stored by Discord: a deliberate exception to
  "only over my own network". The token is never logged (a log filter replaces it).
- Not available from Discord: SSH keys, memory edits, merges on `homelab` (`conversation.FORBIDDEN`).
- Buttons live in memory: after a bot restart an old button says it has expired; send the message again.

## Set up

1. Discord Developer Portal (https://discord.com/developers/applications) > New Application > Bot. Copy the bot token. Leave
   the privileged intents off (a DM carries its text without them). Make it a private bot (Bot > Public Bot off).
2. Install the app for yourself: Installation > enable **User Install**, then add it to your account with the install
   link, so you can open a DM with it. Find your own user id: Discord Settings > Advanced > Developer Mode on, right-click
   yourself > Copy User ID.
3. On the box: `sudo bash /var/lib/homelab/repo/scripts/26-discord-bot.sh` once (creates the account, venv,
   `/etc/homelab/chat-discord-token` for the inbox client and a `/etc/homelab/discord-bot.env` template), then:
   - the token: `sudo install -m 600 /dev/null /etc/homelab/discord-bot-token` and put the token in that file (never in a
     repo, never in chat);
   - your id: `DISCORD_OWNER_ID=<id>` in `/etc/homelab/discord-bot.env`;
   - run the script again: it starts the bot.
   A rebuild from the repo brings the bot back: `21-inbox.sh` runs `26-discord-bot.sh`, which recreates the account and the
   venv (from the hashed requirements) and installs the unit; it starts the bot as soon as the token file and the id exist
   (they are in `/etc/homelab`, which is backed up with the box's secrets, never in the repo). Entered once, never again.
   The token is read by systemd (`LoadCredential`) and is never in the repo, the logs or the conversation.
4. Open the DM with the bot and send `!help`.

## In your own server instead of the DM

Chris's choice (2026-11-14), a change to H-016's "DM only". Set `DISCORD_CHANNEL_ID` and the bot lives in that one
channel: it answers the owner there, the relay posts there, the slash commands work there, and the DM is ignored.
Without the setting nothing changes.

**Everyone who can see that channel reads everything the box sends**: notifications, answers, task questions. Only the
owner's messages and button presses do anything, but reading is up to Discord's channel permissions. Use a server with
only yourself in it, or a channel only you can see.

1. Developer Portal > your application > Bot: switch **Message Content Intent** on. In a server channel a plain message
   arrives empty without it, and with `DISCORD_CHANNEL_ID` set the bot does not start until it is on (it asks for the
   intent at login).
2. Add the bot to your server: OAuth2 > URL Generator, scopes `bot` and `applications.commands`, Integration Type Guild
   Install, bot permissions View Channels, Send Messages, Attach Files and Read Message History. Open the URL and pick
   your server. (With Public Bot off the Install Link must be None; only you can use the generated URL.)
3. Copy the channel's id (Developer Mode on, right-click the channel > Copy Channel ID) and add
   `DISCORD_CHANNEL_ID=<id>` to `/etc/homelab/discord-bot.env`.
4. `sudo bash /var/lib/homelab/repo/scripts/26-discord-bot.sh` (restarts the bot). Send `!help` in the channel.

Back to the DM: remove the line and run the script again. Messages in threads of the channel are ignored.

## Switching it off

- Without a restart: the homelab page, section Discord: **Discord** off stops answering and the relay waits; **Approvals from
  Discord** off keeps the rest but answers "approvals are off" on the buttons.
- Stop the service: `homelab-admin service stop homelab-discord-bot --reason "..."`. Stopped for good: remove the token file,
  the unit has `ConditionPathExists` on it. The page, ntfy and the task queue never depend on the bot.

## Exposure

The bot has no listening port and no ufw rule. `bin/homelab-exposure` fails when `discord-bot/` contains server code or
`26-discord-bot.sh` touches ufw. The unit is limited to `AF_INET`/`AF_INET6`/`AF_UNIX`, an empty capability set and a
read-only system.

## F-021's out-of-scope note

F-021's line "sending tasks through chat apps" is in the brief in the private `product` repo, not in this one; H-016 replaces
it there. In this repo, `docs/inbox.md` (section Gesprek) points to the conversation and this page.
