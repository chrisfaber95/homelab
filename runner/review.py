#!/usr/bin/env python3
"""Runner for one brief review (H-025 R2): wraps `homelab-inbox-task review v<id>` and owns the parsing of Claude's
`--output-format json` document.

Usage: review.py <task id>
The prompt and system prompt are files in RUNNER_HOME/share/v<id>/ (written by the caller); nothing sensitive is in the
arguments. stdout is NDJSON, each line with "v":1 (see "Runner review.py" in runner/SCHEMA.md): a log line and one final
result event. stderr is the task script's and diagnostics. The exit code is that of `homelab-inbox-task review`
(2 for bad runner arguments). Standalone: imports nothing from the inbox.
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
    """Claude's JSON result in the output of the task script. The output can carry a warning line after the JSON (stderr
    is appended, e.g. `[claude-code:unrecognized_model]`): read JSON objects one by one, take the first with `result` or
    `is_error`, else the first object, else {}."""
    dec, pos, first = json.JSONDecoder(), 0, None
    while True:
        pos = out.find("{", pos)
        if pos < 0:
            return first or {}
        try:
            data, end = dec.raw_decode(out[pos:])
        except ValueError:
            return first or {}
        pos += end
        if isinstance(data, dict):
            if "result" in data or "is_error" in data:
                return data
            first = first or data


def main(argv):
    if len(argv) != 1 or not re.fullmatch(r"[0-9]{1,6}", argv[0]):
        sys.stderr.write("usage: review.py <task id>\n")
        return 2
    cmd = [*shlex.split(os.environ.get("TASK_CMD", DEFAULT_TASK_CMD)), "review", f"v{argv[0]}"]
    child = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, errors="replace")

    def forward(signum, _frame):
        # The caller's deadline or cancel: the child (sudo) relays it to Claude; we leave no child behind.
        if child.poll() is None:
            child.send_signal(signum)

    signal.signal(signal.SIGTERM, forward)
    signal.signal(signal.SIGINT, forward)
    emit("log", kind="info", text=f"review v{argv[0]} started")
    out, err = child.communicate()
    code = child.returncode
    if code < 0:
        code = 128 - code
    raw = claude_result(out)
    text = raw.get("result") if isinstance(raw.get("result"), str) else ""
    is_error = bool(raw.get("is_error")) or code != 0 or not raw
    detail = "" if text and not is_error else (out + err).strip()[-1000:]
    if err.strip():
        sys.stderr.write(err)
    emit("result", ok=not is_error, text=text, detail=detail, exit_code=code, raw=raw or None)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
