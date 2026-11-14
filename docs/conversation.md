# The conversation (H-016)

The box keeps one conversation with Chris, in the inbox database (`/var/lib/homelab-inbox/inbox.db`, table
`conversation`), so it survives a restart. Code: `bin/homelab_inbox/conversation.py`. Clients (the Discord bot, later
Jarvis) keep no history of their own.

## What is stored

Every entry has `id` (the cursor), `time` (UTC), `author` (`chris`, `claude`, `box` for notifications, `system`),
`client` (the way it came: `page`, `discord`, `ntfy`, `inbox`, ...), `kind`, `text`, an optional `link` and a `ref`
(`task:12`, `brief:H-016`).

| kind | written by |
|---|---|
| `message` | Chris: a task or reply from the page (or a client with `write`) |
| `question` | Claude's question on a task (full text, with a link to the task) |
| `answer` | Claude's answer to a task |
| `notification` | every `notify()` call (the ntfy push), with the full text and link; ntfy itself gets the text cut to 300 characters. Questions and answers are stored as `question` / `answer` instead (also those of brief and routing tasks) |
| `approval` | Chris approving a brief, on the page or from a client (after the confirm step) |
| `approval_request` | step one of an approval from a client: waiting for confirmation (without the token) |

Text and link are scrubbed with the memory's secret filter (`config.SECRET_RE`) before they are stored: the match is
replaced by `[removed: looks like a secret]`, the rest stays. Entries are cut at 20000 characters. Storing never breaks
the caller: if it fails, the notification is still sent and the failure is in the journal.

## Clients and permissions

`config/clients.json` lists each client with its permissions: `read`, `write`, `ask`, `start_task`, `answer`,
`approve`, `run_job`, `research`. It is read on every request, so a change needs no restart (installed by `scripts/21-inbox.sh`).
An action without the permission is refused, and the refusal goes to the journal and to the table
`conversation_refusals`. A client always writes as Chris and only the kinds `message` (needs `write`), `question`
(`ask`) and `answer` (`answer`); it cannot write notifications, approvals or Claude's text. `start_task`,
`approve` and `run_job` are checked by the code that does those things (`conversation.allowed(client, action)`).
SSH keys, memory edits and merges on `homelab` exist for no client: `ssh_key`, `memory_edit` and `merge_homelab` are in
`conversation.FORBIDDEN`, refused (and logged) first in `allowed()` whatever the config says, and dropped from any
permission list.

## Switches

The homelab page has two switches (section Discord): **Discord** as a whole and **Approvals from Discord**. They live
in the `kv` table (`switch:discord`, `switch:discord_approvals`; on unless set to `off`) and are read on every request,
so they need no restart or rebuild. With Discord off, the door refuses the `discord` client (403, not logged as a
refusal, so a polling bot doesn't flood the log) and approvals are off too; entries keep being written and the
bot's cursor picks them up when it is switched on. With only approvals off, `conversation.allowed("discord",
"approve")` is false ("approvals from Discord are off"), and the bot answers that on its buttons. Other clients
(Jarvis) are not behind these switches. Both switches are on by default.

## The door (internal API)

Clients on the box use the inbox on loopback. The inbox refuses the box itself everywhere else; this is the one
exception, only for `/api/chat/entries`, only from `127.0.0.1`/`::1`, and with a token per client in
`/etc/homelab/chat-<client>-token` (create with `openssl rand -hex 32`, mode 0640, readable by the client's account).

- `GET /api/chat/entries?after=<id>&limit=<n>`: entries with an id above `after`, oldest first; answers
  `{"entries": [...], "next": <id to pass next time>}`. Messages written while a client was away are still there.
- `POST /api/chat/entries` with `{"kind": "message", "text": "...", "ref": "task:12"}`: appends an entry.

- `GET /api/chat/state`: `{"discord": true, "discord_approvals": true}` for any authenticated client; `discord_approvals`
  is false when Discord is off. A client polls this to see the switches.

Statuses: 401 unknown token, 403 refused (not permitted, or not loopback), 400 bad request. Inside the inbox use
`conversation.append(...)`, `conversation.read(after)`.

## Actions of a client (`POST /api/chat/action`)

`bin/homelab_inbox/chat.py`, through the same door and token as the entries. The client names the action explicitly;
nothing classifies free text (a message "approve H-016" is only a message). Body `{"action": ..., ...}`:

| action | fields | permission | does |
|---|---|---|---|
| `task` | `repo` (a repo or `auto`), `text` | `start_task` | queues a task through `storage.new_task`, the same row as the page, so same queue, limits, auto mode and PR; `tasks.source` = the client, shown on the task page and row |
| `answer` | `task`, `text` | `answer` | `queue.answer_task`, the code behind the page's answer form |
| `state` | | `read` | tasks running, waiting for Chris and queued |
| `jobs` / `run_job` | `job` | `read` / `run_job` | list jobs / run one now (`jobs.dispatch`, manual) |
| `research` | `question`, `mode` (`quick` default, or `deep`) | `research` | a read-only research run (`jobs.ask_research`, docs/jobs.md); the report comes back as a conversation entry; 429 with a message above the daily cap for Claude jobs |
| `drafts` | | `read` | draft briefs |
| `approve` | `brief`, `mode` (`build` or `without-me`) | `approve` | step one: a pending approval and a `confirm` token |
| `confirm` | `confirm` | `approve` | step two: approves and starts the build |

**Daily cap.** `tasks_per_day` on the client in `config/clients.json`, else `CLIENT_TASKS_PER_DAY` in
`config/inbox.conf` (10). It counts the tasks and builds with that client as `source` since local midnight
(`JOB_TIMEZONE`); page tasks and job runs don't count. Above it: 429 with "daily limit reached ... nothing was
started". Checked again at confirm.

**Two-step approval.** `approve` only inserts a row in `approvals` (random token, client, brief, mode, expiry
`CONFIRM_TTL_SECONDS`, default 300) and writes an `approval_request` entry (the token is not in it: only the asking
client gets it). Only `confirm` with that token approves. It must be unexpired, unused and from the same client (one
use, claimed atomically); permission, the Discord switches, the cap and "still a draft" are checked again then. The
brief's commit says "approved from discord by Chris" (and ", build without me"), the build task has `source`
`discord` and its page says it was approved there, an `approval` entry is written and ntfy gets a notification.
Statuses: 403 refused (permission, switches, forbidden), 404 unknown token or task, 409 not possible now, 410
expired or used token, 429 cap.

## Does scrubbing and storing slow a notification down?

Measured with `python3 -m unittest tests.test_inbox_conversation.Speed` (200 notifications of about 100 characters, on
the box, 2026-11-14; ntfy replaced by a log line, so these are the added cost only):

| | median | p95 |
|---|---|---|
| `notify()` with conversation entry | 8.1-8.4 ms | 14 ms |
| `notify()` without (log line only) | 0.01 ms | 0.01 ms |

Scrubbing alone takes 0.08 ms for 1000 characters; the rest is the SQLite write (open, commit to disk). Against the
ntfy POST itself (an HTTP call with a 10 s timeout) and notifications that come a few times per task, 8 ms is not
noticeable, so nothing is made asynchronous. The test fails if the added median exceeds 50 ms.

The Discord bot, a client of this conversation: `docs/discord.md`.
