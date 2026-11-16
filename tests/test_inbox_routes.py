"""Route coverage (H-020): every route of server.py and kitt.py, with its method, must be in backend/openapi.yaml.
The routes are read from the Python source with ast, the same way the code reads them: a path literal compared with
`path` / `self.path` / `url.path`, or the first argument of re.fullmatch/re.match on it. The method comes from the
handler it sits in (do_GET, do_POST and the post_* helpers), from the `method == ...` tests of kitt.route, and the
three chat-door paths are checked for both GET and POST (the door answers a wrong method with 405, which the spec
documents). A route counts as documented when a path item of the spec lists the literal in its `x-code` line and has
that method. The test also proves itself: against a copy of the spec with a path or one operation removed it must
fail, and the failure must name the route. tests/test_inbox_openapi.py checks the literals alone (and stale ones).
Run: python3 -m unittest discover -s tests"""
import ast
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER, KITT = ROOT / "bin/homelab_inbox/server.py", ROOT / "bin/homelab_inbox/kitt.py"
SPEC = ROOT / "backend/openapi.yaml"
METHODS = ("get", "post", "put", "patch", "delete")
CHAT_DOOR_METHODS = ("GET", "POST")
FUNCTION_METHOD = {"do_GET": ("GET",), "do_POST": ("POST",), "post_jobs": ("POST",), "post_agent": ("POST",),
                   "post_memory": ("POST",), "cancel_run": ()}


def text_of(node):
    """The literal of a string constant, or of an f-string with its `{...}` parts kept as source text."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.JoinedStr):
        return "".join(v.value if isinstance(v, ast.Constant) else "{" + ast.unparse(v.value) + "}" for v in node.values)
    return None


def is_path(node):
    return (isinstance(node, ast.Attribute) and node.attr == "path") or (isinstance(node, ast.Name) and node.id == "path")


def is_method(node):
    return isinstance(node, ast.Name) and node.id == "method"


def literals(node):
    items = node.elts if isinstance(node, (ast.Tuple, ast.List, ast.Set)) else [node]
    return [s for s in map(text_of, items) if s is not None]


def path_literals(tree):
    """Route literals under `tree`: compared with a path, or the pattern of re.fullmatch/re.match(pattern, path)."""
    found = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
            f = node.func
            if isinstance(f.value, ast.Name) and f.value.id == "re" and f.attr in ("fullmatch", "match") \
                    and len(node.args) > 1 and is_path(node.args[1]):
                found.update(literals(node.args[0]))
        if isinstance(node, ast.Compare):
            parts = [node.left, *node.comparators]
            if any(is_path(p) for p in parts):
                for p in parts:
                    if not is_path(p):
                        found.update(literals(p))
    return {r for r in found if r.startswith("/")}


def method_literals(tree):
    found = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Compare):
            parts = [node.left, *node.comparators]
            if any(is_method(p) for p in parts):
                for p in parts:
                    if not is_method(p):
                        found.update(literals(p))
    return {m for m in found if m in ("GET", "POST", "PUT", "PATCH", "DELETE")}


def server_routes():
    """{(method, literal)} of server.py: the page routes by handler function, the chat door for both methods."""
    tree = ast.parse(SERVER.read_text())
    handler = next(n for n in tree.body if isinstance(n, ast.ClassDef) and n.name == "Handler")
    out = set()
    for fn in (n for n in handler.body if isinstance(n, ast.FunctionDef)):
        if fn.name in FUNCTION_METHOD:
            out |= {(m, p) for m in FUNCTION_METHOD[fn.name] for p in path_literals(fn)}
        elif fn.name == "chat_door":
            out |= {(m, p) for m in CHAT_DOOR_METHODS for p in path_literals(fn)}
    return out


def kitt_routes():
    """{(method, literal)} of kitt.route: per top-level `if` of the function, its path literals x the methods it tests."""
    tree = ast.parse(KITT.read_text())
    fn = next(n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name == "route")
    out = set()
    for stmt in fn.body:
        if isinstance(stmt, ast.If):
            paths = path_literals(ast.Expression(stmt.test))
            if paths:
                out |= {(m, p) for p in paths for m in method_literals(stmt)}
    return out


def code_routes():
    return {"server.py": server_routes(), "kitt.py": kitt_routes()}


def documented(spec_text):
    """{literal: {METHOD}} of the spec: the `x-code` literals of each path item and that item's operations."""
    out, literals_of, methods_of = {}, [], set()

    def flush():
        for lit in literals_of:
            out.setdefault(lit, set()).update(methods_of)
    for line in spec_text.splitlines():
        if re.match(r"  /\S*:\s*$", line):  # a new path item
            flush()
            literals_of, methods_of = [], set()
        elif m := re.match(r"    x-code: (\[.*\])\s*$", line):
            literals_of = json.loads(m.group(1))
        elif m := re.match(rf"    ({'|'.join(METHODS)}):\s*$", line):
            methods_of.add(m.group(1).upper())
    flush()
    return out


def missing_routes(spec_text, routes=None):
    """['GET /api/live (server.py)', ...] for the routes of the code the spec does not have with that method."""
    have = documented(spec_text)
    return sorted(f"{method} {lit} ({src})" for src, found in (routes or code_routes()).items()
                  for method, lit in found if method not in have.get(lit, set()))


def path_items(lines):
    """[(start, end)] of the path items under `paths:`; an item ends at the next line indented 2 or less."""
    out = []
    for n, l in enumerate(lines):
        if re.match(r"  /\S*:\s*$", l):
            end = next((k for k in range(n + 1, len(lines)) if re.match(r"(\S|  \S|  #)", lines[k])), len(lines))
            out.append((n, end))
    return out


def lists(lines, literal):
    return any(re.match(r"    x-code: .*" + re.escape(json.dumps(literal)), l) for l in lines)


def without_path(spec_text, literal):
    """The spec with the whole path item(s) that list `literal` taken out."""
    lines = spec_text.splitlines()
    drop = {n for s, e in path_items(lines) if lists(lines[s:e], literal) for n in range(s, e)}
    return "\n".join(l for n, l in enumerate(lines) if n not in drop)


def without_operation(spec_text, literal, method):
    """The spec with one operation (`    post:` ...) removed from the path item that lists `literal`."""
    lines = spec_text.splitlines()
    for s, e in path_items(lines):
        if not lists(lines[s:e], literal):
            continue
        for n in range(s, e):
            if lines[n] == f"    {method.lower()}:":
                end = next((k for k in range(n + 1, e) if re.match(r"    \S", lines[k])), e)
                return "\n".join(lines[:n] + lines[end:])
    raise AssertionError(f"{method} {literal} is not in the spec")


class RoutesAreInTheSpec(unittest.TestCase):
    spec = SPEC.read_text()

    def test_extraction_finds_the_known_routes(self):
        routes = code_routes()
        self.assertGreater(len(routes["server.py"]), 40)
        self.assertGreater(len(routes["kitt.py"]), 8)
        for expect in (("GET", "/api/live"), ("POST", "/api/forge/(start|stop)"), ("GET", "/api/chat/state"),
                       ("POST", "/api/chat/state"), ("POST", "/api/chat/action"), ("POST", "/tasks"),
                       ("POST", "/job/([a-z0-9-]+)/(run|pause|resume|delete|save|change)"), ("GET", "/api/log/{forge.LIVE_KEY}")):
            self.assertIn(expect, routes["server.py"])
        for expect in (("GET", "/api/clients/kitt"), ("GET", "/api/conversation"), ("POST", "/api/conversation"),
                       ("POST", "/api/approvals/([^/]+)"), ("POST", "/api/jobs/([a-z0-9-]+)/(run|pause|resume)")):
            self.assertIn(expect, routes["kitt.py"])
        self.assertNotIn(("POST", "/api/health"), routes["kitt.py"])

    def test_every_route_with_its_method_is_in_the_spec(self):
        self.assertEqual(missing_routes(self.spec), [], "routes of server.py / kitt.py missing from backend/openapi.yaml "
                         "(method + x-code literal)")

    def test_fails_naming_the_route_when_a_server_path_is_removed(self):
        broken = without_path(self.spec, "/api/live")
        self.assertNotEqual(broken, self.spec)
        self.assertIn("GET /api/live (server.py)", missing_routes(broken))

    def test_fails_naming_the_route_when_a_kitt_path_is_removed(self):
        broken = without_path(self.spec, "/api/health")
        self.assertIn("GET /api/health (kitt.py)", missing_routes(broken))

    def test_fails_when_only_the_method_is_missing(self):
        broken = without_operation(self.spec, "/api/forge/(start|stop)", "post")
        self.assertEqual(missing_routes(broken), ["POST /api/forge/(start|stop) (server.py)"])
        broken = without_operation(self.spec, "/api/chat/state", "post")  # the 405 of the chat door is part of the contract
        self.assertEqual(missing_routes(broken), ["POST /api/chat/state (server.py)"])
        broken = without_operation(self.spec, "/api/conversation", "post")
        self.assertEqual(missing_routes(broken), ["POST /api/conversation (kitt.py)"])

    def test_a_route_added_to_the_code_is_reported(self):
        routes = code_routes()
        routes["server.py"].add(("GET", "/api/brand-new"))
        self.assertEqual(missing_routes(self.spec, routes), ["GET /api/brand-new (server.py)"])


if __name__ == "__main__":
    unittest.main()
