#!/usr/bin/env python3
"""Runner for the router's Claude call (H-025 R2, part 1): wraps `homelab-inbox-task route r<id>` and owns the parsing
of Claude's `--output-format json` document.

Usage: route.py <task id>
The prompt and system prompt are files in RUNNER_HOME/share/r<id>/ (written by the caller); nothing sensitive is in the
arguments. stdout is NDJSON, each line with "v":1: one final {"event":"result",...} (see "Runner route.py" in
runner/SCHEMA.md). stderr is passed through. The exit code is that of `homelab-inbox-task route`; 2 for bad arguments.
Standalone: imports nothing from the inbox. Env: TASK_CMD overrides the command that runs homelab-inbox-task."""
import json
import os
import re
import shlex
import signal
import subprocess
import sys
import tempfile

V = 1
DEFAULT_TASK_CMD = "sudo -n -u inbox /usr/local/lib/homelab/bin/homelab-inbox-task"
ACTION = "route"
ID_PREFIX = "r"


def emit(event, **fields):
    sys.stdout.write(json.dumps({"event": event, "v": V, **fields}, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def claude_document(out):
    """Claude's JSON result in the command's output: the last JSON object that has `result` or `is_error`, else {}.
    Text around it (a warning line after the JSON, e.g. `[claude-code:unrecognized_model]`) does not break the parse."""
    dec, pos, found = json.JSONDecoder(), 0, {}
    while True:
        pos = out.find("{", pos)
        if pos < 0:
            return found
        try:
            obj, end = dec.raw_decode(out, pos)
        except ValueError:
            pos += 1
            continue
        if isinstance(obj, dict) and ("result" in obj or "is_error" in obj):
            found = obj
        pos = end


def main(argv):
    if len(argv) != 1 or not re.fullmatch(r"[0-9]{1,6}", argv[0]):
        sys.stderr.write(f"usage: {os.path.basename(sys.argv[0])} <task id>\n")
        return 2
    cmd = [*shlex.split(os.environ.get("TASK_CMD", DEFAULT_TASK_CMD)), ACTION, f"{ID_PREFIX}{argv[0]}"]
    with tempfile.TemporaryFile(mode="w+", errors="replace") as err:
        child = subprocess.Popen(cmd, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=err, text=True,
                                 errors="replace")

        def forward(signum, _frame):
            if child.poll() is None:
                child.send_signal(signum)

        signal.signal(signal.SIGTERM, forward)
        signal.signal(signal.SIGINT, forward)
        out = child.communicate()[0]
        code = child.returncode
        if code < 0:
            code = 128 - code
        err.seek(0)
        errtext = err.read()
    if errtext:
        sys.stderr.write(errtext)
    doc = claude_document(out)
    text = doc.get("result") if isinstance(doc.get("result"), str) else ""
    emit("result", text=text, is_error=bool(doc.get("is_error")) or code != 0 or not doc, exit_code=code,
         raw=doc or None, stderr=errtext.strip()[-1000:])
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
