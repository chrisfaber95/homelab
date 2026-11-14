"""Connects core.Bot to Discord (discord.py). Only connects outward: no server, no listening port.
Settings come from the environment (systemd: docs/discord.md); the two tokens come from systemd credentials."""
import asyncio
import io
import logging
import os
import sys

import discord
from discord import app_commands

from bot_client import Api
from bot_core import Bot, Cursor, Ctx
from bot_scrub import scrub

log = logging.getLogger("discord-bot")
STYLES = {"primary": discord.ButtonStyle.primary, "danger": discord.ButtonStyle.danger}
NO_MENTIONS = discord.AllowedMentions.none()
RELAY_POLL_SECONDS = 3


def is_dm(channel, guild_id):
    return guild_id is None and getattr(channel, "type", None) == discord.ChannelType.private


class DiscordCtx(Ctx):
    def __init__(self, bot, *, user, channel, message=None, interaction=None):
        self.bot, self.message, self.interaction = bot, message, interaction
        self.user_id = user.id
        guild = (message.guild if message else interaction.guild)
        self.is_dm = is_dm(channel, guild.id if guild else None)
        self.where = "a DM" if self.is_dm else f"channel {getattr(channel, 'id', '?')}"
        self.channel, self.deferred = channel, False

    async def working(self):
        if self.interaction and not self.interaction.response.is_done():
            await self.interaction.response.defer()
            self.deferred = True
        elif self.message:
            await self.channel.typing()  # one typing indicator while the slow part runs

    def view(self, buttons):
        if not buttons:
            return discord.utils.MISSING
        view = discord.ui.View(timeout=3600)
        for b in buttons:
            item = discord.ui.Button(label=b.label, custom_id=b.cid, style=STYLES.get(b.style))

            async def callback(inter, cid=b.cid):
                await self.bot.on_button(DiscordCtx(self.bot, user=inter.user, channel=inter.channel,
                                                    interaction=inter), cid)
            item.callback = callback
            view.add_item(item)
        return view

    async def send(self, text, buttons=None, file=None):
        kw = dict(content=text or None, view=self.view(buttons), allowed_mentions=NO_MENTIONS)
        if file:
            kw["file"] = discord.File(io.BytesIO(file[1]), filename=file[0])
        if self.interaction is None:
            return await self.channel.send(**kw)
        if self.interaction.response.is_done():
            return await self.interaction.followup.send(**kw)
        await self.interaction.response.send_message(**kw)


class DiscordBot(discord.Client):
    def __init__(self, bot, owner_id):
        super().__init__(intents=discord.Intents.default())  # DMs carry their text without the message-content intent
        self.core, self.owner_id, self.tree = bot, owner_id, app_commands.CommandTree(self)
        self.dm = None
        self._commands()

    def _commands(self):
        def command(name, description):
            def wrap(fn):
                fn = app_commands.allowed_contexts(guilds=False, dms=True, private_channels=False)(fn)
                return self.tree.command(name=name, description=description)(fn)
            return wrap

        async def run(inter, name, args=""):
            ctx = DiscordCtx(self.core, user=inter.user, channel=inter.channel, interaction=inter)
            await self.core.on_command(ctx, name, args)

        @command("task", "Start a task on a repo or auto")
        async def task(inter: discord.Interaction, repo: str, text: str):
            await run(inter, "task", f"{repo} {text}")

        @command("status", "Show what is running and queued")
        async def status(inter: discord.Interaction):
            await run(inter, "status")

        @command("jobs", "List the jobs")
        async def jobs(inter: discord.Interaction):
            await run(inter, "jobs")

        @command("runjob", "Run a job now")
        async def runjob(inter: discord.Interaction, job: str):
            await run(inter, "run", job)

        @command("research", "Ask a research question: a report with sources comes back here")
        @app_commands.describe(question="What to look up", mode="quick (a few minutes) or deep")
        @app_commands.choices(mode=[app_commands.Choice(name="quick", value="quick"),
                                    app_commands.Choice(name="deep", value="deep")])
        async def research(inter: discord.Interaction, question: str, mode: str = "quick"):
            await run(inter, "research", f"{mode} {question}")

        @command("drafts", "Draft briefs, with approve buttons")
        async def drafts(inter: discord.Interaction):
            await run(inter, "drafts")

        @command("approve", "Approve a draft brief (asks for a confirm)")
        async def approve(inter: discord.Interaction, brief: str, without_me: bool = False):
            await run(inter, "approve", f"{brief} without-me" if without_me else brief)

    async def setup_hook(self):
        await self.tree.sync()
        self.loop.create_task(self.relay_loop())

    async def on_message(self, message):
        if message.author.id == self.user.id:
            return
        ctx = DiscordCtx(self.core, user=message.author, channel=message.channel, message=message)
        replied = None
        if message.reference and message.reference.message_id:
            try:
                ref = message.reference.resolved or await message.channel.fetch_message(message.reference.message_id)
                replied = getattr(ref, "content", None)
            except discord.HTTPException:
                pass
        await self.core.on_message(ctx, message.content, replied)

    async def send_dm(self, text, file=None):
        if self.dm is None:
            self.dm = await (await self.fetch_user(self.owner_id)).create_dm()
        kw = {"content": text, "allowed_mentions": NO_MENTIONS}
        if file:
            kw["file"] = discord.File(io.BytesIO(file[1]), filename=file[0])
        await self.dm.send(**kw)

    async def relay_loop(self):
        await self.wait_until_ready()
        while not self.is_closed():
            try:
                await self.core.relay_once(self.send_dm)
            except Exception:  # noqa: BLE001  the loop must survive anything; the next poll retries
                log.exception("relay failed")
            await asyncio.sleep(RELAY_POLL_SECONDS)


class TokenFilter(logging.Filter):
    """Nothing that looks like a secret, and never the bot's own token, reaches the log."""
    def __init__(self, secrets_):
        super().__init__()
        self.secrets = [s for s in secrets_ if s]

    def filter(self, record):
        msg = record.getMessage()
        for s in self.secrets:
            msg = msg.replace(s, "[token]")
        record.msg, record.args = scrub(msg), None
        return True


def credential(name, env):
    path = os.path.join(os.environ.get("CREDENTIALS_DIRECTORY", ""), name)
    path = os.environ.get(env) or path
    with open(path) as f:
        return f.read().strip()


def main():
    token, chat_token = credential("discord-token", "DISCORD_TOKEN_FILE"), credential("chat-token", "CHAT_TOKEN_FILE")
    handler = logging.StreamHandler(sys.stdout)
    handler.addFilter(TokenFilter([token, chat_token]))
    handler.setFormatter(logging.Formatter("%(levelname)s %(name)s: %(message)s"))
    logging.basicConfig(level=logging.INFO, handlers=[handler])
    try:
        owner = int(os.environ["DISCORD_OWNER_ID"])
    except (KeyError, ValueError):
        sys.exit("DISCORD_OWNER_ID is not set (see docs/discord.md)")
    state = os.path.join(os.environ.get("STATE_DIRECTORY", "."), "cursor.json")
    api = Api(os.environ.get("INBOX_URL", "http://127.0.0.1:8105"), chat_token,
              os.environ.get("SEARCH_URL", "http://127.0.0.1:8019"))
    DiscordBot(Bot(api, owner, Cursor(state)), owner).run(token, log_handler=None)


if __name__ == "__main__":
    main()
