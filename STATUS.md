# Status

Moved out of `CLAUDE.md` on 2026-10-29. Update this file when the state changes; things the user still has to test
also go in `CHECKLIST.md`.

## The box (2026-10-26)

Checklist steps 0 to 11 are done and checked:
- Ubuntu Server 24.04, UEFI with fallback bootloader, LVM on the full SSD, 8 GB swap, `vm.swappiness=10`.
- Netplan matches `en*` (survives the move); cloud-init networking is off.
- Tailscale: `homelab` = `100.85.70.34`, key expiry off. SSH only with a key; password refused.
- ufw: only 22 from `192.168.1.0/24` and via `tailscale0`; tested that other ports are closed on LAN and tailnet.
- NVIDIA 580.178.04 with prebuilt kernel modules (`linux-modules-nvidia-580-server-generic`, no DKMS); `nvidia-smi`
  works.
- Account `claude` without sudo; Claude Code, `gh` (logged in as `chrisfaber95`, HTTPS) and git identity are set up.
- Laptop only: `/etc/systemd/logind.conf.d/laptop-lid.conf` (closed lid keeps the box on); remove after the move.
- Repo public since 2026-10-26.

## Parts

Running and checked: F-016, F-017 (`llama3.1:8b`), F-018, F-019 and F-020; `homelab-health` is fully OK.

- F-016: health check `bin/homelab-health`, also runs straight from the repo; `scripts/13-health-check.sh` copies it to
  `/usr/local/lib/homelab`.
- F-017: `llm/helper.py` and `bin/llm`, installed with `scripts/17-llm.sh`. On the laptop the model deliberately runs
  mostly on the CPU (the user's choice); the brief requires 100% GPU, which is only possible on the desktop.
- F-018: `homelab-deploy` (sync loop as service `homelab-deploy` under `deploy`, `homelab-deploy status`) syncs
  `homelab` and `claude-dashboard`; the dashboard runs as `homelab-app-claude-dashboard` on `https://homelab:8101` and
  sees Claude's sessions. Rootless Docker for `deploy` and `claude`; the compose deploy is tested with local repos and
  Claude's Docker.
- F-019: `homelab-admin` (root service; socket `/run/homelab-admin.sock`; log: `homelab-admin changes`) and ntfy run;
  `changes/0001` is applied. Lockout test passed: a merged change that blocked SSH via Tailscale was rolled back by
  itself after 5 min 32 s. Notifications go to ntfy (topic `homelab`; the phone is Android).
- F-020: monitor (timer every 2 min; `homelab-overview`), overview and backups (timer 03:30) run; test "service
  stopped → notification, back on → notification" passed. Extra checks in `homelab-health`: llm, deploy,
  `svc:<name>`, backup.
- F-021 and F-022 (2026-10-26): `homelab-inbox` runs (service under `inbox-runner`, page 8105, tabs Taken, Jobs,
  Skills), the tokens work, the pre-push hook also blocks `jobs`. `homelab-inbox-task` runs as `inbox` via one sudo
  rule. The `jobs` branch only exists after the first confirmed job. Jobs are `jobs/<name>.md` on the `jobs` branch of
  `DATA_REPO` (never merged; only the page writes to it); skills are `skills/<name>/SKILL.md` on `main`, only via a PR.
  Not yet seen for real: a run as `inbox-runner` with `homelab-overview`, skills in `~inbox/.claude/skills`, `github:`
  sources with the real token.
- F-023 (merged): memory tab; preferences, facts and history as files on the `memory` branch of `DATA_REPO` (never
  merged; only the inbox writes to it). Claude stores with `GEHEUGEN:` blocks under its last message; Claude Code auto
  memory is off for `inbox`. After `21-inbox.sh` and `18-claude.sh`: the tests in `CHECKLIST.md`.
- F-024 (merged 2026-10-26, PR #20): the homelab page in the same service; `/` is the overview (status
  sentence, live logs of what is running, jobs timeline, this week, health). Built and tested without root, with
  Playwright screenshots. Look approved by the user (futuristic dark and blue after the KITT skin of claude-dashboard,
  dark only; an earlier "paper" version was rejected). The post-merge steps and checks are in `CHECKLIST.md`.
- Claude's skills (2026-10-26): frontend-design, web-artifacts-builder and webapp-testing from `anthropics/skills`,
  pinned in `config/claude.conf`, installed by `scripts/18-claude.sh` (with pnpm in `~/.local` and Playwright in
  `~/.local/share/playwright-venv`; `python` is that venv, `python3` Ubuntu's). Also `gitleaks` (pinned,
  `~/.local/bin`). Not yet actually run: `init-artifact.sh` (auto mode blocked it; the user decides).
- Claude's agents and plugins (2026-10-29): own agents in `claude/agents/` (change-reviewer, rebuild-auditor,
  exposure-checker, health-triage; all four read-only) and the plugins pr-review-toolkit and claude-md-management,
  pinned in `config/claude.conf` via a local marketplace `homelab-pinned`; installed by `scripts/18-claude.sh`. Only
  plugins without hooks or scripts; no security-guidance or claude-security (they call the Claude API themselves; the
  user's choice).
- H-013 sdlc-studio deploy (2026-11-04, this PR): `sdlc-studio` in `config/repos.conf`, `deploy/sdlc-studio.service`
  on 127.0.0.1:8112, `changes/0005-sdlc-studio-serve` publishes it on the tailnet as `http://homelab:8111`, link in
  `config/services.conf`. Needs sdlc-studio PR #8 merged first (serves the UI, real router API). Not yet seen for real:
  the unit running, serve's `Tailscale-User-Login` header making Chris the owner, and a visitor on another tailnet
  login seeing only the examples. New skill `skills/deploy-on-the-box` and a deploy rule in the planner prompt.

## Next work

1. Backups to the external 2 TB USB HDD (PR #11, open): the laptop didn't see the disk yet (no USB storage in
   `lsusb`). The user picks that up later.
2. H-001 (2026-10-30), all three phases merged: the private product repo `chrisfaber95/product` with the designer
   (product PR #1), the tab Briefs with Build, planner, brief reviewer and handoff (`docs/briefs.md`, PR #39;
   `21-inbox.sh` ran on 2026-10-30), and claude-dashboard's two open team-hq tickets (T-103 became brief H-002,
   `needs-design`; T-045 is in `CHECKLIST.md`, PR #40). Tested end to end with fakes, not yet with real Claude
   runs: the first real build is the next step (`CHECKLIST.md`).
3. **pixel-quest backups**: the compose volume `pixel-quest_pgdata` holds the players' profiles, progress and shared
   world and is not backed up. A path in `config/backup.list` would not be enough, because `homelab-backup` copies
   files while Postgres keeps running; it needs a nightly `pg_dump` to a file and that file in the list
   (`docs/projects.md`, section pixel-quest).

Still for the user to test (also in `CHECKLIST.md`):
- F-016: SSH from another network; lid closed.
- F-017: `llm` from Windows.
- F-018: pair a device on the dashboard, 8101 refused from the LAN, Remote-SSH, reboot and power cut.
- F-020: disk full and power cut give a notification (service stopped is tested). The offline notification
  (heartbeat) later; no restore drill (the user's choice).
- Inbox parallelism (2026-10-30): up to 3 tasks per repo, one auto-merge per repo per poll, conflicts with main go back
  to the task. Tested without root (fake GitHub, local repos); still to see live: two tasks on one repo and a real
  conflict round.
- Inbox restarts and Retry (2026-10-30): a restart requeues running tasks instead of failing them; Retry on a failed
  or cancelled task. Tested without root; still to see live: a task resuming after a restart.
- Inbox Retry on a refused task (2026-11-04): Retry with an optional note; the session gets its refusal reason
  and the note. Tested without root (unit tests); still to see live: retry task 97 with a note.
- Brief review diff and CI rule (2026-10-30): the reviewer's diff skips lockfiles, generated files and fixtures
  (code first, REVIEW_MAX_DIFF 150000); the planner gives a repo without CI a CI task first.
- pixel-quest over HTTPS (2026-11-07, `changes/0007-pixel-quest-https`): open
  <https://homelab.tail4eb878.ts.net:8113> on a phone and a laptop on the tailnet and **sign in**, which is what a
  wrong cookie flag breaks (a loading page proves nothing). Checked by the change itself: a real TLS request to
  `/health` and `COOKIE_SECURE=1` in the running container.
- After the move to the desktop: BIOS, `EXPECTED_GPU` to the GTX 1080, reboot and power test, the rebuild drill.

## Answered questions

- 2026-10-26: the 2 TB USB HDD is only the backup disk (PR #11, `scripts/20-usb-disk.sh`); there is no other data on
  it. That also answers the lead's question in F-016 and F-020.
- 2026-10-25: Claude pushes to branches and opens PRs, not to `main` (agreement + hook; no technical barrier, also
  not after F-019). The dashboard on the box shows sessions and may start Claude sessions. Notifications via an own
  ntfy on the tailnet. Kernel and NVIDIA not via unattended-upgrades (`changes/0001`).
