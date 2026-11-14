"""The bot's two outgoing connections, both on loopback: the inbox's chat door (docs/conversation.md) and the search
API. Blocking urllib, called from the bot through asyncio.to_thread. Every call returns (status, body); status 0 means
the service could not be reached. The tokens are only ever in the Authorization header."""
import json
import urllib.error
import urllib.parse
import urllib.request


class Api:
    def __init__(self, inbox_url, chat_token, search_url, timeout=30, search_timeout=120):
        self.inbox, self.search_url = inbox_url.rstrip("/"), search_url.rstrip("/")
        self._token, self.timeout, self.search_timeout = chat_token, timeout, search_timeout

    def _call(self, url, body=None, auth=True, timeout=None):
        req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode(),
                                     method="GET" if body is None else "POST")
        if body is not None:
            req.add_header("Content-Type", "application/json")
        if auth:
            req.add_header("Authorization", f"Bearer {self._token}")
        try:
            with urllib.request.urlopen(req, timeout=timeout or self.timeout) as r:
                return r.status, json.loads(r.read() or b"{}")
        except urllib.error.HTTPError as e:
            try:
                return e.code, json.loads(e.read() or b"{}")
            except ValueError:
                return e.code, {"error": f"HTTP {e.code}"}
        except (urllib.error.URLError, OSError, ValueError) as e:
            return 0, {"error": f"not reachable ({type(e).__name__})"}

    def entries(self, after, limit=200):
        return self._call(f"{self.inbox}/api/chat/entries?after={int(after)}&limit={int(limit)}")

    def append(self, kind, text, ref=None):
        return self._call(f"{self.inbox}/api/chat/entries", {"kind": kind, "text": text, "ref": ref})

    def action(self, action, **fields):
        return self._call(f"{self.inbox}/api/chat/action", {"action": action, **fields})

    def ask(self, question):
        q = urllib.parse.urlencode({"question": question})
        return self._call(f"{self.search_url}/v1/ask?{q}", auth=False, timeout=self.search_timeout)
