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
| `notification` | every `notify()` call (the ntfy push), except question and answer, which are stored in full as above |
| `approval` | Chris approving a brief on the page |
| `approval_request` | reserved for the Discord bot's confirm step (not written yet) |

Text and link are scrubbed with the memory's secret filter (`config.SECRET_RE`) before they are stored: the match is
replaced by `[removed: looks like a secret]`, the rest stays. Entries are cut at 20000 characters. Storing never breaks
the caller: if it fails, the notification is still sent and the failure is in the journal.

## Clients and permissions

`config/clients.json` lists each client with its permissions: `read`, `write`, `ask`, `start_task`, `answer`,
`approve`, `run_job`. It is read on every request, so a change needs no restart (installed by `scripts/21-inbox.sh`).
An action without the permission is refused, and the refusal goes to the journal and to the table
`conversation_refusals`. A client always writes as Chris and only the kinds `message` (needs `write`), `question`
(`ask`) and `answer` (`answer`); it cannot write notifications, approvals or Claude's text. `start_task`,
`approve` and `run_job` are checked by the code that does those things (`conversation.allowed(client, action)`).
SSH keys, memory edits and merges on `homelab` exist for no client.

## The door (internal API)

Clients on the box use the inbox on loopback. The inbox refuses the box itself everywhere else; this is the one
exception, only for `/api/chat/entries`, only from `127.0.0.1`/`::1`, and with a token per client in
`/etc/homelab/chat-<client>-token` (create with `openssl rand -hex 32`, mode 0640, readable by the client's account).

- `GET /api/chat/entries?after=<id>&limit=<n>`: entries with an id above `after`, oldest first; answers
  `{"entries": [...], "next": <id to pass next time>}`. Messages written while a client was away are still there.
- `POST /api/chat/entries` with `{"kind": "message", "text": "...", "ref": "task:12"}`: appends an entry.

Statuses: 401 unknown token, 403 refused (not permitted, or not loopback), 400 bad request. Inside the inbox use
`conversation.append(...)`, `conversation.read(after)`.

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
