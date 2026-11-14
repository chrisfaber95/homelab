"""Research runs (H-017): the job kind, the prompt, the cap, the read-only guarantee, delivery to where the question
came from, and the report with its mode and time. Run: python3 -m unittest discover -s tests"""
import os
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

ROOT = Path(__file__).resolve().parent.parent
os.environ.update(INBOX_RUNNER_HOME=tempfile.mkdtemp(), NTFY_FAKE="1", REPOS_CONF=str(ROOT / "config/repos.conf"),
                  INBOX_ALLOW_ALL="1")
sys.path.insert(0, str(ROOT / "bin"))
from homelab_inbox import chat, conversation, jobs, pages, queue, rounds  # noqa: E402
from homelab_inbox.storage import db, get  # noqa: E402

FILE = ('---\nwhen: weekly\nschedule: "0 9 * * 1"\nkind: research\nmodel: claude\n{extra}---\n'
        'What changed in Tailscale ACL syntax this year?\n')
REPORT = ("ANTWOORD: ## Antwoord\nOne claim [1].\n\n## Bronnen\n[1] https://example.org/a\n\n## Doorzocht\n"
          "private: tailscale acl; web: tailscale acl syntax")


def parse(extra="", name="acl-watch"):
    return jobs.parse_job(name, FILE.format(extra=extra), repos={}, skills={})


CLIENTS = {"discord": {"read", "research"}, "reader": {"read"}}


def setUpModule():
    global _patch
    _patch = mock.patch.object(conversation, "clients", lambda: CLIENTS)
    _patch.start()


def tearDownModule():
    _patch.stop()


def reset():
    with db() as c:
        c.execute("DELETE FROM tasks")
        c.execute("DELETE FROM job_runs")
        c.execute("DELETE FROM kv")
    conversation.refusals()
    with db() as c:
        c.execute("DELETE FROM conversation")


class Format(unittest.TestCase):
    def test_default_mode_is_quick_and_deep_is_allowed(self):
        self.assertEqual(parse()["mode"], "quick")
        self.assertEqual(parse("mode: deep\n")["mode"], "deep")

    def test_bad_mode_local_model_and_mode_elsewhere_are_errors(self):
        with self.assertRaisesRegex(ValueError, "mode moet"):
            parse("mode: slow\n")
        with self.assertRaisesRegex(ValueError, "op Claude"):
            jobs.parse_job("x", FILE.format(extra="").replace("model: claude", "model: local"), repos={}, skills={})
        with self.assertRaisesRegex(ValueError, "alleen voor kind: research"):
            jobs.parse_job("x", "---\nschedule: '0 7 * * *'\nkind: box-report\nmodel: local\nmode: deep\n---\nhi\n",
                           repos={}, skills={})

    def test_reserved_name(self):
        with self.assertRaisesRegex(ValueError, "naam research"):
            parse(name="research")

    def test_report_needs_no_nothing_marker_to_deliver(self):
        self.assertEqual(parse("deliver: conversation\n")["deliver"], "conversation")

    def test_old_job_kinds_unchanged(self):
        j = jobs.parse_job("x", "---\nschedule: '0 7 * * *'\nkind: box-report\nmodel: local\n---\nhi\n", repos={}, skills={})
        self.assertIsNone(j["mode"])


class Prompt(unittest.TestCase):
    def test_prompt_demands_sources_private_first_and_no_guessing(self):
        p = jobs.research_prompt({**parse(), "mode": "quick"})
        self.assertIn("Tailscale ACL", p)
        self.assertLess(p.index("homelab-search"), p.index("WebSearch"))
        for word in ("link", "bron, plaats en datum", "NIETS GEVONDEN", "## Doorzocht", "Gok niet", "geen commit"):
            self.assertIn(word, p)

    def test_quick_is_smaller_than_deep(self):
        qm, qt = jobs.research_limits("quick")
        dm, dt = jobs.research_limits("deep")
        self.assertLess(qm, dm)
        self.assertLess(qt, dt)
        self.assertEqual((dm, dt), (int(jobs.CONF["MAX_MINUTES"]), int(jobs.CONF["MAX_TURNS"])))
        self.assertIn("5", jobs.research_prompt({**parse(), "mode": "quick"}))

    def test_report_problems(self):
        self.assertEqual(jobs.report_problems("## Antwoord\nx\n## Bronnen\n- y\n## Doorzocht\n- z"), [])
        self.assertEqual(jobs.report_problems("NIETS GEVONDEN\n\n## Doorzocht\n- z"), [])
        self.assertIn("no sources", jobs.report_problems("## Antwoord\nx\n## Doorzocht\n- z"))
        self.assertIn("no list of what was searched", jobs.report_problems("NIETS GEVONDEN"))


class Ask(unittest.TestCase):
    def setUp(self):
        reset()

    def test_a_question_is_one_claude_run_against_the_cap(self):
        code, body = jobs.ask_research("What is X?", "deep")
        self.assertEqual(code, 200)
        self.assertEqual(jobs.claude_runs_today(), 1)
        with db() as c:
            run = c.execute("SELECT * FROM job_runs WHERE id=?", (body["run"],)).fetchone()
            t = c.execute("SELECT * FROM tasks WHERE run_id=?", (body["run"],)).fetchone()
        self.assertEqual((run["kind"], run["mode"], run["deliver"], run["source"]), ("research", "deep", "page", None))
        self.assertEqual((t["job"], t["status"]), ("research", "queued"))
        self.assertIn("What is X?", t["text"])
        self.assertIn("Modus: deep", t["text"])

    def test_above_the_cap_it_is_refused_with_a_message_and_nothing_starts(self):
        with mock.patch.dict(jobs.CONF, {"JOB_CLAUDE_CAP": "1"}):
            self.assertEqual(jobs.ask_research("one", "quick")[0], 200)
            code, body = jobs.ask_research("two", "quick")
        self.assertEqual(code, 429)
        self.assertIn("daily limit", body["error"])
        self.assertEqual(jobs.claude_runs_today(), 1)
        with db() as c:
            self.assertEqual(c.execute("SELECT COUNT(*) FROM tasks").fetchone()[0], 1)

    def test_a_scheduled_research_job_counts_and_waits_like_any_claude_job(self):
        job = parse()
        with mock.patch.dict(jobs.CONF, {"JOB_CLAUDE_CAP": "1"}), mock.patch.object(jobs, "notify"):
            for _ in range(2):
                with db() as c:
                    rid = c.execute("INSERT INTO job_runs (job, kind, model, due, created, status, mode) VALUES "
                                    "(?,?,?,?,?,?,?)", (job["name"], "research", "claude", "x", jobs.now(), "queued",
                                                        job["mode"])).lastrowid
                jobs.start_claude(rid, job)
        with db() as c:
            statuses = [r["status"] for r in c.execute("SELECT status FROM job_runs ORDER BY id")]
        self.assertEqual(statuses, ["claude", "held"])

    def test_bad_input(self):
        self.assertEqual(jobs.ask_research("", "quick")[0], 400)
        self.assertEqual(jobs.ask_research("q", "slow")[0], 400)

    def test_chat_needs_the_research_permission_and_delivers_to_the_conversation(self):
        self.assertEqual(chat.research("reader", "q", "quick")[0], 403)
        code, body = chat.research("discord", "q?", "quick")
        self.assertEqual(code, 200)
        with db() as c:
            run = c.execute("SELECT * FROM job_runs WHERE id=?", (body["run"],)).fetchone()
        self.assertEqual((run["source"], run["deliver"]), ("discord", "conversation"))

    def test_chat_refusal_above_the_cap_is_a_clear_message(self):
        with mock.patch.dict(jobs.CONF, {"JOB_CLAUDE_CAP": "0"}):
            code, body = chat.research("discord", "q?", "quick")
        self.assertEqual(code, 429)
        self.assertIn("daily limit", body["error"])


class Finish(unittest.TestCase):
    def setUp(self):
        reset()

    def start(self, source=None, mode="quick"):
        _, body = jobs.ask_research("What is X?", mode) if not source else chat.research("discord", "What is X?", mode)
        return get(next(iter(db().execute("SELECT id FROM tasks WHERE run_id=?", (body["run"],)).fetchone())))

    def finish(self, t, text, capped=None):
        with mock.patch.object(queue, "push_branch", side_effect=AssertionError("research must never push")), \
                mock.patch.object(queue, "task_cmd") as cmd, mock.patch.object(queue, "notify") as n, \
                mock.patch.object(queue, "gh", side_effect=AssertionError("research must never open a PR")):
            queue.finish_round(t["id"], "cache", text, {}, capped)
        return get(t["id"]), cmd, n

    def test_report_has_mode_and_time_and_sources_and_stays_on_the_page(self):
        t = self.start(mode="deep")
        t, cmd, _ = self.finish(t, REPORT)
        self.assertEqual(t["status"], "answered")
        first = t["answer"].splitlines()[0]
        self.assertRegex(first, r"^Research report \| mode: deep \| time taken: \d+ (min \d+ )?s$")
        self.assertIn("https://example.org/a", t["answer"])
        self.assertIn(("cleanup", t["id"]), [c.args[:2] for c in cmd.call_args_list])
        self.assertIn("https://example.org/a", pages.task_page(t))
        self.assertEqual(jobs.job_runs("research")[0]["mode"], "deep")

    def test_page_question_delivers_nothing_extra(self):
        t = self.start()
        before = len(conversation.read(0, 100))
        self.finish(t, REPORT)
        self.assertEqual(len(conversation.read(0, 100)), before)

    def test_discord_question_gets_its_report_in_the_conversation(self):
        t = self.start(source="discord")
        before = len(conversation.read(0, 100))
        t, _, _ = self.finish(t, REPORT)
        new = conversation.read(0, 100)[before:]
        report = [e for e in new if "Research report" in e["text"]]
        self.assertEqual(len(report), 1)
        self.assertIn("mode: quick", report[0]["text"])
        self.assertIn("https://example.org/a", report[0]["text"])
        self.assertTrue(report[0]["link"])

    def test_nothing_found_is_delivered_as_the_report(self):
        t = self.start(source="discord")
        text = "ANTWOORD: NIETS GEVONDEN\nNo source covers this.\n\n## Doorzocht\n- private: x\n- web: y"
        t, _, _ = self.finish(t, text)
        self.assertIn("NIETS GEVONDEN", t["answer"])
        self.assertIn("## Doorzocht", t["answer"])
        self.assertIsNone(t["note"])

    def test_report_without_sources_is_flagged(self):
        t, _, _ = self.finish(self.start(), "ANTWOORD: ## Antwoord\nA claim.\n\n## Doorzocht\n- x")
        self.assertIn("no sources", t["note"])

    def test_no_report_is_a_failure_not_a_pr(self):
        t, _, n = self.finish(self.start(), "KLAAR: I changed things")
        self.assertEqual(t["status"], "failed")
        self.assertIsNone(t["pr_number"])
        n.assert_called_once()

    def test_a_run_that_hits_its_limit_pushes_nothing(self):
        t, cmd, n = self.finish(self.start(), "KLAAR: half", capped="6 minuten")
        self.assertEqual(t["status"], "capped")
        self.assertIsNone(t["pr_number"])
        self.assertIn("6 minuten", n.call_args.args[0])

    def test_system_prompt_is_the_read_only_one(self):
        t = self.start()
        rounds.write_prompt(t, "p")
        system = (rounds.HOME / "share" / str(t["id"]) / "system.txt").read_text()
        self.assertIn("Je commit niets", system)
        self.assertNotIn("KLAAR", system)


class Permissions(unittest.TestCase):
    """The read-only guarantee sits in the tools the run gets (bin/homelab-inbox-task), not only in the prompt."""
    SCRIPT = (ROOT / "bin/homelab-inbox-task").read_text()

    def research_block(self):
        return self.SCRIPT.split("== research ]]; then", 1)[1].split("\n    fi\n", 1)[0]

    def test_tools_are_read_only(self):
        block = self.research_block()
        self.assertIn('--tools "Read,Grep,Glob,WebSearch,WebFetch,Bash"', block)
        self.assertIn("--permission-mode dontAsk", block)
        self.assertNotIn("bypassPermissions", block)
        self.assertNotRegex(block.split("--disallowedTools")[0], r'--tools "[^"]*(Edit|Write)')
        for denied in ('"Edit"', '"Write"', '"Bash(git:*)"', '"Bash(homelab-admin:*)"', '"Bash(sudo:*)"'):
            self.assertIn(denied, block.split("--disallowedTools")[1])
        allowed = block.split("--allowedTools")[1].split("--disallowedTools")[0]
        self.assertIn("Bash(homelab-search query:*)", allowed)
        self.assertNotIn("Bash(*", allowed)

    def test_run_accepts_only_the_research_kind(self):
        self.assertIn('[[ -z $kind || $kind == research ]]', self.SCRIPT)


if __name__ == "__main__":
    unittest.main()


class Page(unittest.TestCase):
    def setUp(self):
        reset()

    def test_form_asks_question_and_mode_and_posts_to_ask(self):
        html = pages.research_page()
        self.assertIn('action="/research/ask"', html)
        self.assertIn('name="question"', html)
        self.assertIn('<option value="quick">', html)
        self.assertIn('<option value="deep">', html)

    def test_asked_question_shows_in_the_history_and_an_error_keeps_the_question(self):
        self.assertEqual(jobs.ask_research("What is <X>?", "quick")[0], 200)
        self.assertIn("quick", pages.research_page())
        html = pages.research_page(error="daily limit reached", question="Keep <me>")
        self.assertIn("daily limit reached", html)
        self.assertIn("Keep &lt;me&gt;", html)
