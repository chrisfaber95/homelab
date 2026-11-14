"""H-018: the door for kitt and the gate in front of it. kitt's permission list and switch decide what the app can do;
the door only accepts kitt's own service account acting for Chris; the gate answers only the owner's devices.
Run: python3 -m unittest discover -s tests"""
import http.client
import json
import os
import socket
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HOME = tempfile.mkdtemp()
os.environ.update(INBOX_RUNNER_HOME=HOME, NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"))
ALL = ["read", "write", "ask", "start_task", "run_job", "approve"]
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import briefs, chat, config, conversation, gate, kitt, pages, server, storage  # noqa: E402

OWNER = "chris@example.com"
BID = "H-018"


def call(port, method, path, body=None, headers=None):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    h = {"X-Homelab-Client": "kitt", "Tailscale-User-Login": OWNER, "Content-Type": "application/json", **(headers or {})}
    c.request(method, path, json.dumps(body) if body is not None else None, h)
    r = c.getresponse()
    data = r.read()
    return r.status, (json.loads(data) if data[:1] in (b"{", b"[") else data.decode())


class Base(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.srv = ThreadingHTTPServer(("127.0.0.1", 0), server.Handler)
        cls.port = cls.srv.server_address[1]
        threading.Thread(target=cls.srv.serve_forever, daemon=True).start()

    @classmethod
    def tearDownClass(cls):
        cls.srv.shutdown()

    def setUp(self):
        self._clients_json = os.environ.get("CLIENTS_JSON")  # other test files set theirs for the whole run
        conversation.refusals()
        with storage.db() as c:
            for t in ("tasks", "kv", "conversation", "conversation_refusals", "job_runs"):
                c.execute(f"DELETE FROM {t}")
            c.execute("DROP TABLE IF EXISTS approvals")
        self.set_clients(ALL)
        self.saved = (kitt.account_uid, kitt.peer_uid, pages.Access.owner, briefs.brief_by_id, chat.brief_by_id,
                      briefs.set_brief_status, briefs.add_new_repo, chat.notify, kitt.load_briefs)
        kitt.account_uid, kitt.peer_uid = lambda: 4242, lambda addr, port: 4242
        pages.Access.owner = OWNER
        self.brief = {"id": BID, "title": "Kitt", "status": "draft", "repos": ["homelab"], "build": {}}
        self.commits, self.pushed = [], []
        briefs.brief_by_id = chat.brief_by_id = lambda bid, max_age=60: self.brief if bid == BID else None
        briefs.add_new_repo = lambda b: None
        briefs.set_brief_status = lambda bid, status, why: (self.commits.append(why), self.brief.update(status=status))
        chat.notify = lambda title, msg, click=None, store=True: self.pushed.append((title, msg))
        kitt.load_briefs = lambda: [self.brief]

    def tearDown(self):
        (kitt.account_uid, kitt.peer_uid, pages.Access.owner, briefs.brief_by_id, chat.brief_by_id,
         briefs.set_brief_status, briefs.add_new_repo, chat.notify, kitt.load_briefs) = self.saved
        conversation.set_switch("kitt", True)
        if self._clients_json is None:
            os.environ.pop("CLIENTS_JSON", None)
        else:
            os.environ["CLIENTS_JSON"] = self._clients_json

    def set_clients(self, perms):
        os.environ["CLIENTS_JSON"] = json.dumps({"clients": {"kitt": {"permissions": perms, "tasks_per_day": 5}}})


class Door(Base):
    def test_only_kitts_account_acting_for_chris(self):
        self.assertEqual(call(self.port, "GET", "/api/clients/kitt")[0], 200)
        kitt.peer_uid = lambda addr, port: 1000  # another local account claiming to be kitt
        self.assertEqual(call(self.port, "GET", "/api/clients/kitt")[0], 403)
        kitt.peer_uid = lambda addr, port: 4242
        self.assertEqual(call(self.port, "GET", "/api/clients/kitt", headers={"Tailscale-User-Login": "other@x"})[0], 403)
        self.assertEqual(call(self.port, "GET", "/api/clients/kitt", headers={"Tailscale-User-Login": ""})[0], 403)
        kitt.account_uid = lambda: None  # the unit is not running
        self.assertEqual(call(self.port, "GET", "/api/clients/kitt")[0], 403)

    def test_real_peer_uid_is_the_owner_of_the_connection(self):
        seen = []
        orig = self.saved[1]  # the real function, not setUp's stand-in
        kitt.peer_uid = lambda addr, port: seen.append(orig(addr, port)) or 4242
        call(self.port, "GET", "/api/clients/kitt")
        self.assertEqual(seen, [os.getuid()])

    def test_a_remote_address_has_no_uid(self):
        self.assertIsNone(self.saved[1](("100.1.2.3", 5555), self.port))

    def test_unknown_path_and_no_forbidden_routes(self):
        for path in ("/api/memory", "/api/ssh", "/api/merge", "/api/jobs/x/delete", "/api/jobs/x/edit"):
            self.assertEqual(call(self.port, "POST", path, {})[0], 404, path)

    def test_state_follows_the_permission_list_and_the_switch(self):
        _, s = call(self.port, "GET", "/api/clients/kitt")
        self.assertEqual(s, {"enabled": True, "permissions": {"chat": True, "jobs": True, "approvals": True,
                                                              "usage": True, "logs": True}})
        self.set_clients([p for p in ALL if p != "approve"])
        self.assertFalse(call(self.port, "GET", "/api/clients/kitt")[1]["permissions"]["approvals"])
        conversation.set_switch("kitt", False)
        _, s = call(self.port, "GET", "/api/clients/kitt")
        self.assertEqual((s["enabled"], any(s["permissions"].values())), (False, False))
        self.assertEqual(call(self.port, "GET", "/api/conversation")[0], 403)
        self.assertEqual(call(self.port, "GET", "/api/jobs")[0], 403)


class Approvals(Base):
    def test_bell_lists_and_approves_as_kitt(self):
        _, r = call(self.port, "GET", "/api/approvals")
        self.assertEqual([i["id"] for i in r["items"]], [BID])
        status, r = call(self.port, "POST", f"/api/approvals/{BID}", {"client": "kitt", "decision": "approve_unattended"})
        self.assertEqual(status, 200, r)
        self.assertEqual(self.commits, ["approved from kitt by Chris, build without me"])
        t = storage.get(r["task"])
        self.assertEqual((t["source"], t["unattended"]), ("kitt", 1))
        self.assertTrue(any("from Kitt" in e["text"] for e in conversation.read(0, 100) if e["kind"] == "approval"))
        self.assertEqual(len(self.pushed), 1)

    def test_plain_approve_and_deny_and_questions(self):
        self.assertEqual(call(self.port, "POST", f"/api/approvals/{BID}", {"decision": "approve"})[0], 200)
        self.assertEqual(self.commits, ["approved from kitt by Chris"])
        self.assertEqual(call(self.port, "POST", f"/api/approvals/{BID}", {"decision": "deny"})[0], 400)
        self.assertEqual(call(self.port, "POST", "/api/approvals/question:3", {"decision": "approve"})[0], 409)

    def test_without_the_permission_the_bell_lists_but_does_not_approve(self):
        self.set_clients([p for p in ALL if p != "approve"])
        self.assertEqual(len(call(self.port, "GET", "/api/approvals")[1]["items"]), 1)
        status, r = call(self.port, "POST", f"/api/approvals/{BID}", {"decision": "approve"})
        self.assertEqual(status, 403)
        self.assertIn("approvals from kitt are off", r["error"])
        self.assertEqual((self.commits, self.pushed), ([], []))

    def test_waiting_questions_are_on_the_bell(self):
        tid = storage.new_task("homelab", "q")
        storage.update(tid, status="waiting", question="Which one?")
        ids = [i["id"] for i in call(self.port, "GET", "/api/approvals")[1]["items"]]
        self.assertIn(f"question:{tid}", ids)


class Conversation(Base):
    def test_task_and_question_carry_kitt_and_other_clients_show(self):
        conversation.append("chris", "discord", "message", "hello from discord")
        status, r = call(self.port, "POST", "/api/conversation", {"kind": "task", "text": "do a thing"})
        self.assertEqual((status, r["entry"]["client"], r["entry"]["kind"]), (200, "kitt", "task"))
        t = storage.get(1)
        self.assertEqual(t["source"], "kitt")
        kitt.answer_question = lambda q: None  # no search service here
        status, r = call(self.port, "POST", "/api/conversation", {"kind": "question", "text": "what?"})
        self.assertEqual((status, r["entry"]["kind"]), (200, "question"))
        _, page = call(self.port, "GET", "/api/conversation?after=0")
        self.assertEqual([e["client"] for e in page["entries"]], ["discord", "kitt", "kitt"])
        self.assertEqual(page["cursor"], page["entries"][-1]["id"])
        self.assertEqual(call(self.port, "GET", f"/api/conversation?after={page['cursor']}")[1]["entries"], [])

    def test_search_answer_lands_in_the_thread(self):
        orig = kitt.search.ask
        kitt.search.ask = lambda form: (200, {"found": True, "answer": "Port 8105.", "citations": [
            {"n": 1, "path": "docs/inbox.md", "location": "top"}]})
        try:
            kitt.answer_question("which port?")
        finally:
            kitt.search.ask = orig
        e = conversation.read(0, 10)[-1]
        self.assertEqual((e["kind"], e["client"], e["author"]), ("answer", "kitt", "box"))
        self.assertIn("docs/inbox.md", e["text"])

    def test_needs_ask_permission(self):
        self.set_clients(["read", "write"])
        self.assertEqual(call(self.port, "POST", "/api/conversation", {"kind": "question", "text": "x"})[0], 403)


class JobsAndLogs(Base):
    def test_cards_and_actions(self):
        job = config.JOBS["nightly"] = {"name": "nightly", "when": "daily 7:00", "schedule": "0 7 * * *", "model": "local",
                                       "deliver": "page", "paused": False, "text": "---\nkind: digest\n---\nx",
                                       "cron": __import__("homelab_inbox.jobs", fromlist=["Cron"]).Cron("0 7 * * *")}
        try:
            _, r = call(self.port, "GET", "/api/jobs")
            card = r["jobs"][0]
            self.assertEqual((card["name"], card["model"], card["deliver"], card["paused"], card["lastResult"]),
                             ("nightly", "local", "page", False, None))
            self.assertTrue(card["nextRun"])
            saved = []
            orig = kitt.save_job
            kitt.save_job = lambda name, text, msg: saved.append((name, text, msg))
            try:
                self.assertEqual(call(self.port, "POST", "/api/jobs/nightly/pause", {})[0], 200)
                self.assertIn("paused: true", saved[0][1])
                self.assertEqual(saved[0][2], "jobs: nightly paused (from kitt)")
                self.set_clients(["read", "write", "ask"])
                self.assertEqual(call(self.port, "POST", "/api/jobs/nightly/resume", {})[0], 403)
                self.assertEqual(call(self.port, "POST", "/api/jobs/nightly/run", {})[0], 403)
            finally:
                kitt.save_job = orig
            self.assertEqual(call(self.port, "POST", "/api/jobs/nope/run", {})[0], 404)
        finally:
            config.JOBS.pop("nightly")

    def test_logs_list_and_read(self):
        from homelab_inbox.helpers import log_line
        tid = storage.new_task("homelab", "first line\nsecond")
        storage.update(tid, status="running", started=storage.now())
        log_line(f"t{tid}", "info", "started")
        log_line(f"t{tid}", "claude", "working")
        _, r = call(self.port, "GET", "/api/logs")
        self.assertEqual([(l["id"], l["state"], l["kind"]) for l in r["logs"]], [(f"t{tid}", "running", "task")])
        _, c = call(self.port, "GET", f"/api/logs/t{tid}?from=1")
        self.assertEqual((len(c["lines"]), c["next"], c["state"]), (1, 2, "running"))
        self.assertIn("working", c["lines"][0])
        self.assertEqual(call(self.port, "GET", "/api/logs/t99999")[0], 404)
        self.assertEqual(call(self.port, "GET", "/api/logs/..%2Fetc")[0], 404)

    def test_health(self):
        from homelab_inbox import overview
        orig = kitt.health
        kitt.health = lambda: {"checks": {"gpu": {"status": "OK", "text": "fine"}, "disk": {"status": "FAIL", "text": "full"}}}
        try:
            _, r = call(self.port, "GET", "/api/health")
        finally:
            kitt.health = orig
        self.assertEqual([(c["name"], c["status"]) for c in r["checks"]], [("disk", "fail"), ("gpu", "ok")])


class FakeKitt(BaseHTTPRequestHandler):
    seen = []

    def log_message(self, *a):
        pass

    def do_GET(self):
        FakeKitt.seen.append(dict(self.headers))
        body = b"data: one\n\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.end_headers()
        self.wfile.write(body)


class Gate(unittest.TestCase):
    """The same owner check as the homelab page: another user, the LAN and the box itself get a 403."""

    @classmethod
    def setUpClass(cls):
        cls.up = ThreadingHTTPServer(("127.0.0.1", 0), FakeKitt)
        threading.Thread(target=cls.up.serve_forever, daemon=True).start()
        config.CONF["KITT_UPSTREAM"] = f"http://127.0.0.1:{cls.up.server_address[1]}"
        cls.gate = gate.GateServer(("127.0.0.1", 0), gate.Gate)
        threading.Thread(target=cls.gate.serve_forever, daemon=True).start()
        cls.port = cls.gate.server_address[1]

    @classmethod
    def tearDownClass(cls):
        cls.up.shutdown()
        cls.gate.shutdown()

    def setUp(self):
        self._allow_all = os.environ.pop("INBOX_ALLOW_ALL", None)  # other test files set it for the whole run
        self.saved = (pages.Access.owner, pages.Access.own, pages.Access.cache)
        pages.Access.owner, pages.Access.own, pages.Access.cache = OWNER, {"100.85.70.34"}, {}
        FakeKitt.seen.clear()
        self.logins = {}
        pages.Access.cache["127.0.0.1"] = ((self.logins.get("127.0.0.1", OWNER), "phone"), 10 ** 12)

    def tearDown(self):
        pages.Access.owner, pages.Access.own, pages.Access.cache = self.saved
        if self._allow_all is not None:
            os.environ["INBOX_ALLOW_ALL"] = self._allow_all

    def get(self, headers=None):
        c = http.client.HTTPConnection("127.0.0.1", self.port, timeout=10)
        c.request("GET", "/v1/x", headers=headers or {})
        r = c.getresponse()
        return r.status, r.read().decode()

    def test_owner_passes_with_identity_and_forged_headers_are_dropped(self):
        status, body = self.get({"Tailscale-User-Login": "evil@x", "X-Homelab-Client": "kitt"})
        self.assertEqual((status, body), (200, "data: one\n\n"))
        seen = {k.lower(): v for k, v in FakeKitt.seen[0].items()}
        self.assertEqual((seen["tailscale-user-login"], seen["tailscale-user-name"]), (OWNER, "phone"))
        self.assertNotIn("x-homelab-client", seen)

    def test_another_tailnet_user_is_refused(self):
        pages.Access.cache["127.0.0.1"] = (("someone@else.com", "laptop"), 10 ** 12)
        self.assertEqual(self.get()[0], 403)
        self.assertEqual(FakeKitt.seen, [])

    def test_the_box_itself_is_refused(self):
        pages.Access.own = {"127.0.0.1"}
        self.assertEqual(self.get()[0], 403)
        self.assertEqual(FakeKitt.seen, [])

    def test_unknown_peer_is_refused_and_kitt_down_is_a_502(self):
        pages.Access.cache["127.0.0.1"] = ((None, None), 10 ** 12)
        self.assertEqual(self.get()[0], 403)
        pages.Access.cache["127.0.0.1"] = ((OWNER, "phone"), 10 ** 12)
        config.CONF["KITT_UPSTREAM"] = "http://127.0.0.1:1"
        try:
            self.assertEqual(self.get()[0], 502)
        finally:
            config.CONF["KITT_UPSTREAM"] = f"http://127.0.0.1:{self.up.server_address[1]}"

    def test_a_wildcard_host_keeps_the_gate_off(self):
        config.CONF["KITT_GATE_HOST"] = "0.0.0.0"
        try:
            gate.run()  # returns at once instead of listening
        finally:
            config.CONF.pop("KITT_GATE_HOST")


class Page(Base):
    def test_switch_form_and_route(self):
        self.assertIn('id="switch-kitt"', pages.kitt_html())
        conversation.set_switch("kitt", False)
        self.assertIn("Off", pages.kitt_html())


if __name__ == "__main__":
    unittest.main()
