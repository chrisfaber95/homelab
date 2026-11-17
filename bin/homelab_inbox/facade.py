"""The facade in front of the Java backend (H-023, B6): the Python server forwards the routes listed in BACKEND_ROUTES
to the Spring Boot backend on loopback, with a shared secret header. The access check and the CSRF check of server.py
run first. With BACKEND_ROUTES empty (the default) nothing is forwarded and the server behaves as before."""
import http.client
import os
import urllib.parse

from .config import CONF, HOME

ROUTES = tuple(sorted({p.strip().rstrip("/") for p in os.environ.get("BACKEND_ROUTES", CONF.get("BACKEND_ROUTES", "")).split(",")
                       if p.strip().startswith("/api/")}))  # only /api/*: pages and /internal/* are never forwarded
BACKEND_URL = os.environ.get("BACKEND_URL") or f"http://127.0.0.1:{CONF.get('BACKEND_PORT', '8130')}"
SECRET_FILE = os.environ.get("BACKEND_SECRET_FILE") or str(HOME / "backend-secret")
TIMEOUT = 30
MAX_BODY = 8 * 1024 * 1024
LOOPBACK = ("127.0.0.1", "::1", "localhost")
SEND_HEADERS = ("Content-Type", "Accept", "Accept-Language", "If-None-Match", "If-Modified-Since")  # nothing else goes along
HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailer",
              "transfer-encoding", "upgrade", "content-length", "server", "date"}


def matches(path):
    """True when `path` is under a listed prefix (the prefix itself or below it, not a longer name)."""
    return any(path == r or path.startswith(r + "/") for r in ROUTES)


def clean_path(path):
    """False for a path that could climb out of its prefix (`..`, encoded dots or slashes, backslashes)."""
    decoded = urllib.parse.unquote(path)
    return "\\" not in decoded and "//" not in decoded and ".." not in decoded.split("/") and "%2f" not in path.lower()


def secret():
    """The shared secret from BACKEND_SECRET (env) or the file BACKEND_SECRET_FILE (default $RUNNER_HOME/backend-secret,
    0600, made by scripts/27-backend.sh); "" when there is none."""
    if os.environ.get("BACKEND_SECRET"):
        return os.environ["BACKEND_SECRET"].strip()
    try:
        with open(SECRET_FILE) as f:
            return f.read().strip()
    except OSError:
        return ""


def forward(method, target, headers, body, who, extra=None, timeout=TIMEOUT):
    """Send one request to the backend; return (status, [(header, value)], body bytes).
    Raises RuntimeError with a short message when the backend cannot be reached or is not configured."""
    base = urllib.parse.urlsplit(BACKEND_URL)
    if base.scheme != "http" or base.hostname not in LOOPBACK:
        raise RuntimeError("BACKEND_URL must be an http URL on loopback")
    key = secret()
    if not key:
        raise RuntimeError("no backend secret (BACKEND_SECRET or BACKEND_SECRET_FILE)")
    out = {h: headers[h] for h in SEND_HEADERS if headers.get(h)}
    out["X-Inbox-Facade"] = key
    out.update(extra or {})  # headers the inbox itself sets (the chat door's X-Inbox-Client); never the caller's
    out["X-Inbox-Who"] = "".join(c for c in who if c.isascii() and c.isprintable())[:100]
    conn = http.client.HTTPConnection(base.hostname, base.port or 80, timeout=timeout)
    try:
        conn.request(method, target, body=body or None, headers=out)
        r = conn.getresponse()
        data = r.read()
        return r.status, [(k, v) for k, v in r.getheaders() if k.lower() not in HOP_BY_HOP], data
    except (OSError, http.client.HTTPException) as e:
        raise RuntimeError(f"the backend did not answer ({type(e).__name__})") from e
    finally:
        conn.close()
