#!/usr/bin/env python3
"""Runner for one job draft (H-025 R2): wraps `homelab-inbox-task draft` and owns the parsing of Claude's JSON answer.

Usage: draft.py d<draft id>
The prompt and system prompt are files in RUNNER_HOME/share/d<id>/ (written by the caller); nothing sensitive is in the
arguments. stdout is NDJSON, each line with "v":1 (see "Runner draft.py" in runner/SCHEMA.md); stderr is Claude's own and
diagnostics. The exit code is that of `homelab-inbox-task draft`. Standalone: imports nothing from the inbox.
Env: TASK_CMD overrides the command that runs homelab-inbox-task (tests use the fake)."""
import json
import os
import re
import shlex
import signal
import subprocess
import sys

V = 1
DEFAULT_TASK_CMD = "sudo -n -u inbox /usr/local/lib/homelab/bin/homelab-inbox-task"


def emit(event, **fields):
    sys.stdout.write(json.dumps({"event": event, "v": V, **fields}, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def claude_result(out):
    """The JSON document of `claude -p --output-format json`. A warning line before or after it (for example
    `[claude-code:unrecognized_model]`) must not break the parse and hide the real error."""
    start = out.find("{")
    if start < 0:
        return {}
    try:
        data, _ = json.JSONDecoder().raw_decode(out[start:])
    except ValueError:
        return {}
    return data if isinstance(data, dict) else {}


def main(argv):
    if len(argv) != 1 or not re.fullmatch(r"d[0-9]{1,6}", argv[0]):
        sys.stderr.write("usage: draft.py d<draft id>\n")
        return 2
    cmd = [*shlex.split(os.environ.get("TASK_CMD", DEFAULT_TASK_CMD)), "draft", argv[0]]
    child = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace")

    def forward(signum, _frame):
        if child.poll() is None:
            child.send_signal(signum)

    signal.signal(signal.SIGTERM, forward)
    signal.signal(signal.SIGINT, forward)
    out, err = child.communicate()
    out, err = out or "", err or ""
    sys.stderr.write(err)  # Claude's own stderr stays visible to the caller
    code = child.returncode
    if code < 0:
        code = 128 - code
    raw = claude_result(out)
    text = raw.get("result") if isinstance(raw.get("result"), str) else ""
    emit("result", text=text, is_error=bool(raw.get("is_error")) or code != 0, exit_code=code, raw=raw or None,
         output_tail=(out + err).strip()[-300:])
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
