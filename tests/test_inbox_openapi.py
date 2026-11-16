"""backend/openapi.yaml must list every route of server.py and kitt.py (docs/architecture/inbox-split.md, slice C1).
Each path item carries `x-code`: the literal route strings as they appear in the Python source. This test collects those
literals from the source (with ast) and fails for any that no `x-code` line names. Run: python3 -m unittest discover -s tests"""
import ast
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = [ROOT / "bin/homelab_inbox/server.py", ROOT / "bin/homelab_inbox/kitt.py"]
SPEC = ROOT / "backend/openapi.yaml"


def text_of(node):
    """The route string of a constant, or of an f-string with its `{...}` parts kept as source text."""
    if isinstance(node, ast.Constant) and isinstance(node.value, str):
        return node.value
    if isinstance(node, ast.JoinedStr):
        return "".join(v.value if isinstance(v, ast.Constant) else "{" + ast.unparse(v.value) + "}" for v in node.values)
    return None


def is_path(node):
    return (isinstance(node, ast.Attribute) and node.attr == "path") or (isinstance(node, ast.Name) and node.id == "path")


def strings(node):
    items = node.elts if isinstance(node, (ast.Tuple, ast.List, ast.Set)) else [node]
    return [s for s in map(text_of, items) if s is not None]


def routes_in(source):
    """Route literals: first argument of re.fullmatch/re.match, comparisons of a `path` with strings, and startswith."""
    found = set()
    for node in ast.walk(ast.parse(source.read_text())):
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
            f = node.func
            if isinstance(f.value, ast.Name) and f.value.id == "re" and f.attr in ("fullmatch", "match") and node.args \
                    and len(node.args) > 1 and is_path(node.args[1]):
                found.update(strings(node.args[0]))
            if f.attr == "startswith" and is_path(f.value) and node.args:
                found.update(strings(node.args[0]))
        if isinstance(node, ast.Compare) and any(is_path(x) for x in [node.left, *node.comparators]):
            for x in [node.left, *node.comparators]:
                if not is_path(x):
                    found.update(strings(x))
    return {r for r in found if r.startswith("/")}


def documented():
    out = set()
    for line in SPEC.read_text().splitlines():
        m = re.match(r"\s*x-code: (\[.*\])\s*$", line)
        if m:
            out.update(json.loads(m.group(1)))
    return out


class OpenApiCoversRoutes(unittest.TestCase):
    def test_every_route_is_in_the_spec(self):
        have = documented()
        for source in SOURCES:
            routes = routes_in(source)
            self.assertTrue(routes, f"found no routes in {source.name}: the extraction is broken")
            missing = sorted(routes - have)
            self.assertEqual(missing, [], f"{source.name}: routes missing from backend/openapi.yaml (x-code): {missing}")

    def test_spec_names_no_route_the_code_lacks(self):
        code = set().union(*(routes_in(s) for s in SOURCES))
        stale = sorted(documented() - code)
        self.assertEqual(stale, [], f"x-code entries that are no longer in server.py or kitt.py: {stale}")

    def test_every_operation_has_an_access_kind(self):
        lines = SPEC.read_text().splitlines()
        ops = [i for i, l in enumerate(lines) if re.fullmatch(r"    (get|post):", l)]
        self.assertGreater(len(ops), 40)
        for i in ops:
            block = []
            for l in lines[i + 1:]:
                if re.match(r"    \S|  \S", l):
                    break
                block.append(l)
            self.assertTrue(any(re.match(r"\s+x-access: (page|chat door|kitt door)$", l) for l in block),
                            f"operation at line {i + 1} has no x-access")

    def test_spec_is_valid_yaml_with_the_openapi_shape(self):
        try:
            import yaml
        except ImportError:
            self.skipTest("PyYAML is not installed")
        spec = yaml.safe_load(SPEC.read_text())
        self.assertTrue(str(spec["openapi"]).startswith("3."))
        for path, item in spec["paths"].items():
            for method in ("get", "post"):
                if method in item:
                    self.assertIn("responses", item[method], f"{method.upper()} {path}")
                    self.assertIn(item[method]["x-access"], ("page", "chat door", "kitt door"), f"{method.upper()} {path}")


if __name__ == "__main__":
    unittest.main()
