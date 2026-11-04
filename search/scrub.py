"""homelab: secret scrubbing for the search index (H-012). Runs on every chunk before it is embedded or stored.

The patterns live in config/search.json ("scrub"). Each has a name, a regex and optionally a group number: with a
group only that group is replaced (the key name stays readable), without one the whole match is. Only stdlib.
"""
import re


class Scrubber:
    def __init__(self, patterns):
        self.rules = []
        for p in patterns:
            self.rules.append((p["name"], re.compile(p["regex"]), int(p.get("group", 0))))

    def scrub(self, text):
        """Return text with every secret replaced by [REDACTED:<pattern name>]."""
        for name, rx, group in self.rules:
            text = rx.sub(lambda m, n=name, g=group: _replace(m, n, g), text)
        return text


def _replace(m, name, group):
    mark = f"[REDACTED:{name}]"
    if not group:
        return mark
    if m.start(group) < 0:
        return m.group(0)
    s = m.start(group) - m.start(0)
    e = m.end(group) - m.start(0)
    whole = m.group(0)
    return whole[:s] + mark + whole[e:]
