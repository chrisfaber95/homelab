# Task inbox (F-021)

Vanaf je telefoon een taak voor Claude op de box: **`http://homelab.tail4eb878.ts.net:8105/taken`** (Tailscale aan;
de tab Taken van de homelab-pagina, `docs/dashboard.md`).
Kies een repo, beschrijf wat er moet gebeuren of wat je wilt weten, en verstuur. Claude eindigt met een PR, een
antwoord op de pagina, één vraag terug, of een weigering met reden; bij elk daarvan krijg je een melding.

> Plan to split the frontend (TypeScript) from the backend (Spring Boot, with Python runners): [`docs/architecture/inbox-split.md`](architecture/inbox-split.md).

## Wat er gebeurt

```
telefoon ──tailnet──> homelab-inbox (inbox-runner) ── wachtrij ──sudo -u inbox──> homelab-inbox-task (inbox)
                          │                                                        Claude, auto mode, eigen werkkopie
                          │  <── git bundle met de commits van inbox/<id> (alleen data) ──┘
                          └── push inbox/<id> + PR (eigen token) ──> GitHub ──> jij reviewt en merget
```

1. **Repo's**: precies de repo's uit `config/repos.conf` (F-018) plus `homelab`. Andere repo's kun je niet kiezen.
2. **Queue**: at most `MAX_RUNNING` tasks at once (4), and at most `MAX_PER_REPO` (3) on one repo; the rest waits in
   order and starts by itself. Each task has its own working copy and branch; the service's own copy of a repo is
   locked while one task uses it.
3. **Uitvoeren**: het account `inbox` clonet de repo in een eigen werkkopie (`/var/lib/inbox/work/<id>`), op branch
   `inbox/<id>`, en draait Claude Code in **auto mode**: de veiligheidscheck van Claude Code beoordeelt elke actie
   (nooit `bypassPermissions`). Wat die weigert, staat op de pagina ("N actie(s) geweigerd door auto mode").
4. **Afronden**: de inbox-dienst haalt de commits op als git-bundle, pusht ze zelf naar `inbox/<id>` en opent de PR.

## Uitkomsten

| Status | Wat |
| --- | --- |
| PR open | code-taak klaar: PR met samenvatting en commits; melding met de link |
| beantwoord | een vraag ("waarom ...", "wat doet ..."): antwoord op de pagina, geen commit, branch of PR; je kunt er op de pagina op reageren, dan gaat dezelfde sessie verder (nieuwe werkkopie) |
| wacht op jou | Claude vond de taak te onduidelijk en stelde één vraag; antwoord op de pagina, dan gaat dezelfde taak verder |
| geweigerd | mag niet (bijvoorbeeld iets wat F-019 verbiedt); de reden staat op de pagina. **Retry** puts it back in the queue (see below) |
| gestopt bij limiet | 80 beurten of 30 minuten per ronde (`config/inbox.conf`); de commits staan op `inbox/<id>`, zonder PR. **Continue** on the task page carries on in the same session and working copy, uncommitted work included (see below) |
| geannuleerd | jij annuleerde hem (knop op de pagina); daarna geen commits of pushes meer |
| mislukt | iets anders ging mis; de reden staat op de pagina. **Retry** on the task page puts it back in the queue (see below) |
| gemerged / PR gesloten | de PR is dicht; de werkkopie is opgeruimd |

**Terugsturen**: zet review-commentaar op de PR (gewoon commentaar, een review of opmerkingen bij regels). Binnen een
minuut gaat de taak terug in de rij; Claude gaat verder in dezelfde sessie en werkkopie, de inbox pusht naar dezelfde
PR, en je krijgt een melding. Alleen commentaar van `OWNER_GITHUB` (`chrisfaber95`) telt; andere accounts worden
genegeerd. Elke ronde krijgt een nieuwe limiet.

**Restarts**: a restart of the inbox (for example after a merge that reinstalls it) stops the Claude of every
running task. When the inbox starts again, those tasks go back in the queue and their own session continues in the
same working copy, with a message that the last turn was cut off. The router and the planner start over. A task cut
off by `RESTART_ROUNDS` (2) restarts in a row fails ("onderbroken door een herstart"). A brief task whose PR review
was cut off is reviewed again.

**Retry**: a failed, cancelled or refused task has a Retry button on its page, with an optional note for Claude.
For a refused task, the session gets its own refusal reason and your note (say why it is fine after all, or what
to do differently) and is told not to refuse again for the same reason. It goes back in the queue: in its own session
and working copy if that still exists, otherwise in a fresh copy of main (the task is told its earlier work is gone).
Tasks of the same build or message that were cancelled because of it ("niet gestart: de voorgaande taak ...") come
back too, in their order, and the build or message counts as running again.

**Continue**: a task that stopped at the limit has a Continue button instead. Its working copy is kept as it was when
the round was cut off, so the next round picks up half-finished, uncommitted work instead of redoing it. A copy nobody
continued for `CAPPED_KEEP_DAYS` (7) is removed; Continue after that rebuilds it from the commits on `inbox/<id>` and
tells the task its uncommitted work is gone.

**Follow-ups of a task that ended badly**: a follow-up doesn't start when its predecessor failed, was refused, was
cancelled, stopped at the limit or had its PR closed. Two things bring it back, in order, without running the
predecessor again:

- **Merge the predecessor's PR anyway.** The inbox sees the merge within a poll, sets the task to merged and requeues
  its follow-ups. Follow-ups cancelled before this feature existed don't come back this way.
- **Mark as fine, continue follow-ups** on the predecessor's page, when you don't need its work. The task keeps its
  status, so a build with such a task gets no automatic handoff. Follow-ups start from main: unmerged work of the
  predecessor isn't in them. Retry on that task clears the mark.

**Conflicts with main**: tasks on one repo run in parallel, so a PR can conflict with main once another one is merged.
When GitHub reports that, the inbox merges main into `inbox/<id>` in the task's working copy (`homelab-inbox-task
sync`). Without conflicts it pushes the merge right away. With conflicts the merge stays in progress and the task goes
back in the queue: its own session continues, gets the list of conflicted files, resolves them, commits and ends with
`KLAAR:` again; the inbox pushes to the same PR (a brief task is reviewed again). At most `CONFLICT_ROUNDS` (2) times
per task; after that, or if the merge fails otherwise, you get a notification.

**A task without a PR** (its push failed, so there is nothing for GitHub to report) gets main merged in by
the inbox itself when its next round starts: it is told that main moved on, or which files conflict so its
own session resolves them. A task cannot do this for itself, because its `origin` is the service's copy and
git refuses a repository of another account.

## Automatisch doorsturen (repo "auto")

Kies je in het formulier **auto (homelab beslist)** in plaats van een repo, dan verdeelt een router jouw bericht:

1. Een korte Claude-run **zonder tools** (zelfde route als het opstellen van een job) krijgt je bericht, de repo's uit
   `config/repos.conf` plus `homelab` en per repo het begin van `CLAUDE.md` of `README.md`. Hij geeft JSON terug:
   per taak een repo, een zelfstandige opdracht, een reden en eventueel `after` (eerst die andere taak).
2. De inbox controleert dat: alleen repo's uit de lijst, hooguit `ROUTE_MAX_CHILDREN` (3) taken, geldige verwijzingen.
   Klopt het niet, dan mislukt het bericht (er wordt niets verzonnen). Zijn het er te veel of kan de router niet kiezen,
   dan krijg je één vraag ("wacht op jou") en gaat hij verder na je antwoord.
3. Elke taak wordt een gewone taak ("Inbox N", met `Onderdeel van bericht #M` en de reden): zelfde wachtrij, limieten,
   auto mode, PR en review-commentaar. Een taak met `after` start pas als zijn voorganger klaar is (antwoord of PR open);
   mislukt, weigert of stopt de voorganger, dan start hij niet.
4. Het bericht zelf krijgt de status **doorgestuurd**, met de taken eronder. Zodra alle taken klaar zijn, staat er een
   samenvatting met de PR's en krijg je één extra melding. Elke taak meldt zelf ook nog (PR, vraag, ...).
   Annuleren van het bericht annuleert alle taken die nog niet klaar zijn.

Taken die de box zelf raken (dienst, poort, pakket, firewall) stuurt de router naar `homelab`; daar gelden F-019 en
`changes/` zoals altijd. De router kan niets uitvoeren en heeft geen toegang tot repo's of tokens.

## Jobs en skills (F-022)

Op dezelfde pagina staan de tabs **Jobs** (geplande jobs, op het lokale model of op Claude) en **Skills** (vaste
manieren van werken die Claude hergebruikt). Een Claude-job loopt als een gewone taak door deze rij, met "job <naam>"
in de lijst; hij meldt alleen wat jou nodig heeft (geen melding bij een gewoon antwoord). Na een gelukte taak kan
Claude een skill voorstellen: dat wordt een PR in de homelab-repo die jij merget of sluit. Alles hierover:
`docs/jobs.md`.

## Geheugen (F-023)

Elke taak en Claude-job begint met wat Claude over jou, de box en de repo weet, en kan eerdere taken doorzoeken. Corrigeer
je Claude in review-commentaar of een antwoord, dan bewaart hij dat als voorkeur. Alles staat op de tab **Geheugen**,
waar je het bewerkt of wist, en op de branch `memory` van de privé repo `homelab-data`. Alles hierover: `docs/memory.md`.

## Gesprek (H-016)

De box houdt één gesprek met jou bij (berichten, vragen, antwoorden, meldingen, goedkeuringen) voor clients zoals de
Discord-bot, met rechten per client in `config/clients.json`. Alles hierover: `docs/conversation.md`.

## Wat Claude in de inbox wel en niet kan

- **Niet**: pushen, een PR openen of mergen, naar `main` pushen. `inbox` heeft geen GitHub-token en geen `gh`-login;
  alleen de dienst (account `inbox-runner`) heeft een token, en die pusht alleen naar `inbox/<id>`. Dit wordt dus
  echt afgedwongen, anders dan bij Claudes eigen sessies (daar is het een afspraak, zie `docs/admin.md`).
- **Niet**: je dev-clones (`~/dev`), Claudes eigen bestanden (`/home/claude`) of de deploy-clones (`/srv/homelab`)
  wijzigen: `inbox` kan er niet bij.
- **Box-wijzigingen** (in de repo `homelab`): alleen via F-019. Wat op de vaste lijst staat, doet Claude meteen met
  `homelab-admin` (in het logboek als gebruiker `inbox`); al het andere wordt een `changes/`-map in de PR, die pas na
  jouw merge wordt uitgevoerd. Wat F-019 verbiedt, eindigt als "geweigerd".

## Toegang

De pagina antwoordt alleen aan apparaten op je tailnet die van jouw Tailscale-login zijn (gecontroleerd met
`tailscale whois`), niet aan de box zelf en niet via het LAN (ufw: 8100-8199 alleen via `tailscale0`). Formulieren
hebben een token en worden alleen vanaf de pagina zelf geaccepteerd (tegen CSRF).

## Beheer

- `homelab-inbox list`: alle taken. De geschiedenis staat in `/var/lib/homelab-inbox/inbox.db` en overleeft een
  herstart; een taak die liep tijdens een herstart, staat daarna op "mislukt" (verstuur hem opnieuw).
- Grenzen en aantallen: `config/inbox.conf` (PR, daarna `scripts/21-inbox.sh`).
- Tokens: `/etc/homelab/inbox-claude-token` (Claude Code voor `inbox`, van `claude setup-token`),
  `/etc/homelab/inbox-github-token` (GitHub voor `inbox-runner`: Contents, Pull requests en Workflows read/write; Commit statuses en Actions read-only. A fine-grained token has no Checks permission: see "Build without me" in `docs/briefs.md`),
  `/etc/homelab/inbox-ntfy-token`. Verloopt er een, draai dan `scripts/21-inbox.sh` opnieuw: het vraagt opnieuw.
- A task that fails with `refusing to allow a Personal Access Token to create or update workflow`: the GitHub
  token misses Workflows: Read and write, so the push of a branch that adds or changes a file in
  `.github/workflows/` is rejected. Add that permission to the token on GitHub (its value stays the same, so
  nothing has to be pasted again) and use Retry on the task.
- Code: `bin/homelab-inbox` is the entry point; the parts are modules in `bin/homelab_inbox/` (map in `CLAUDE.md`).
  `scripts/21-inbox.sh` installs them next to it.
- Logs: `journalctl -u homelab-inbox`.

## Claude round runner (H-025 R1)

`rounds.stream_claude` no longer calls `homelab-inbox-task run` itself: it starts `python3 -I runner/round.py <id> <session> <new|resume> [research]` (`RUNNER_DIR`, installed to `/usr/local/lib/homelab/runner` by `21-inbox.sh`) and reads its NDJSON events (log, model, turn, result). The runner owns the stream-json parsing and calls `homelab-inbox-task` through the same sudoers line; the inbox keeps the turn limit, the deadline and cancel. The contract is in `runner/SCHEMA.md`; a Java `RunnerService` starts the same script later. Rollback: revert the PR.

H-025 R2: `jobs.make_draft` no longer calls `homelab-inbox-task draft` itself: it starts `python3 -I runner/draft.py d<id>` and reads the single `result` line (text, is_error, exit_code); the runner owns the parsing of Claude's JSON. Same sudoers line, no prompt or token in arguments (contract in `runner/SCHEMA.md`). Rollback: revert the PR.

H-025 R2 part 1: `router.run_route` starts `python3 -I runner/route.py <task id>` the same way and reads its one `result` line; the runner owns the Claude call (`route r<id>` through the same sudoers line) and the parsing of Claude's JSON, `router.py` keeps when and with what. No prompt or token in the arguments. Contract: "Runner `route.py`" in `runner/SCHEMA.md`. Rollback: revert the PR.

A retry never resumes a session that does not exist (inbox 235): if a round was cut off before Claude made its session (a restart of the inbox seconds after the start), `run_round` asks `homelab-inbox-task session <id> <session>` (exit 0 when `$TASK_HOME/.claude/projects/*/<session>.jsonl` exists) and, when it is missing, starts a new session with the task text plus the retry note. A round that ends with 0 turns and an error or empty result now fails with Claude's own message (`Claude deed niets (0 beurten): ...`, also in the task log) instead of "no commits". Tests: `tests/test_inbox_dead_session.py`. Rollback: revert the PR.

## Cut-over to the Java backend: the data step (H-027 X1a)

`scripts/inbox_cutover.py` moves the last data of the old Python inbox (`inbox.db`) to PostgreSQL right before the switch.
It does not switch anything: no port, no unit is enabled, disabled or changed (another task does that). Run it as root from
root's clone, only when H-023 to H-026 are merged and installed:

```sh
sudo python3 /var/lib/homelab/repo/scripts/inbox_cutover.py
```

In this order:

1. **Guard.** Any task with status `running` in `inbox.db` means: exit 1 with `REFUSED ... task(s) running`, nothing changed.
   Wait for the task or cancel it, then run again. The check is repeated after step 2 (a task that started in between).
2. **Stop** `homelab-inbox`, so nothing writes `inbox.db` any more.
3. **Final import**: a consistent snapshot of `inbox.db` (SQLite backup API, WAL included), then `import-sqlite` of the jar
   (`backend/README.md`, "One-off import") as `inbox-runner`.
4. **Row counts** per table, snapshot against PostgreSQL: `tasks`, `job_runs`, `job_state`, `drafts`, `skill_prs`. (`conversation`,
   `conversation_refusals`, `approvals` and the `switch:*` keys are Java's since H-024 M4, so they are not compared.) Any
   difference: exit 1, and `homelab-inbox` is started again if it was running: the old inbox stays in place.
5. **Keep the copies** in `/var/backups/homelab/inbox-cutover-<YYYY-MM-DD>/` (0700): `inbox.db` (the final copy) and
   `inbox.dump` (a fresh `pg_dump --format=custom` of `inbox`). Both paths are printed. Keep them for one release; X2 removes
   them. `homelab-backup` does not prune this directory. To roll back after the switch, restore from them (Q9: data written
   since the switch is lost).

On success `homelab-inbox` stays stopped, ready for the switch. Safe to rerun: the import refuses a non-empty database, so a
marker `cutover-import.json` next to `inbox.db` records the hash of the imported snapshot; the same `inbox.db` skips the import
and only compares and backs up again. A changed `inbox.db` with already filled tables aborts: empty the tables first
(`backend/README.md`). Options (flags or `CUTOVER_*` environment variables): `--inbox-db`, `--jar`, `--java`, `--backup-root`,
`--runner`, `--pg-run`, `--unit`, `--database`. Tests: `python3 -m unittest tests.test_inbox_cutover`.

## Staging the Java inbox (H-028 S1)

`changes/0010-inbox-staging` runs the Java backend a second time, as the unit `homelab-inbox-staging`, beside the live
inbox, before it takes over 8105 (X1b, "Cut-over to the Java inbox" below; the cut-over removes this unit). After the merge `homelab-admin` runs `apply.sh`; it can also be run by hand from root's
clone (`sudo bash /var/lib/homelab/repo/changes/0010-inbox-staging/apply.sh`). Merge it after `scripts/27-backend.sh` has
rebuilt the jar (the jar needs the tailnet connector of W5b; `apply.sh` checks and says so).

| What | Value |
|---|---|
| Tailnet port | **8125** on the tailnet address only (`INBOX_PUBLIC_PORT`), inside the existing `8100:8199` rule on `tailscale0`: ufw does not change. Not 8104, 8105 or 8121. Checked free with `ss -ltnp` (also 8131) |
| Loopback port | 8131 (`BACKEND_PORT`; the live backend has 8130) |
| Settings | `INBOX_FRONT_DOOR_ENABLED=true`, `CORE_EFFECTS=shadow`, `CORE_SCHEDULER_ENABLED=false`, `KITT_GATE_ENABLED=false`, `NTFY_FAKE=1`, no GitHub token (`GH_TOKEN_FILE` points nowhere) |
| Database | its own `inbox_staging` (owner `inbox_app`, one `pg_hba` line); the live `inbox` is not opened |
| Data | `/var/lib/homelab-inbox-staging/` (0700, `INBOX_RUNNER_HOME`): `inbox.db` is a SQLite backup-API copy of the live one (opened read-only), `memory-repo` and `jobs-repo` are copies **without their `origin`**, so staging can never push. The unit may write only this directory (`ReadWritePaths`) and may reach only loopback and the tailnet (`IPAddressDeny=any`) |
| Import | `scripts/inbox_cutover.py --inbox-db <copy> --database inbox_staging --unit homelab-inbox-staging --backup-root /var/backups/homelab-staging`: the same import and row-count check as the cut-over, against the copy; it stops only the staging unit |

The live `inbox.db`, the database `inbox` and the units `homelab-inbox` and `homelab-inbox-backend` are not changed. While a task
runs in the live inbox, `apply.sh` waits up to 5 minutes and then stops with a message; run it again by hand later. Staging
holds test data once it is used: a second `apply.sh` does not copy again. To refresh the copy run `revert.sh`, then `apply.sh`.
The skills page is empty on staging (no `repos/homelab` clone in the copy). The SSH tab still talks to the real
`homelab-admin` socket, as the page does on 8105: do not confirm a key request you did not make.

### The checks

The page and the JSON routes accept only the owner's own devices on the tailnet, so everything below that goes to port 8125
runs **from such a device** (the phone, or a laptop on the tailnet), not from the box. `<staging>` is
`http://homelab.tail4eb878.ts.net:8125`.

1. **The 403 check (what `scripts/21-inbox.sh` does).** On the box: `curl -s -o /dev/null -w '%{http_code}\n' http://100.85.70.34:8125/`
   must print `403` (the box is not one of the owner's devices). `apply.sh` does this itself. `ss -ltnp | grep 8125` must show
   only `100.85.70.34:8125`.
2. **Contract tests.** From an owner device with a clone of the repo:
   `INBOX_CONTRACT_BASE_URL=<staging> python3 tests/test_inbox_contract.py`. Every case that arranges state inside the Python
   process skips. The door cases (`ChatDoor`, `KittDoor`) are loopback-only by design and answer 403 on 8125; run those on the box
   against the loopback port: `INBOX_CONTRACT_BASE_URL=http://127.0.0.1:8131 INBOX_CONTRACT_CHAT_TOKEN=<token of a client with read>
   python3 -m unittest tests.test_inbox_contract.ChatDoor`. The POST cases write to staging's database only.
3. **`/api/tasks` against 8105.** From the same device, at a moment when no task changes:
   `curl -s http://homelab.tail4eb878.ts.net:8105/api/tasks | python3 -m json.tool --sort-keys > py.json`, the same for
   `<staging>/api/tasks` into `java.json`, then `diff py.json java.json`. The copy was taken when `apply.sh` ran: tasks
   that moved since then are the only expected differences (compare the ids that did not move, or run `revert.sh` and
   `apply.sh` right before the comparison for a fresh copy). Do the same for `/api/jobs-page` if needed.
4. **Rollback.** `sudo bash /var/lib/homelab/repo/changes/0010-inbox-staging/revert.sh` removes the unit, the database, the
   `pg_hba` line and the data, then checks that 8125 and 8131 are free and the live database `inbox` is still there.
   `ss -ltnp | grep -E ':(8125|8131)'` prints nothing afterwards.

## Cut-over to the Java inbox (H-028 X1b)

`changes/0011-inbox-java-cutover` makes the Java backend the inbox. It replaces H-027 X1b. **Chris merges it at a moment when no
task is running**, after the staging checks above and his phone check have passed. A task that runs during the switch is cut off,
and `apply.sh` refuses to start while one runs (see "Order"); a merge that was refused is not run again by itself (a failed change
only runs again when `apply.sh` changes), so run it by hand: `sudo bash /var/lib/homelab/repo/changes/0011-inbox-java-cutover/apply.sh`.
The jar must be the new one (the shared-port connector): the merge also changes `backend/`, so `scripts/27-backend.sh` rebuilds it;
if `apply.sh` ran first it says "too old" and stops before it touches anything, and a rerun by hand after the script does it.

| After the switch | Value |
|---|---|
| 8105 | the Java backend (`homelab-inbox-backend`): `127.0.0.1:8105` for the doors (Discord bot, kitt adapter, `/api/chat/action`) and, through `INBOX_PUBLIC_PORT=8105`, the tailnet address only for the page. A second connector may share the port because the main address is loopback; `PublicPortGuard` tells them apart by address and keeps the doors refused on the tailnet address |
| 8121 | the kitt gate, a connector of the same process (`KITT_GATE_ENABLED=true`, `KITT_GATE_HOST`/`KITT_GATE_PORT` from `config/inbox.conf`) |
| 8104 | unchanged: `homelab-overview-web` |
| ufw | unchanged: 8105 and 8121 are inside the existing `8100:8199` rule on `tailscale0`; the change adds none |
| Settings | a drop-in `/etc/systemd/system/homelab-inbox-backend.service.d/10-cutover.conf`: `INBOX_FRONT_DOOR_ENABLED=true`, `CORE_EFFECTS=live`, `CORE_SCHEDULER_ENABLED=true`. It also lifts the sandbox the runners cannot live in (`NoNewPrivileges`, `ProtectSystem`, `PrivateTmp`, `RestrictSUIDSGID`, `RestrictNamespaces`, capabilities for `sudo -u inbox`), as the old unit had none, and adds `SupplementaryGroups=inbox-share` and `UMask=0027` |
| Old unit | `homelab-inbox.service` is disabled and stopped but stays installed. The marker `/var/lib/homelab-inbox/java-cutover` makes `scripts/21-inbox.sh` leave it alone (it checks `/api/backend/health` instead) and `scripts/27-backend.sh` check the new shape (port 8105, tailnet listeners allowed, no facade-secret check) |
| Chat and memory routes | `ChatEntriesFilter` (the Discord bot's `/api/chat/entries` and `/state`, bearer token, loopback only) and `MemoryPageController` (the memory forms) are what the Python facade did for them; both exist only with the front door on |
| Staging | the unit `homelab-inbox-staging`, its database, data and ports 8125 and 8131 are removed by the last step (`changes/0010-inbox-staging/revert.sh`) |

### Order

1. Merge at a quiet moment (the inbox page shows no running task). The merge also restarts the old inbox through `21-inbox.sh`'s
   rerun if that runs first; that is the known cost of any merge on homelab.
2. `apply.sh` as root: preconditions (jar, PostgreSQL, ufw rule, tailnet address, 8104 held by `homelab-overview-web`, old unit installed).
3. It **refuses while a task runs** (`status = 'running'` in `inbox.db`): exit 1, nothing changed.
4. **The final import** `scripts/inbox_cutover.py` (the data step above): it checks again, stops `homelab-inbox`, imports `inbox.db`,
   **compares the row counts** per table and keeps the final `inbox.db` and a `pg_dump` in `/var/backups/homelab/inbox-cutover-<date>/`.
   Different counts: the old inbox is started again and nothing is switched.
5. **The switch**: the old unit is disabled, the drop-in and the marker are written, `homelab-inbox-backend` restarts.
6. **Checks**: `/api/backend/health` on `127.0.0.1:8105` answers 200; the backend listens only on `127.0.0.1:8105`, `<tailnet>:8105` and
   `<tailnet>:8121`; the page from the box is refused (403) and so is the chat door on the tailnet address; the kitt gate answers;
   8104 is still `homelab-overview-web`'s; ufw is unchanged; the old unit is installed, disabled and stopped. A failed check after the
   import puts the old inbox back at once (drop-in and marker removed, old unit enabled and started) and `apply.sh` fails.
7. The staging unit is removed.

What Chris checks afterwards (a task is the real test of the live effects): the phone page on 8105 (tasks, a task, jobs, briefs,
memory, search, forge, SSH); a small test task runs through to a PR; a Discord message gets an answer; kitt works on the phone.
`journalctl -u homelab-inbox-backend` is not readable for `claude`; ask Chris, or look at the task logs on the page.

### Rollback (Q9)

Revert the PR, then enable the old unit. In this order, so the files still exist:

1. On the box, as root, from root's clone while the cut-over is still on `main`:
   `sudo bash /var/lib/homelab/repo/changes/0011-inbox-java-cutover/revert.sh`. It refuses while a task runs in the Java inbox
   (`FORCE=1` cuts it off). It removes the drop-in and the marker, restarts the backend as the shadow backend on `127.0.0.1:8130`,
   enables and restarts `homelab-inbox`, and checks that 8105 answers 403 from the old process.
2. Merge the revert PR of the cut-over, so `main` and the box agree again (the scripts are then the old ones; the jar rebuilds).
   Without `revert.sh` (the revert PR already merged): `sudo rm /etc/systemd/system/homelab-inbox-backend.service.d/10-cutover.conf
   /var/lib/homelab-inbox/java-cutover && sudo systemctl daemon-reload && sudo systemctl restart homelab-inbox-backend &&
   sudo systemctl enable --now homelab-inbox`.

**Data written after the cut-over is lost on a rollback (Q9, accepted for one release):** the old inbox starts on the last
`inbox.db`, as it was when the final import stopped it; tasks, drafts, runs and job state made by the Java inbox since do not go back
to SQLite. Conversation, memory and the job files were Java's already (H-024 M4 to M6) and stay. The last `inbox.db` and the `pg_dump`
stay in `/var/backups/homelab/inbox-cutover-<date>/` (X2 removes them one release later); `pg_dump` is also the way to look at what
Java wrote. A second try after a rollback needs the Java tables emptied by hand if `inbox.db` changed in between
(`backend/README.md`, "One-off import"): the import refuses a database that already holds other data.
