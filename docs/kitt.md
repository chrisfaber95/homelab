# Kitt on the box (H-018)

Kitt (`chrisfaber95/kitt`, private) is the OpenJarvis frontend in the dark and blue KITT colours: chat, agents, dashboard,
logs and an approval bell for the box, in one app. It keeps no data of its own. The app and its adapter are in that repo
(`docs/adapter.md` there); this page is the homelab side.

## Parts

| Part | Where | What |
|---|---|---|
| Adapter + built app | repo `kitt`, `service` line in `config/repos.conf`, unit `deploy/kitt.service` (`homelab-app-kitt`) | `npm ci`, `npm run build`, `npm start`; listens on **127.0.0.1:8122** (`KITT_HOST`, `KITT_PORT`), never on a wildcard. Nothing to back up. |
| Gate | `bin/homelab_inbox/gate.py`, `KITT_GATE_*` in `config/inbox.conf` | The tailnet listener: **100.85.70.34:8121** (`http://homelab:8121`). Forwards to the adapter. |
| Door | `bin/homelab_inbox/kitt.py`, routes in `server.py` | What the adapter calls on the inbox (loopback, `KITT_BOX_URL`). |
| Permissions | `kitt` in `config/clients.json` | What the app may do. |
| Switch | the Kitt block on the homelab page | Off: the app shows itself as disconnected. |

## Who gets an answer

The same rule as the homelab page (`Access.allowed`): only Chris's own devices on the tailnet. The gate does the check
on the peer address with `tailscale whois`; another tailnet user, the LAN (a request to the LAN address is refused or not
answered, since the gate binds only the Tailscale address) and the box itself get a 403. The adapter listens on loopback
only, so there is no way round the gate. `bin/homelab-exposure` checks this: the unit binds loopback, the gate address
is the Tailscale address, the gate runs `Access.allowed`, `ufw` opens 8100-8199 on `tailscale0` only, and live that 8121
listens on the Tailscale address only and 8122 on loopback only. No `tailscale serve`, no funnel.

The gate removes any `Tailscale-User-*` and `X-Homelab-Client` header the caller sent and sets `Tailscale-User-Login`
to the owner (and `Tailscale-User-Name` to the device), because the adapter passes these on to the inbox.

## The door

The inbox refuses the box itself everywhere except `/api/chat/*` (with a token). Kitt's adapter has no token, so its
door is recognised differently, and every call must pass all three checks (`kitt.identify`):

1. header `X-Homelab-Client: kitt`;
2. the connection is owned by the user of `homelab-app-kitt` (a DynamicUser, `KITT_ACCOUNT` in `inbox.conf`), read from
   the kernel's socket table (`/proc/net/tcp`), so another local account cannot pretend to be kitt;
3. `Tailscale-User-Login` is the owner of the tailnet: a call that did not pass the gate has none.

Routes (anything else is a 404; there is no route for SSH keys, memory edits, merges, or creating, editing or deleting
a job):

| Route | Needs | Does |
|---|---|---|
| `GET /api/clients/kitt` | | `{enabled, permissions: {chat, jobs, approvals, usage, logs}}`; all false while the switch is off |
| `GET /api/conversation?after=` | read | entries as kitt shows them (`message` from Chris becomes `question`, or `task` when it started a task or job; the inbox itself and ntfy show as `box`) |
| `POST /api/conversation` `{kind, text}` | `ask` / `start_task` | a question is stored and answered by the private search (the answer is an entry too, `client` kitt); a task is queued by `chat.start_task` on repo `auto`, like Discord's button, with source `kitt` and the daily cap |
| `GET /api/logs`, `GET /api/logs/<id>?from=` | read | recent tasks and job runs; the log of one (`t<task>`, `r<run>`) from a position |
| `GET /api/approvals` | read | draft briefs and tasks waiting for an answer (the bell) |
| `POST /api/approvals/<brief>` `{decision}` | `approve` | `approve` or `approve_unattended`: the two steps of Discord's approval run in one call (the app has asked for its confirm), so the brief's commit says "approved from kitt by Chris", the task has source `kitt`, an `approval` entry is written and ntfy gets a notification. `deny` is not offered. |
| `GET /api/jobs` | read | a card per job: schedule, model, delivery, last result, next run, paused |
| `POST /api/jobs/<name>/run\|pause\|resume` | `run_job` | what the Jobs tab does (pause and resume commit to the jobs branch, so the page shows them too) |
| `GET /api/health` | read | the monitor's checks |

Usage (requests per backend, share local, Claude runs avoided) is not here: the adapter reads the router's
`GET /v1/usage` itself, so the numbers equal the router's own.

## Permissions

`config/clients.json`, client `kitt`, read on every call (no restart). A screen is on when the client has all of its
permissions: **chat** = read, write, ask; **jobs** = read, run_job; **approvals** = approve; **usage** and **logs** =
read. `start_task` lets a task be started from the chat; `tasks_per_day` caps tasks and builds started from kitt.

- Take `approve` out: the bell still lists, its buttons say approvals are off, and the door answers 403 "approvals from
  kitt are off".
- Switch Kitt off on the homelab page: every door call except the state is refused, the state says `enabled: false`,
  and the app shows it is disconnected.

## After a rebuild

Merge, then `18-projects.sh` installs the unit and `21-inbox.sh` the inbox (both run by `homelab-admin` after a merge,
`# homelab-rerun`). Nothing is created by hand and nothing needs backing up. If the gate cannot bind yet (Tailscale
not up), the inbox logs it and tries again every 30 s.

Check: `homelab-deploy status` shows kitt; `http://homelab:8121` opens from a device of yours; a request from the LAN
address or from another tailnet user gets a 403; `bin/homelab-exposure` passes.
