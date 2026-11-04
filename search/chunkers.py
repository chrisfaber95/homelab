"""homelab: chunking rules for the search index (H-012, docs/search.md). Pure functions, no I/O except reading the
bytes they are given. Every chunker yields dicts {location, text, date} (date may be None: the caller falls back
to the file date). Only stdlib."""
import json
import re
from datetime import datetime, timezone

CHUNKER_VERSION = 1  # bump when a rule changes: the index then asks for a rebuild
TARGET_CHARS = 1500
MAX_CHARS = 2400

FENCE = re.compile(r"^\s*(```|~~~)")
HEADING = re.compile(r"^(#{1,6})\s+(.*?)\s*#*\s*$")
SYSTEM_REMINDER = re.compile(r"<system-reminder>.*?</system-reminder>", re.S)


def split_long(text, limit=MAX_CHARS):
    """Split text at blank lines (then lines, then characters) into parts of at most `limit` characters."""
    if len(text) <= limit:
        return [text]
    parts, cur = [], ""
    for para in re.split(r"\n\s*\n", text):
        for piece in _hard_split(para, limit):
            if cur and len(cur) + len(piece) + 2 > limit:
                parts.append(cur)
                cur = piece
            else:
                cur = cur + "\n\n" + piece if cur else piece
    if cur:
        parts.append(cur)
    return parts


def _hard_split(text, limit):
    if len(text) <= limit:
        return [text]
    out, cur = [], ""
    for line in text.split("\n"):
        while len(line) > limit:
            if cur:
                out.append(cur)
                cur = ""
            out.append(line[:limit])
            line = line[limit:]
        if cur and len(cur) + len(line) + 1 > limit:
            out.append(cur)
            cur = line
        else:
            cur = cur + "\n" + line if cur else line
    if cur:
        out.append(cur)
    return out


def _parts(location, text, date=None):
    parts = split_long(text)
    for i, part in enumerate(parts, 1):
        yield {"location": location if len(parts) == 1 else f"{location} (part {i}/{len(parts)})",
               "text": part, "date": date}


# --- Markdown: by heading

def front_matter(text):
    """Return ({key: value}, body) for a document that starts with a --- block (simple `key: value` lines)."""
    if not text.startswith("---\n"):
        return {}, text
    end = text.find("\n---", 4)
    if end < 0:
        return {}, text
    meta = {}
    for line in text[4:end].splitlines():
        if ":" in line and not line.startswith((" ", "-")):
            k, v = line.split(":", 1)
            meta[k.strip()] = v.strip().strip("\"'")
    rest = text[end + 4:]
    return meta, rest.lstrip("\n")


def markdown_date(meta):
    for key in ("updated", "date", "gewijzigd", "toegevoegd", "created"):
        m = re.match(r"\d{4}-\d{2}-\d{2}", meta.get(key, ""))
        if m:
            return m.group(0)
    return None


def chunk_markdown(text, title):
    """One chunk per heading section; location is the heading path ("Router > Tiers"). Text before the first heading
    is located at the document title. Headings inside code fences are not headings. Empty sections are skipped."""
    meta, body = front_matter(text)
    date = markdown_date(meta)
    stack, lines, in_fence, sections = [], [], False, []
    cur_loc = title

    def flush():
        content = "\n".join(lines).strip()
        if content:
            sections.append((cur_loc, content))

    for line in body.splitlines():
        if FENCE.match(line):
            in_fence = not in_fence
        m = None if in_fence else HEADING.match(line)
        if m:
            flush()
            lines = []
            level = len(m.group(1))
            stack = [(lv, t) for lv, t in stack if lv < level] + [(level, m.group(2))]
            cur_loc = " > ".join(t for _, t in stack)
        else:
            lines.append(line)
    flush()
    for loc, content in sections:
        # The heading path goes into the text too: it is what the embedding should know the section is about.
        yield from _parts(loc, f"{loc}\n\n{content}" if loc != title else content, date)


# --- Transcripts: by turn

def _turn_text(content):
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    # Only what was said: no tool calls or tool results (noisy, and where secrets from files and commands end up).
    return "\n".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")


def transcript_turns(raw):
    """Yield (role, text, date, cwd) per user or assistant turn with text, in order. Bad lines are skipped."""
    for line in raw.splitlines():
        try:
            rec = json.loads(line)
        except ValueError:
            continue
        if not isinstance(rec, dict) or rec.get("type") not in ("user", "assistant") or rec.get("isMeta"):
            continue
        msg = rec.get("message") or {}
        text = SYSTEM_REMINDER.sub("", _turn_text(msg.get("content"))).strip()
        if not text:
            continue
        ts = str(rec.get("timestamp", ""))[:10] or None
        yield rec["type"], text, ts, rec.get("cwd")


def chunk_transcript(raw):
    for n, (role, text, date, _cwd) in enumerate(transcript_turns(raw), 1):
        yield from _parts(f"turn {n} ({role})", f"{role}: {text}", date)


# --- Code: by function/class sized blocks

BLOCK_START = {
    ".py": re.compile(r"^(?:@\w|(?:async\s+)?def\s|class\s)"),
    ".sh": re.compile(r"^(?:[A-Za-z_][\w-]*\s*\(\)\s*\{|function\s)"),
    ".js": re.compile(r"^(?:export\s|function\s|async\s+function\s|class\s|const\s+\w+\s*=\s*(?:async\s*)?(?:\(|function))"),
    ".ts": re.compile(r"^(?:export\s|function\s|async\s+function\s|class\s|interface\s|type\s|const\s+\w+\s*=\s*(?:async\s*)?(?:\(|function))"),
}
BLOCK_START[".mjs"] = BLOCK_START[".js"]
BLOCK_START[".vue"] = BLOCK_START[".ts"]
WINDOW_LINES = 60


def chunk_code(text, ext):
    """Blocks that start at a top-level function or class (Python, shell, JS/TS); small neighbours are merged up to
    TARGET_CHARS, a block over MAX_CHARS is cut by lines. Files without a known block rule (config, units, Python
    scripts without extension use .py) are cut into windows of WINDOW_LINES lines. Location: `L<start>-<end>` plus the
    name of the first definition."""
    lines = text.splitlines()
    if not lines:
        return
    rx = BLOCK_START.get(ext)
    starts = [0]
    if rx:
        prev_decorator = False
        for i, line in enumerate(lines):
            if i and rx.match(line):
                if not prev_decorator:
                    starts.append(i)
                prev_decorator = line.startswith("@")
            elif i:
                prev_decorator = False
    else:
        starts = list(range(0, len(lines), WINDOW_LINES))
    bounds = starts[1:] + [len(lines)]
    blocks = [(s, e) for s, e in zip(starts, bounds) if "\n".join(lines[s:e]).strip()]
    merged = []
    for s, e in blocks:
        if merged and sum(len(x) + 1 for x in lines[merged[-1][0]:merged[-1][1]]) + sum(len(x) + 1 for x in lines[s:e]) <= TARGET_CHARS:
            merged[-1] = (merged[-1][0], e)
        else:
            merged.append((s, e))
    for s, e in merged:
        for ps, pe in _line_parts(lines, s, e):
            body = "\n".join(lines[ps:pe])
            name = _first_name(lines[ps:pe])
            yield {"location": f"L{ps + 1}-{pe}" + (f" {name}" if name else ""), "text": body, "date": None}


def _line_parts(lines, s, e):
    size, ps = 0, s
    for i in range(s, e):
        n = len(lines[i]) + 1
        if size and size + n > MAX_CHARS:
            yield ps, i
            ps, size = i, 0
        size += n
    yield ps, e


_NAME = re.compile(r"^\s*(?:export\s+)?(?:async\s+)?(?:def|class|function|interface|type|const)\s+(\w+)|^(\w[\w-]*)\s*\(\)\s*\{")


def _first_name(block):
    for line in block:
        m = _NAME.match(line)
        if m:
            return m.group(1) or m.group(2)
    return ""


def iso_date(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).strftime("%Y-%m-%d")
