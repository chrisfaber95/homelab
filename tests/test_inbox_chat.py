"""H-016: what a chat client may do: start a task, answer, list, run a job, and the two-step approval; the daily cap,
the switches and the actions no client can ever do. Run: python3 -m unittest discover -s tests"""
import json
import os
import sys
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOME = tempfile.mkdtemp()
TOKENS = tempfile.mkdtemp()
os.environ.update(INBOX_RUNNER_HOME=HOME, NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"),
                  CHAT_TOKEN_DIR=TOKENS, INBOX_ALLOW_ALL="1")
CLIENTS = {"clients": {
    "discord": {"permissions": ["read", "write", "ask", "start_task", "answer", "approve", "run_job",
                                "ssh_key", "memory_edit", "merge_homelab"], "tasks_per_day": 3},
    "reader": {"permissions": ["read"]}}}
os.environ["CLIENTS_JSON"] = json.dumps(CLIENTS)
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import briefs, chat, config, conversation  # noqa: E402
from homelab_inbox import helpers, pages, server, storage  # noqa: E402

BID = "H-016"
FAKE_BRIEF = {"id": BID, "title": "Chat", "status": "draft", "repos": ["homelab"], "build": {},
              "project": "homelab", "path": "homelab/briefs/H-016.md", "text": "x"}


class Base(unittest.TestCase):
    def setUp(self):
        conversation.refusals()  # creates the conversation tables
        with storage.db() as c:
            c.execute("DELETE FROM tasks")
            c.execute("DELETE FROM kv")
            c.execute("DELETE FROM conversation")
            c.execute("DELETE FROM conversation_refusals")
            c.execute("DROP TABLE IF EXISTS approvals")
        self._env = {k: os.environ.get(k) for k in ("CLIENTS_JSON", "CHAT_TOKEN_DIR")}  # other test files set theirs
        os.environ.update(CLIENTS_JSON=json.dumps(CLIENTS), CHAT_TOKEN_DIR=TOKENS)
        self.brief = dict(FAKE_BRIEF)
        self.commits = []
        self.pushed = []
        self.now = 1000.0
        self._saved = (briefs.brief_by_id, briefs.set_brief_status, briefs.add_new_repo, chat.clock, chat.brief_by_id,
                       helpers.notify, chat.notify, briefs.start_build)
        briefs.brief_by_id = chat.brief_by_id = lambda bid, max_age=60: self.brief if bid == BID else None
        briefs.add_new_repo = lambda b: None
        briefs.set_brief_status = self.fake_commit
        chat.clock = lambda: self.now
        chat.notify = lambda title, msg, click=None, store=True: self.pushed.append((title, msg, store))

    def tearDown(self):
        for k, v in self._env.items():
            os.environ.pop(k, None) if v is None else os.environ.__setitem__(k, v)
        conversation.set_switch("discord", True)
        conversation.set_switch("discord_approvals", True)
        (briefs.brief_by_id, briefs.set_brief_status, briefs.add_new_repo, chat.clock, chat.brief_by_id,
         helpers.notify, chat.notify, briefs.start_build) = self._saved

    def fake_commit(self, bid, status, why):
        self.commits.append(why)
        self.brief["status"] = status

    def kinds(self, kind):
        return [e for e in conversation.read(0, 1000) if e["kind"] == kind]


class StartTask(Base):
    def test_task_is_queued_like_a_page_task_and_marked_discord(self):
        status, r = chat.start_task("discord", "homelab", "fix the thing")
        self.assertEqual(status, 200)
        t = storage.get(r["task"])
        self.assertEqual((t["repo"], t["status"], t["source"], t["unattended"]), ("homelab", "queued", "discord", 0))
        self.assertIn("Discord", pages.task_page(t))  # its page says where it came from
        self.assertIn("via Discord", pages.task_row(t))
        page_id = storage.new_task("homelab", "from the page")
        self.assertIsNone(storage.get(page_id)["source"])
        self.assertEqual(kinds := [e["client"] for e in self.kinds("message")], ["discord"], kinds)

    def test_auto_and_unknown_repo(self):
        self.assertEqual(chat.start_task("discord", "auto", "something")[0], 200)
        self.assertEqual(chat.start_task("discord", "no-such-repo", "x")[0], 400)
        self.assertEqual(chat.start_task("discord", "homelab", "  ")[0], 400)

    def test_needs_the_permission(self):
        self.assertEqual(chat.start_task("reader", "homelab", "x")[0], 403)
        self.assertEqual(chat.start_task("nobody", "homelab", "x")[0], 403)
        self.assertEqual(len(conversation.refusals()), 2)

    def test_daily_cap_refuses_clearly_and_starts_nothing(self):
        for i in range(3):
            self.assertEqual(chat.start_task("discord", "homelab", f"task {i}")[0], 200)
        status, r = chat.start_task("discord", "homelab", "one too many")
        self.assertEqual(status, 429)
        self.assertIn("daily limit", r["error"])
        self.assertEqual(storage.db().execute("SELECT COUNT(*) FROM tasks").fetchone()[0], 3)
        storage.new_task("homelab", "page tasks do not count")
        self.assertEqual(chat.start_task("discord", "homelab", "still too many")[0], 429)

    def test_cap_default_comes_from_config(self):
        os.environ["CLIENTS_JSON"] = json.dumps({"clients": {"discord": {"permissions": ["start_task"]}}})
        self.assertEqual(chat.daily_cap("discord"), int(config.CONF.get("CLIENT_TASKS_PER_DAY", "10")))

    def test_switch_off_stops_tasks(self):
        conversation.set_switch("discord", False)
        self.assertEqual(chat.start_task("discord", "homelab", "x")[0], 403)


class Answer(Base):
    def waiting(self):
        tid = storage.new_task("homelab", "q")
        storage.update(tid, status="waiting", question="Which?")
        return tid

    def test_answer_continues_the_task_like_the_page(self):
        tid = self.waiting()
        self.assertEqual(chat.answer("discord", tid, "the second one")[0], 200)
        t = storage.get(tid)
        self.assertEqual(t["status"], "queued")
        self.assertIn("the second one", t["pending_input"])
        self.assertEqual(self.kinds("message")[-1]["client"], "discord")

    def test_not_waiting_unknown_and_forbidden(self):
        tid = storage.new_task("homelab", "q")
        self.assertEqual(chat.answer("discord", tid, "x")[0], 409)
        self.assertEqual(chat.answer("discord", 99999, "x")[0], 404)
        self.assertEqual(chat.answer("reader", self.waiting(), "x")[0], 403)


class Lists(Base):
    def test_state_lists_running_waiting_and_queued(self):
        a, b = storage.new_task("homelab", "one"), storage.new_task("homelab", "two")
        storage.update(a, status="running")
        done = storage.new_task("homelab", "three")
        storage.update(done, status="merged")
        _, r = chat.tasks_state("discord")
        self.assertEqual([(t["id"], t["status"]) for t in r["tasks"]], [(a, "running"), (b, "queued")])

    def test_jobs_listed_and_run_now(self):
        config.JOBS.clear()
        config.JOBS["demo"] = {"name": "demo", "kind": "script", "schedule": "daily", "paused": False}
        config.JOBS["broken"] = {"name": "broken", "error": "bad"}
        ran = []
        orig, chat.dispatch = chat.dispatch, lambda job, due, manual=False: ran.append((job["name"], manual)) or 7
        try:
            self.assertEqual([j["name"] for j in chat.jobs_list("discord")[1]["jobs"]], ["broken", "demo"])
            self.assertEqual(chat.run_job("discord", "demo")[0], 200)
            self.assertEqual(chat.run_job("discord", "broken")[0], 404)
            self.assertEqual(chat.run_job("reader", "demo")[0], 403)
        finally:
            chat.dispatch = orig
            config.JOBS.clear()
        self.assertEqual(ran, [("demo", True)])


class Approval(Base):
    def request(self, mode="build"):
        status, r = chat.approval_request("discord", BID, mode)
        self.assertEqual(status, 200, r)
        return r["confirm"]

    def test_request_alone_approves_nothing(self):
        self.request()
        self.assertEqual((self.commits, self.brief["status"], len(self.kinds("approval_request"))),
                         ([], "draft", 1))
        self.assertEqual(storage.db().execute("SELECT COUNT(*) FROM tasks").fetchone()[0], 0)

    def test_confirm_approves_records_and_notifies(self):
        token = self.request("without-me")
        status, r = chat.approval_confirm("discord", token)
        self.assertEqual(status, 200, r)
        self.assertEqual(self.brief["status"], "approved")
        self.assertIn("approved from discord", self.commits[0])  # on the brief's commit
        self.assertIn("build without me", self.commits[0])
        t = storage.get(r["task"])
        self.assertEqual((t["repo"], t["source"], t["unattended"]), ("brief", "discord", 1))
        self.assertIn("approved there", pages.task_page(t))  # on the build's page
        self.assertEqual(self.kinds("approval")[0]["client"], "discord")
        self.assertEqual(len(self.pushed), 1)  # and an ntfy notification
        self.assertIn("from Discord", self.pushed[0][0])

    def test_token_not_in_the_conversation(self):
        token = self.request()
        self.assertNotIn(token, json.dumps(conversation.read(0, 1000)))

    def test_expired_confirm_approves_nothing(self):
        token = self.request()
        self.now += chat.ttl() + 1
        status, r = chat.approval_confirm("discord", token)
        self.assertEqual(status, 410)
        self.assertIn("expired", r["error"])
        self.assertEqual((self.commits, self.brief["status"]), ([], "draft"))

    def test_token_works_once(self):
        token = self.request()
        self.assertEqual(chat.approval_confirm("discord", token)[0], 200)
        self.assertEqual(chat.approval_confirm("discord", token)[0], 410)
        self.assertEqual(len(self.commits), 1)

    def test_wrong_token_or_another_client(self):
        token = self.request()
        self.assertEqual(chat.approval_confirm("discord", "guess")[0], 404)
        os.environ["CLIENTS_JSON"] = json.dumps({"clients": {**CLIENTS["clients"],
                                                             "other": {"permissions": ["approve"]}}})
        self.assertEqual(chat.approval_confirm("other", token)[0], 404)
        self.assertEqual(self.commits, [])

    def test_free_text_never_approves(self):
        for text in ("approve H-016", "Approve & build H-016", f"confirm {BID}"):
            for kind in ("message", "question", "answer"):
                conversation.client_append("discord", kind, text, ref=f"brief:{BID}")
        status, _ = chat.start_task("discord", "homelab", "approve H-016")  # a task text is only a task text
        self.assertEqual((self.commits, self.brief["status"]), ([], "draft"))
        self.assertEqual(self.kinds("approval"), [])
        self.assertEqual(conversation.client_append("discord", "approval", "approve H-016"), None)
        self.assertEqual(storage.db().execute("SELECT COUNT(*) FROM tasks WHERE repo='brief'").fetchone()[0], 0)

    def test_approvals_switch_off_refuses_both_steps(self):
        token = self.request()
        conversation.set_switch("discord_approvals", False)
        status, r = chat.approval_request("discord", BID, "build")
        self.assertEqual(status, 403)
        self.assertIn("approvals from Discord are off", r["error"])
        self.assertEqual(chat.approval_confirm("discord", token)[0], 403)  # switched off between the steps
        self.assertEqual(self.commits, [])
        conversation.set_switch("discord_approvals", True)
        self.assertEqual(chat.approval_confirm("discord", token)[0], 200)

    def test_needs_the_approve_permission(self):
        self.assertEqual(chat.approval_request("reader", BID, "build")[0], 403)

    def test_only_drafts_and_unbuilt(self):
        self.brief["status"] = "approved"
        self.assertEqual(chat.approval_request("discord", BID, "build")[0], 409)
        self.assertEqual(chat.approval_request("discord", "../x", "build")[0], 400)
        self.brief["status"] = "draft"
        self.assertEqual(chat.approval_request("discord", BID, "yolo")[0], 400)

    def test_cap_applies_at_confirm(self):
        token = self.request()
        for i in range(3):
            chat.start_task("discord", "homelab", f"t{i}")
        status, r = chat.approval_confirm("discord", token)
        self.assertEqual(status, 409)
        self.assertIn("daily limit", r["error"])
        self.assertEqual(self.commits, [])


class Forbidden(Base):
    def test_config_cannot_grant_them(self):
        for action in conversation.FORBIDDEN:
            self.assertNotIn(action, conversation.clients()["discord"])
            self.assertFalse(conversation.allowed("discord", action))
        self.assertEqual({r["action"] for r in conversation.refusals()}, set(conversation.FORBIDDEN))

    def test_not_even_with_every_switch_on(self):
        os.environ["CLIENTS_JSON"] = json.dumps({"clients": {"discord": {"permissions": list(conversation.FORBIDDEN)}}})
        self.assertFalse(conversation.allowed("discord", "merge_homelab"))
        self.assertFalse(conversation.allowed("discord", "approve"))


class Door(Base):
    @classmethod
    def setUpClass(cls):
        (Path(TOKENS) / "chat-discord-token").write_text("tok-discord\n")
        (Path(TOKENS) / "chat-reader-token").write_text("tok-reader\n")
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()
        cls.url = f"http://127.0.0.1:{cls.srv.server_address[1]}/api/chat/action"

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def call(self, token, data):
        req = urllib.request.Request(self.url, method="POST", data=json.dumps(data).encode(),
                                     headers={"Authorization": f"Bearer {token}"})
        try:
            with urllib.request.urlopen(req) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def test_task_through_the_door(self):
        status, r = self.call("tok-discord", {"action": "task", "repo": "auto", "text": "do it"})
        self.assertEqual((status, storage.get(r["task"])["source"]), (200, "discord"))
        self.assertEqual(self.call("tok-reader", {"action": "task", "repo": "auto", "text": "x"})[0], 403)
        self.assertEqual(self.call("nope", {"action": "state"})[0], 401)

    def test_two_steps_through_the_door(self):
        status, r = self.call("tok-discord", {"action": "approve", "brief": BID, "mode": "build"})
        self.assertEqual(status, 200)
        self.assertEqual(self.brief["status"], "draft")
        status, r = self.call("tok-discord", {"action": "confirm", "confirm": r["confirm"]})
        self.assertEqual((status, self.brief["status"]), (200, "approved"))

    def test_forbidden_actions_through_the_door(self):
        for action in ("ssh_key", "memory_edit", "merge_homelab"):
            self.assertEqual(self.call("tok-discord", {"action": action})[0], 403)
        self.assertEqual(self.call("tok-discord", {"action": "unknown"})[0], 400)

    def test_discord_switch_off_blocks_the_door(self):
        conversation.set_switch("discord", False)
        self.assertEqual(self.call("tok-discord", {"action": "state"})[0], 403)


if __name__ == "__main__":
    unittest.main()
