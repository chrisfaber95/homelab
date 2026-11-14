"""The secret filter. A copy of config.SECRET_RE of the inbox (this service imports nothing from the inbox);
tests/test_discord_bot.py fails when the two differ. Everything that leaves the box goes through scrub()."""
import re

SECRET_RE = re.compile(
    r"gh[pousr]_[A-Za-z0-9]{20,}|github_pat_\w{20,}|sk-ant-[\w-]{10,}|sk-[A-Za-z0-9]{20,}|xox[abprs]-[\w-]{10,}"
    r"|AKIA[0-9A-Z]{16}|tskey-[\w-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----|eyJ[\w-]{10,}\.[\w-]{10,}\.[\w-]{5,}"
    r"|(?i:bearer)\s+[\w.~+/-]{16,}=*|(?i:token|password|passwd|secret|api[_-]?key|wachtwoord)\s*[:=]\s*\S{8,}")
REMOVED = "[removed: looks like a secret]"


def scrub(text):
    return SECRET_RE.sub(REMOVED, text or "")
