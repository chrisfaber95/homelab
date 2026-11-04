"""Search tab backend (H-012): the page talks to the inbox only; the inbox calls the search API and the router on
loopback. The browser never needs another port, so no CORS and no new listener."""
import json
import re
import urllib.error
import urllib.parse
import urllib.request

from .config import CONF

SEARCH_URL = CONF.get("SEARCH_URL", "http://127.0.0.1:8019").rstrip("/")
ROUTER_URL = CONF.get("ROUTER_URL", "http://127.0.0.1:8018").rstrip("/")
FILTER_FIELDS = ("project", "type", "source")
REQUEST_ID_RE = re.compile(r"[A-Za-z0-9_-]{6,64}")
ASK_SECONDS = 330  # a hard question goes to Claude, whose tier timeout is up to 300 s


def call(url, body=None, timeout=30):
    """(status, parsed JSON) of an upstream call. A dead or garbled upstream becomes a 502 with a JSON error."""
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json"} if body is not None else {})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.load(e)
        except ValueError:
            return e.code, {"error": {"message": f"Upstream answered HTTP {e.code}."}}
    except (OSError, ValueError) as e:
        return 502, {"error": {"message": f"The search service is unreachable: {e}"}}


def filters():
    return call(f"{SEARCH_URL}/v1/filters")


def results(q, params):
    query = {"q": q, **{k: params[k] for k in FILTER_FIELDS if params.get(k)}}
    return call(f"{SEARCH_URL}/v1/search?{urllib.parse.urlencode(query)}")


def ask(form):
    question = form.get("question", "").strip()
    if not question:
        return 400, {"error": {"message": "Type a question first."}}
    body = {"question": question[:2000], **{k: form[k] for k in FILTER_FIELDS if form.get(k)}}
    if form.get("hard") in ("1", "true", "on"):
        body["hard"] = True
    return call(f"{SEARCH_URL}/v1/ask", body, ASK_SECONDS)


def feedback(form):
    """Thumbs-down for the router request behind an answer; the router stores caller, tier and backend with it."""
    rid = form.get("request_id", "")
    if not REQUEST_ID_RE.fullmatch(rid):
        return 400, {"error": {"message": "That answer has no request id to mark."}}
    return call(f"{ROUTER_URL}/v1/feedback", {"request_id": rid, "note": form.get("note", "")[:1000] or None})
