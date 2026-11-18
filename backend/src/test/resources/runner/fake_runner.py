#!/usr/bin/env python3
"""Fake runner for RunnerServiceTest: speaks the NDJSON contract of runner/SCHEMA.md and docs/architecture/inbox-split.md.

fake_runner.py <mode> <dir> [args...]   modes: ok, fail, hang, stubborn, echo, kill
`kill <dir>` is the fake of `homelab-inbox-task kill <id>`: it records the call and asks the runner in <dir>/pid to stop.
"""
import json, os, signal, subprocess, sys, time

mode, d = sys.argv[1], sys.argv[2]


def emit(o):
    o.setdefault("v", 1)
    print(json.dumps(o), flush=True)


if mode == "kill":
    open(os.path.join(d, "kill-called"), "w").write(" ".join(sys.argv[1:]))
    if os.path.exists(os.path.join(d, "obey")):
        os.kill(int(open(os.path.join(d, "pid")).read()), signal.SIGTERM)
    sys.exit(0)

open(os.path.join(d, "pid"), "w").write(str(os.getpid()))
emit({"event": "model", "model": "fake"})
print("not json, ignored", flush=True)
sys.stderr.write("diagnostic line\n")
sys.stderr.flush()

if mode == "ok":
    emit({"event": "turn", "turns": 1})
    emit({"event": "result", "outcome": "done", "text": "hello"})
elif mode == "fail":
    emit({"event": "result", "outcome": "none", "text": "bad"})
    sys.exit(3)
elif mode == "echo":
    emit({"event": "result", "outcome": "done", "argv": sys.argv[3:], "stdin": sys.stdin.read()})
elif mode in ("hang", "stubborn"):
    if mode == "stubborn":
        signal.signal(signal.SIGTERM, signal.SIG_IGN)
        child = subprocess.Popen(["sleep", "300"])
        open(os.path.join(d, "child"), "w").write(str(child.pid))
    signal.signal(signal.SIGTERM, signal.SIG_IGN) if mode == "stubborn" else None
    open(os.path.join(d, "ready"), "w").write("1")
    while True:
        time.sleep(0.1)
