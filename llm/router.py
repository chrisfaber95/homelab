#!/usr/bin/env python3
"""homelab: LLM router (H-012). One place to send an LLM request.

A request carries a caller and a tier. config/router.json maps the tier to a backend: the local model (the F-017
core in jobs.py) or the `claude` CLI (Pro subscription; no API key is used or stored, and the key variables are
removed from the environment of the CLI). Per tier the config says what happens on failure or timeout: fail, or
escalate to the other backend. Every request is logged to an append-only SQLite table.

  POST /v1/route     {"caller", "tier", "messages" | "prompt", "system"?, "job"?, "timeout"?}
  GET  /v1/tiers     the tier table
  GET  /v1/usage?days=7
  GET  /health

Only stdlib. Overridable for tests: ROUTER_CONFIG, ROUTER_DB, ROUTER_PORT.
"""
import json
import os
import re
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import jobs  # noqa: E402
import netutil  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
CALLER_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$")
OTHER = {"local": "claude", "claude": "local"}
ON_FAILURE = ("fail", "escalate")
MAX_BODY = 1_000_000
# The claude CLI must run on the subscription: these would switch it to API billing.
API_ENV = ("ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN")


class RouterError(Exception):
    def __init__(self, status, message, code):
        super().__init__(message)
        self.status, self.code = status, code


# --- Config: the tier table

_conf_cache = {}


def config_path():
    return Path(os.environ.get("ROUTER_CONFIG") or ROOT / "config" / "router.json")


def load_tiers():
    """The parsed and validated table, re-read when the file changes (no restart needed)."""
    path = config_path()
    mtime = path.stat().st_mtime_ns
    if _conf_cache.get("key") == (str(path), mtime):
        return _conf_cache["conf"]
    raw = json.loads(path.read_text())
    tiers = raw.get("tiers")
    if not isinstance(tiers, dict) or not tiers:
        raise ValueError("router.json: 'tiers' must be an object with at least one tier")
    for name, t in tiers.items():
        if not CALLER_RE.match(name):
            raise ValueError(f"router.json: bad tier name {name!r}")
        if t.get("backend") not in OTHER:
            raise ValueError(f"router.json: tier {name}: backend must be one of {', '.join(OTHER)}")
        if t.get("on_failure", "fail") not in ON_FAILURE:
            raise ValueError(f"router.json: tier {name}: on_failure must be one of {', '.join(ON_FAILURE)}")
        if not isinstance(t.get("timeout"), (int, float)) or not 0 < t["timeout"] <= jobs.MAX_TIMEOUT:
            raise ValueError(f"router.json: tier {name}: timeout must be a number of seconds, 1 to {jobs.MAX_TIMEOUT}")
    conf = {"tiers": tiers, "claude": {"command": "claude", "model": None, "extra_args": []} | raw.get("claude", {})}
    _conf_cache.update(key=(str(path), mtime), conf=conf)
    return conf


# --- Log: append-only

_db_lock = threading.Lock()


def db_path():
    return os.environ.get("ROUTER_DB") or str(Path(os.environ.get("STATE_DIRECTORY", ".")) / "router.db")


def db():
    conn = sqlite3.connect(db_path())
    conn.execute("""CREATE TABLE IF NOT EXISTS requests (
        ts TEXT NOT NULL, caller TEXT NOT NULL, tier TEXT NOT NULL, backend TEXT NOT NULL,
        escalated_from TEXT, outcome TEXT NOT NULL CHECK (outcome IN ('ok', 'failed', 'escalated')),
        duration_ms INTEGER NOT NULL, error TEXT, prompt_tokens INTEGER, completion_tokens INTEGER)""")
    for verb in ("UPDATE", "DELETE"):
        conn.execute(f"""CREATE TRIGGER IF NOT EXISTS requests_no_{verb.lower()} BEFORE {verb} ON requests
                         BEGIN SELECT RAISE(ABORT, 'requests is append-only'); END""")
    return conn


def log_request(**row):
    row["ts"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    with _db_lock, db() as conn:
        conn.execute(f"INSERT INTO requests ({', '.join(row)}) VALUES ({', '.join('?' * len(row))})",
                     list(row.values()))


def usage(days):
    since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat(timespec="seconds")
    with _db_lock, db() as conn:
        rows = conn.execute("""SELECT caller, tier, backend, outcome, COUNT(*), COALESCE(SUM(duration_ms), 0),
                                      COALESCE(SUM(prompt_tokens), 0), COALESCE(SUM(completion_tokens), 0)
                               FROM requests WHERE ts >= ? GROUP BY caller, tier, backend, outcome
                               ORDER BY COUNT(*) DESC""", (since,)).fetchall()
    keys = ("caller", "tier", "backend", "outcome", "count", "duration_ms", "prompt_tokens", "completion_tokens")
    groups = [dict(zip(keys, r)) for r in rows]
    total = sum(g["count"] for g in groups)
    served = [g for g in groups if g["outcome"] != "failed"]
    local = sum(g["count"] for g in served if g["backend"] == "local")
    return {"days": days, "since": since, "total": total, "groups": groups,
            "served_locally": local, "share_local": round(local / sum(g["count"] for g in served), 3) if served else None,
            "escalations": sum(g["count"] for g in groups if g["outcome"] == "escalated"),
            # Estimate: a request the local model answered is a Claude run that was not made.
            "claude_runs_avoided": local}


# --- Backends: (request, timeout) -> (text, prompt_tokens, completion_tokens); errors are jobs.JobError

def _messages(req):
    if req.get("messages"):
        return req["messages"]
    return [{"role": "user", "content": req["prompt"]}]


def run_local(req, timeout):
    job = req.get("job")
    if job:
        text, resp = jobs.run_job(job, {**req, "messages": _messages(req)}, timeout)
    else:
        text, resp = jobs.run_chat(_messages(req), timeout, req.get("system"))
    return text, resp.get("prompt_eval_count"), resp.get("eval_count")


def claude_prompt(req):
    parts = []
    system = req.get("system")
    schema = None
    if req.get("job"):
        system, schema = jobs.job_system(req["job"], req)
    if system:
        parts.append(system)
    msgs = _messages(req)
    if len(msgs) == 1 and msgs[0].get("role", "user") == "user":
        parts.append(msgs[0].get("content") or "")
    else:
        parts += [f"{m.get('role', 'user')}: {m.get('content') or ''}" for m in msgs]
    return "\n\n".join(parts), schema


def parse_claude_output(raw):
    """(text, prompt_tokens, completion_tokens) from `claude -p --output-format json`; tokens None if absent."""
    try:
        data = json.loads(raw)
    except ValueError:
        raise jobs.JobError(502, f"claude gaf geen JSON: {raw[:200]!r}", "claude_error")
    if isinstance(data, list):  # newer CLIs may print the event list: take the result event
        data = next((e for e in reversed(data) if isinstance(e, dict) and e.get("type") == "result"), {})
    if not isinstance(data, dict) or data.get("is_error") or not isinstance(data.get("result"), str):
        raise jobs.JobError(502, f"claude meldde een fout: {str(data.get('result') if isinstance(data, dict) else data)[:300]}",
                            "claude_error")
    u = data.get("usage") if isinstance(data.get("usage"), dict) else {}
    prompt = sum(u.get(k) or 0 for k in ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"))
    completion = u.get("output_tokens")
    return data["result"].strip(), (prompt if u else None), completion


def run_claude(req, timeout):
    conf = load_tiers()["claude"]
    prompt, schema = claude_prompt(req)
    cmd = [conf["command"], "-p", "--output-format", "json"]
    if conf.get("model"):
        cmd += ["--model", conf["model"]]
    cmd += list(conf.get("extra_args") or [])
    env = {k: v for k, v in os.environ.items() if k not in API_ENV}
    with tempfile.TemporaryDirectory(prefix="router-") as cwd:  # a plain question needs no repo
        try:
            out = subprocess.run(cmd, input=prompt, capture_output=True, text=True, timeout=timeout, cwd=cwd, env=env)
        except subprocess.TimeoutExpired:
            raise jobs.JobError(504, f"De job duurde langer dan {timeout:.0f} s (timeout).", "timeout")
        except OSError as e:
            raise jobs.JobError(503, f"claude is niet te starten: {e}", "claude_unavailable")
    if out.returncode != 0:
        raise jobs.JobError(502, f"claude stopte met code {out.returncode}: {(out.stderr or out.stdout)[:300]}",
                            "claude_error")
    text, pt, ct = parse_claude_output(out.stdout)
    if schema:
        try:
            value = json.loads(re.sub(r"^```(?:json)?\s*|\s*```$", "", text.strip()))
            jobs.validate(value, schema)
            text = json.dumps(value, ensure_ascii=False)
        except ValueError as e:
            raise jobs.JobError(502, f"ongeldige JSON van claude: {e}", "invalid_output")
    if not text:
        raise jobs.JobError(502, "claude gaf een leeg antwoord", "invalid_output")
    return text, pt, ct


BACKENDS = {"local": run_local, "claude": run_claude}  # tests replace entries with fakes


# --- Route

def parse_request(req):
    if not isinstance(req, dict):
        raise RouterError(400, "De body is geen JSON-object.", "bad_request")
    caller, tier = req.get("caller"), req.get("tier")
    if not isinstance(caller, str) or not CALLER_RE.match(caller):
        raise RouterError(400, "caller is verplicht (letters, cijfers, . _ : -, tot 64 tekens).", "bad_request")
    if not isinstance(tier, str) or not tier:
        raise RouterError(400, "tier is verplicht.", "bad_request")
    if not (isinstance(req.get("prompt"), str) and req["prompt"].strip()) and not (
            isinstance(req.get("messages"), list) and req["messages"]):
        raise RouterError(400, "prompt of messages is verplicht.", "bad_request")
    if req.get("job") not in (None, *jobs.JOBS):
        raise RouterError(400, f"Onbekende job {req.get('job')!r}; kies uit {', '.join(jobs.JOBS)}.", "bad_request")
    return caller, tier


def route(req):
    """Run one request. Returns the result dict; raises RouterError (already logged, except bad requests)."""
    caller, tier = parse_request(req)
    try:
        conf = load_tiers()
    except (OSError, ValueError) as e:
        raise RouterError(500, f"router.json is onbruikbaar: {e}", "bad_config")
    if tier not in conf["tiers"]:
        raise RouterError(400, f"Onbekende tier {tier!r}; kies uit {', '.join(conf['tiers'])}.", "unknown_tier")
    t = conf["tiers"][tier]
    try:
        timeout = float(req.get("timeout") or t["timeout"])
    except (TypeError, ValueError):
        raise RouterError(400, "timeout moet een getal (seconden) zijn.", "bad_request")
    if not 0 < timeout <= jobs.MAX_TIMEOUT:
        raise RouterError(400, f"timeout moet tussen 0 en {jobs.MAX_TIMEOUT} s liggen.", "bad_request")

    start = time.monotonic()
    attempts = [t["backend"]]
    errors = []
    result = None
    while True:
        name = attempts[-1]
        try:
            result = BACKENDS[name](req, timeout)
            break
        except jobs.JobError as e:
            err = e
        except Exception as e:  # a backend bug must still end in a logged, visible failure
            err = jobs.JobError(500, f"Interne fout: {e}", "internal")
        errors.append(f"{name}: {err.code}: {err}")
        # A bad request is the caller's fault: another backend would not help.
        if t.get("on_failure", "fail") == "escalate" and len(attempts) == 1 and err.code != "bad_request":
            attempts.append(OTHER[name])
            continue
        break

    row = {"caller": caller, "tier": tier, "backend": attempts[-1],
           "escalated_from": attempts[0] if len(attempts) > 1 else None,
           "duration_ms": int((time.monotonic() - start) * 1000)}
    if result is None:
        row |= {"outcome": "failed", "error": " | ".join(errors)}
        _log(row)
        raise RouterError(err.status, " | ".join(errors) if len(errors) > 1 else str(err), err.code)
    text, pt, ct = result
    row |= {"outcome": "escalated" if len(attempts) > 1 else "ok", "error": errors[0] if errors else None,
            "prompt_tokens": pt, "completion_tokens": ct}
    _log(row)
    return {"output": text, "caller": caller, "tier": tier, "backend": row["backend"], "outcome": row["outcome"],
            "escalated_from": row["escalated_from"], "duration_ms": row["duration_ms"],
            "usage": {"prompt_tokens": pt, "completion_tokens": ct}}


def _log(row):
    try:
        log_request(**row)
    except Exception as e:  # the answer matters more than the log line, but say so
        sys.stderr.write(f"router-log schrijven mislukt: {e}\n")


# --- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "homelab-router/1"

    def log_message(self, fmt, *args):
        sys.stderr.write(f"{self.client_address[0]} {fmt % args}\n")

    def send_json(self, status, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_error_json(self, status, message, code, **extra):
        self.send_json(status, {"error": {"message": message, "type": code, "code": code}, **extra})

    def allowed(self):
        if netutil.client_allowed(self.client_address[0]):
            return True
        self.send_error_json(403, "Alleen vanaf het tailnet.", "forbidden")
        return False

    def do_GET(self):
        if not self.allowed():
            return
        path, _, query = self.path.partition("?")
        if path == "/v1/tiers":
            try:
                conf = load_tiers()
            except (OSError, ValueError) as e:
                return self.send_error_json(500, str(e), "bad_config")
            self.send_json(200, {"tiers": conf["tiers"]})
        elif path == "/v1/usage":
            params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
            try:
                days = min(90, max(1, int(params.get("days", 7))))
            except ValueError:
                return self.send_error_json(400, "days moet een getal zijn.", "bad_request")
            self.send_json(200, usage(days))
        elif path == "/health":
            try:
                self.send_json(200, {"ok": True, "tiers": list(load_tiers()["tiers"])})
            except (OSError, ValueError) as e:
                self.send_json(503, {"ok": False, "error": str(e)})
        else:
            self.send_error_json(404, f"Onbekend pad {path}.", "not_found")

    def do_POST(self):
        if not self.allowed():
            return
        if self.path.partition("?")[0] != "/v1/route":
            return self.send_error_json(404, f"Onbekend pad {self.path}.", "not_found")
        try:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                raise RouterError(413, "De body is te groot.", "too_large")
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except ValueError:
                raise RouterError(400, "De body is geen JSON.", "bad_request")
            self.send_json(200, route(body))
        except RouterError as e:
            self.send_error_json(e.status, str(e), e.code)
        except Exception as e:
            self.send_error_json(500, f"Interne fout: {e}", "internal")


def main():
    port = int(os.environ.get("ROUTER_PORT") or jobs.CONF["router_port"])
    load_tiers()  # refuse to start on a broken table
    db().close()
    server = netutil.Server(("::", port), Handler)
    sys.stderr.write(f"llm-router op poort {port}, tabel {config_path()}\n")
    server.serve_forever()


if __name__ == "__main__":
    main()
