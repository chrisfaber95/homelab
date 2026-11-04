# Search index (H-012, index side)

A private, local index over everything written down: the second brain, the product repo, session transcripts, repo
docs and code, and the F-023 memory. This part builds and keeps the index; the query side (router answers, Search
tab, MCP tool) is a later task. Code: `search/` (`chunkers.py`, `scrub.py`, `index.py`), CLI `bin/homelab-search`,
config `config/search.json`, nightly unit `systemd/homelab-search-sync.{service,timer}`, installer
`scripts/24-search.sh`. Tests: `tests/test_search_index.py`.

```
homelab-search sync [--source NAME]    re-index what changed, forget what is gone (the nightly job)
homelab-search rebuild                 build the whole index again from the sources and swap it in
homelab-search status [--json]         chunks per source, type and project; last sync
homelab-search query TEXT [--project P] [--type T] [-k N]   nearest chunks (to check the index)
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
