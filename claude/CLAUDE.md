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

## Merging product PRs

Chris's choice (2026-10-30): on the product repos Claude merges its own PRs, without waiting for Chris. These are the
repos in `config/repos.conf` of homelab except homelab itself: `claude-dashboard`, `Visualshopping-Server-portfolio`,
`Client-portfolio`, `Werknemers-portfolio`. The box deploys `main` within a minute (`homelab-deploy`).

- Only a PR you opened yourself, from a branch you pushed. `gh` is logged in as Chris, so the PR author tells you
  nothing: never merge a PR from someone else, from a fork or from the inbox (`inbox/<id>` branches) this way.
- Before merging: the build and tests pass locally, `gh pr checks` is green (if the repo has CI), no secrets in the
  diff (`gitleaks git . --log-opts=origin/main..HEAD`), and a repo's own merge rules (its `CLAUDE.md`, e.g.
  `merge_mode: ask`) win.
- Merge with `gh pr merge <n> --merge --delete-branch`; still never push to `main` (the pre-push hook stays).
- Afterwards, check `homelab-deploy status`: the new commit is running. If the deploy failed or the app broke, open
  and merge a revert PR right away, and say so in your answer.
- Not on `homelab` (a merge there runs scripts as root: Chris merges), not on the data repo (jobs, memory), and not
  on any repo outside that list. In `product`, only a designer's own brief PR (`brief/H-NNN`) with `status: draft`:
  Chris approves briefs on the Briefs tab of the homelab page, never by a merge.
