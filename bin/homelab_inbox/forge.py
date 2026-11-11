"""Forge controls on the homelab page (H-015): the page talks to the inbox only; the inbox calls forge's own
endpoints on the tailnet address. Forge starts stopped after every restart; only a press on Start runs it."""
from .config import CONF
from .search import call

FORGE_URL = CONF.get("FORGE_URL", "http://100.85.70.34:8120").rstrip("/")


def running(data):
    """Forge's /api/state says whether the worker runs; accept a boolean or a status word."""
    if isinstance(data.get("running"), bool):
        return data["running"]
    return str(data.get("status") or data.get("state") or "").lower() == "running"


def state():
    status, data = call(f"{FORGE_URL}/api/state", timeout=5)
    if status != 200 or not isinstance(data, dict):
        return 502, {"error": {"message": "Forge is not reachable (is homelab-app-forge running?)."}}
    return 200, {"running": running(data), "step": data.get("step"), "project": data.get("project")}


def control(action, who="?"):
    if action not in ("start", "stop"):
        return 400, {"error": {"message": "Unknown action."}}
    status, data = call(f"{FORGE_URL}/api/{action}", {}, timeout=10)
    if status >= 400:
        message = data.get("error", {}).get("message") if isinstance(data.get("error"), dict) else data.get("error")
        return 502, {"error": {"message": f"Forge refused {action} (HTTP {status}): {message or 'no reason given'}"}}
    print(f"forge {action} by {who}", flush=True)
    return state() if status == 200 else (status, data)
