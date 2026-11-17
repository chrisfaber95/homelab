#!/usr/bin/env python3
"""Parity harness (H-023 B7): the same GET requests to the Python inbox and to the Java backend, both on the same data,
and a per-route diff of status and normalized body. Exit 0: no differences. 1: at least one route differs. 2: cannot run.

It only reads: `fetch` sends GET and nothing else. Routes come from backend/openapi.yaml (the GET operations of the
H-020 C1 contract; needs PyYAML); parity.json adds examples for parameterized paths, exclusions and ignored fields.
See docs/inbox-backend.md ("Parity runs")."""
import argparse
import difflib
import http.client
import json
import re
import sys
import urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = HERE.parent / "openapi.yaml"
CONFIG = HERE / "parity.json"
FACADE_HEADER = "X-Inbox-Facade"  # docs/architecture/inbox-split.md: the secret Java wants from the Python facade
NOT_IMPLEMENTED = (404, 501)  # what Java answers for a route it does not have yet


class Setup(Exception):
    """The run cannot start (bad input, a side that is down): exit code 2, not a parity result."""


# --- routes --------------------------------------------------------------------------------------------------------

def spec_paths(spec):
    """GET paths of an OpenAPI document, in file order."""
    return [p for p, item in (spec.get("paths") or {}).items() if isinstance(item, dict) and "get" in item]


def build_routes(paths, config):
    """Concrete request paths: a path with parameters needs `examples`; excluded ones are dropped, `skip` ones are kept
    but marked. Returns [(path, skip_reason or None)]; a parameterized path without example is skipped, not guessed."""
    exclude, skip = set(config.get("exclude", [])), set(config.get("skip", []))
    out = []
    for p in paths:
        if p in exclude:
            continue
        if "{" in p:
            examples = config.get("examples", {}).get(p)
            if not examples:
                out.append((p, "no example in parity.json"))
                continue
            out.extend((e, "listed in skip" if p in skip else None) for e in examples)
        else:
            out.append((p, "listed in skip" if p in skip else None))
    return out


def spec_key(path, config):
    """The spec path a concrete request path came from (for the per-route ignore list)."""
    if path in config.get("examples", {}):
        return path
    for template, examples in config.get("examples", {}).items():
        if path in examples:
            return template
    return path


# --- normalization and comparison ------------------------------------------------------------------------------------

def strip_keys(value, names):
    """Remove the keys in `names` at any depth. A name may be a dotted path from the root ("health.checked")."""
    top = {n for n in names if "." not in n}
    dotted = [n.split(".") for n in names if "." in n]

    def walk(v, trail):
        if isinstance(v, dict):
            return {k: walk(x, trail + [k]) for k, x in v.items()
                    if k not in top and trail + [k] not in dotted}
        if isinstance(v, list):
            return [walk(x, trail) for x in v]
        return v
    return walk(value, [])


def media_type(headers):
    return (headers.get("content-type") or "").split(";")[0].strip().lower()


def normalize(status, headers, body, ignore):
    """What is compared: status, media type (not the charset: Spring and the Python server differ there by habit) and
    the body: parsed JSON without the ignored keys, otherwise text with collapsed whitespace."""
    mt = media_type(headers)
    text = body.decode("utf-8", "replace")
    if mt == "application/json" or mt.endswith("+json"):
        try:
            return {"status": status, "type": mt, "body": strip_keys(json.loads(text), ignore)}
        except ValueError:
            pass
    return {"status": status, "type": mt, "body": re.sub(r"\s+", " ", text).strip()}


def render(norm):
    body = norm["body"]
    text = json.dumps(body, indent=2, sort_keys=True, ensure_ascii=False) if not isinstance(body, str) else body
    return [f"status: {norm['status']}", f"type: {norm['type']}", "body:"] + text.splitlines()


def compare(path, python, java, ignore):
    """Each side is (status, headers, body) or None when the request failed. Returns (outcome, lines) with outcome
    one of "same", "diff", "skipped"; `lines` explains diff and skipped."""
    if python is None or java is None:
        side = "python" if python is None else "java"
        return "diff", [f"the {side} request failed (see above)"]
    if java[0] in NOT_IMPLEMENTED and python[0] != java[0]:
        return "skipped", [f"java answers {java[0]}: not implemented yet (python {python[0]})"]
    a, b = normalize(*python, ignore), normalize(*java, ignore)
    if a == b:
        return "same", []
    diff = difflib.unified_diff(render(a), render(b), "python", "java", lineterm="", n=2)
    return "diff", list(diff)


# --- the requests ----------------------------------------------------------------------------------------------------

def fetch(base, path, headers, timeout=20):
    """GET only. Returns (status, lowercased headers, body)."""
    method = "GET"
    u = urllib.parse.urlsplit(base)
    conn = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=timeout)
    try:
        conn.request(method, path, headers=headers)
        r = conn.getresponse()
        return r.status, {k.lower(): v for k, v in r.getheaders()}, r.read()
    finally:
        conn.close()


def parse_headers(items):
    out = {}
    for item in items or []:
        name, sep, value = item.partition(":")
        if not sep or not name.strip():
            raise Setup(f"header must look like 'Name: value': {name!r}")
        out[name.strip()] = value.strip()
    return out


def run(routes, config, fetch_python, fetch_java, out=print):
    """Compare every route. `fetch_*` take a path and return (status, headers, body) or raise OSError."""
    counts = {"same": 0, "diff": 0, "skipped": 0}
    for path, reason in routes:
        if reason:
            counts["skipped"] += 1
            out(f"SKIP {path}: {reason}")
            continue
        ignore = list(config.get("ignore", {}).get("*", [])) + list(config.get("ignore", {}).get(spec_key(path, config), []))
        answers = []
        for name, fetcher in (("python", fetch_python), ("java", fetch_java)):
            try:
                answers.append(fetcher(path))
            except OSError as e:
                out(f"ERROR {path}: {name}: {e}")
                answers.append(None)
        outcome, lines = compare(path, answers[0], answers[1], ignore)
        counts[outcome] += 1
        label = {"same": "OK  ", "diff": "DIFF", "skipped": "SKIP"}[outcome]
        out(f"{label} {path}" + (f": {lines[0]}" if outcome == "skipped" else ""))
        if outcome == "diff":
            for line in lines:
                out(f"     {line}")
    return counts


def main(argv=None):
    ap = argparse.ArgumentParser(description="Compare the Python inbox and the Java backend on GET routes (read-only).")
    ap.add_argument("--python-url", default="http://127.0.0.1:8105")
    ap.add_argument("--java-url", default="http://127.0.0.1:8130")
    ap.add_argument("--python-header", action="append", metavar="'Name: value'", help="extra header for the Python side")
    ap.add_argument("--java-header", action="append", metavar="'Name: value'", help="extra header for the Java side")
    ap.add_argument("--java-secret-file", help=f"file with the facade secret, sent as {FACADE_HEADER}")
    ap.add_argument("--config", default=str(CONFIG))
    ap.add_argument("--spec", default=str(SPEC))
    ap.add_argument("--only", action="append", metavar="PREFIX", help="only routes starting with this prefix")
    args = ap.parse_args(argv)
    try:
        config = json.loads(Path(args.config).read_text())
        try:
            import yaml
        except ImportError:
            raise Setup("PyYAML is needed to read backend/openapi.yaml (apt install python3-yaml)")
        routes = build_routes(spec_paths(yaml.safe_load(Path(args.spec).read_text())), config)
        if args.only:
            routes = [r for r in routes if r[0].startswith(tuple(args.only))]
        py_headers, java_headers = parse_headers(args.python_header), parse_headers(args.java_header)
        if args.java_secret_file:
            java_headers[FACADE_HEADER] = Path(args.java_secret_file).read_text().strip()
        for name, base, hdrs in (("python", args.python_url, py_headers), ("java", args.java_url, java_headers)):
            try:
                fetch(base, "/api/backend/health" if name == "java" else "/api/live", hdrs, timeout=5)
            except OSError as e:
                raise Setup(f"the {name} side at {base} is not reachable: {e}")
    except (Setup, OSError, ValueError) as e:
        print(f"parity: {e}", file=sys.stderr)
        return 2
    counts = run(routes, config, lambda p: fetch(args.python_url, p, py_headers),
                 lambda p: fetch(args.java_url, p, java_headers))
    print(f"parity: {counts['same']} same, {counts['diff']} different, {counts['skipped']} skipped")
    return 1 if counts["diff"] else 0


if __name__ == "__main__":
    sys.exit(main())
