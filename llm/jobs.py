"""homelab: the local-model core of the LLM helper (F-017): config, Ollama calls and the summarize/extract jobs.

Used by the router (llm/router.py, the local backend) and by the helper's HTTP shim (llm/helper.py).
Only stdlib. Settings: config/llm.conf next to llm/; overridable for tests with OLLAMA_URL, LLM_PORT and LLM_DB.
"""
import json
import os
import socket
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JOBS = ("summarize", "extract")
MAX_TIMEOUT = 600
# Hoeveel tokens het model mag schrijven: een model dat niet ophoudt, bezet anders de GPU tot de timeout.
MAX_OUTPUT_TOKENS = {"summarize": 512, "extract": 1024}
CHARS_PER_TOKEN = 3  # voorzichtig: tekst die mogelijk niet in de context past, weigeren we liever dan afkappen


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
        "router_port": int(os.environ.get("ROUTER_PORT", conf.get("ROUTER_PORT", 8018))),
        "db": os.environ.get("LLM_DB", str(Path(os.environ.get("STATE_DIRECTORY", ".")) / "usage.db")),
    }


CONF = load_config()


class JobError(Exception):
    def __init__(self, status, message, code="job_failed"):
        super().__init__(message)
        self.status, self.code = status, code


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


def job_system(job, body):
    """The system prompt and (extract only) the schema of a job; the router also gives it to the claude backend."""
    if job == "summarize":
        return SUMMARIZE_PROMPT, None
    schema = extract_schema(body)
    lines = [f"- {k}: {v.get('description') or ''}".rstrip(": ") +
             (f" (one of: {', '.join(map(str, v['enum']))})" if "enum" in v else "")
             for k, v in schema["properties"].items()]
    return EXTRACT_PROMPT.format(fields="\n".join(lines)), schema


def build_job(job, body):
    """The Ollama request, the schema (extract only) and the system prompt for a summarize/extract job."""
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
    system, schema = job_system(job, body)
    if schema:
        request["format"] = schema
        request["options"]["temperature"] = 0
    request["messages"] = [{"role": "system", "content": system}] + [
        {"role": m.get("role", "user"), "content": m.get("content") or ""} for m in messages]
    return request, schema, system


def run_job(job, body, timeout):
    request, schema, _ = build_job(job, body)

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


def run_chat(messages, timeout, system=None):
    """A plain chat on the local model, for router requests without a job."""
    if not isinstance(messages, list) or not messages:
        raise JobError(400, "messages ontbreekt.", "bad_request")
    msgs = ([{"role": "system", "content": system}] if system else []) + [
        {"role": m.get("role", "user"), "content": m.get("content") or ""} for m in messages]
    request = {"model": CONF["model"], "stream": False, "think": False, "keep_alive": "10m", "messages": msgs,
               "options": {"num_ctx": CONF["num_ctx"], "temperature": 0.2}}
    resp = ollama("/api/chat", request, timeout=timeout)
    content = resp.get("message", {}).get("content", "").strip()
    if not content:
        raise JobError(502, "het model gaf een leeg antwoord", "invalid_output")
    return content, resp
