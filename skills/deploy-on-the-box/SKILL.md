---
name: deploy-on-the-box
description: Make a web app or API deployable on the homelab box (homelab-deploy, F-018) and give it a link on the homelab page. Use for any task that builds a new app people open in a browser, a task with "deploy" in its criteria, or when an app is merged but nothing runs (no line in homelab's config/repos.conf). Covers what the app repo needs and the homelab changes (repos.conf, unit or compose, services.conf link, docs, tailscale serve), with the network rules. Not for changing the deploy system itself (bin/homelab-deploy).
---

# Deploy an app on the box

A merged app does not run by itself. The box only deploys repos listed in homelab's `config/repos.conf`, and the
homelab page only links services listed in `config/services.conf`. The lesson came from sdlc-studio (H-013): five
build tasks merged, but no page and no link existed until a separate deploy PR was made.

Read homelab's `docs/projects.md` first (sections "De lijst" and the f1-dashboard and sdlc-studio sections).

## Network rules (never break these)

- Nothing reachable from the internet; no `tailscale funnel`, no router port forwarding.
- Ports 8100-8199, tailnet only. Bind the Tailscale address (`100.85.70.34`) or loopback, **never `0.0.0.0`**.
  The app itself should refuse a wildcard bind.
- Look up free ports in the "In gebruik" list in `docs/projects.md` and with `ss -ltn` if you can run it. Add the
  new port to that list.

## 1. In the app repo (the task's own repo)

- **One process, or one compose file.** A Node `service`: `npm ci` and `npm run build` at the root must build
  everything, and the server must serve the built frontend itself (GET outside `/api` serves `dist/` with an
  `index.html` fallback). A dev-only Vite proxy is not a deployment.
- **Config by env vars:** host, port, data path (a state dir, not the release folder: releases are read-only and
  replaced on every deploy), secrets only from files or env files outside the repo.
- **Health endpoint** (`GET /api/health` or `/health`): the box counts a unit that dies within 15 s as a failed
  start and rolls back.
- **Calls to box services** use their real contracts, read from the homelab docs, never a guessed API: the LLM router
  is `POST http://127.0.0.1:8018/v1/route` (`docs/router.md`), and search is `docs/search.md`. Test the real call
  once if you can reach it.
- **Owner vs visitors:** a browser can't send a bearer token. If the app has an owner, publish it with
  `tailscale serve` and trust `Tailscale-User-Login` from a loopback peer only.
- **README:** a short "Running on the homelab box" note with the address.

## 2. In homelab (its own PR; Chris merges it)

An inbox task works in one repo. If your task is in the app repo, you can't change homelab: put the exact homelab
changes below in your result and PR description, as ready-to-paste blocks, and list "deploy on the box (homelab
PR)" as not done. If your task is in homelab, make them:

1. `config/repos.conf`: `<name> chrisfaber95/<repo> service` (or `compose`), with a comment line naming the brief
   and port.
2. A `service` needs `deploy/<name>.service` in homelab (copy `deploy/sdlc-studio.service` or
   `deploy/f1-dashboard.service`: `DynamicUser`, `StateDirectory`, `WorkingDirectory=/srv/homelab/releases/<name>/current`,
   `ProtectSystem=strict`). After the merge, `scripts/18-projects.sh` installs it (root). A `compose` repo publishes
   `"100.85.70.34:<port>:<container port>"` and sets `restart: unless-stopped`.
3. Behind `tailscale serve`: a change `changes/NNNN-<name>-serve/` with `apply.sh` and `revert.sh`
   (`tailscale serve --bg --yes --http=<port> http://127.0.0.1:<internal port>`). Run the `change-reviewer` and
   `exposure-checker` agents on it.
4. `config/services.conf`: `<Name> | http://homelab:<port> | <what it is> (docs/projects.md)`. This is the link
   on the homelab page.
5. `docs/projects.md`: the port in the "In gebruik" list and a section for the app (address, owner, state,
   secrets, what to do after a rebuild).
6. State that can't be rebuilt from git (a database of user data) goes in `config/backup.list`.

## 3. Check after the merges

`homelab-deploy status` shows the repo and the commit running (the line appears within a minute; a `service`
says `is niet geïnstalleerd` until `18-projects.sh` ran). Then open the address and try the main flow once. If the
deploy failed, read `journalctl -u homelab-app-<name>`.
