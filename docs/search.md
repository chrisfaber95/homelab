# Search index (H-012, index side)

A private, local index over everything written down: the second brain, the product repo, session transcripts, repo
docs and code, and the F-023 memory. The index side builds and keeps the index; the query side
(ranked results, cited answers, MCP tool; below) reads it. The Search tab of the homelab page and the claude-dashboard
panel are later tasks and use the HTTP API described here. Code: `search/` (`chunkers.py`, `scrub.py`, `index.py`,
`answer.py`, `server.py`), CLI `bin/homelab-search`, MCP server `bin/homelab-search-mcp`, config `config/search.json`,
units `systemd/homelab-search-sync.{service,timer}` and `systemd/homelab-search-api.service`, installer
`scripts/24-search.sh`. Tests: `tests/test_search_index.py`, `tests/test_search_query.py`.

```
homelab-search sync [--source NAME]    re-index what changed, forget what is gone (the nightly job)
homelab-search rebuild                 build the whole index again from the sources and swap it in
homelab-search status [--json]         chunks per source, type and project; last sync
homelab-search query TEXT [--project P] [--type T] [--source S] [-k N] [--json]   ranked results
homelab-search ask TEXT [--hard] [--project P] [--type T] [--source S] [--json]    one cited answer
```

## Read-only on sources

The indexer opens source files for reading only, never follows symlinks, and reads the memory branch with git plumbing
(`ls-tree`, `cat-file`): nothing is checked out. The one write outside its own index is optional: a `git_branch`
source with `"fetch": true` runs `git fetch origin <branch>`, which updates only the clone's remote-tracking ref (the
unit allows writing just that clone, `-/home/claude/dev/homelab-data`). Nothing is pushed; the vault, the repos and the
`memory` branch are never changed. A test snapshots every source before and after a sync.

## Sources (`config/search.json`, `sources`)

Each entry has its own `name`, `type`, `kind`, `paths`, `include`/`exclude` patterns and can be switched off with
`"enabled": false`: at the next sync its chunks leave the index. Paths are a list per entry; globs work and `~` is the
service user's home (`claude`).

| name | type | kind | default path | what |
| --- | --- | --- | --- | --- |
| `second-brain` | `wiki` | markdown | `/srv/obsidian/second-brain` | `wiki/**`, `index.md`, `log.md` (not `raw/`) |
| `product` | `product` | markdown | `~/dev/product` | briefs, visions, roadmaps, research notes |
| `inbox-transcripts` | `transcript` | transcript | `/var/lib/inbox-sessions/projects` | inbox task sessions, copied out of `/var/lib/inbox` (see below) |
| `claude-sessions` | `transcript` | transcript | `~/.claude/projects` | interactive sessions of `claude` |
| `repo-docs` | `repo-doc` | markdown | `/srv/homelab/repos/*` | `*.md` of every deployed repo |
| `repo-code` | `repo-code` | code | `/srv/homelab/repos/*` | source, scripts, units, `bin/*` (not lockfiles, build output, `.env*`, keys, files over 300 KB) |
| `memory` | `memory` | memory | `~/dev/homelab-data`, branch `origin/memory` | F-023 preferences, facts, history |

**Inbox transcripts.** `claude` has no access to `/var/lib/inbox`. The existing copy for claude-dashboard
(`/var/lib/inbox-sessions`, written by `homelab-inbox-sessions.service`) only held the session `*.json` files, so
`scripts/21-inbox.sh` now also copies the transcript files (`projects/**/*.jsonl`, group `claude`, 0640) into
`/var/lib/inbox-sessions/projects`. The two transcript sources are separate entries; switch either off by setting
`enabled` to `false`. The memory clone `~/dev/homelab-data` must exist for `claude` (`gh repo clone
chrisfaber95/homelab-data`); a missing path simply yields no files.

## Chunking rules

| kind | one chunk is | `location` | date |
| --- | --- | --- | --- |
| markdown, memory | a heading section (text before the first heading is its own chunk, located at the title; headings in code fences don't count; empty sections are skipped) | heading path, `Router > Tiers` | front matter `updated`, `date`, `gewijzigd`, `toegevoegd`, else the file's mtime |
| transcript | one turn: a user or assistant message with text. Tool calls and tool results, thinking and `<system-reminder>` blocks are dropped | `turn 7 (assistant)` | the turn's timestamp |
| code | a function or class sized block: a block starts at a top-level `def`/`class` (Python), function (shell, JS/TS/Vue); small neighbours merge up to ~1500 characters. Files without a block rule are cut in windows of 60 lines | `L12-58 build_index` (lines and the first definition) | the file's mtime |

Text over 2400 characters is split at blank lines, then lines, then characters; the location gets `(part 2/3)`.
The heading path is part of the chunk text, so the embedding knows what a section is about. A rule change means
bumping `CHUNKER_VERSION` in `search/chunkers.py` (see Incremental behaviour).

## Metadata per chunk

`source` (the config entry), `type` (`wiki`, `product`, `transcript`, `repo-doc`, `repo-code`, `memory`), `path` (inside
the source folder; for `multi_root` sources `<repo>/<path>`), `location`, `date` (`YYYY-MM-DD`), `project`, `text`.
Queries filter on `project`, `type` and `source`. Project comes from the source's `project` (`{root}` is the folder
name) and `project_rules` (first match wins): wiki `wiki/projects/<name>.md` gives `<name>`, product repo the first
folder (`homelab`, `claude-dashboard`, ...), memory the `over` field of the front matter (`algemeen`, `box`, a repo),
transcripts the repo named in a `Taak voor de repo X:` prompt, else the working folder of the session.

## Secret scrubbing

Every chunk, its location and its path go through the patterns in `config/search.json` (`scrub`) **before it is
embedded or stored**; a secret is replaced by `[REDACTED:<name>]`. A pattern with a `group` replaces only that group
(`password: [REDACTED:assigned-secret]`). Patterns: private-key blocks (also a truncated block), Anthropic and
OpenAI-style keys, GitHub tokens, AWS keys, Slack tokens, JWTs, bearer tokens, passwords in URLs, `.env`-style
`NAME_TOKEN=value` lines, `password|token|secret|api_key ...: value`, "the password is X". Files such as `.env*`,
`*.pem` and `*.key` are not read at all. Add a pattern to the config to cover a new kind of secret; the index then
asks for a rebuild (below), so old chunks are scrubbed again from their sources. Scrubbing is pattern-based: a secret
in a shape no pattern knows is not caught, so don't put one in a note.
`tests/test_search_index.py` plants a fake key, password, `.env` value and private key in a test transcript and
checks the database file bytes, the embedder's input and the results of a query for each secret.

## Incremental behaviour

- Per file the index keeps `key` (size and mtime; for git the blob id) and `hash` (sha256 of the content). A sync
  lists each source, skips files whose key is unchanged, hashes the rest, and re-chunks only a file whose hash changed
  (a file that was only touched is just updated). Chunks of a changed file whose scrubbed text did not change keep their
  vector, so appending to a transcript embeds only the new turns.
- A file that is gone, or a source switched off or removed from the config, has its chunks removed.
- The embedding of a file finishes before its old chunks are replaced, and each file is committed on its own: a failed
  or interrupted sync leaves a consistent index and the next one carries on.
- A **sync never re-indexes everything.** If the embedding model, `CHUNKER_VERSION`, `doc_prefix` or a scrub pattern
  changed since the index was built, `sync` stops with exit code 3 and asks for `rebuild`; it does not start a
  multi-hour full pass on its own.
- `rebuild` (`homelab-search rebuild`, or `sudo bash /var/lib/homelab/repo/scripts/24-search.sh --rebuild`) builds
  `index.db.new` from the sources and swaps it in at the end; the old index serves until then. On a rebuilt box
  `24-search.sh` builds the index when there is none.

## Nightly sync and install

`systemd/homelab-search-sync.timer` runs `homelab-search sync` at 03:15 (after the deploy at 02:30, before the
backup at 03:30) as user `claude`, `Nice=10`, with the file system read-only except `/var/lib/homelab-search` (the
index: `/var/lib/homelab-search/index.db`, state of the unit). `scripts/24-search.sh` (root) installs the programs
into `/usr/local/lib/homelab`, pulls the embedding model into Ollama, installs the units and builds the first index. It
opens no port: Ollama stays on `127.0.0.1`. `scripts/21-inbox.sh` must also have been re-run for the transcript copy.
Errors show in `journalctl -u homelab-search-sync` (or `systemctl status`).

## Embedding model and GPU memory

`nomic-embed-text` (137M parameters, 768 dimensions, 2048-token context, Ollama `/api/embed`, with its
`search_document:`/`search_query:` prefixes), run by the same Ollama as the 8B model, so it sits on the same GPU.
`keep_alive` is 30 s: after a sync the embedder leaves the GPU. Chosen because it is small (about 0.27 GB on disk),
good for English and mixed technical text, and the vectors are only 3 KB each. Not measured against alternatives
(`all-minilm`, 46 MB, would be smaller but with a 256-token context it truncates chunks).

Measured on the laptop (GTX 1050, 2048 MiB), 2026-11-04, `llama3.1:8b` loaded with `LLM_NUM_CTX=4096`
(84%/16% CPU/GPU, as in F-017), `nvidia-smi`:

| state | GPU memory used |
| --- | --- |
| `llama3.1:8b` only | 901 MiB |
| plus `nomic-embed-text` loaded and embedding (4 chunks of ~2.4 KB per call) | 1075 MiB (**+174 MiB**; `ollama ps`: 414 MB, 74%/26% CPU/GPU) |

It fits with about 970 MiB to spare. Throughput while the 8B model is also loaded: about 4 chunks per second (3,391 chunks in 14 minutes, first sync of the wiki, repo docs, repo code and `claude`'s own transcripts; the laptop CPU is shared with the 8B model, so a first build takes long and a nightly sync of a few changed files takes seconds).
Index size on disk: 45 MB for 8,436 chunks (about 5 KB per chunk, 3 KB of it the vector). A query over that index (embedding the question plus ranking) took 0.6 s.

**GTX 1080 (8192 MiB): not measured yet; to be done by hand after the move.** Measure with `llama3.1:8b` at
`LLM_NUM_CTX=8192` (100% GPU), then `curl -s localhost:11434/api/embed -d '{"model":"nomic-embed-text","input":["test"]}'`
and `nvidia-smi --query-gpu=memory.used --format=csv`; fill in here:

| state | GPU memory used |
| --- | --- |
| `llama3.1:8b` only (8192 context) | _to measure_ |
| plus `nomic-embed-text` | _to measure_ |

## Store and limits

SQLite (`index.db`) with tables `files`, `chunks` (text, metadata, `embedding` as a float32 blob) and `meta`. There is
no vector extension on the box (no `sqlite-vec`, no numpy), so a query pre-filters by project, type or source in SQL
and ranks the remaining vectors by cosine similarity in Python: fine for tens of thousands of chunks (0.6 s measured at
8,400). Beyond that, swap the ranking in `Index.query` for `sqlite-vec` or an ANN index; the stored data stays
the same.

## Query and answer (H-012, query side)

Everything here is **read-only**: the index is opened with SQLite `mode=ro` (a missing index is an error, never created),
sources are never opened, and the only thing written anywhere is the router's own usage log. A query against an index
built with another model, chunker version or scrub patterns is refused (as `sync` is): run `rebuild`.

### Results

`homelab-search query "where did we decide on sqlite?" --project homelab --type product` prints, per result, the score,
`[type] project · path · location · date`, a snippet and a link. `--json` gives the same as objects. The HTTP and MCP
variants return the same fields: `score`, `source`, `type`, `path`, `location` (heading path or `turn N (role)`), `date`,
`project`, `link`, `snippet`. Filters: `project`, `type` (`wiki`, `product`, `transcript`, `repo-doc`, `repo-code`,
`memory`), `source` (a config entry name).

Links come from `query.links` in `config/search.json`: one URL template per source name with `{path}`, `{path_noext}`,
`{repo}` (first folder) and `{rest}`. Wiki notes link to `obsidian://open?vault=second-brain&file=...`, product, repo and
memory chunks to GitHub on the branch the index reads (the repos are private: you need to be logged in). **Transcript
turns have no link** (there is no page for a session file yet); they show path and turn. A source without a template just
has no link.

### Ask

`homelab-search ask "why did we choose a local embedding model?"` retrieves the `query.ask_k` (6) best chunks, keeps those
with cosine similarity >= `query.min_score` and asks the router to write at most five sentences from them, citing
`[n]`. One question, one answer: nothing is kept between calls.

- **Tier**: caller `search`, tier `easy` (local model) by default; `--hard` (`hard: true`, `hard=1`) uses tier `hard`
  (Claude through the CLI, so it spends allowance). Which backend each tier means is the router's table
  (`config/router.json`), and its escalation rule applies as for any caller. Tier names are `query.tier` and
  `query.hard_tier`.
- **Nothing relevant**: when no chunk reaches `min_score` (or the filters match nothing) the answer is
  "Nothing relevant was found in the search index for this question, so there is no answer.", `found` is `false`,
  `request_id` is `null` and **the router is not called**. `best_score` tells how close the best chunk came.
- **Citations**: `citations` lists the excerpts the answer refers to (`n`, source, location, date, project, `link`,
  `cited`). `answer_markdown` turns each `[n]` into a link when the source has one. If the model cites no valid number,
  all excerpts it was given are listed with `cited: false`; a number it invents is ignored.
- **Feedback**: the router's `request_id` is returned (CLI prints it). A bad answer: `llm-route feedback <request_id>`
  (or `POST /v1/feedback` on the router); it shows in the router usage view under caller `search`.
- The excerpts are put in the prompt as data and the model is told to ignore instructions in them. The question is run
  through the same secret scrubbing as the index before it is embedded or sent.

**`min_score` is not calibrated yet.** The default 0.5 is a starting value for `nomic-embed-text`; the index was not
available while this was built. After the first sync, try a few questions you know the answer to and some nonsense with
`homelab-search ask --json` (`best_score`) and set `query.min_score` just below the former and above the latter. Too high:
real questions get "nothing found"; too low: weak chunks reach the model.

### HTTP API (service `homelab-search-api`, port 8019)

Tailnet only: ufw `allow in on tailscale0 to any port 8019`, no LAN rule, and the server itself refuses clients outside
loopback and `100.64.0.0/10` (like the router). The log holds the path, not the query string.

```
GET  /v1/search?q=TEXT[&project=P][&type=T][&source=S][&k=N]
GET  /v1/ask?q=TEXT[&hard=1][&project=P][&type=T][&source=S]      (or POST {"question", "hard", "project", ...})
GET  /v1/filters     projects, types, sources, chunk count, last sync (for a filter dropdown)
GET  /health
```

Errors are `{"error": {"message", "request_id"?}}`: 400 empty or bad input, 413 question over 2000 characters, 502 router
unreachable or failed (with its request id), 503 index missing or stale. The service runs as `claude` with the file
system read-only (`ProtectSystem=strict`, `ReadOnlyPaths=/var/lib/homelab-search`, `ProtectHome`). It needs Ollama
(embedding the question) and, for `ask`, the router on `127.0.0.1:8018` (`query.router_url`).

### Claude tool (MCP) and the instruction

`bin/homelab-search-mcp` is an MCP server over stdio (stdlib only, no SDK: the box has no `mcp` package) with two
read-only tools, `search` and `ask` (arguments as above; `hard` is an optional boolean). It only calls the HTTP API
(`SEARCH_URL`, default `http://127.0.0.1:8019`), so it needs the service running and has no access to the index or sources.

Install notes:
1. `sudo bash /var/lib/homelab/repo/scripts/24-search.sh` installs the programs, the service and the ufw rule, and
   registers the tool for the user `claude` (`claude mcp add --scope user homelab-search -- /usr/local/bin/homelab-search-mcp`;
   the script prints the command when it cannot find `claude`). Check as `claude`: `claude mcp list`.
2. As `claude`: `bash /home/claude/homelab/scripts/18-claude.sh` (or the clone you use) installs `claude/CLAUDE.md` as
   `~/.claude/CLAUDE.md`, whose section **Private search** tells every session to query search before reading many files,
   with the CLI as fallback. Edit that text in the repo, not in `~/.claude`.
3. In sessions with a permission prompt the first call asks for `mcp__homelab-search__search` / `__ask`; allow them
   (both are read-only). Whether inbox rounds see user-scope MCP servers depends on the account they run as; not verified here.

Other clients: the Search tab on the homelab page and the claude-dashboard panel call the same `/v1/*` endpoints (not part
of this change).
