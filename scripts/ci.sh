#!/usr/bin/env bash
# homelab: the checks of every pull request (the workflow in docs/ci/pull-request.yml runs this). Run it yourself
# before a PR: bash scripts/ci.sh. There is no package.json, so no npm lint, typecheck or build; for this repo those are:
#   lint       shellcheck on the shell scripts, py_compile on the Python (also catches syntax errors = "typecheck")
#   validate   docker compose config on compose.yaml, and the exposure check on its definition
#   tests      python3 -m unittest discover -s tests
#   build      docker compose pull of the pinned image (CI, or CI_BUILD=1): proves the image tag exists
set -euo pipefail
cd "$(dirname "$(dirname "$(readlink -f "$0")")")"

echo "== shellcheck"
if command -v shellcheck >/dev/null; then
  shellcheck -S error scripts/*.sh bin/homelab-health bin/homelab-inbox-task bin/homelab-exposure changes/*/*.sh
else
  # In CI (CI=true, set by GitHub Actions) a missing shellcheck is a failure, never a silent skip.
  [[ ${CI:-} == true ]] && { echo "shellcheck is not installed" >&2; exit 1; }
  echo "shellcheck is not installed: skipped (CI would fail here)" >&2
fi

echo "== python syntax"
python3 -I - <<'PY'
import ast, pathlib
files = [p for p in pathlib.Path(".").rglob("*.py") if ".git" not in p.parts]
files += [pathlib.Path(f) for f in ("bin/homelab-admin", "bin/homelab-backup", "bin/homelab-deploy",
                                    "bin/homelab-inbox", "bin/homelab-monitor", "bin/llm")]
for p in files:
    ast.parse(p.read_text(), str(p))
print(f"{len(files)} files parse")
PY

echo "== compose config and exposure"
docker compose -f compose.yaml config --quiet
bin/homelab-exposure --static

echo "== build"
# The only thing built from this repo is the pinned image reference: pulling it proves the tag exists.
# Needs the network and a Docker daemon, so it runs in CI (CI=true) or with CI_BUILD=1, not on every local run.
if [[ ${CI:-} == true || ${CI_BUILD:-} == 1 ]]; then
  docker compose -f compose.yaml pull --quiet
else
  echo "skipped locally (set CI_BUILD=1 to pull the image)"
fi

echo "== tests"
python3 -m unittest discover -s tests

echo "ci: all checks passed"
