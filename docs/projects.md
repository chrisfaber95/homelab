# Projecten op de box (F-018)

De box houdt een lijst repo's bij. Een push naar `main` staat binnen ~20 s in de deploy-clone, en een repo die een
dienst is wordt daarna gebouwd en herstart. De box maakt alleen uitgaande verbindingen.

```
GitHub main ──(elke 20 s: git ls-remote, read-only token)──> /srv/homelab/repos/<naam>      deploy-clone = origin/main
                                                                   │ git archive
                                                                   ▼
                                                     /srv/homelab/releases/<naam>/<commit>  bouwen (npm / compose build)
                                                                   │ current -> <commit>
                                                                   ▼
                                       compose: rootless Docker van deploy   service: homelab-app-<naam>.service
```

## De lijst

`config/repos.conf` in deze repo, één regel per repo: `<naam> <owner/repo> <soort>`.

| Soort | Wat de box doet |
| --- | --- |
| `sync` | alleen een deploy-clone bijhouden (bijvoorbeeld scripts) |
| `compose` | het compose-bestand in de root bouwen en starten in de rootless Docker van het account `deploy` |
| `service` | met een `package.json`: `npm ci` en `npm run build`; dan `homelab-app-<naam>.service` herstarten |

**Toevoegen**: een regel erbij, commit, PR, mergen naar `main`. Binnen een minuut staat de deploy-clone er.
- `compose`: publiceer poorten in de reeks **8100-8199**. Die laat ufw alleen via `tailscale0` door, en rootless
  Docker gaat niet om ufw heen. Bind bij voorkeur aan het Tailscale-adres (`"100.85.70.34:8109:3000"`).
  In gebruik: 8101 claude-dashboard, 8102 ntfy, 8103 bevestigpagina, 8104 overzicht, 8105 inbox/dashboard,
  8106-8108 visualshopping, 8109 f1-dashboard. Kijk bij twijfel met `ss -ltn`.
  Zet `restart: unless-stopped` in het compose-bestand; na een reboot start homelab-deploy de containers ook zelf.
- `service`: also add a unit `deploy/<name>.service` to this repo (see `deploy/claude-dashboard.service`). After the
  merge, `homelab-admin` runs `scripts/18-projects.sh` as root, which installs the unit (`docs/admin.md`, "Install
  scripts after a merge"). Until then the status says `is niet geïnstalleerd`; after the script the box retries
  within 30 minutes.

**Weghalen**: de regel eruit en mergen. De dienst stopt (`compose down`; de images gaan weg, volumes blijven; of de
unit stopt) en de deploy-clone en releases worden verwijderd. De unit van een service haal je weg met `deploy/<naam>.service` eruit en
het script opnieuw.

## Sync en deploy

- Het account `deploy` draait `homelab-deploy run` als dienst `homelab-deploy`. Elke ronde: `git ls-remote` per repo;
  is `main` veranderd, dan `fetch` en `reset --hard origin/main`. Alleen `main` wordt gesynct.
- Een gewijzigde commit van een `compose`- of `service`-repo wordt een release: `git archive` naar
  `/srv/homelab/releases/<naam>/<commit>`, daar bouwen, en dan `current` naar die release laten wijzen.
- **Build mislukt**: er verandert niets, de vorige versie draait door. Na 30 minuten probeert de box het één keer per
  half uur opnieuw (een tijdelijke npm-storing lost zo vanzelf op), en een nieuwe push gaat altijd door.
- **Start mislukt** (compose: een container komt niet op binnen 120 s; service: de unit draait na 15 s niet meer):
  `current` gaat terug naar de vorige release en die wordt opnieuw gestart. Die commit wordt niet nog eens geprobeerd.
- Per ronde hoogstens één deploy; daarna wordt eerst alles weer gesynct, zodat een lange build de rest niet ophoudt.
- De box bewaart de draaiende en de vorige release. Na een geslaagde compose-deploy ruimt hij images op die nergens
  meer bij horen (`docker image prune`, in de Docker van `deploy`).
- Na een reboot: de rootless Docker van `deploy` en `homelab-deploy` starten via systemd (linger), en
  `homelab-deploy` doet `compose up` voor elke compose-repo. Services zijn gewone systemd-units.

## Status

```
homelab-deploy status
```

Per repo: de commit die draait (met de commit-boodschap), wanneer de laatste sync was, en het resultaat van de
laatste deploy, met de foutmelding als die mislukte. Exit 1 als een sync of deploy faalt of de sync-lus stil ligt
(voor de health alerts van F-020). Logs: `journalctl -u homelab-deploy`, `journalctl -u homelab-app-<naam>`.

## Deploy-clones en dev-clones

| | Deploy-clone | Dev-clone |
| --- | --- | --- |
| Waar | `/srv/homelab/repos/<naam>` (van `deploy`) | `~/dev/<naam>` (van jou of van `claude`) |
| Wie wijzigt | niemand: de sync zet hem altijd terug op `origin/main` | jij, Claude |
| Sync | ja | nooit: sync kijkt alleen in `/srv/homelab` |

- **Jij**: VS Code → Remote-SSH → `homelab` → `~/dev/<repo>` (`git clone` met je eigen login). Node 24 staat
  systeembreed (`/usr/bin/node`).
- **Claude**: werkt in `/home/claude/dev/<repo>`, met zijn eigen rootless Docker (`docker` werkt zonder sudo en
  zonder de groep `docker`). Claude pushes branches and opens a PR. On the product repos Claude merges its own PRs
  (rules in `claude/CLAUDE.md`); on homelab you merge. Then the box deploys.
  Een globale pre-push-hook weigert pushes van Claude naar `main` (GitHub kan dat voor privé-repo's zonder Pro niet
  afdwingen).

## Terugdraaien

Push een revert naar `main` (`git revert <commit>`, PR, mergen). De box deployt die als een gewone nieuwe commit.
Er is geen apart rollback-commando: wat draait is altijd een commit op `main`, of de vorige als de nieuwste faalde.

## Toegang

- De diensten zijn alleen via het tailnet bereikbaar: ufw laat 8100-8199 alleen via `tailscale0` door.
- Het token van `deploy` (`/etc/homelab/github-deploy-token`, alleen leesbaar voor `deploy`) is een fine-grained
  token met **Contents: Read-only** op alle repo's. Het script controleert dat GitHub er een push mee weigert.
  Verloopt het, dan toont `homelab-deploy status` bij elke repo een sync-fout: maak een nieuw token, zet het in het
  bestand (`sudo install -m 600 -o deploy -g deploy /dev/stdin /etc/homelab/github-deploy-token`, plakken,
  Ctrl+D) en herstart `homelab-deploy`.

## claude-dashboard

Draait als `homelab-app-claude-dashboard.service` onder het account `claude`, vanuit de release (die `claude` niet kan
wijzigen). Hij ziet Claudes sessies op de box en kan Claude-sessies starten in `~/dev` van claude
(`PROJECTS_ROOT`). Omdat hij Claude laat handelen, draait hij in LAN-modus: HTTPS met login per apparaat, op
`https://homelab:8101` (het certificaat is zelfondertekend; je browser waarschuwt de eerste keer).

It also lists the live sessions of inbox tasks (account `inbox`, project label `inbox`): read-only, without
transcript or tokens. `scripts/21-inbox.sh` installs `homelab-inbox-sessions.path` and `.timer`, which copy
`/var/lib/inbox/.claude/sessions/*.json` (never the `.key` files) as `inbox` into `/var/lib/inbox-sessions/sessions`
(group `claude`, on every change and once a minute), and `CLAUDE_EXTRA_DIRS` in `deploy/claude-dashboard.service`
points the dashboard there. An ACL on the original folder doesn't work: Claude Code chmods it to 0700 on every start.

**Een apparaat koppelen** kan alleen vanaf de box zelf (de vrijstelling "this PC": loopback). Vanaf Windows:

```
ssh -N -L 18101:127.0.0.1:8101 homelab
```

Laat dat venster open (met `-N` is er geen prompt; de tunnel draait zolang het venster open is). Neem niet 4777: dat
is de poort van de dashboard op je pc, en dan opent `ssh` de tunnel niet (alleen een regel `bind ... 4777` als
waarschuwing). Open dan `https://localhost:18101` (https, de browser waarschuwt voor het certificaat): daar ben je
"this PC" en maak je een apparaat aan, met een token of een QR-code voor
je telefoon. Daarna logt dat apparaat in op `https://homelab:8101`.

**Let op**: net als op de pc kan elk proces op de box via loopback een apparaat aanmaken, en dus Claude laten handelen.
Op de box zijn dat ook de builds van gedeployde repo's (`npm ci` draait als `deploy`). Claude heeft geen sudo, maar kan
wel bij alles van het account `claude`. De gegevens van de dashboard (apparaten, certificaat, `commands.log`) staan in
`/home/claude/.local/share/claude-dashboard`.

## visualshopping-server

De webshop van Coop Lubbers: de klant-app op **`http://homelab:8107`** (`visualshopping-client`, repo
`Client-portfolio`), de API op `http://homelab:8106/api/v1/` (`visualshopping-server`, repo
`Visualshopping-Server-portfolio`, met MySQL en de productcatalogus). nginx in de client stuurt `/api/` door naar
8106, dus app en foto's staan op één adres. De database bouwt zich bij de eerste start op uit `db/` in de server-repo
(ongeveer een minuut; de API verbindt pas bij een verzoek, zodat `compose up --wait` niet op de import wacht).
Mollie en mail staan uit: afrekenen werkt niet.
De werknemers-app staat op **`http://homelab:8108`** (`visualshopping-werk`, repo `Werknemers-portfolio`), met
inloggen als `demo@example.com` / `demo1234` (rol Admin, uit `db/06_demo.sql` van de server; op de bestaande
database gezet door `changes/0003-visualshopping-demo`).

- **Productfoto's** (854 MB, 9.340 bestanden) staan niet in git. Ze staan in `/home/claude/data/coop-lubbers/public`
  (herkomst en kopiëren: README in `homelab-data/databases/coop-lubbers`). `changes/0002-visualshopping-fotos` kopieert
  ze naar de volume `visualshopping-server_public`. Na een herbouw: eerst de map terugzetten, dan die change opnieuw
  (`apply.sh` aanpassen, bijvoorbeeld de datum in een commentaarregel, want een change draait één keer).
- **Database opnieuw opbouwen** (na een wijziging in `db/`), als `deploy` in `/srv/homelab/releases/visualshopping-server/current`:
  `docker compose -p visualshopping-server down`, `docker volume rm visualshopping-server_db`, `docker compose -p
  visualshopping-server up -d`. Geen `down -v`: dat wist ook de volume met foto's en uploads.

## Herbouwen op een lege box

1. Checklist stap 0-12, dan de scripts van stap 13 (health check, LLM).
2. `sudo bash /home/claude/homelab/scripts/18-projects.sh` (vraagt om het read-only token).
3. Als `claude`: `bash /home/claude/homelab/scripts/18-claude.sh`.
4. `homelab-deploy status`: alle repo's uit de lijst staan er binnen een paar minuten, en de dashboard draait.

## f1-dashboard

Live F1 timing page for Chris alone. `service` repo (`chrisfaber95/f1-dashboard`): the box runs `npm ci`, `npm run build`
and then `homelab-app-f1-dashboard.service` (`deploy/f1-dashboard.service`, `DynamicUser`, `npm start`). It listens on the
Tailscale address only, port 8109: **`http://homelab:8109`** (the server refuses `0.0.0.0`; ufw allows 8100-8199 only via
`tailscale0`). Starts on boot like every service.

- `/etc/f1-dashboard/env` (root, 0600) holds `F1_SOURCE=live` on a fresh box: the service connects to the live feed and needs a token.
  `changes/0004-f1-dashboard-config` creates it, and an empty `/etc/f1-dashboard/token`, because the unit always loads the
  credential `LoadCredential=f1-token:/etc/f1-dashboard/token`. It never overwrites existing files.
- Token: put the F1TV token in `/etc/f1-dashboard/token` (`docs/feed-authentication.md` in the app repo) and restart
  `homelab-app-f1-dashboard`. Until then the feed keeps reconnecting with reason `auth`. To play the fixture without a
  login, set `F1_SOURCE=replay` in the env file and restart.
- Assumption: the app starts with `npm start` and reads `HOST`/`PORT` (as in its own `docs/deployment.md`). If the
  status says the unit keeps failing, check the start script.
- Token from the page: the token dialog saves a pasted token in `/var/lib/f1-dashboard/token`
  (`F1_TOKEN_UPLOAD_FILE`, service-private). It wins over `/etc/f1-dashboard/token` until it expires, and needs no
  restart. The form only works with `F1_SOURCE=live`.
