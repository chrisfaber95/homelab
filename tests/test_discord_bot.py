"""The Discord bot (H-016) against a fake Discord layer: no discord.py, no network. The fake `Ctx` records what the
bot sends; the fake API records what it asked the inbox and search to do."""
import asyncio
import os
import re
import sys
import tempfile
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "discord-bot"))
sys.path.insert(0, os.path.join(ROOT, "bin"))

import bot_core as core  # noqa: E402
from bot_scrub import REMOVED, SECRET_RE  # noqa: E402

OWNER = 4242
FAKE_TOKEN = "ghp_" + "a" * 30


class FakeCtx(core.Ctx):
    def __init__(self, user=OWNER, dm=True):
        self.user_id, self.is_dm, self.where = user, dm, "a DM" if dm else "channel 9"
        self.sent, self.worked = [], 0

    async def working(self):
        self.worked += 1

    async def send(self, text, buttons=None, file=None):
        self.sent.append({"text": text, "buttons": buttons or [], "file": file})

    @property
    def texts(self):
        return "\n".join(s["text"] for s in self.sent)


class FakeApi:
    """Answers like the inbox door; `replies` maps an action to (status, body)."""
    def __init__(self):
        self.calls, self.replies, self.entries_data, self.appended = [], {}, [], []
        self.search = (200, {"found": True, "answer": "It is on port 8105.", "citations": [
            {"n": 1, "path": "homelab/docs/inbox.md", "location": "Ports", "link": "https://x/y", "cited": True}]})

    def action(self, action, **kw):
        self.calls.append((action, kw))
        return self.replies.get(action, (200, {"task": 7, "url": "http://h/task/7", "tasks": [], "jobs": [],
                                               "briefs": [], "job": kw.get("job")}))

    def ask(self, q):
        self.calls.append(("ask", q))
        return self.search

    def append(self, kind, text, ref=None):
        self.appended.append((kind, text))
        return 200, {}

    def entries(self, after, limit=200):
        es = [e for e in self.entries_data if e["id"] > after][:limit]
        return 200, {"entries": es, "next": es[-1]["id"] if es else after}

    def did(self, name):
        return [kw for a, kw in self.calls if a == name]


def entry(i, text="hello", kind="notification", client="inbox", link=None, ref=None):
    return {"id": i, "time": "2026-11-14T10:00:00+00:00", "author": "box", "client": client, "kind": kind,
            "text": text, "link": link, "ref": ref}


class BotTest(unittest.IsolatedAsyncioTestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.api = FakeApi()
        self.cursor = core.Cursor(os.path.join(self.tmp.name, "cursor.json"))
        self.now = 1000.0
        self.bot = core.Bot(self.api, OWNER, self.cursor, clock=lambda: self.now)


class OwnerOnly(BotTest):
    async def test_other_account_and_other_place_do_nothing_and_are_logged(self):
        for ctx in (FakeCtx(user=1), FakeCtx(dm=False), FakeCtx(user=1, dm=False)):
            with self.assertLogs("discord-bot", "WARNING") as logs:
                await self.bot.on_message(ctx, "what is the port?")
                await self.bot.on_message(ctx, "!task auto do it")
                await self.bot.on_command(ctx, "status")
                await self.bot.on_button(ctx, "ab:H-016")
            self.assertEqual(ctx.sent, [])
            self.assertEqual(len(logs.records), 4)
        self.assertEqual(self.api.calls, [])
        self.assertEqual(self.api.appended, [])

    async def test_log_has_who_and_where_but_not_the_content(self):
        with self.assertLogs("discord-bot", "WARNING") as logs:
            await self.bot.on_message(FakeCtx(user=77, dm=False), "secret plan")
        self.assertIn("77", logs.output[0])
        self.assertNotIn("secret plan", logs.output[0])


class Questions(BotTest):
    async def test_plain_message_is_answered_from_search_with_citation_and_task_button(self):
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "which port does the inbox use?")
        self.assertIn(("ask", "which port does the inbox use?"), self.api.calls)
        self.assertIn("It is on port 8105.", ctx.texts)
        self.assertIn("[1] homelab/docs/inbox.md (Ports) <https://x/y>", ctx.texts)
        self.assertEqual([b.label for b in ctx.sent[-1]["buttons"]], ["Make this a task"])
        self.assertEqual(self.api.did("task"), [])  # a question never starts a task by itself
        self.assertEqual(ctx.worked, 1)

    async def test_nothing_found_says_so_and_still_offers_the_task_button(self):
        self.api.search = (200, {"found": False, "answer": "x", "citations": []})
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "unknown thing?")
        self.assertIn("Nothing relevant found", ctx.texts)
        self.assertEqual(len(ctx.sent[-1]["buttons"]), 1)

    async def test_search_down_says_so(self):
        self.api.search = (0, {"error": "not reachable"})
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "hello?")
        self.assertIn("could not answer", ctx.texts)

    async def test_button_turns_the_message_into_an_auto_task_once(self):
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "please fix the thing")
        cid = ctx.sent[-1]["buttons"][0].cid
        press = FakeCtx()
        await self.bot.on_button(press, cid)
        self.assertEqual(self.api.did("task"), [{"repo": "auto", "text": "please fix the thing"}])
        self.assertIn("Task #7", press.texts)
        again = FakeCtx()
        await self.bot.on_button(again, cid)
        self.assertEqual(len(self.api.did("task")), 1)
        self.assertIn("expired", again.texts)

    async def test_task_button_expires(self):
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "later")
        self.now += core.TASK_BUTTON_TTL + 1
        press = FakeCtx()
        await self.bot.on_button(press, ctx.sent[-1]["buttons"][0].cid)
        self.assertEqual(self.api.did("task"), [])

    async def test_answer_is_scrubbed(self):
        self.api.search = (200, {"found": True, "answer": f"the key is {FAKE_TOKEN}", "citations": []})
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "key?")
        self.assertNotIn(FAKE_TOKEN, ctx.texts)
        self.assertIn(REMOVED, ctx.texts)

    async def test_approve_in_free_text_starts_nothing(self):
        ctx = FakeCtx()
        for text in ("approve H-016", "Approve & build H-016", "yes, build it without me"):
            await self.bot.on_message(ctx, text)
        self.assertEqual(self.api.did("approve") + self.api.did("confirm") + self.api.did("task"), [])


class Commands(BotTest):
    async def test_task_command(self):
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "!task homelab add a thing")
        self.assertEqual(self.api.did("task"), [{"repo": "homelab", "text": "add a thing"}])
        self.assertIn("http://h/task/7", ctx.texts)
        ctx = FakeCtx()
        await self.bot.on_command(ctx, "task", "auto")
        self.assertIn("Usage", ctx.texts)

    async def test_daily_cap_is_told_clearly(self):
        self.api.replies["task"] = (429, {"error": "daily limit reached: 10 task(s) per day may start from Discord; "
                                                   "nothing was started"})
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "!task auto more")
        self.assertIn("Daily limit reached", ctx.texts)
        self.assertIn("nothing was started", ctx.texts)

    async def test_status_jobs_run(self):
        self.api.replies["state"] = (200, {"tasks": [{"id": 3, "status": "running", "repo": "homelab", "what": "x",
                                                      "source": "page", "url": "http://h/task/3"}]})
        self.api.replies["jobs"] = (200, {"jobs": [{"name": "nightly", "kind": "claude", "when": "03:00",
                                                    "paused": False, "error": None}]})
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "!status")
        await self.bot.on_message(ctx, "!jobs")
        await self.bot.on_message(ctx, "!run nightly")
        self.assertIn("#3 running [homelab] x <http://h/task/3>", ctx.texts)
        self.assertIn("nightly (claude, 03:00)", ctx.texts)
        self.assertEqual(self.api.did("run_job"), [{"job": "nightly"}])

    async def test_inbox_down(self):
        self.api.replies["state"] = (0, {"error": "x"})
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "!status")
        self.assertIn("not reachable", ctx.texts)

    async def test_reply_to_a_question_continues_the_task(self):
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "use postgres", reply_to_text="**Question**\nWhich db?\n(task 12) reply")
        self.assertEqual(self.api.did("answer"), [{"task": 12, "text": "use postgres"}])
        self.assertEqual([c for c in self.api.calls if c[0] == "ask"], [])  # not a search question

    async def test_reply_to_a_task_that_is_not_waiting(self):
        self.api.replies["answer"] = (409, {"error": "x"})
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "late", reply_to_text="(task 5)")
        self.assertIn("not waiting", ctx.texts)


class Approvals(BotTest):
    def setUp(self):
        super().setUp()
        self.api.replies["approve"] = (200, {"confirm": "tok123", "brief": "H-016", "mode": "build",
                                             "title": "Chat", "expires_in": 300})
        self.api.replies["confirm"] = (200, {"task": 9, "url": "http://h/task/9"})

    async def test_command_needs_confirm_and_confirm_starts_the_build(self):
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "!approve H-016 without-me")
        self.assertEqual(self.api.did("approve"), [{"brief": "H-016", "mode": "without-me"}])
        self.assertEqual(self.api.did("confirm"), [])
        self.assertIn("5 min", ctx.texts)
        press = FakeCtx()
        await self.bot.on_button(press, ctx.sent[-1]["buttons"][0].cid)
        self.assertEqual(self.api.did("confirm"), [{"confirm": "tok123"}])
        self.assertIn("build #9", press.texts)

    async def test_confirm_expires_and_is_single_use(self):
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "!approve H-016")
        cid = ctx.sent[-1]["buttons"][0].cid
        self.now += 301
        late = FakeCtx()
        await self.bot.on_button(late, cid)
        self.assertEqual(self.api.did("confirm"), [])
        self.assertIn("expired", late.texts)
        await self.bot.on_message(ctx, "!approve H-016")
        cid = ctx.sent[-1]["buttons"][0].cid
        await self.bot.on_button(FakeCtx(), cid)
        await self.bot.on_button(FakeCtx(), cid)
        self.assertEqual(len(self.api.did("confirm")), 1)

    async def test_drafts_have_both_buttons_and_each_asks_for_confirm(self):
        self.api.replies["drafts"] = (200, {"briefs": [{"id": "H-016", "title": "Chat"}]})
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "!drafts")
        labels = [b.label for b in ctx.sent[0]["buttons"]]
        self.assertEqual(labels, ["Approve & build", "Approve & build without me"])
        await self.bot.on_button(FakeCtx(), ctx.sent[0]["buttons"][1].cid)
        self.assertEqual(self.api.did("approve"), [{"brief": "H-016", "mode": "without-me"}])
        self.assertEqual(self.api.did("confirm"), [])

    async def test_approvals_off_is_told(self):
        self.api.replies["approve"] = (403, {"error": "refused: approvals from Discord are off"})
        ctx = FakeCtx()
        await self.bot.on_button(ctx, "ab:H-016")
        self.assertIn("switched off", ctx.texts)
        self.assertEqual(self.api.did("confirm"), [])

    async def test_cap_at_confirm_is_told(self):
        self.api.replies["confirm"] = (409, {"error": "daily limit reached: 10 task(s)"})
        ctx = FakeCtx()
        await self.bot.on_message(ctx, "!approve H-016")
        press = FakeCtx()
        await self.bot.on_button(press, ctx.sent[-1]["buttons"][0].cid)
        self.assertIn("daily limit reached", press.texts)


class Splitting(unittest.TestCase):
    def test_parts_are_complete_and_within_the_limit(self):
        text = "\n\n".join(f"paragraph {i} " + "word " * 120 for i in range(12))
        parts = core.split_text(text)
        self.assertTrue(all(len(p) <= core.LIMIT for p in parts))
        self.assertEqual("".join(parts), text)
        long_word = "x" * 5000
        self.assertEqual("".join(core.split_text(long_word)), long_word)


class Relay(BotTest):
    def setUp(self):
        super().setUp()
        self.out = []

        async def send(text, file=None):
            self.out.append((text, file))
        self.send = send

    async def test_first_start_skips_history_then_new_entries_arrive_once(self):
        self.api.entries_data = [entry(1), entry(2)]
        await self.bot.relay_once(self.send)
        self.assertEqual(self.out, [])
        self.assertEqual(self.cursor.load(), 2)
        self.api.entries_data += [entry(3, "build done", link="http://h/task/3"), entry(4, "second")]
        await self.bot.relay_once(self.send)
        await self.bot.relay_once(self.send)
        self.assertEqual(len(self.out), 2)
        self.assertIn("build done", self.out[0][0])
        self.assertIn("<http://h/task/3>", self.out[0][0])
        self.assertEqual(self.cursor.load(), 4)

    async def test_entries_written_while_the_bot_was_down_arrive_after_restart(self):
        self.cursor.save(5)
        self.api.entries_data = [entry(i, f"n{i}") for i in range(4, 9)]
        bot2 = core.Bot(self.api, OWNER, core.Cursor(self.cursor.path))
        await bot2.relay_once(self.send)
        self.assertEqual([t.split("\n")[1] for t, _ in self.out], ["n6", "n7", "n8"])

    async def test_failed_delivery_is_retried_without_duplicates(self):
        self.cursor.save(0)
        self.api.entries_data = [entry(1, "a"), entry(2, "b")]
        fail = {"on": True}

        async def flaky(text, file=None):
            if fail["on"] and "b" == text.split("\n")[1]:
                raise RuntimeError("discord down")
            self.out.append(text)
        with self.assertLogs("discord-bot", "WARNING"):
            await self.bot.relay_once(flaky)
        self.assertEqual(self.cursor.load(), 1)
        fail["on"] = False
        await self.bot.relay_once(flaky)
        self.assertEqual([t.split("\n")[1] for t in self.out], ["a", "b"])

    async def test_long_entry_is_split_complete_and_very_long_is_an_attachment(self):
        self.cursor.save(0)
        body = "\n".join(f"line {i} " + "x" * 50 for i in range(60))  # ~3.5k characters
        self.api.entries_data = [entry(1, body, link="http://h/task/1")]
        await self.bot.relay_once(self.send)
        self.assertGreater(len(self.out), 1)
        joined = "".join(t for t, _ in self.out)
        self.assertIn(body, joined)
        self.assertTrue(self.out[-1][0].rstrip().endswith("<http://h/task/1>"))
        self.out.clear()
        huge = "y" * 30000
        self.api.entries_data.append(entry(2, huge, link="http://h/task/2"))
        await self.bot.relay_once(self.send)
        (text, file), = self.out
        self.assertEqual(file[1].decode(), huge)
        self.assertIn("<http://h/task/2>", text)
        self.assertLessEqual(len(text), core.LIMIT)

    async def test_questions_carry_the_reply_marker_and_own_echoes_are_skipped(self):
        self.cursor.save(0)
        self.api.entries_data = [entry(1, "Which db?", kind="question", client="inbox", ref="task:12"),
                                 entry(2, "[auto] x", kind="message", client="discord", ref="task:13"),
                                 entry(3, "Approved", kind="approval", client="discord"),
                                 entry(4, "from the page", kind="message", client="page")]
        await self.bot.relay_once(self.send)
        self.assertEqual(len(self.out), 2)
        self.assertIsNotNone(core.TASK_MARK.search(self.out[0][0]))
        self.assertEqual(core.TASK_MARK.search(self.out[0][0]).group(1), "12")

    async def test_relayed_text_is_scrubbed(self):
        self.cursor.save(0)
        self.api.entries_data = [entry(1, f"leaked {FAKE_TOKEN}")]
        await self.bot.relay_once(self.send)
        self.assertNotIn(FAKE_TOKEN, self.out[0][0])

    async def test_switched_off_inbox_is_waited_out(self):
        self.cursor.save(0)
        self.api.entries = lambda after, limit=200: (403, {"error": "refused: Discord is switched off"})
        with self.assertLogs("discord-bot", "WARNING"):
            await self.bot.relay_once(self.send)
        await self.bot.relay_once(self.send)  # second time: no second log line, no crash
        self.assertEqual(self.out, [])


class Filter(unittest.TestCase):
    def test_same_pattern_as_the_inbox(self):
        from homelab_inbox import config
        self.assertEqual(SECRET_RE.pattern, config.SECRET_RE.pattern)

    def test_token_never_in_a_log_line(self):
        import importlib.util
        if importlib.util.find_spec("discord") is None:
            self.skipTest("discord.py is not installed here (it lives in the bot's venv)")
        import logging
        import bot_run as run
        rec = logging.LogRecord("x", logging.INFO, "", 0, "login with MY.DISCORD.TOKEN ok", None, None)
        run.TokenFilter(["MY.DISCORD.TOKEN"]).filter(rec)
        self.assertNotIn("MY.DISCORD.TOKEN", rec.getMessage())


class NoListener(unittest.TestCase):
    def test_no_server_code(self):
        d = os.path.join(ROOT, "discord-bot")
        for f in os.listdir(d):
            if f.endswith(".py"):
                with open(os.path.join(d, f)) as fh:
                    src = fh.read()
                self.assertIsNone(re.search(r"\.listen\(|\.bind\(|HTTPServer|TCPSite|run_app|start_server|web\.Application",
                                            src), f)


if __name__ == "__main__":
    unittest.main()
