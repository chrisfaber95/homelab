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
wijzigingen. Stijl: `web/app.css`; het overzicht is TypeScript (`frontend/`), de overige pagina's gebruiken `web/app.js` (live logs, Search).

## Frontend project (H-021)

`frontend/` is the TypeScript frontend: Vite and plain TypeScript, no UI framework, all dependencies pinned in
`package.json` and `package-lock.json`. Its types come from `backend/openapi.yaml` (`src/api/schema.d.ts`, generated).
Ported so far: the Overview (`/`, `src/pages/overview.ts`, data from `GET /api/overview`, "Nu bezig" from `GET /api/live`).
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
  (`overview.jobs_data`). The HTML page is unchanged until the page is ported; `/api/jobs` is kitt's door, hence the name.
- `PORTED_PAGES` defaults to `/,/briefs` (the Overview and Briefs); every other page is still rendered by the Python inbox. A path listed
  there gets `frontend/dist/index.html`. Without a build, `/` and `/briefs` (and `/briefs/<id>`, which follows `/briefs`)
  answer 503 ("The frontend build is missing"), because their Python pages are gone; other listed paths fall back to their Python page.
- The Briefs page (`frontend/src/pages/briefs.ts`) reads `GET /api/briefs` and `GET /api/briefs/<id>`. Approve & build, Build and
  Park POST to `/briefs/<id>/build|park` as before; the script follows the redirect, and a failure is a 400 `{"error"}` that is
  shown above the list.
- `scripts/21-inbox.sh` builds `frontend/dist` with `npm ci --ignore-scripts` as an unprivileged throwaway user (never as
  root), installs it to `/usr/local/lib/homelab/frontend/dist` and checks the result itself.

## JSON for the Tasks pages (H-022 F10)

`GET /api/tasks` returns what `/taken` renders (repos for the form, `queue`, `history`) and `GET /api/tasks/<id>` what
`/task/<id>` renders: the task, its derived facts, `actions` (which forms apply) and `log` (the lines so far, `next`,
`done`). The live log keeps polling `GET /api/log/t<id>?from=<next>`; there are no server-sent events. The builders are
`tasks_view` and `task_view` in `overview.py`; the HTML pages are unchanged. Contract: `backend/openapi.yaml`; tests:
`tests/test_inbox_tasks_api.py`.

## JSON API: CSRF token and errors (H-021)

The page's JavaScript talks to `/api/*` (contract: `backend/openapi.yaml`). Two rules apply to every `/api/*` call of
the page (the chat and kitt doors have their own checks):

- `GET /api/session` returns `{"csrf_token": "..."}`. Every mutating request (POST, PUT, PATCH, DELETE) must send it as
  the header `X-CSRF-Token`, next to the `Origin` of the page. A missing or wrong token is 403. `web/app.js` fetches
  the token once (`csrfToken()`) before its POSTs (search ask and feedback, forge start and stop). The HTML forms keep
  their hidden `csrf` field; `/api/*` does not accept the field.
- Every error under `/api/*` is `{"error": "text"}`: a refused caller (403), an unknown route (404), a bad parameter
  (400), a failure of an upstream (502) and an unexpected exception (500, details in the journal). The `{"error":
  {"message": ...}}` of forge and the search API is unwrapped by the inbox. PUT, PATCH and DELETE are not implemented
  and answer 405 after the token check.

Tests: `tests/test_inbox_session.py`. Pages that are not under `/api/` still answer with HTML.

## Beheer

Alles zit in de dienst `homelab-inbox`; na een wijziging aan `bin/homelab-inbox`, `bin/homelab_inbox/`, `web/` of de config:
`sudo bash /var/lib/homelab/repo/scripts/21-inbox.sh`. Logs van taken en runs staan in
`/var/lib/homelab-inbox/logs/` (één bestand per taak of run). Testen zonder root: zie de docstring van
`bin/homelab-inbox` (de HTML staat in `bin/homelab_inbox/pages.py`, de gegevens in `overview.py`, de HTTP-handler in `server.py`), plus `MONITOR_DIR`, `DEPLOY_STATE` en `BACKUP_STATUS` voor de gezondheid.

## Ported pages (H-021)

`PORTED_PAGES` (environment variable of the inbox, comma-separated URL paths such as `/taken,/jobs`; empty by default)
lists the pages that the TypeScript frontend has taken over. For a GET on a listed path the inbox sends
`frontend/dist/index.html`; its built assets are served under `/app/` from `frontend/dist` (override the folder with
`FRONTEND_DIST`). Every other path is rendered by `pages.py` as before, so URLs and notification links stay valid. If a
listed path has no `frontend/dist/index.html`, the Python page is served and a warning goes to the journal.
Tests: `tests/test_inbox_ported_pages.py`.

Build on the box: `scripts/21-inbox.sh` (rerun line lists `frontend`) copies `frontend/` to a staging folder and runs
`npm ci --ignore-scripts` and `npm run build` there as a throwaway user (`systemd-run`, `DynamicUser`, unit
`homelab-frontend-build`; its own state folder is the only writable path, `ProtectSystem=strict`). Never as root. Root
then copies `dist` to `/usr/local/lib/homelab/frontend/dist` (root-owned, 755/644, readable by `inbox-runner`) and checks
that `index.html` exists, is non-empty and is readable by the service; the script fails otherwise and installs nothing
from a failed build. The build needs network access to the npm registry.
