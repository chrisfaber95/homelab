"""The bot's behaviour, without any Discord import: the owner-only check, questions, commands, buttons, the confirm
step and the relay of the conversation. bot_run.py connects it to discord.py; the tests connect it to a fake.

A `ctx` (see Ctx) is one incoming event: who sent it, whether it was in the bot's one place (the DM, or the configured
server channel), and how to answer it. The bot keeps no
history: only a cursor file (the last conversation entry delivered) and a few short-lived buttons in memory."""
import asyncio
import json
import logging
import os
import re
import secrets
import time

from bot_scrub import scrub

log = logging.getLogger("discord-bot")
LIMIT = 2000        # characters in one Discord message
MAX_PARTS = 5       # a text needing more messages than this goes as an attachment instead
TASK_BUTTON_TTL = 3600
TASK_MARK = re.compile(r"\(task (\d+)\)")
HELP = ("Send a message to ask a question: the answer comes from your private search, with sources.\n"
        "Commands: `!task <repo|auto> <text>`, `!status`, `!jobs`, `!run <job>`, `!drafts`, "
        "`!research [quick|deep] <question>` (a report with sources, one Claude run), `!approve <brief> [without-me]` (also as slash commands). Reply to a question from Claude to answer it. "
        "Approving always asks for a confirm button; a message like \"approve H-016\" does nothing.")


def in_place(home_channel_id, channel_id, is_dm):
    """Is this the bot's one place? Without a configured channel that is the DM with the owner; with one
    (DISCORD_CHANNEL_ID) it is that server channel and nothing else, so the DM is then ignored too."""
    return channel_id == home_channel_id if home_channel_id else bool(is_dm)


class Ctx:
    """One incoming event. user_id, in_place and where are set by the Discord layer. `working()` shows that a slow answer
    is coming (defer or typing); `send()` delivers text, optional buttons and an optional (filename, bytes) file."""
    user_id = None
    in_place = False
    where = "?"

    async def working(self): ...
    async def send(self, text, buttons=None, file=None): ...


class Button:
    def __init__(self, label, cid, style="primary"):
        self.label, self.cid, self.style = label, cid, style


def split_text(text, limit=LIMIT):
    """Cut at a paragraph, line or space where possible, else hard. Nothing is dropped: the parts join into the text."""
    parts = []
    while len(text) > limit:
        cut = max((text.rfind(sep, 0, limit) + len(sep) for sep in ("\n\n", "\n", " ") if text.rfind(sep, 0, limit) > 0),
                  default=0)
        cut = cut if cut > limit // 2 else limit
        parts.append(text[:cut])
        text = text[cut:]
    return parts + [text] if text or not parts else parts


async def deliver(ctx, text, buttons=None, title=None):
    """Send text complete: one message, several, or (over MAX_PARTS messages) a short message plus an attachment."""
    text = scrub(text)
    parts = split_text(text)
    if len(parts) <= MAX_PARTS:
        for i, part in enumerate(parts):
            await ctx.send(part, buttons if i == len(parts) - 1 else None)
        return
    head = scrub(title or "")[:LIMIT - 100]
    await ctx.send(f"{head}\nThe full text ({len(text)} characters) is attached.".strip(), buttons,
                   ("message.txt", text.encode()))


def failure(status, body):
    """What to tell Chris when the inbox refused or failed an action."""
    err = str((body or {}).get("error") or f"HTTP {status}")
    if status == 0:
        return "The inbox is not reachable right now. Nothing was done."
    if status == 429:
        return f"Daily limit reached. {err}"
    if status == 403 and "approvals from Discord are off" in err:
        return "Approvals from Discord are switched off (homelab page, section Discord). Nothing was approved."
    if status == 403 and "switched off" in err:
        return "Discord is switched off on the homelab page. Nothing was done."
    if status == 403:
        return f"Not allowed: {err.removeprefix('refused: ')}"
    if status == 410:
        return f"{err}"
    return f"That did not work ({status}): {err}"


class Cursor:
    """The id of the last conversation entry delivered, in a small file. Written after every delivered entry."""
    def __init__(self, path):
        self.path = path

    def load(self):
        try:
            with open(self.path) as f:
                return int(json.load(f)["cursor"])
        except (OSError, ValueError, KeyError, TypeError):
            return None

    def save(self, n):
        tmp = f"{self.path}.tmp"
        with open(tmp, "w") as f:
            json.dump({"cursor": int(n)}, f)
        os.replace(tmp, self.path)


class Bot:
    def __init__(self, api, owner_id, cursor, clock=time.monotonic):
        self.api, self.owner_id, self.cursor, self.clock = api, int(owner_id), cursor, clock
        self.pending = {}   # button key -> (kind, data, expires); lost on a restart, the button then says so
        self._reported_off = False

    async def call(self, fn, *a, **kw):
        return await asyncio.to_thread(fn, *a, **kw)

    def guard(self, ctx, what):
        """Owner and place (the DM, or the one configured server channel), checked on every message, command and
        button. Anything else does nothing and is logged
        (who and where, never the content)."""
        if ctx.user_id == self.owner_id and ctx.in_place:
            return True
        log.warning("ignored %s from user %s in %s", what, ctx.user_id, ctx.where)
        return False

    def _hold(self, kind, data, ttl):
        key = secrets.token_hex(6)
        now = self.clock()
        self.pending = {k: v for k, v in self.pending.items() if v[2] > now}
        self.pending[key] = (kind, data, now + ttl)
        return f"{kind}:{key}"

    def _take(self, cid):
        kind, _, key = cid.partition(":")
        item = self.pending.pop(key, None)
        return item[1] if item and item[0] == kind and item[2] > self.clock() else None

    # --- messages -----------------------------------------------------------------------------------------------
    async def on_message(self, ctx, text, reply_to_text=None):
        if not self.guard(ctx, "message"):
            return
        text = (text or "").strip()
        if not text:
            return
        if text.startswith("!"):
            name, _, args = text[1:].partition(" ")
            return await self._command(ctx, name.lower(), args.strip())
        if reply_to_text and (m := TASK_MARK.search(reply_to_text)):
            return await self._answer(ctx, int(m.group(1)), text)
        await self._question(ctx, text)

    async def _question(self, ctx, text):
        """A plain message is a question for the private search. Nothing decides it is a task: that is the button."""
        await ctx.working()
        await self.call(self.api.append, "question", text)  # for the thread; best effort, the answer matters more
        status, body = await self.call(self.api.ask, text)
        if status == 200 and body.get("found"):
            cites = "\n".join(f"[{c['n']}] {c['path']} ({c['location']})" + (f" <{c['link']}>" if c.get("link") else "")
                              for c in body.get("citations", []))
            out = f"{body['answer']}\n\n**Sources**\n{cites}" if cites else body["answer"]
        elif status == 200:
            out = "Nothing relevant found in your private search for that question."
        else:
            out = f"The search could not answer: {(body or {}).get('error', status)}."
        button = Button("Make this a task", self._hold("mk", text, TASK_BUTTON_TTL))
        await deliver(ctx, out, [button], title="Answer")

    async def _answer(self, ctx, task, text):
        await ctx.working()
        status, body = await self.call(self.api.action, "answer", task=task, text=text)
        if status == 200:
            return await ctx.send(f"Sent as your answer to task {task}: {body['url']}")
        await ctx.send(failure(status, body) if status != 409 else f"Task {task} is not waiting for an answer.")

    # --- commands -----------------------------------------------------------------------------------------------
    async def on_command(self, ctx, name, args=""):
        if self.guard(ctx, f"command {name}"):
            await self._command(ctx, name, args)

    async def _command(self, ctx, name, args):
        if name == "help":
            return await ctx.send(HELP)
        await ctx.working()
        if name == "task":
            repo, _, text = args.partition(" ")
            if not repo or not text.strip():
                return await ctx.send("Usage: `!task <repo|auto> <text>`")
            status, body = await self.call(self.api.action, "task", repo=repo, text=text.strip())
            return await ctx.send(f"Task #{body['task']} started on {repo}: {body['url']}" if status == 200
                                  else failure(status, body))
        if name == "status":
            status, body = await self.call(self.api.action, "state")
            if status != 200:
                return await ctx.send(failure(status, body))
            rows = [f"#{t['id']} {t['status']} [{t['repo']}] {t['what']} <{t['url']}>" for t in body["tasks"]]
            return await deliver(ctx, "\n".join(rows) or "Nothing is running or queued.")
        if name == "jobs":
            status, body = await self.call(self.api.action, "jobs")
            if status != 200:
                return await ctx.send(failure(status, body))
            rows = [f"{j['name']} ({j['kind']}, {j['when'] or 'manual'})" + (" paused" if j["paused"] else "")
                    + (f" ERROR {j['error']}" if j["error"] else "") for j in body["jobs"]]
            return await deliver(ctx, "\n".join(rows) or "No jobs.")
        if name == "run":
            if not args:
                return await ctx.send("Usage: `!run <job>`")
            status, body = await self.call(self.api.action, "run_job", job=args)
            return await ctx.send(f"Job {body['job']} started." if status == 200 else failure(status, body))
        if name == "research":
            mode, _, rest = args.partition(" ")
            mode, question = (mode.lower(), rest.strip()) if mode.lower() in ("quick", "deep") else ("quick", args)
            if not question:
                return await ctx.send("Usage: `!research [quick|deep] <question>` (quick is the default)")
            status, body = await self.call(self.api.action, "research", question=question, mode=mode)
            if status != 200:
                return await ctx.send(failure(status, body))
            return await ctx.send(f"Research started ({body['mode']}). The report comes back here when it is done "
                                  f"(quick takes a few minutes): <{body['url']}>")
        if name == "drafts":
            status, body = await self.call(self.api.action, "drafts")
            if status != 200:
                return await ctx.send(failure(status, body))
            for b in body["briefs"]:
                await ctx.send(f"{b['id']}: {b['title']}", [Button("Approve & build", f"ab:{b['id']}"),
                                                            Button("Approve & build without me", f"as:{b['id']}")])
            if not body["briefs"]:
                await ctx.send("No draft briefs.")
            return
        if name == "approve":
            bid, _, rest = args.partition(" ")
            if not bid or rest.strip() not in ("", "without-me"):
                return await ctx.send("Usage: `!approve <brief> [without-me]`")
            return await self._approve(ctx, bid, "without-me" if rest.strip() else "build")
        await ctx.send(f"Unknown command `{name}`. {HELP}")

    async def _approve(self, ctx, bid, mode):
        """Step one: ask the inbox for a confirmation. Nothing is approved until the Confirm button is pressed."""
        status, body = await self.call(self.api.action, "approve", brief=bid, mode=mode)
        if status != 200:
            return await ctx.send(failure(status, body))
        what = "Approve & build without me" if mode == "without-me" else "Approve & build"
        mins = max(1, body["expires_in"] // 60)
        cid = self._hold("cf", {"token": body["confirm"], "brief": bid, "what": what}, body["expires_in"])
        await ctx.send(f"{what}: {bid} ({body['title']}). Press Confirm within {mins} min to start it; "
                       "after that this expires and nothing starts.", [Button("Confirm", cid, "danger")])

    # --- buttons ------------------------------------------------------------------------------------------------
    async def on_button(self, ctx, cid):
        if not self.guard(ctx, f"button {cid.partition(':')[0]}"):
            return
        await ctx.working()
        kind, _, rest = cid.partition(":")
        if kind in ("ab", "as"):
            return await self._approve(ctx, rest, "build" if kind == "ab" else "without-me")
        if kind not in ("mk", "cf"):
            return await ctx.send("That button is not known any more.")
        data = self._take(cid)
        if data is None:
            return await ctx.send("This button has expired. " + (
                "Nothing was approved; ask again." if kind == "cf" else "Send the message again to get a new one."))
        if kind == "mk":
            status, body = await self.call(self.api.action, "task", repo="auto", text=data)
            if status != 200:
                self.pending[cid.partition(":")[2]] = ("mk", data, self.clock() + TASK_BUTTON_TTL)
                return await ctx.send(failure(status, body))
            return await ctx.send(f"Task #{body['task']} started on auto: {body['url']}")
        status, body = await self.call(self.api.action, "confirm", confirm=data["token"])
        if status != 200:
            return await ctx.send(failure(status, body))
        await ctx.send(f"{data['what']} confirmed for {data['brief']}: build #{body['task']} started: {body['url']}")

    # --- relay --------------------------------------------------------------------------------------------------
    @staticmethod
    def own_echo(e):
        """Entries the bot's own commands wrote: Chris just saw the answer to those where he typed them."""
        return e["client"] == "discord" and e["kind"] in ("message", "question", "approval", "approval_request")

    @staticmethod
    def render(e):
        head = f"**{e['kind'].replace('_', ' ').capitalize()}** · {e['time'][:16].replace('T', ' ')} UTC"
        foot = ["<" + e["link"] + ">"] if e.get("link") else []
        m = re.fullmatch(r"task:(\d+)", e.get("ref") or "")
        if m and e["kind"] in ("question", "answer"):
            foot.append(f"(task {m.group(1)}) reply to this message to answer")
        return head, e["text"], "\n".join(foot)

    async def relay_once(self, send):
        """Deliver conversation entries newer than the cursor through `send(ctx-like)`. The cursor moves only after an
        entry is delivered, so a failure retries it and a restart skips what already arrived. With no cursor yet
        (first start) it skips to the end: the history is not replayed."""
        cur = await self.call(self.cursor.load)
        if cur is None:
            cur = 0
            while True:
                status, body = await self.call(self.api.entries, cur, 1000)
                if status != 200 or not body["entries"]:
                    break
                cur = body["next"]
            if status == 200:
                await self.call(self.cursor.save, cur)
                log.info("first start: cursor at %s", cur)
            return
        while True:
            status, body = await self.call(self.api.entries, cur, 100)
            if status != 200:
                if not self._reported_off:
                    log.warning("conversation not readable (%s): %s", status, (body or {}).get("error"))
                self._reported_off = True
                return
            self._reported_off = False
            for e in body["entries"]:
                if not self.own_echo(e):
                    head, text, foot = self.render(e)
                    try:
                        await deliver_entry(send, head, text, foot)
                    except Exception as ex:  # noqa: BLE001
                        log.warning("could not deliver entry %s (%s); retrying", e["id"], type(ex).__name__)
                        return
                cur = e["id"]
                await self.call(self.cursor.save, cur)
            if len(body["entries"]) < 100:
                return


class _Out:
    """Adapts a plain `send(text, file)` coroutine to what deliver() needs from a ctx."""
    def __init__(self, send):
        self._send = send

    async def send(self, text, buttons=None, file=None):
        await self._send(text, file)


async def deliver_entry(send, head, text, foot):
    """The full text with its link: split when long, an attachment when very long. The link and the reply hint go in
    the last message (or the short message beside the attachment)."""
    whole = scrub("\n".join(p for p in (head, text, foot) if p))
    out = _Out(send)
    parts = split_text(whole)
    if len(parts) <= MAX_PARTS:
        for part in parts:
            await out.send(part)
    else:
        await out.send(scrub("\n".join(p for p in (head, f"The full text ({len(text)} characters) is attached.", foot)
                                       if p)), file=("message.txt", scrub(text).encode()))
