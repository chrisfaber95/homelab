# All Claude sessions of `claude` on the homelab box

Installed as `~/.claude/CLAUDE.md` by `scripts/18-claude.sh` from `claude/CLAUDE.md` in the homelab repo; edit it
there (via a PR), not here: the next run of the script overwrites this file. Repo-level `CLAUDE.md` files still apply
on top of this one.

## Second brain: project knowledge

`/srv/obsidian/second-brain` is an Obsidian vault with an LLM-maintained wiki about Chris's projects: one page per
project, concept and decision, each claim with its source. Start at its `index.md`.

- Read it when a task needs context beyond the repo: why something was decided, how projects relate, what a project
  is for, open questions. Pages name their sources; follow them when it matters.
- It's synced nightly from each repo's main branch. When it disagrees with the repo you're in, the repo wins: say so
  in your answer so Chris can fix the wiki.
- **Read-only from here.** Don't edit, add, move or commit anything in the vault from another session; only sessions
  started inside the vault (its own skills and nightly job) change it. Found something worth keeping (a decision, a
  lasting fact)? Mention it in your final answer, and Chris can add it with `/ingest`.
- No secrets in there, and don't copy any from it into code, PRs or messages.

## Code map: code structure

`/srv/obsidian/code-map` is a graphify knowledge graph of the code on each repo's main branch (generated nightly; never
edit it). For structure questions (what calls what, where a feature lives, how modules group), before reading many
files:

- `_START.md` and `_REPORT_<repo>.md` there: clusters, the most connected symbols, surprising links.
- `~/.local/share/graphify-venv/bin/graphify query "<question>" --graph ~/.local/share/code-map/out/<repo>/graphify-out/graph.json --budget 2000`
  (also `explain "<symbol>"` and `path "<A>" "<B>"`; `<repo>`: homelab, claude-dashboard, client-portfolio,
  visualshopping-server, werknemers-portfolio; all repos at once: `~/.local/share/code-map/merged/graphify-out/graph.json`).

It reflects main, not your working branch: check the actual files before changing code.
