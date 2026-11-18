#!/usr/bin/env python3
"""Runner for one Claude round (H-025 R1): wraps `homelab-inbox-task run` and owns the stream-json parsing.

Usage: round.py <task id> <session uuid> <new|resume> [research]
The prompt and system prompt are files in RUNNER_HOME/share/<id>/ (written by the caller); nothing sensitive is in the
arguments. stdout is NDJSON, each line with "v":1 (see "Runner round.py" in runner/SCHEMA.md); stderr is Claude's own and
diagnostics. The exit code is that of `homelab-inbox-task run`. Standalone: imports nothing from the inbox.
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
OUTCOMES = {"KLAAR": "done", "ANTWOORD": "answer", "VRAAG": "question", "GEWEIGERD": "refused"}


def emit(event, **fields):
    sys.stdout.write(json.dumps({"event": event, "v": V, **fields}, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def tool_brief(inp):
    for k in ("command", "file_path", "pattern", "path", "url", "description", "prompt"):
        if inp.get(k):
            return str(inp[k])[:240]
    return json.dumps(inp, ensure_ascii=False)[:240]


def stream_lines(ev):
    """What happens in a stream-json event of Claude Code, as readable log lines: (kind, text)."""
    kind = ev.get("type")
    if kind == "system" and ev.get("subtype") == "init":
        return [("info", f"sessie gestart, model {ev.get('model', '?')}")]
    if kind == "result":
        return [("info", f"ronde klaar na {ev.get('num_turns', '?')} beurten, "
                         f"{round((ev.get('duration_ms') or 0) / 1000)} s")]
    out = []
    for b in (ev.get("message") or {}).get("content") or [] if kind in ("assistant", "user") else []:
        if not isinstance(b, dict):
            continue
        if b.get("type") == "text" and b.get("text", "").strip():
            out.append(("claude", b["text"].strip()[:1500]))
        elif b.get("type") == "tool_use":
            out.append(("tool", f"{b.get('name')}: {tool_brief(b.get('input') or {})}"))
        elif b.get("type") == "tool_result":
            c = b.get("content")
            txt = (c if isinstance(c, str) else " ".join(x.get("text", "") for x in c or [] if isinstance(x, dict))).strip()
            n = len(txt.splitlines())
            first = txt.splitlines()[0][:200] if txt else "(leeg)"
            out.append(("error" if b.get("is_error") else "result", first + (f" (+{n - 1} regels)" if n > 1 else "")))
    return out


def outcome_of(text):
    return OUTCOMES.get((text or "").strip().partition(":")[0].strip().upper(), "none")


def main(argv):
    if len(argv) not in (3, 4) or not re.fullmatch(r"[0-9]{1,6}", argv[0]) or (len(argv) == 4 and argv[3] != "research"):
        sys.stderr.write("usage: round.py <task id> <session> <new|resume> [research]\n")
        return 2
    cmd = [*shlex.split(os.environ.get("TASK_CMD", DEFAULT_TASK_CMD)), "run", *argv]
    child = subprocess.Popen(cmd, stdout=subprocess.PIPE, text=True, errors="replace")  # stderr is ours: passes through

    def forward(signum, _frame):
        # The backend's cancel: the child (sudo) relays it to Claude; we leave no child behind.
        if child.poll() is None:
            child.send_signal(signum)

    signal.signal(signal.SIGTERM, forward)
    signal.signal(signal.SIGINT, forward)
    turns, result = 0, None
    for line in child.stdout:
        try:
            ev = json.loads(line)
        except ValueError:
            continue
        if not isinstance(ev, dict):
            continue
        for kind, text in stream_lines(ev):
            emit("log", kind=kind, text=text)
        if ev.get("type") == "system" and ev.get("model"):
            emit("model", model=ev["model"])
        if ev.get("type") == "assistant":
            turns += 1
            emit("turn", turns=turns)
        elif ev.get("type") == "result":
            result = ev
    code = child.wait()
    if code < 0:
        code = 128 - code
    text = result.get("result") if result and isinstance(result.get("result"), str) else ""
    emit("result", outcome=outcome_of(text) if result and not result.get("is_error") else "none", text=text,
         turns=turns, exit_code=code, raw=result)
    return code


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
