# LLM router (H-012)

One place to send an LLM request. The caller says who it is and how hard the job is (the **tier**); the router
picks the backend from a table in `config/router.json`, enforces the timeout, handles failure, and logs every
request. Code: `llm/router.py` (service `llm-router`, port 8018), `llm/jobs.py` (the local model, the F-017 core),
`bin/llm-route` (CLI client). Installed by `scripts/17-llm.sh`.

## Backends

| Backend | What answers | Notes |
| --- | --- | --- |
| `local` | the F-017 model on this box (Ollama, `LLM_MODEL` in `config/llm.conf`) | free, slower on the laptop's 2 GB GPU, limited context |
| `claude` | the `claude` CLI: `claude -p --output-format json`, Pro subscription | shares the allowance with everything else |

**No Claude API key is used or stored anywhere.** The Claude backend only runs the CLI; the router removes
`ANTHROPIC_API_KEY` and `ANTHROPIC_AUTH_TOKEN` from the CLI's environment so it cannot fall back to API billing.
The service runs as `ROUTER_USER` (`config/llm.conf`, default `claude`): the CLI must be logged in as that user.
Change the user there if the subscription login lives under another account.

## The tier table: `config/router.json`

```json
{
  "claude": {"command": "claude", "model": null, "extra_args": []},
  "tiers": {
    "easy": {"backend": "local",  "timeout": 120, "on_failure": "fail"},
    "hard": {"backend": "claude", "timeout": 300, "on_failure": "fail"}
  }
}
```

- Tier names are free (letters, digits, `. _ : -`); add, rename or remap them in this file only. The router
  re-reads the file when it changes, so no code change and no restart. A broken file makes requests fail with
  `bad_config` (and the service refuses to start on it), it never routes by guess.
- `backend`: `local` or `claude`.
- `timeout`: seconds per attempt (1 to 600). A request may pass its own `timeout`.
- `on_failure`: what happens when the backend fails or times out:
  - `fail` (default): the caller gets the error (HTTP status 4xx/5xx, `error.code` such as `timeout`,
    `ollama_unreachable`, `claude_error`, `invalid_output`). Nothing is retried.
  - `escalate`: the router tries the **other** backend once (local to claude, claude to local), again with the
    tier's timeout. A request the caller got wrong (`bad_request`) is never escalated; too long for the local
    context (`too_long`) is.
- `claude`: `command` (path of the CLI), optional `model`, optional `extra_args`.

Starting tiers: `easy` is local, `hard` is Claude, both `fail`. Escalating costs allowance silently, so it is
off until a tier is known to need it. Which jobs start on which tier is Chris's call; see "Declaring a tier".

## Declaring a tier

The caller decides; the router never classifies a prompt and does not look at the remaining allowance.

- `easy`: short, tolerant jobs a local 8B model does well: summaries, labelling, extracting fields, rewriting.
- `hard`: jobs where a worse answer matters: reasoning over many facts, code, anything written for Chris to rely on.
- Unsure? Start on `easy` with a caller name of its own and look at `llm-route usage` and the failures after a
  week; move it in the table or in the caller.
- Pick a stable, specific caller name per job (`nightly-digest`, `inbox:routing`, `session:claude`): the usage
  log is per caller and tier.

## Calling it

Reachable on loopback and the tailnet only: `http://homelab:8018` (ufw rule on `tailscale0` only, and the server
refuses clients outside loopback and `100.64.0.0/10`). No LAN port.

CLI (box, Windows; stdlib only; `caller` and `tier` are required):

```
echo "Summarise this log" | llm-route ask --caller nightly-digest --tier easy
llm-route ask --caller session:claude --tier hard --system "Answer briefly" question.txt
llm-route ask --caller x --tier easy --job extract --fields name,amount:number invoice.txt
llm-route ask --caller x --tier easy --json prompt.txt      # backend, outcome, tokens as JSON
llm-route tiers
llm-route usage --days 7
```

Exit codes: 0 ok, 1 request failed, 2 wrong call (including an unknown tier), 3 router unreachable, 4 timeout.
An escalation prints `escalated from X to Y` on stderr.

HTTP:

```
POST /v1/route   {"caller": "nightly-digest", "tier": "easy", "prompt": "...",
                  "system": "...", "job": "summarize|extract", "messages": [...], "timeout": 60}
  200 {"output", "caller", "tier", "backend", "outcome", "escalated_from", "duration_ms",
       "usage": {"prompt_tokens", "completion_tokens"}}
  4xx/5xx {"error": {"message", "code"}}
GET /v1/tiers     GET /v1/usage?days=7     GET /health
```

`prompt` or `messages` (chat format) is required. `job` reuses the F-017 prompts (`summarize`, `extract` with
`fields`/`labels`/`response_format`), on either backend; extract answers are validated against the schema.

Who calls it:

- **Scheduled jobs and apps on the box:** `llm-route` or a POST to `127.0.0.1:8018`.
- **Inbox tasks and Claude sessions:** the same CLI, as a tool call, with a caller such as `inbox:<id>` or
  `session`. Claude Code itself is not proxied through the router; a session asks the router for a sub-question.
- **Other tailnet devices:** `ROUTER_URL=http://homelab:8018 llm-route ...`.

## F-017 helper (shim)

The old entry points keep working, unchanged for callers: the `llm` CLI and `POST :8017/v1/chat/completions`. The
helper now forwards each chat to the router as a request with tier `easy` and the caller from `X-LLM-Caller` or
`user` (default `f017-legacy`; the `llm` CLI's default caller is now `f017-legacy` too). So the `easy` row of the
table also decides what the legacy callers get, including `on_failure`. The helper keeps its own `/usage` log
(per device, GPU share) for the dashboard (F-024). If the router is down the helper answers 503 `router_unreachable`.

## Usage log

SQLite `requests` in `/var/lib/llm-router/router.db`, **append-only** (triggers refuse UPDATE and DELETE). One row per
request:

| Column | Meaning |
| --- | --- |
| `ts`, `caller`, `tier` | when, who, the tier asked for |
| `backend` | the backend that **actually** answered (or failed last) |
| `outcome` | `ok`: the tier's own backend answered; `escalated`: it failed and the other backend answered; `failed`: the caller got an error |
| `escalated_from` | the first backend, set when the request was escalated (also when the escalation failed too) |
| `error` | the errors of the attempts, empty on `ok` |
| `duration_ms` | the whole request, all attempts |
| `prompt_tokens`, `completion_tokens` | as the backend reports them; local from Ollama; Claude from the CLI's JSON `usage` (input plus cache tokens as prompt). `NULL` when not reported |

Bad requests (no caller, unknown tier, ...) are rejected with 400 before routing and are not logged.

`llm-route usage` / `GET /v1/usage` give requests per caller, tier, backend and outcome, the share served locally, the number of
escalations and "Claude runs avoided". That last number is an estimate: every request the local model answered
is counted as a Claude run that did not happen, because the CLI reports no cost. A usage page on the homelab
page, and thumbs-down on answers, are still to be built.

## Tests

`python3 -m unittest discover -s tests` (part of `scripts/ci.sh`): `tests/test_llm_router.py` uses fake backends,
a fake `claude` script and a fake Ollama; nothing real is called.

## Install and check

`sudo bash /var/lib/homelab/repo/scripts/17-llm.sh` installs the service, the ufw rule and the client, then
checks `/health` and runs one `easy` request. Check by hand: `llm-route tiers`, and
`ss -Htln 'sport = :8018'` (it must be reachable via the tailnet only: the exposure checker).
