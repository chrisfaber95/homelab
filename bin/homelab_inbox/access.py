"""Who may open the page: the CSRF token and the Tailscale owner check (F-024)."""
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import time

from .config import SECRET_FILE
from .storage import log


def secret():
    if not SECRET_FILE.exists():
        SECRET_FILE.write_text(secrets.token_hex(32))
        SECRET_FILE.chmod(0o600)
    return SECRET_FILE.read_text().strip().encode()


def csrf_token():
    return hmac.new(secret(), b"inbox-form", hashlib.sha256).hexdigest()


class Access:
    """Wie mag de pagina zien: alleen de eigenaar van de box op Tailscale, niet de box zelf, niet het LAN."""
    own, owner, cache = set(), None, {}

    @classmethod
    def init(cls):
        try:
            st = json.loads(subprocess.run(["tailscale", "status", "--json"], capture_output=True, text=True,
                                           timeout=10).stdout)
            cls.owner = st["User"][str(st["Self"]["UserID"])]["LoginName"]
            cls.own = set(st["Self"]["TailscaleIPs"]) | {"127.0.0.1", "::1"}
        except Exception as e:
            log(f"tailscale status: {e}")

    @classmethod
    def allowed(cls, ip):
        if os.environ.get("INBOX_ALLOW_ALL") == "1":  # alleen voor tests
            return True, "test"
        if ip in cls.own or not cls.owner:
            return False, "niet vanaf de box zelf"
        hit = cls.cache.get(ip)
        if not hit or hit[1] < time.monotonic():
            try:
                w = json.loads(subprocess.run(["tailscale", "whois", "--json", ip], capture_output=True, text=True,
                                              timeout=5).stdout)
                login = w["UserProfile"]["LoginName"]
                device = w["Node"]["ComputedName"]
            except Exception:
                login, device = None, None
            hit = ((login, device), time.monotonic() + 300)
            cls.cache[ip] = hit
        login, device = hit[0]
        return login == cls.owner, device or ip
