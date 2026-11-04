#!/usr/bin/env python3
"""homelab: LLM-helper (F-017).

Een OpenAI-compatibele API voor twee jobs op het lokale model via Ollama:
  POST /v1/chat/completions  met model "summarize" of "extract"
  GET  /v1/models            de jobs
  GET  /usage?days=7         totalen uit het usage-log per caller en per job
  GET  /usage/hourly?days=8  aantal calls per uur (UTC), voor de dashboard (F-024)
  GET  /health               draait Ollama en is het model er

Sinds H-012 is dit een dunne shim op de router (llm/router.py): een chat-aanroep gaat als verzoek met tier "easy"
en caller "f017-legacy" (of de opgegeven caller) naar de router, die het model kiest, de timeout bewaakt en
logt. De Ollama-kern staat in llm/jobs.py. Het eigen usage-log blijft voor /usage en de dashboard.

Alleen stdlib. Instellingen: config/llm.conf naast llm/. Voor tests overschrijfbaar met
OLLAMA_URL, LLM_PORT, ROUTER_PORT en LLM_DB.
"""
import ipaddress
import json
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from jobs import CONF, JOBS, MAX_TIMEOUT, JobError, gpu_pct, ollama  # noqa: E402
from netutil import Server, client_allowed  # noqa: E402

LEGACY_CALLER = "f017-legacy"
LEGACY_TIER = "easy"
ROUTER_URL = "http://127.0.0.1:{port}"

# --- Usage-log

_db_lock = threading.Lock()


def db():
    conn = sqlite3.connect(CONF["db"])
    conn.execute("""CREATE TABLE IF NOT EXISTS calls (
        ts TEXT NOT NULL, caller TEXT NOT NULL, device TEXT NOT NULL, job TEXT NOT NULL,
        model TEXT NOT NULL, duration_ms INTEGER NOT NULL, ok INTEGER NOT NULL, error TEXT,
        prompt_tokens INTEGER, completion_tokens INTEGER, gpu_pct INTEGER)""")
    return conn


def log_call(**row):
    row["ts"] = datetime.now(timezone.utc).isoformat(timespec="seconds")
    cols = ", ".join(row)
    with _db_lock, db() as conn:
        conn.execute(f"INSERT INTO calls ({cols}) VALUES ({', '.join('?' * len(row))})", list(row.values()))


def usage_totals(days):
    since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat(timespec="seconds")
    out = {"days": days, "since": since}
    with _db_lock, db() as conn:
        for group in ("caller", "job"):
            rows = conn.execute(f"""SELECT {group}, COUNT(*), AVG(duration_ms), SUM(1 - ok)
                                    FROM calls WHERE ts >= ? GROUP BY {group} ORDER BY COUNT(*) DESC""", (since,))
            out["by_" + group] = [{group: g, "count": n, "avg_seconds": round(avg / 1000, 1), "failures": f}
                                  for g, n, avg, f in rows]
        n, avg, f = conn.execute("SELECT COUNT(*), AVG(duration_ms), SUM(1 - ok) FROM calls WHERE ts >= ?",
                                 (since,)).fetchone()
        out["total"] = {"count": n, "avg_seconds": round((avg or 0) / 1000, 1), "failures": f or 0}
        out["min_gpu_pct"] = dict(conn.execute("""SELECT model, MIN(gpu_pct) FROM calls
                                                  WHERE ts >= ? AND gpu_pct IS NOT NULL GROUP BY model""", (since,)))
    return out


def usage_hourly(days):
    """Calls per uur in UTC ("2026-10-26T13"); de dashboard telt ze op per dag in zijn eigen tijdzone."""
    since = (datetime.now(timezone.utc) - timedelta(days=days)).isoformat(timespec="seconds")
    with _db_lock, db() as conn:
        rows = conn.execute("""SELECT substr(ts, 1, 13), COUNT(*), SUM(1 - ok) FROM calls WHERE ts >= ?
                               GROUP BY substr(ts, 1, 13) ORDER BY 1""", (since,))
        return {"days": days, "since": since,
                "hours": [{"hour": h, "count": n, "failures": f} for h, n, f in rows]}


# --- Wie belt er: het Tailscale-apparaat bij het IP-adres

_devices = {}


def device_name(ip):
    addr = ipaddress.ip_address(ip)
    if addr.is_loopback:
        return socket.gethostname()
    hit = _devices.get(ip)
    if hit and hit[1] > time.monotonic():
        return hit[0]
    try:
        out = subprocess.run(["tailscale", "whois", "--json", ip], capture_output=True, text=True, timeout=3)
        name = json.loads(out.stdout)["Node"]["ComputedName"]
    except Exception:
        name = ip
    _devices[ip] = (name, time.monotonic() + 600)
    return name


def via_router(caller, job, body, timeout):
    """Send the chat as a router request. Returns the router's result dict; raises JobError like the old code did."""
    req = {"caller": caller, "tier": LEGACY_TIER, "job": job, "messages": body.get("messages"), "timeout": timeout}
    for key in ("fields", "labels", "response_format"):
        if key in body:
            req[key] = body[key]
    http = urllib.request.Request(ROUTER_URL.format(port=CONF["router_port"]) + "/v1/route", method="POST",
                                  data=json.dumps(req).encode(), headers={"Content-Type": "application/json"})
    try:
        # The router guards the job timeout (twice when the tier escalates); we wait a little longer.
        with urllib.request.urlopen(http, timeout=2 * timeout + 15) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        try:
            err = json.load(e)["error"]
        except (ValueError, KeyError, TypeError):
            err = {"message": f"De router gaf {e.code}", "code": "router_error"}
        raise JobError(e.code, err.get("message", ""), err.get("code", "router_error"))
    except (TimeoutError, socket.timeout):
        raise JobError(504, f"De job duurde langer dan {timeout:.0f} s (timeout).", "timeout")
    except urllib.error.URLError as e:
        raise JobError(503, f"De router is niet bereikbaar: {e.reason}", "router_unreachable")


# --- HTTP

class Handler(BaseHTTPRequestHandler):
    server_version = "homelab-llm/1"

    def log_message(self, fmt, *args):
        sys.stderr.write(f"{self.client_address[0]} {fmt % args}\n")

    def send_json(self, status, obj):
        data = json.dumps(obj, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def send_error_json(self, status, message, code):
        self.send_json(status, {"error": {"message": message, "type": code, "code": code}})

    def client_ip(self):
        ip = self.client_address[0]
        return ip.removeprefix("::ffff:")

    def allowed(self):
        if client_allowed(self.client_ip()):
            return True
        self.send_error_json(403, "Alleen vanaf het tailnet.", "forbidden")
        return False

    def do_GET(self):
        if not self.allowed():
            return
        path, _, query = self.path.partition("?")
        if path == "/v1/models":
            self.send_json(200, {"object": "list", "data": [
                {"id": j, "object": "model", "created": 0, "owned_by": "homelab"} for j in JOBS]})
        elif path in ("/usage", "/usage/hourly"):
            params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
            try:
                days = min(90, max(1, int(params.get("days", 7))))
            except ValueError:
                return self.send_error_json(400, "days moet een getal zijn.", "bad_request")
            self.send_json(200, usage_totals(days) if path == "/usage" else usage_hourly(days))
        elif path == "/health":
            try:
                names = {m["name"] for m in ollama("/api/tags", timeout=3).get("models", [])}
                ok = CONF["model"] in names or CONF["model"] + ":latest" in names
                self.send_json(200 if ok else 503, {"ok": ok, "model": CONF["model"],
                                                    "error": None if ok else "model is niet gedownload"})
            except JobError as e:
                self.send_json(503, {"ok": False, "model": CONF["model"], "error": str(e)})
        else:
            self.send_error_json(404, f"Onbekend pad {path}.", "not_found")

    def do_POST(self):
        if not self.allowed():
            return
        if self.path.partition("?")[0] != "/v1/chat/completions":
            return self.send_error_json(404, f"Onbekend pad {self.path}.", "not_found")

        start = time.monotonic()
        job, caller, result = "unknown", LEGACY_CALLER, {}
        try:
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
                if not isinstance(body, dict):
                    raise ValueError
            except ValueError:
                raise JobError(400, "De body is geen JSON-object.", "bad_request")
            caller = str(self.headers.get("X-LLM-Caller") or body.get("user") or LEGACY_CALLER)[:64]
            requested = str(body.get("model") or "")[:32]
            if requested not in JOBS:
                job = "invalid"
                raise JobError(400, f"Onbekende job {requested!r}; kies uit {', '.join(JOBS)}.", "bad_request")
            job = requested
            if body.get("stream"):
                raise JobError(400, "stream wordt niet ondersteund.", "bad_request")
            try:
                timeout = float(body.get("timeout") or CONF["timeout"])
            except (TypeError, ValueError):
                raise JobError(400, "timeout moet een getal (seconden) zijn.", "bad_request")
            if not 0 < timeout <= MAX_TIMEOUT:
                raise JobError(400, f"timeout moet tussen 0 en {MAX_TIMEOUT} s liggen.", "bad_request")

            result = via_router(caller, job, body, timeout)
            usage = result.get("usage") or {}
            prompt_tokens, completion_tokens = usage.get("prompt_tokens") or 0, usage.get("completion_tokens") or 0
            model = CONF["model"] if result["backend"] == "local" else "claude"
            self.send_json(200, {
                "id": f"homelab-{int(time.time() * 1000)}", "object": "chat.completion",
                "created": int(time.time()), "model": model,
                "choices": [{"index": 0, "finish_reason": "stop",
                             "message": {"role": "assistant", "content": result["output"]}}],
                "usage": {"prompt_tokens": prompt_tokens, "completion_tokens": completion_tokens,
                          "total_tokens": prompt_tokens + completion_tokens}})
            ok, error = 1, None
        except JobError as e:
            self.send_error_json(e.status, str(e), e.code)
            ok, error = 0, f"{e.code}: {e}"
        except Exception as e:
            self.send_error_json(500, f"Interne fout: {e}", "internal")
            ok, error = 0, f"internal: {e}"
        finally:
            try:
                local = result.get("backend") == "local"
                usage = result.get("usage") or {}
                log_call(caller=caller, device=device_name(self.client_ip()), job=job,
                         model=CONF["model"] if local or not result else "claude",
                         duration_ms=int((time.monotonic() - start) * 1000), ok=ok, error=error,
                         prompt_tokens=usage.get("prompt_tokens"), completion_tokens=usage.get("completion_tokens"),
                         gpu_pct=gpu_pct() if local else None)
            except Exception as e:
                sys.stderr.write(f"usage-log schrijven mislukt: {e}\n")


def main():
    db().close()
    server = Server(("::", CONF["port"]), Handler)
    sys.stderr.write(f"llm-helper op poort {CONF['port']}, model {CONF['model']}, Ollama {CONF['ollama']}\n")
    server.serve_forever()


if __name__ == "__main__":
    main()
