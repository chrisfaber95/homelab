---
name: fix-a-failed-deploy
description: Work out why homelab-deploy reports FOUT for a repo and fix it, when an app is listed in homelab's config/repos.conf but is not running. Use when `homelab-deploy status` shows a failed deploy, when a merged app does not come up, or for a build error about a missing env variable, a secret, a port or a failed container start. Covers where to read the error without journal access, the usual causes (a compose secret, a wrong bind, a Secure cookie over HTTP), what you may fix yourself and what needs a changes/ script Chris merges. Not for an app that was never registered (use deploy-on-the-box) or for changing bin/homelab-deploy itself.
---

# Fix a failed deploy

The repo is in `config/repos.conf`, the deploy-clone syncs, but nothing runs. `homelab-deploy status` shows `FOUT`
with the last error. This skill is about reading that error and fixing the cause.

The lesson came from pixel-quest (H-014): it was registered as a `compose` repo and then failed every round on
`required variable POSTGRES_PASSWORD is missing a value`, because nobody had given the box the secret. It had never
run once.

## 1. Read the real error

You run as `claude` without sudo and **cannot read system service journals** (no `adm` group). So:

- `homelab-deploy status` — the one-line error per repo, and whether the sync loop is alive.
- `/srv/homelab/state/<name>.json` — the whole truth: `synced_sha`, `running`, `running_detail`, and `last_deploy`
  with `at`, `sha`, `ok`, `message` and `retry`. Read this before anything else.
- `/srv/homelab/releases/<name>/<sha>/` — the exact files the box built from (`git archive` of that commit, so it
  can differ from your working copy). `current` is a symlink to the live one; no `.homelab-built` file means the
  build never finished.
- Compose logs need the `deploy` account's Docker, which you cannot reach. Put what you need in the fix instead of
  trying to get a shell there.

## 2. Know the timing before you promise anything

The retry is **time-based**, not event-based: `RETRY_SECONDS` is 30 minutes measured from `last_deploy.at`.
Restarting `homelab-deploy` does **not** make it retry sooner, because the failed commit is remembered.

- A **new commit on that repo's main** always deploys immediately. That is the only way to force it.
- So after a homelab-side fix for repo X, merging any small PR on X (a doc fix) starts the deploy at once instead
  of waiting up to half an hour.

## 3. The usual causes

### A secret the compose file needs (the pixel-quest case)

`error while interpolating services.<x>.environment.<VAR>: required variable <VAR> is missing a value`.

A `.env` next to the compose file **does not work**: homelab-deploy unpacks every commit into a fresh release with
`git archive`, so the file is gone on the next deploy. And a secret may not go in the public homelab repo.

The box's mechanism: `/etc/homelab/<name>.env` (`ENV_DIR` in `config/deploy.conf`), mode 600 for the account
`deploy`. When that file exists, homelab-deploy runs compose with `--env-file` on it, which fills both `${VAR}` in
the compose file and, through `environment:`, the containers. Writing it is a root action, so it goes in a
`changes/NNNN-.../apply.sh` (see `changes/0006-pixel-quest-env-and-serve`) that:

- generates the value **once** and never rewrites it. For a database this is critical: Postgres applies
  `POSTGRES_PASSWORD` only when it initialises its volume, so a new password locks the box out of the existing
  data. Treat an existing-but-unusable file as something to report, not to replace.
- writes to a temp file in the same directory and `mv`s it into place, because the deploy loop polls every 20 s and
  would otherwise be able to read a half-written file.
- `chown deploy:deploy` and `chmod 600`, and asserts both afterwards.

### A Secure cookie served over plain HTTP

The build succeeds, the container is healthy, the page loads — and logging in silently loops with no error. A
session cookie marked `Secure` (`COOKIE_SECURE=1`) is never sent back by the browser over `http://`.

Serve it over HTTPS with `tailscale serve --https=<port> http://127.0.0.1:<app port>`, with the app published on
loopback only. **Check first** that the tailnet can issue certificates:

```sh
tailscale status --json | jq .CertDomains   # null = HTTPS Certificates is off in the admin console
```

If it is `null`, `--https` is refused and only Chris can change it (Tailscale admin console, DNS > HTTPS
Certificates; it also publishes the hostname in the public Certificate Transparency logs). Put that check at the
**top** of `apply.sh` so a too-early merge changes nothing.

Serving plain HTTP on the tailnet is a legitimate choice — WireGuard already encrypts the traffic, and it is what
pixel-quest does today (`changes/0006`, Chris's choice on 2026-11-07, with `changes/0007` prepared for the switch).
But then the cookie flag must move with it: **the entry and `COOKIE_SECURE` are one decision, in both repos.** Of
the two mismatches only one is loud, so get the order right:

| Entry | `COOKIE_SECURE` | Result |
| --- | --- | --- |
| HTTPS | 1 | correct |
| HTTP | 0 | correct, one hardening flag less |
| HTTP | **1** | login fails silently — no error anywhere |
| HTTPS | 0 | harmless |

So switch **to** HTTPS entry-first then cookie, and **away** from it cookie-first then entry, and never let the
third row exist in between. Make the flag come from the env file rather than the compose file, so the switch is a
line in `/etc/homelab/<name>.env` plus `compose up -d` — interpolation happens at `up`, not at build, so the
container has to be recreated for a new value to land.

### A wrong or clashing bind

Docker's published port bypasses ufw, so the bind address is the real boundary. Either publish on the Tailscale
address (`100.85.70.34:<port>`) **or** on `127.0.0.1` with `tailscale serve` in front — not both on the same port:
tailscaled itself binds the Tailscale address for a served port, so a container on it collides. Check with
`ss -ltn` and `tailscale serve status --json`.

### The build itself

`npm ci`/`npm run build` or `docker build` failing is usually real and reproducible in your own clone. Run it there
first; `retry: true` with a temporary cause (npm unreachable) fixes itself within 30 minutes.

## 4. Verify before you open the PR

You have no Docker daemon, but `docker compose config` only reads files:

```sh
docker compose -p <name> -f <release>/docker-compose.yml --env-file <env file> config
```

That proves the interpolation, the published ports and the bind address without building anything. Also run
`npm ci && npm run build` in your own clone to be sure the password (or whatever you fixed) was the only blocker.

## 5. What you may and may not do

- **Never merge a homelab PR.** A merge there runs scripts as root; Chris merges. Say in your answer what he has to
  merge, in what order, and any manual step no script can do (an admin-console toggle).
- Allowed directly, with `homelab-admin <action> --reason "..."`: service start/stop/restart, an Ubuntu package, a
  tailnet port, security updates. Everything else root touches is a `changes/` script in a PR.
- Run the `change-reviewer` agent on every `changes/*/apply.sh` and the `exposure-checker` agent whenever a port or
  bind changes, **before** opening the PR. They catch exactly the things above.
- Ports 8100-8199 are already open on `tailscale0` (`scripts/18-projects.sh`), so a port in that range needs no
  firewall change.
- Record the fix: `docs/projects.md` (the "In gebruik" port list and the app's own section), `config/services.conf`
  for the link, and `changes/` for the root action. A change you don't write down is gone after a rebuild.
- State that a rebuild cannot recreate (a database of user data) belongs in `config/backup.list` — but a
  file-by-file copy of a **live** database directory is not reliably restorable. `homelab-backup` copies while
  everything keeps running, so a Postgres volume needs a nightly `pg_dump` to a file and that file in the list.
  Say so plainly instead of adding a path that quietly won't restore.
