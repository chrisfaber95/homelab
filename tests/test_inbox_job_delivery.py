"""Result delivery of jobs (H-017): the deliver setting, 'nothing to report', and that nothing else changed.
Run: python3 -m unittest discover -s tests"""
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
from homelab_inbox import conversation, jobs, pages  # noqa: E402

HEAD = '---\nwhen: daily\nschedule: "0 7 * * *"\nkind: box-report\nmodel: local\n{extra}---\n{body}\n'
NOTHING = "Nothing broke: answer NOTHING TO REPORT as the first line."


def parse(extra="", body="Report what failed."):
    return jobs.parse_job("demo", HEAD.format(extra=extra, body=body), repos={}, skills={})


def job(deliver):
    return {"name": "demo", "deliver": deliver, "instruction": NOTHING}


def entries():
    return [e for e in conversation.read(0, 1000) if e["ref"] and e["ref"].startswith("job:demo")]


class Format(unittest.TestCase):
    def test_default_is_page_and_old_files_parse(self):
        self.assertEqual(parse()["deliver"], "page")

    def test_values(self):
        for v in ("ntfy", "conversation"):
            self.assertEqual(parse(f"deliver: {v}\n", NOTHING)["deliver"], v)
        self.assertEqual(parse("deliver: page\n")["deliver"], "page")

    def test_unknown_value_is_an_error(self):
        with self.assertRaisesRegex(ValueError, "deliver moet"):
            parse("deliver: mail\n", NOTHING)

    def test_delivering_job_must_define_nothing_to_report(self):
        with self.assertRaisesRegex(ValueError, "NOTHING TO REPORT"):
            parse("deliver: ntfy\n")

    def test_page_shows_the_statement(self):
        j = parse("deliver: conversation\n", NOTHING)
        self.assertIn("NOTHING TO REPORT", jobs.nothing_statement(j))
        self.assertIn("Niets te melden", pages.job_facts(j))
        self.assertNotIn("Niets te melden", pages.job_facts(parse()))


class Delivery(unittest.TestCase):
    def test_page_sends_nothing(self):
        before = len(entries())
        with mock.patch.object(jobs, "notify") as n:
            self.assertFalse(jobs.deliver_result(job("page"), 1, "result", "http://x/run/1"))
        n.assert_not_called()
        self.assertEqual(len(entries()), before)

    def test_ntfy_sends_in_full_with_link(self):
        with mock.patch.object(jobs, "notify") as n:
            self.assertTrue(jobs.deliver_result(job("ntfy"), 2, "result " * 100, "http://x/run/2"))
        args, kw = n.call_args
        self.assertEqual(args[2], "http://x/run/2")
        self.assertGreater(kw["limit"], 300)

    def test_conversation_gets_full_scrubbed_text_and_link(self):
        text = "A long result. " * 300 + "ghp_" + "a" * 36
        self.assertTrue(jobs.deliver_result(job("conversation"), 3, text, "http://x/task/3"))
        e = entries()[-1]
        self.assertEqual(e["link"], "http://x/task/3")
        self.assertIn("A long result. " * 300, e["text"])
        self.assertNotIn("ghp_", e["text"])

    def test_nothing_to_report_sends_nothing(self):
        before = len(entries())
        with mock.patch.object(jobs, "notify") as n:
            for d in ("ntfy", "conversation"):
                self.assertFalse(jobs.deliver_result(job(d), 4, "NOTHING TO REPORT\nall quiet", "http://x"))
        n.assert_not_called()
        self.assertEqual(len(entries()), before)

    def test_marker_only_counts_as_first_line(self):
        self.assertTrue(jobs.is_nothing("  **Nothing to report**.\n"))
        self.assertFalse(jobs.is_nothing("Disk full.\nNOTHING TO REPORT else"))


class LocalRun(unittest.TestCase):
    def run_local(self, deliver):
        run_id = None
        with mock.patch.object(jobs, "gather", return_value="data"), \
                mock.patch.object(jobs, "call_llm", return_value=self.answer), \
                mock.patch.object(jobs, "deliver_result", wraps=jobs.deliver_result) as d, \
                mock.patch.object(jobs, "notify") as n:
            with jobs.db() as c:
                run_id = c.execute("INSERT INTO job_runs (job, kind, model, due, created, status, manual) "
                                   "VALUES ('demo','box-report','local','x','x','queued',0)").lastrowid
            jobs.run_local(run_id, {**parse(), "deliver": deliver, "instruction": NOTHING})
        with jobs.db() as c:
            row = c.execute("SELECT * FROM job_runs WHERE id=?", (run_id,)).fetchone()
        return row, d, n

    def test_result_stays_in_history_when_nothing_to_report(self):
        self.answer = "NOTHING TO REPORT"
        row, _, n = self.run_local("conversation")
        self.assertEqual((row["status"], row["summary"]), ("ok", "NOTHING TO REPORT"))

    def test_failure_notification_unchanged(self):
        with mock.patch.object(jobs, "gather", side_effect=RuntimeError("boom")), mock.patch.object(jobs, "notify") as n:
            with jobs.db() as c:
                rid = c.execute("INSERT INTO job_runs (job, kind, model, due, created, status, manual) "
                                "VALUES ('demo','box-report','local','x','x','queued',0)").lastrowid
            jobs.run_local(rid, {**parse(), "deliver": "ntfy", "instruction": NOTHING})
        self.assertEqual(n.call_args[0][0], "Job demo mislukt")
        self.assertEqual(len(n.call_args[0]), 3)


if __name__ == "__main__":
    unittest.main()
