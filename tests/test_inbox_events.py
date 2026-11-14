"""Events in the conversation and the Discord switches (H-016): every notification arrives in full with its link, ntfy
keeps sending when the conversation fails, questions are stored, and the switches work without a restart.
Run: python3 -m unittest discover -s tests"""
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
import uuid
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
HOME = tempfile.mkdtemp()
TOKENS = tempfile.mkdtemp()
os.environ.update(INBOX_RUNNER_HOME=HOME, NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"),
                  INBOX_ALLOW_ALL="1")
CLIENTS = json.dumps({"clients": {
    "discord": {"permissions": ["read", "write", "ask", "answer", "approve"]},
    "jarvis": {"permissions": ["read"]}}})
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import conversation, helpers, pages, queue, server, storage  # noqa: E402

LONG = "A long result sentence. " * 400  # about 9000 characters: far beyond ntfy's 300 and one Discord message


def setUpModule():  # the clients and tokens of this file only: other test files set their own in the same process
    global _env
    _env = mock.patch.dict(os.environ, CLIENTS_JSON=CLIENTS, CHAT_TOKEN_DIR=TOKENS)
    _env.start()


def tearDownModule():
    _env.stop()


def last():
    return conversation.read(0, 1000)[-1]


class Notifications(unittest.TestCase):
    def test_full_text_and_link_are_stored(self):
        helpers.notify("Inbox 5: PR #3", LONG, "https://github.com/x/y/pull/3")
        e = last()
        self.assertEqual((e["author"], e["client"], e["kind"]), ("box", "ntfy", "notification"))
        self.assertEqual(e["link"], "https://github.com/x/y/pull/3")
        self.assertEqual(e["text"], f"Inbox 5: PR #3\n{LONG.strip()}")

    def test_ntfy_gets_the_short_text(self):
        os.environ["NTFY_FAKE"] = "0"
        sent = []
        try:
            with mock.patch.object(helpers.urllib.request, "urlopen", lambda req, timeout=0: (
                    sent.append(json.loads(req.data)), mock.MagicMock())[1]), \
                    mock.patch.object(helpers.NTFY_TOKEN_FILE.__class__, "read_text", return_value="t"):
                helpers.notify("T", LONG, "http://x/1")
        finally:
            os.environ["NTFY_FAKE"] = "1"
        self.assertEqual(len(sent[0]["message"]), 300)
        self.assertEqual(sent[0]["click"], "http://x/1")
        self.assertGreater(len(last()["text"]), 5000)

    def test_ntfy_still_sends_when_the_conversation_fails(self):
        logged = []
        with mock.patch.object(conversation, "append", side_effect=OSError("disk full")), \
                mock.patch.object(helpers, "log", logged.append):
            helpers.notify("Inbox 6 mislukt", "boom", "http://x/6")
        self.assertTrue(any(l.startswith("MELDING: Inbox 6 mislukt") for l in logged))

    def test_notifications_written_while_the_client_is_away_wait_for_its_cursor(self):
        cursor = last()["id"]
        helpers.notify("one", "a")
        helpers.notify("two", "b")
        self.assertEqual([e["text"].split("\n")[0] for e in conversation.client_read("discord", cursor)],
                         ["one", "two"])

    def test_questions_of_a_task_are_stored_in_full(self):
        tid = _task()
        text = f"VRAAG: {LONG}"
        with mock.patch.object(queue, "task_cmd", return_value=(0, "")), mock.patch.object(queue, "push_branch"):
            queue.finish_round(tid, Path(HOME), text, {}, None)
        e = last()
        self.assertEqual((e["author"], e["kind"], e["ref"]), ("claude", "question", f"task:{tid}"))
        self.assertEqual(e["text"], LONG.strip())
        self.assertIn(f"/task/{tid}", e["link"])


def _task():
    with storage.db() as c:
        return c.execute("INSERT INTO tasks (repo, text, status, created, session) VALUES (?,?,?,?,?)",
                         ("homelab", "t", "running", storage.now(), str(uuid.uuid4()))).lastrowid


class Switches(unittest.TestCase):
    def tearDown(self):
        for n in conversation.SWITCHES:
            conversation.set_switch(n, True)

    def test_on_by_default_and_work_without_restart(self):
        self.assertEqual(conversation.state(), {"discord": True, "discord_approvals": True})
        conversation.set_switch("discord_approvals", False)
        self.assertEqual(conversation.state(), {"discord": True, "discord_approvals": False})
        conversation.set_switch("discord_approvals", True)
        self.assertTrue(conversation.state()["discord_approvals"])

    def test_unknown_switch_is_rejected(self):
        with self.assertRaises(ValueError):
            conversation.set_switch("everything", False)

    def test_approvals_off_refuses_approve_but_not_other_actions(self):
        self.assertTrue(conversation.allowed("discord", "approve"))
        conversation.set_switch("discord_approvals", False)
        self.assertFalse(conversation.allowed("discord", "approve"))
        self.assertEqual(conversation.switched_off("discord", "approve"), "approvals from Discord are off")
        self.assertTrue(conversation.allowed("discord", "write"))

    def test_discord_off_means_off_everywhere(self):
        conversation.set_switch("discord", False)
        self.assertEqual(conversation.state(), {"discord": False, "discord_approvals": False})
        self.assertFalse(conversation.allowed("discord", "approve"))
        self.assertIsNone(conversation.client_append("discord", "message", "hi"))
        self.assertIsNone(conversation.switched_off("jarvis"))  # the switches are for Discord only

    def test_page_shows_both_switches_in_their_state(self):
        conversation.set_switch("discord_approvals", False)
        html = pages.discord_html()
        self.assertIn('action="/discord/discord/off"', html)
        self.assertIn('action="/discord/discord_approvals/on"', html)
        conversation.set_switch("discord", False)
        self.assertIn("arrives when it is switched on", pages.discord_html())


class Door(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (Path(TOKENS) / "chat-discord-token").write_text("tok-d\n")
        (Path(TOKENS) / "chat-jarvis-token").write_text("tok-j\n")
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.base = f"http://127.0.0.1:{cls.srv.server_address[1]}/api/chat/"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()
        cls.srv.server_close()

    def tearDown(self):
        for n in conversation.SWITCHES:
            conversation.set_switch(n, True)

    def get(self, path, token="tok-d", method="GET"):
        req = urllib.request.Request(self.base + path, method=method, headers={"Authorization": f"Bearer {token}"})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_state_is_exposed_to_clients_and_follows_the_switches(self):
        self.assertEqual(self.get("state"), (200, {"discord": True, "discord_approvals": True}))
        conversation.set_switch("discord_approvals", False)
        self.assertEqual(self.get("state")[1], {"discord": True, "discord_approvals": False})
        self.assertEqual(self.get("state", "tok-j")[0], 200)
        self.assertEqual(self.get("state", "wrong")[0], 401)
        self.assertEqual(self.get("state", method="POST")[0], 405)

    def test_discord_off_closes_its_door_but_keeps_the_entries(self):
        helpers.notify("while off", "kept")
        cursor = last()["id"] - 1
        conversation.set_switch("discord", False)
        refused = len(conversation.refusals(1000))
        status, r = self.get(f"entries?after={cursor}")
        self.assertEqual(status, 403)
        self.assertFalse(r["discord"])
        self.assertEqual(len(conversation.refusals(1000)), refused)  # a polling client does not flood the log
        self.assertEqual(self.get("entries", "tok-j")[0], 200)  # other clients are not behind the switch
        conversation.set_switch("discord", True)
        status, r = self.get(f"entries?after={cursor}")
        self.assertEqual(status, 200)
        self.assertEqual(r["entries"][0]["text"], "while off\nkept")


if __name__ == "__main__":
    unittest.main()
