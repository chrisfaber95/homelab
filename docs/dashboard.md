# Homelab-pagina (F-024)

Eén pagina voor alles: **`http://homelab.tail4eb878.ts.net:8105`** (Tailscale aan). Het overzicht bovenaan zegt in één
zin of alles in orde is, wat er nu draait en welke job hierna komt. De tabs **Taken** (F-021), **Jobs** (F-022),
**Geheugen** (F-023), **Search** (H-012, `docs/search.md`: vragen met bronvermelding), **Skills** en **SSH** (een SSH-sleutel aanvragen, `docs/admin.md`) zitten op dezelfde pagina. Het oude overzicht op poort 8104 verwijst hierheen.

## Op het overzicht

- **De statuszin**: "Alles in orde.", of welke check faalt of aandacht vraagt, of dat de bewaking al meer dan 10
  minuten niet gecontroleerd heeft. Het lampje erboven en de scanner eronder zijn groen/blauw, oker of rood.
- **Nu bezig**: elke inbox-taak en job die nu draait, met het model (Claude en welk model, of het lokale model) en hoe
  lang hij al loopt. **Log tonen** laat het log meelopen (hooguit ~2 s achter): wat Claude zegt, welke tools hij
  gebruikt en wat die teruggeven (alleen de eerste regel). Iets wat op een token of wachtwoord lijkt, staat er als
  `[verborgen]`. **Annuleren** werkt als in F-021; een lokale job-run stopt meteen. Een afgeronde run verdwijnt binnen
  een paar seconden. Ook op de pagina van een taak of run staat zijn log.
- **Jobs**: per job een baan met de runs van de laatste 7 dagen (links, 70% van de breedte) en de geplande runs van de
  komende 24 uur (rechts, 30%), met een lijn op "nu". Dicht rondje: gelukt (groen) of PR (blauw); ruit: mislukt;
  oker: wacht op de daglimiet of stelt een vraag; open rondje: bezig of gepland. Elk merkje linkt naar zijn run;
  "Nu draaien" staat op de pagina van de job en gaat door dezelfde rij, regels en daglimiet als een geplande run.
- **Wijzigingen op de box**: de laatste vijf via `homelab-admin` (F-019).
- **Deze week** (de laatste 7 dagen, per dag als staafjes, vandaag in blauw):
  - lokaal model: calls uit het F-017-log (alle callers);
  - Claude voor jobs: runs tegen de daglimiet `JOB_CLAUDE_CAP` (de stippellijn);
  - Claude voor jouw taken: inbox-taken die je stuurde;
  - tijd bespaard: de `minutes` uit het job-bestand, voor elke gelukte run (ok of PR), per soort job. Een job zonder
    `minutes` telt niet mee. De waarde wordt bij het starten van een run vastgelegd.
  Het echte verbruik van je Claude-abonnement kan de box niet lezen; de pagina telt alleen de runs van de box zelf.
- **Services**: the address of every service on the box (from `config/services.conf`: `name | address | what it is`;
  http(s) addresses are links, SSH commands are shown as code) and how to start the designer (`docs/briefs.md`).
- **Gezondheid** (alleen lezen, uit F-020): de checks die niet in orde zijn, alle checks onder "Alle checks", de
  deploys en de laatste backup.

## Toegang

Zoals in F-021: alleen apparaten van jouw Tailscale-login, niet het LAN, niet het internet. De pagina laadt alleen
eigen bestanden (stijl, script en lettertypen komen van de box zelf) en toont geen geheimen. Zonder JavaScript werkt
alles behalve het meelopende log en de live lijst (die tonen dan de stand bij het laden).

## Vormgeving

Futuristisch, donker en blauw, naar de KITT-skin ("Co-pilot") van de claude-dashboard (keuze van de gebruiker,
2026-10-26): bijna zwart met een blauwe gloed, doorschijnende panelen, kleine labels in JetBrains Mono en de rest in
Space Grotesk (beide OFL, in `web/fonts`, dezelfde bestanden als de claude-dashboard). Blauw is het accent; rood
betekent alleen "faalt". Onder de statuszin loopt een **scanner** (LED-segmenten met een heen en weer lopend licht):
langzaam als het rustig is, snel zolang er iets draait, oker of rood als er iets aandacht vraagt of faalt.
De staafjes van Deze week zijn LED-staafjes. Alleen donker, zoals de KITT-skin (de brief vroeg licht en donker; de
gebruiker koos voor alleen donker). Animaties lopen altijd, zoals in de claude-dashboard, en raken alleen transform,
opacity en background-position. Op de telefoon staan de panelen onder elkaar: nu bezig, jobs, deze week, gezondheid,
wijzigingen. Stijl: `web/app.css`; de pagina's zijn TypeScript (`frontend/`); `web/app.js` en `pages.py` bestaan niet meer.

## Frontend project (H-021)

`frontend/` is the TypeScript frontend: Vite and plain TypeScript, no UI framework, all dependencies pinned in
`package.json` and `package-lock.json`. Its types come from `backend/openapi.yaml` (`src/api/schema.d.ts`, generated).
Every page is ported (H-022): `src/pages/` has one file per page (overview, jobs, job, tasks, task, briefs, memory, skills, search, ssh), each reading its JSON endpoint from `backend/openapi.yaml`.
`src/main.ts` picks the page by path, `src/shell.ts` draws the header with the tabs, `src/format.ts` has the Dutch time
texts (tests in `format.test.ts`). The stylesheet and fonts are still `web/app.css` and `web/fonts`, loaded from `/static/`.
Vite builds with `base: "/app/"` (the inbox serves the build under `/app/`).

npm scripts (run in `frontend/`, install with `npm ci --ignore-scripts`):

- `npm run gen:types`: regenerate `src/api/schema.d.ts` from `../backend/openapi.yaml` (openapi-typescript).
- `npm run lint`: `tsc --noEmit -p tsconfig.lint.json`.
- `npm run typecheck`: `tsc --noEmit`.
- `npm test`: `vitest run`.
- `npm run build`: `vite build`, output in `frontend/dist`.

CI (`.github/workflows/ci.yml`, job `frontend`, on every pull request): `npm ci --ignore-scripts`, lint, typecheck,
tests, build, a check that `dist/index.html` exists, and a check that `npm run gen:types` leaves `src/api/schema.d.ts`
unchanged (the committed types match `backend/openapi.yaml`).

In short, how the frontend fits into the inbox (details in the sections below):

- `GET /api/session` returns the CSRF token; mutating `/api/*` requests send it as `X-CSRF-Token`; errors under `/api/*`
  are `{"error": "..."}`.
- `GET /api/jobs-page` returns what the Jobs page renders (counter, drafts, catalogue, jobs, latest runs) as JSON
  (`overview.jobs_data`). `/api/jobs` is kitt's door, hence the name. The Jobs page is TypeScript (`frontend/src/pages/jobs.ts`); its forms are the
  same HTML forms as before (hidden `csrf` field, same actions).
- `PORTED_PAGES` defaults to `/,/jobs,/job,/draft,/run,/research,/memory,/search,/skills,/ssh,/taken,/task,/briefs` (Overview, Jobs, Search (`frontend/src/pages/search.ts`, data from `/api/search/*`), Memory (`frontend/src/pages/memory.ts`, data from `GET /api/memory` and `GET /api/memory/e/<id>`; `/memory/e/<id>` is the Memory path for an entry that exists; the add, save and delete forms post to `/memory/add` and `/memory/e/<id>/save|delete` with fetch and show the `{error}` of a 400), Skills (`frontend/src/pages/skills.ts`, data from `GET /api/skills`), SSH, the Tasks pages and Briefs; `/task` stands for every `/task/<id>`, likewise `/job`, `/draft` and `/run` (for a job, draft or run that exists; see "Job pages") and `/research` (also at `/job/research`); the SSH page is `frontend/src/pages/ssh.ts`, data from `GET /api/ssh`, form posting to `POST /api/ssh`); every other page is still rendered by the Python inbox. A path listed there gets `frontend/dist/index.html`. Without a
  build, these pages (and `/briefs/<id>`) answer 503 ("The frontend build is missing"), because their Python versions are gone; other listed
  paths fall back to their Python page.
- The Briefs page (`frontend/src/pages/briefs.ts`) reads `GET /api/briefs` and `GET /api/briefs/<id>`. Approve & build, Build and
  Park POST to `/briefs/<id>/build|park` as before; the script follows the redirect, and a failure is a 400 `{"error"}` that is
  shown above the list.
- `scripts/21-inbox.sh` builds `frontend/dist` with `npm ci --ignore-scripts` as an unprivileged throwaway user (never as
  root), installs it to `/usr/local/lib/homelab/frontend/dist` and checks the result itself.

## Job pages (H-022 F7b)

`/job/<name>`, `/draft/<id>`, `/run/<id>` and `/research` are TypeScript (`frontend/src/pages/job.ts`; the live log of a
local run is `log.ts`). Data: `GET /api/job/<name>` (facts, file and history; `facts` is null for a broken file),
`GET /api/draft/<id>`, `GET /api/run/<id>` (with `log_key`, `cancellable`) and `GET /api/research` (`overview.job_detail`,
`draft_detail`, `run_detail`, `research_data`). A page for an unknown name or id is a 404 page, with or without a build;
without a build the others answer 503. The forms post to the same URLs as before (`/job/<name>/run|pause|resume|save|change|delete`,
`/draft/<id>/confirm|cancel`, `/run/<id>/cancel`, `/research/ask`) with fetch (`frontend/src/form.ts`): a 303 is followed, and a
failure is a 400 (429 for the research cap) `{"error": "..."}` that the page shows above the form, so what you typed stays in the box.
A job named `research` makes `/job/research` that job and `/research` a 404. Tests: `tests/test_inbox_job_pages_api.py`.

## Tasks pages (H-022 F10)

`/taken` and `/task/<id>` are TypeScript (`frontend/src/pages/tasks.ts`, `task.ts`; the live log is `log.ts`). Forms are
plain HTML forms (hidden `csrf` field, POST, 303 redirect) as before. The page reloads itself every 20 s while tasks
are queued (`refresh`), and the log polls every 2 s. An unknown task id or a query string on `/task/<id>` is still a 404.

`GET /api/tasks` returns what `/taken` renders (repos for the form, `queue`, `history`) and `GET /api/tasks/<id>` what
`/task/<id>` renders: the task, its derived facts, `actions` (which forms apply) and `log` (the lines so far, `next`,
`done`). The live log keeps polling `GET /api/log/t<id>?from=<next>`; there are no server-sent events. The builders are
`tasks_view` and `task_view` in `overview.py`; both carry the `timezone` the times are shown in. Contract: `backend/openapi.yaml`; tests:
`tests/test_inbox_tasks_api.py`.

## JSON API: CSRF token and errors (H-021)

The page's JavaScript talks to `/api/*` (contract: `backend/openapi.yaml`). Two rules apply to every `/api/*` call of
the page (the chat and kitt doors have their own checks):

- `GET /api/session` returns `{"csrf_token": "..."}`. Every mutating request (POST, PUT, PATCH, DELETE) must send it as
  the header `X-CSRF-Token`, next to the `Origin` of the page. A missing or wrong token is 403. `frontend/src/api/client.ts` fetches
  the token once (`csrfToken()`) before its POSTs. The HTML forms keep
  their hidden `csrf` field; `/api/*` does not accept the field.
- Every error under `/api/*` is `{"error": "text"}`: a refused caller (403), an unknown route (404), a bad parameter
  (400), a failure of an upstream (502) and an unexpected exception (500, details in the journal). The `{"error":
  {"message": ...}}` of forge and the search API is unwrapped by the inbox. PUT, PATCH and DELETE are not implemented
  and answer 405 after the token check.

Tests: `tests/test_inbox_session.py`. Outside `/api/`, errors and refused forms answer with a small HTML page (`page()` in `server.py`).

## Beheer

Alles zit in de dienst `homelab-inbox`; na een wijziging aan `bin/homelab-inbox`, `bin/homelab_inbox/`, `web/` of de config:
`sudo bash /var/lib/homelab/repo/scripts/21-inbox.sh`. Logs van taken en runs staan in
`/var/lib/homelab-inbox/logs/` (één bestand per taak of run). Testen zonder root: zie de docstring van
`bin/homelab-inbox` (de gegevens van de pagina's staan in `overview.py`, de toegangscontrole en het CSRF-token in `access.py`, de HTTP-handler in `server.py`; de pagina's zelf zijn TypeScript in `frontend/`), plus `MONITOR_DIR`, `DEPLOY_STATE` en `BACKUP_STATUS` voor de gezondheid.

## Ported pages (H-021)

`PORTED_PAGES` (environment variable of the inbox, comma-separated URL paths such as `/taken,/skills`; default `/,/jobs,/job,/draft,/run,/research,/memory,/search,/skills,/ssh,/taken,/task,/briefs`)
lists the pages that the TypeScript frontend has taken over. For a GET on a listed path the inbox sends
`frontend/dist/index.html`; its built assets are served under `/app/` from `frontend/dist` (override the folder with
`FRONTEND_DIST`). URLs and notification links stay valid. If a listed path has no `frontend/dist/index.html`, a warning goes to the
journal and the answer is a 503 (`/`, `/jobs`, `/job/<name>`, `/draft/<id>`, `/run/<id>`, `/research`, `/ssh`, `/taken`, `/task/<id>`), so run `scripts/21-inbox.sh` to build the frontend.
Tests: `tests/test_inbox_ported_pages.py`.

Build on the box: `scripts/21-inbox.sh` (rerun line lists `frontend`) copies `frontend/` to a staging folder and runs
`npm ci --ignore-scripts` and `npm run build` there as a throwaway user (`systemd-run`, `DynamicUser`, unit
`homelab-frontend-build`; its own state folder is the only writable path, `ProtectSystem=strict`). Never as root. Root
then copies `dist` to `/usr/local/lib/homelab/frontend/dist` (root-owned, 755/644, readable by `inbox-runner`) and checks
that `index.html` exists, is non-empty and is readable by the service; the script fails otherwise and installs nothing
from a failed build. The build needs network access to the npm registry.

## Structure and deploy (H-022 F12)

- **Backend:** the Python server `bin/homelab-inbox` with the modules in `bin/homelab_inbox/`; the JSON API is described in
  `backend/openapi.yaml`. `access.py` has the CSRF token and the Tailscale owner check, `overview.py` the data of every
  page, `server.py` the routes. There is no HTML rendering left except the small error page.
- **Frontend:** TypeScript in `frontend/`, built once (`npm run build`) into `frontend/dist`; the server serves
  `index.html` for every page path and the assets under `/app/`. The stylesheet and fonts are `web/app.css` and
  `web/fonts`, under `/static/`.
- **Deploy:** the inbox is part of this repo, so `scripts/21-inbox.sh` is the deploy: one build (frontend, as a throwaway
  user), the unit `homelab-inbox` serving the built UI. Host and port come from the environment: `INBOX_HOST` (default
  `::`, all interfaces; ufw and Tailscale limit who gets in) and `INBOX_PORT` (default `8105` from `config/inbox.conf`);
  `FRONTEND_DIST` overrides the build folder. No new `repos.conf` line, unit, `services.conf` link or port is needed:
  the homelab page already links itself.
