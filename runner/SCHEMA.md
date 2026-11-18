# Runner contract: `homelab-inbox-task`

`bin/homelab-inbox-task` is the only door between the inbox service (`homelab-inbox`, account `homelab-inbox`) and
everything that needs the `inbox` account: working copies, Claude Code, and job scripts. This file is the contract a
replacement runner has to keep. It describes the script as it is today (H-020 C3, no change in behaviour).

The service calls it as `sudo -n -u inbox /usr/local/lib/homelab/bin/homelab-inbox-task <action> <id> [args...]`
(`TASK_CMD` in `bin/homelab_inbox/config.py`). Two call styles exist:

- `helpers.task_cmd(*args, timeout=600)` runs it to the end and returns `(exit code, (stdout + stderr).strip())`.
  Callers see stdout and stderr as one string, and their trailing newline is gone.
- `rounds.stream_claude` starts `run` with `Popen`, reads stdout line by line and keeps stderr in a temp file.

Pinned by tests: `tests/fixtures/runner/*.json` (recorded outputs), `tests/fakes/homelab-inbox-task` (replays them),
`tests/test_runner_contract.py` (checks the real script, the fixtures, the fake and the table below against each other).
Re-record with `python3 tests/record_runner_fixtures.py --write`.

## Common rules

- Arguments: `<action> <id> ...`. `<id>` must match `^[djrv]?[0-9]{1,6}$`, otherwise exit 2 with `ongeldig taaknummer` on
  stderr (also with no arguments at all). The prefix says what the id is: none = task, `j` = job run, `d` = job draft,
  `r` = router (a task id), `v` = brief review (a task id).
- The id is checked before the action, so an unknown action with a valid id gives exit 2 `onbekende actie`.
- Paths: `TASK_HOME` (`/var/lib/inbox`) holds `work/<id>/` (the working copy in `repo/`, plus `base` and `pid`) and
  `out/<id>.bundle`. `RUNNER_HOME` (`/var/lib/homelab-inbox`) holds `repos/<name>` (the service's copies, the clone
  source) and `share/<id>/` (`prompt.txt`, `system.txt`, `geschiedenis/`), which the service writes before the call.
  Tests override both with `INBOX_TASK_HOME` and `INBOX_RUNNER_HOME` (and `INBOX_CLAUDE_TOKEN` for the token file).
- Messages on stderr are Dutch and fixed (`geen werkkopie`, `onbekende repo`, ...); the service quotes them in errors.
- Git runs with `safe.directory='*'` for the service's copies, which belong to another account. Git warnings about an
  unreadable `HOME` can appear on stderr in test mode; they are not part of the contract.
- If `TOKEN_FILE` (`/etc/homelab/inbox-claude-token`) is readable it goes to Claude as `CLAUDE_CODE_OAUTH_TOKEN`.
- Any command that fails unexpectedly under `set -e` ends the script with that command's exit code (git: 128, `cat`: 1).

## Actions

### `prepare <id> <repo>`

Fresh working copy `work/<id>/repo` of `RUNNER_HOME/repos/<repo>` on branch `inbox/<id>` (from `refs/inbox/<id>` of the
source if an earlier round pushed commits, else from `origin/main`). Writes `work/<id>/base` (the sha of `origin/main`),
sets the git identity, syncs the skills of the homelab repo to `~/.claude/skills`. `<repo>` must match
`^[a-z0-9][a-z0-9-]*$` and have a `.git` in `RUNNER_HOME/repos/`. An existing `work/<id>` is removed first.
stdout: empty. Exit: 0; 2 `onbekende repo`.

### `run <id> <session> <new|resume> [research]`

Runs Claude Code in `work/<id>/repo` with the prompt from `share/<id>/prompt.txt` and the system prompt from
`share/<id>/system.txt`. `<session>` is a uuid (`^[0-9a-f-]{36}$`); `new` starts it (`--session-id`), `resume` continues it
(`--resume`). Without `research`: `--permission-mode auto`. With `research`: read-only tool set, `--permission-mode
dontAsk` (see the script for the exact flags). `--add-dir share/<id>/geschiedenis` is added when that directory exists.
The script sets `CLAUDE_CODE_DISABLE_AUTO_MEMORY=1`, writes its pid (that of Claude, it `exec`s) to `work/<id>/pid` and
stays running until Claude ends.
stdout: Claude's `--output-format stream-json --verbose`: one JSON object per line (`system` with `model`, `assistant`,
`user`, ..., and a last `result` event with `is_error`, `result`, `session_id`, ...). The service ignores lines that
are not JSON. stderr: Claude's own, kept by the service (the last 300 characters go into the error).
Exit: Claude's exit code; 2 `ongeldige soort run` (fifth argument not `research`), `ongeldige sessie` (bad uuid or mode),
`geen werkkopie`.

### `bundle <id>`

The commits of `inbox/<id>` since `base`, as a git bundle in `TASK_HOME/out/<id>.bundle` (mode 640, replaces an older one).
stdout: one line per commit, `<abbreviated sha> <subject>`, oldest last (`git log --format='%h %s' base..inbox/<id>`).
The service keeps the lines that start with 7 or more hex digits and a space.
Exit: 0; 3 no new commits (nothing written, empty stdout); 1 no working copy (the `cd` fails; there is no `geen werkkopie`
check here).

### `sync <id>`

Fetches `main` of the service's copy and merges it into `inbox/<id>`; on success `base` moves to `origin/main`.
stdout: empty on success; on a conflict one path per line (`git diff --name-only --diff-filter=U`), and the merge stays in
progress in the working copy. stderr on exit 1: `git merge origin/main failed without conflicts (uncommitted changes?)`.
Exit: 0 merged (or nothing to merge); 4 conflicts; 1 the merge failed without conflicts (aborted); 2 `geen werkkopie`.

### `behind <id>`

Fetches `main` of the service's copy and tells whether `inbox/<id>` lacks it. stdout: empty always.
Exit: 0 the branch is behind (a `sync` would change something); 1 it already contains main; 2 `geen werkkopie`.

### `has <id>`

Whether `work/<id>/repo` exists. stdout and stderr: empty. Exit: 0 yes; 1 no.

### `kill <id>`

Stops the Claude of this task: SIGTERM to its children and to the pid in `work/<id>/pid`, 3 s wait, then SIGKILL, then
removes `pid`. Without a `pid` file it does nothing. stdout: empty. Exit: 0 always (takes about 3 s when it killed something).
Also used for job runs (`j<run>`).

### `cleanup <id>`

Removes `work/<id>` and `TASK_HOME/out/<id>.bundle`. stdout: empty. Exit: 0, also when there was nothing.

### `script <id> <repo> <path> [args...]`

For jobs, with `j<run>` ids. Clones `RUNNER_HOME/repos/<repo>` to `work/<id>/repo`, runs `<path>` in it (executable files
directly, others with `bash`) with the args, no stdin, stdout and stderr together in one file, under `timeout
${JOB_SCRIPT_MINUTES:-10}m` (the value of `config/inbox.conf` wins over the environment). `work/<id>` is removed afterwards.
`<repo>` as for `prepare`; `<path>` must match `^[A-Za-z0-9_][A-Za-z0-9._/-]*$` and not contain `..`.
stdout: the last 20000 bytes of the combined output of the script (so the script's stderr is on stdout); after a timeout
one more line `[gestopt na <minutes> minuten]`. stderr: only the refusals below and git's own messages.
Exit: the script's own exit code; 65 the script did not run (`onbekende repo`, `ongeldig pad`, `<path> staat niet in
<repo>`); 124 timeout (not recorded in a fixture: the configured minutes cannot be shortened in a test).

### `route <id>`, `plan <id>`, `draft <id>`, `review <id>`

Claude without tools, for the router (`r<task>`), the brief planner (`plan`, `r<task>`), job drafts (`d<draft>`) and
brief reviews (`v<task>`). Reads `share/<id>/prompt.txt` (on stdin) and `share/<id>/system.txt`, works in a temporary
`work/<id>` that is removed after.
The command is `claude -p --append-system-prompt <system> [--model <m> [--fallback-model <f>]] --tools "" --output-format json`.
`plan` adds `--model $PLAN_MODEL` and `review` adds `--model $REVIEW_MODEL` when set in `config/inbox.conf`, plus
`--fallback-model $FALLBACK_MODEL` when that is set too; `route` and `draft` never pass a model.
stdout: Claude's one JSON document (`--output-format json`): an object with `is_error` and `result` (the text). Callers cut
the string at the first `{`, parse it, and fail on a non-zero exit or `is_error`.
Exit: Claude's exit code; 1 when `prompt.txt` or `system.txt` is missing (`cat` fails).

## Exit codes

One table for all actions. `any` rows hold for every action. An action row adds to them. Where the table says "own", the
code is that of the command the script ran and can be any value.

| Action | Code | Meaning |
|---|---|---|
| any | 0 | Done (for `has`, `behind`, `bundle`, `sync` the meaning is in the action rows). |
| any | 2 | Refused before doing anything: bad task number, unknown action, bad or unknown argument, no working copy (`ongeldig taaknummer`, `onbekende actie`, `onbekende repo`, `ongeldige soort run`, `ongeldige sessie`, `geen werkkopie`). |
| prepare, sync | 128 | Git failed (clone, fetch, checkout): its own code (not recorded in a fixture). |
| run, route, plan, draft, review | 1 | Claude's own exit code (any value; 1 is the usual failure). `route`, `plan`, `draft` and `review` also give 1 when the prompt files are missing. |
| bundle | 1 | No working copy (`cd` fails), not `geen werkkopie` and not exit 2. |
| bundle | 3 | No new commits on `inbox/<id>`: nothing to push. |
| sync | 1 | The merge failed without conflicts and was aborted. |
| sync | 4 | Merge conflict: stdout lists the conflicted files and the merge stays in progress. |
| behind | 1 | Not behind: the branch already contains main. |
| has | 1 | No working copy. |
| script | 1 | Own: the exit code of the job script (any value except the reserved ones). |
| script | 65 | The script did not run: unknown repo, bad path, or the path is not in the repo. |
| script | 124 | The script was stopped by the timeout (stdout ends with `[gestopt na N minuten]`). |

How the service reads them: `behind` 0 then `sync` (0 merged, 4 conflict, else ignored) before a round; `bundle` 3 =
nothing to push, other non-zero = error; `has` non-zero = prepare again; `script` 65 = refused (error), other codes are
shown to the user as `(exitcode N)`.

## Runner `round.py` (H-025 R1)

`runner/round.py <id> <session> <new|resume> [research]` is the process contract in front of `run`: the caller (the Python
inbox now, a Java `RunnerService` later) starts it with an argument array (`python3 -I runner/round.py ...`) and reads
NDJSON; it never parses Claude's stream-json. The runner calls `TASK_CMD run ...` itself (default: the sudo line above, so
the sudoers line is unchanged), imports nothing from the inbox, and puts no prompt or token in any argument: the prompt
files are in `share/<id>/` as for `run`, the token is read by `homelab-inbox-task`.

stdout, one JSON object per line, each with `"v":1`:

- `{"event":"log","kind":"info|claude|tool|result|error","text":...}` readable log lines (same as the old `stream_lines`).
- `{"event":"model","model":...}` once Claude reports its model.
- `{"event":"turn","turns":N}` after every assistant message; N counts from 1 in this run. The caller owns the turn limit
  and the deadline: to stop, run `homelab-inbox-task kill <id>` or send SIGTERM to the runner (it forwards it).
- Last, exactly one `{"event":"result","outcome":"done|answer|question|refused|none","text":...,"turns":N,"exit_code":C,
  "raw":{...}|null}`. `raw` is Claude's own `result` event (`null` if Claude ended without one, for example when killed);
  `text` its final message; `outcome` follows the first word (`KLAAR`, `ANTWOORD`, `VRAAG`, `GEWEIGERD`, else `none`,
  also when `is_error`).

stderr is Claude's and diagnostics. Exit code: that of `homelab-inbox-task run` (the table above; a child killed by signal
S gives 128+S); 2 for bad runner arguments (usage on stderr).

## Runner `review.py` (H-025 R2)

`runner/review.py <id>` (numeric task id) is the process contract in front of `review v<id>`: Claude without tools judges
a brief task's PR against its criteria. Started like `round.py` (`python3 -I runner/review.py <id>`, `TASK_CMD` as
there, so the sudoers line is unchanged); the prompt and system prompt are files in `share/v<id>/`, so no prompt or token
is in any argument. The runner owns the parsing of Claude's `--output-format json` document (the first JSON object with
`result` or `is_error`; a warning line after it is ignored). The caller (`helpers.run_runner`) owns the deadline: SIGTERM
to the runner is forwarded, then SIGKILL.

stdout, NDJSON with `"v":1`: `{"event":"log","kind":"info","text":...}` once, then exactly one
`{"event":"result","ok":bool,"text":...,"detail":...,"exit_code":C,"raw":{...}|null}`. `ok` is true when the script exited 0
and Claude gave a result without `is_error`; `text` is Claude's `result` string; `detail` (the last 1000 characters of
the script's stdout and stderr) is filled when not ok. `parse_review` of the text stays in the inbox (`briefs.py`:
orchestration). Exit code: that of `homelab-inbox-task review` (child killed by signal S gives 128+S); 2 for bad runner
arguments (usage on stderr).

## Runner `route.py` (H-025 R2, part 1)

`runner/route.py <task id>` is the process contract in front of `route r<id>` (the router's Claude without tools). Started
like `round.py` (`python3 -I runner/route.py 228`, argument array, no shell); it calls `TASK_CMD route r<id>` itself, so
the sudoers line is unchanged, imports nothing from the inbox, and has no prompt or token in any argument (`share/r<id>/`
holds the files, as for `route`). The caller owns the deadline: SIGTERM is forwarded to the child.

stdout is exactly one line, with `"v":1`:
`{"event":"result","text":...,"is_error":bool,"exit_code":C,"raw":{...}|null,"stderr":"..."}`. `raw` is Claude's JSON
document (the last JSON object with `result` or `is_error` in the output, so a warning line after it does not matter;
`null` if there is none); `text` its `result`; `is_error` is true when Claude said so, when the exit code is not 0 or
when there is no document; `stderr` the last 1000 characters of the command's stderr (also passed through on stderr).
Exit code: that of `homelab-inbox-task route` (a child killed by signal S gives 128+S); 2 for bad arguments (no output).
