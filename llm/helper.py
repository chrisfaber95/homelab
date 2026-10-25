#!/usr/bin/env python3
"""homelab: LLM-helper (F-017).

Een OpenAI-compatibele API voor twee jobs op het lokale model via Ollama:
  POST /v1/chat/completions  met model "summarize" of "extract"
  GET  /v1/models            de jobs
  GET  /usage?days=7         totalen uit het usage-log per caller en per job
  GET  /health               draait Ollama en is het model er

Alleen stdlib. Instellingen: config/llm.conf naast llm/. Voor tests overschrijfbaar met
OLLAMA_URL, LLM_PORT en LLM_DB.
"""
import ipaddress
import json
import os
import socket
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JOBS = ("summarize", "extract")
MAX_TIMEOUT = 600
# Hoeveel tokens het model mag schrijven: een model dat niet ophoudt, bezet anders de GPU tot de timeout.
MAX_OUTPUT_TOKENS = {"summarize": 512, "extract": 1024}
CHARS_PER_TOKEN = 3  # voorzichtig: tekst die mogelijk niet in de context past, weigeren we liever dan afkappen

# Alleen loopback en het tailnet; ufw houdt het LAN al tegen, dit is de tweede laag.
ALLOWED_NETS = [ipaddress.ip_network(n) for n in
                ("127.0.0.0/8", "::1/128", "100.64.0.0/10", "fd7a:115c:a1e0::/48")]

SUMMARIZE_PROMPT = (
    "You summarize text for a script. Write a factual summary of the text the user gives you in at most "
    "three sentences, in the same language as that text. Never copy the text or its lines: say what happened "
    "and what matters. Keep names, numbers, dates and error messages exactly as they appear. "
    "Output only the summary: no introduction, no closing remarks."
)
EXTRACT_PROMPT = (
    "You extract data from text for a script. Answer with one JSON object that matches the schema, "
    "and nothing else. Use only what the text says; when a field is not in the text, use null. "
    "Fields:\n{fields}"
)


def load_config():
    conf = {}
    for line in (ROOT / "config" / "llm.conf").read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            conf[key.strip()] = value.strip().strip('"')
    return {
        "model": conf["LLM_MODEL"],
        "num_ctx": int(conf["LLM_NUM_CTX"]),
        "port": int(os.environ.get("LLM_PORT", conf["LLM_PORT"])),
        "timeout": int(conf["LLM_DEFAULT_TIMEOUT"]),
        "ollama": os.environ.get("OLLAMA_URL", "http://127.0.0.1:11434"),
        "db": os.environ.get("LLM_DB", str(Path(os.environ.get("STATE_DIRECTORY", ".")) / "usage.db")),
    }


CONF = load_config()


class JobError(Exception):
    def __init__(self, status, message, code="job_failed"):
        super().__init__(message)
        self.status, self.code = status, code


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


# --- Ollama

def ollama(path, body=None, timeout=10):
    req = urllib.request.Request(CONF["ollama"] + path, method="POST" if body is not None else "GET",
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as e:
        raise JobError(502, f"Ollama gaf {e.code}: {e.read().decode(errors='replace')[:300]}", "ollama_error")
    except (TimeoutError, socket.timeout):
        raise JobError(504, f"De job duurde langer dan {timeout:.0f} s (timeout).", "timeout")
    except urllib.error.URLError as e:
        if isinstance(e.reason, (TimeoutError, socket.timeout)):
            raise JobError(504, f"De job duurde langer dan {timeout:.0f} s (timeout).", "timeout")
        raise JobError(503, f"Ollama is niet bereikbaar: {e.reason}", "ollama_unreachable")


def gpu_pct():
    """Welk deel van het geladen model in VRAM zit (100 = helemaal op de GPU)."""
    try:
        for m in ollama("/api/ps", timeout=3).get("models", []):
            if m["name"] == CONF["model"] or m["model"] == CONF["model"]:
                return round(100 * m["size_vram"] / m["size"]) if m["size"] else None
    except JobError:
        pass
    return None


# --- Jobs

def extract_schema(body):
    """Het JSON-schema voor extract: response_format (standaard OpenAI) of de kortere fields/labels."""
    rf = body.get("response_format") or {}
    if rf.get("type") == "json_schema":
        schema = (rf.get("json_schema") or {}).get("schema")
        if not isinstance(schema, dict) or schema.get("type") != "object" or not schema.get("properties"):
            raise JobError(400, "response_format.json_schema.schema moet een object met properties zijn.", "bad_request")
        return schema
    fields, labels = body.get("fields") or [], body.get("labels") or []
    if not fields and not labels:
        raise JobError(400, "extract heeft fields, labels of response_format.json_schema nodig.", "bad_request")
    props = {}
    for f in fields:
        name, typ, desc = (f, "string", None) if isinstance(f, str) else (f["name"], f.get("type", "string"), f.get("description"))
        if typ not in ("string", "number", "integer", "boolean"):
            raise JobError(400, f"Veld {name}: type {typ} wordt niet ondersteund.", "bad_request")
        props[name] = {"type": [typ, "null"]} | ({"description": desc} if desc else {})
    if labels:
        if "label" in props:
            raise JobError(400, "Een veld mag niet 'label' heten als je ook labels opgeeft.", "bad_request")
        props["label"] = {"type": "string", "enum": list(labels), "description": "exactly one of the allowed labels"}
    return {"type": "object", "properties": props, "required": list(props), "additionalProperties": False}


def validate(value, schema, path="$"):
    """Een klein deel van JSON Schema: type, enum, properties (precies die sleutels), items."""
    types = schema.get("type")
    types = [types] if isinstance(types, str) else types or []
    checks = {"object": lambda v: isinstance(v, dict), "array": lambda v: isinstance(v, list),
              "string": lambda v: isinstance(v, str), "boolean": lambda v: isinstance(v, bool),
              "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
              "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
              "null": lambda v: v is None}
    if types and not any(checks.get(t, lambda v: True)(value) for t in types):
        raise ValueError(f"{path} is geen {'/'.join(types)}")
    if "enum" in schema and value not in schema["enum"]:
        raise ValueError(f"{path} = {value!r} staat niet in {schema['enum']}")
    if isinstance(value, dict) and "properties" in schema:
        want, got = set(schema["properties"]), set(value)
        if want != got:
            raise ValueError(f"{path} heeft sleutels {sorted(got)}, verwacht {sorted(want)}")
        for k, sub in schema["properties"].items():
            validate(value[k], sub, f"{path}.{k}")
    if isinstance(value, list) and isinstance(schema.get("items"), dict):
        for i, v in enumerate(value):
            validate(v, schema["items"], f"{path}[{i}]")


def run_job(job, body, timeout):
    messages = body.get("messages")
    if not isinstance(messages, list) or not messages:
        raise JobError(400, "messages ontbreekt.", "bad_request")
    text_len = sum(len(m.get("content") or "") for m in messages if isinstance(m, dict))
    max_chars = CONF["num_ctx"] * CHARS_PER_TOKEN - 2000
    if text_len > max_chars:
        raise JobError(400, f"De tekst is te lang voor het model ({text_len} tekens, max. ongeveer {max_chars}).",
                       "too_long")

    request = {"model": CONF["model"], "stream": False, "think": False, "keep_alive": "10m",
               "options": {"num_ctx": CONF["num_ctx"], "temperature": 0.2, "num_predict": MAX_OUTPUT_TOKENS[job]}}
    schema = None
    if job == "summarize":
        system = SUMMARIZE_PROMPT
    else:
        schema = extract_schema(body)
        lines = [f"- {k}: {v.get('description') or ''}".rstrip(": ") +
                 (f" (one of: {', '.join(map(str, v['enum']))})" if "enum" in v else "")
                 for k, v in schema["properties"].items()]
        system = EXTRACT_PROMPT.format(fields="\n".join(lines))
        request["format"] = schema
        request["options"]["temperature"] = 0
    request["messages"] = [{"role": "system", "content": system}] + [
        {"role": m.get("role", "user"), "content": m.get("content") or ""} for m in messages]

    deadline = time.monotonic() + timeout
    for attempt in (1, 2):
        left = deadline - time.monotonic()
        if left <= 0:
            raise JobError(504, f"De job duurde langer dan {timeout:.0f} s (timeout).", "timeout")
        try:
            resp = ollama("/api/chat", request, timeout=left)
        except JobError as e:
            if e.code == "timeout":
                raise JobError(504, f"De job duurde langer dan {timeout:.0f} s (timeout).", "timeout")
            raise
        content = resp.get("message", {}).get("content", "").strip()
        if resp.get("done_reason") == "length":  # opnieuw proberen loopt net zo vast
            raise JobError(502, f"Het model hield na {MAX_OUTPUT_TOKENS[job]} tokens niet op.", "invalid_output")
        if schema is None:
            if content:
                return content, resp
            error = "het model gaf een leeg antwoord"
        else:
            try:
                value = json.loads(content)
                validate(value, schema)
                return json.dumps(value, ensure_ascii=False), resp
            except ValueError as e:  # JSONDecodeError is ook een ValueError
                error = f"ongeldige JSON van het model: {e}"
    raise JobError(502, f"Twee pogingen mislukt, {error}.", "invalid_output")


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
        addr = ipaddress.ip_address(self.client_ip())
        if any(addr in net for net in ALLOWED_NETS):
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
        elif path == "/usage":
            params = dict(p.split("=", 1) for p in query.split("&") if "=" in p)
            try:
                days = max(1, int(params.get("days", 7)))
            except ValueError:
                return self.send_error_json(400, "days moet een getal zijn.", "bad_request")
            self.send_json(200, usage_totals(days))
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
        job, caller, resp = "unknown", "unknown", {}
        try:
            try:
                body = json.loads(self.rfile.read(int(self.headers.get("Content-Length") or 0)) or b"{}")
                if not isinstance(body, dict):
                    raise ValueError
            except ValueError:
                raise JobError(400, "De body is geen JSON-object.", "bad_request")
            caller = str(self.headers.get("X-LLM-Caller") or body.get("user") or "unknown")[:64]
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

            content, resp = run_job(job, body, timeout)
            prompt_tokens, completion_tokens = resp.get("prompt_eval_count", 0), resp.get("eval_count", 0)
            self.send_json(200, {
                "id": f"homelab-{int(time.time() * 1000)}", "object": "chat.completion",
                "created": int(time.time()), "model": CONF["model"],
                "choices": [{"index": 0, "finish_reason": "stop",
                             "message": {"role": "assistant", "content": content}}],
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
                log_call(caller=caller, device=device_name(self.client_ip()), job=job, model=CONF["model"],
                         duration_ms=int((time.monotonic() - start) * 1000), ok=ok, error=error,
                         prompt_tokens=resp.get("prompt_eval_count"), completion_tokens=resp.get("eval_count"),
                         gpu_pct=gpu_pct() if resp else None)
            except Exception as e:
                sys.stderr.write(f"usage-log schrijven mislukt: {e}\n")


class Server(ThreadingHTTPServer):
    # Dual-stack: MagicDNS geeft ook een IPv6-adres, en een client die dat eerst probeert mag niet blijven hangen.
    address_family = socket.AF_INET6
    daemon_threads = True

    def server_bind(self):
        self.socket.setsockopt(socket.IPPROTO_IPV6, socket.IPV6_V6ONLY, 0)
        super().server_bind()


def main():
    db().close()
    server = Server(("::", CONF["port"]), Handler)
    sys.stderr.write(f"llm-helper op poort {CONF['port']}, model {CONF['model']}, Ollama {CONF['ollama']}\n")
    server.serve_forever()


if __name__ == "__main__":
    main()
