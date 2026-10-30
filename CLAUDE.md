# homelab

The old desktop (i7-4790K, 16 GB, GTX 1080 8 GB) as a headless Ubuntu box for a local LLM, projects and Claude Code.
For now it runs on the old laptop (7th gen i7, 24 GB, GTX 1050 Mobile 2 GB) while the desktop's PSU is broken; after
that the SSD (250 GB) moves to the desktop.

The why and what are in the briefs: the private repo `chrisfaber95/product`, folder `homelab/` (VISION, ROADMAP, briefs
F-016 to F-024 and H-NNN, research), cloned at `~/dev/product`. New features are designed there (`docs/briefs.md`).

Current state, next work and things the user still has to test: `STATUS.md` (and `CHECKLIST.md`). Testing tips per
part load automatically from `.claude/rules/` when you work on that part.

## Rules

- Language: write everything new in English (docs, CLAUDE.md, CHECKLIST, comments, UI text, messages, commit
  messages, PRs). Existing Dutch text stays as it is; don't translate it unless asked. Rewriting a section anyway?
  Then write it in English. Keep existing keywords and routes (`GEHEUGEN:`, `KLAAR`, `/taken`, ...) unchanged: live
  data depends on them.
- You run as `claude`, without sudo. Never ask for sudo or a password. Root changes (F-019, `docs/admin.md`):
  - On the list (service start/stop/restart, install a package from Ubuntu, open/close a tailnet port, security
    updates): do it yourself with `homelab-admin <action> --reason "..."`. Needed permanently? Also put it in
    `config/machine.conf` (via a PR); `homelab-admin status` reports what is still missing there.
  - Everything else: `changes/<NNNN-name>/apply.sh` (first comment line = summary; optionally `revert.sh`) in a PR.
    After the merge the box runs it, with a safety net for firewall and network. Run the `change-reviewer` agent
    before opening a PR with a change.
  - Installing or rebuilding a component: a script in `scripts/`. Its `# homelab-rerun: <root|claude|manual>
    <path>...` line lists every repo path it installs. After a merge that changes one of them, `homelab-admin` runs
    it again (`docs/admin.md`). A new script needs that line too, or the user has to run it by hand.
  Write scripts idempotent, with `set -euo pipefail`, without prompts on a re-run, and let them check for themselves
  that they worked.
- Everything on the box must be rebuildable from this repo: every change is in `CHECKLIST.md`, in a script, in
  `changes/` or in `config/machine.conf`. A change you don't record no longer exists after a rebuild.
- Nothing reachable from the internet. From the LAN only SSH; services only via Tailscale.
- ufw also filters the tailnet (Tailscale runs with `--netfilter-mode=nodivert`); a new service gets its own
  `ufw allow in on tailscale0 to any port <p>` rule. Docker ports bypass ufw: bind them to `127.0.0.1` or to the
  Tailscale address. Run the `exposure-checker` agent for a new port or service.
- The NVIDIA driver (580-server) and the kernel stay manual work for the user; not via unattended-upgrades
  (`changes/0001`).
- Laptop phase: expect GTX 1050 / 2048 MiB; after the move GTX 1080 / 8192 MiB. Keep that in one setting
  (`EXPECTED_GPU` in `config/health.conf`), not in code.
- Never push to `main` (of any repo): work on a branch, push it and open a PR. A global pre-push hook in
  `~/.config/git/hooks` blocks it; don't disable it. On **this repo** the user merges: don't run `gh pr merge` here.
  A merge to `main` of homelab means root actions on the box (changes and install scripts). This is an agreement
  (the user's choice), not a technical boundary: stick to it. Product repos are different: Claude merges its own PRs
  there (`claude/CLAUDE.md`, "Merging product PRs").
- Have the user run root scripts from root's clone, `/var/lib/homelab/repo/scripts/` (merged `main`, only root can
  change it). Not from the deploy clone in `/srv/homelab` (owned by `deploy`, which runs projects' npm code) and not
  from your own working copy. `/home/claude` is 750, so give the user scripts with their full path.
- Public repo (`chrisfaber95/homelab`, portfolio): never secrets or personal data in it. Tokens go in `/etc/homelab`,
  jobs and memory in the private `DATA_REPO`. Treat PRs from others as untrusted. Before a repo goes public:
  `gitleaks git <repo> --log-opts=--all`.
- Claude never requests an SSH key for `chris` itself and doesn't look for a workaround (only `inbox-runner` may, via
  the SSH tab of the homelab page, confirmed on the phone).
- Inbox tasks run in auto mode, never `bypassPermissions` (the user's choice).
- When testing: never stop processes with `pkill -f` or `ps | grep` on a pattern; that hits your own shell or tries
  to stop the real service. Find a test server by its port or a pid file.

## Map

| Part | Code | Config | Docs |
|---|---|---|---|
| F-016 base box, health check | `scripts/05-09-base.sh`, `bin/homelab-health` (`scripts/13-health-check.sh`) | `config/health.conf` | `CHECKLIST.md` |
| F-017 LLM helper (OpenAI API, port 8017) | `llm/helper.py`, `bin/llm`, `scripts/17-llm.sh` | `config/llm.conf` | `docs/llm.md` |
| F-018 projects and deploy | `bin/homelab-deploy`, `deploy/`, `scripts/18-projects.sh` | `config/repos.conf`, `config/deploy.conf` | `docs/projects.md` |
| F-019 root changes, ntfy | `bin/homelab-admin`, `changes/`, `scripts/19-admin.sh` | `config/machine.conf`, `config/admin.conf` | `docs/admin.md` |
| F-020 monitor, overview, backups | `bin/homelab-monitor`, `bin/homelab-backup`, `scripts/20-monitor.sh` | `config/backup.conf`, `config/backup.list` | `docs/monitor.md` |
| F-021 task inbox (port 8105) | `bin/homelab-inbox` (entry point), `bin/homelab_inbox/` (modules: see below), `bin/homelab-inbox-task`, `scripts/21-inbox.sh` | `config/inbox.conf` | `docs/inbox.md` |
| F-022 jobs and skills | `bin/homelab_inbox/jobs.py` | `config/inbox.conf` | `docs/jobs.md` |
| F-023 memory | `bin/homelab_inbox/memory.py` | `config/inbox.conf` | `docs/memory.md` |
| F-024 homelab page | `bin/homelab_inbox/{overview,pages,server}.py`, `web/` | | `docs/dashboard.md` |
| H-001 briefs: build, review, handoff | `bin/homelab_inbox/briefs.py` | `config/inbox.conf` | `docs/briefs.md` |
| Claude's skills, agents, plugins, global CLAUDE.md | `scripts/18-claude.sh`, `claude/agents/`, `claude/CLAUDE.md` | `config/claude.conf` | `STATUS.md` |

`bin/homelab-inbox` only parses the command line and imports the modules in `bin/homelab_inbox/` (installed next to it
by `scripts/21-inbox.sh`). Layers, each importing only from the ones above it: `config` (settings, shared state),
`storage` (SQLite), `helpers` (git, GitHub, notify, live logs), `memory` (F-023), `jobs` (F-022), `rounds` (one Claude
round), `router`, `briefs` (H-001), `queue` (scheduler, run_round, finish_round), `github` (PR polling, auto merge),
`overview` (page data), `pages` (HTML), `server` (HTTP handler, serve). No import cycles: keep it that way.

Addresses: Tailscale `homelab` = `100.85.70.34`; homelab page and inbox `http://homelab.tail4eb878.ts.net:8105`
(8104 redirects there); ntfy on port 8102 (topic `homelab`); claude-dashboard on `https://homelab:8101`.
Deploy clones live in `/srv/homelab/repos`, your own dev clones in `~/dev`. You can't read system service journals
(no `adm` group): look in `/srv/homelab/state/`.
