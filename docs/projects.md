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
  Docker gaat niet om ufw heen. Bind bij voorkeur aan het Tailscale-adres (`"100.85.70.34:8102:3000"`).
  Zet `restart: unless-stopped` in het compose-bestand; na een reboot start homelab-deploy de containers ook zelf.
- `service`: zet ook een unit `deploy/<naam>.service` in deze repo (zie `deploy/claude-dashboard.service`) en draai
  `sudo bash /srv/homelab/repos/homelab/scripts/18-projects.sh`: een nieuwe systemd-unit heeft root nodig. Zonder unit
  meldt de status `is niet geïnstalleerd`. Na het script probeert de box het binnen 30 minuten opnieuw.

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
  zonder de groep `docker`). Claude pusht naar branches en opent een PR; jij merget naar `main`, en dan deployt de box.
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

De API van de webshop van Coop Lubbers (repo `Visualshopping-Server-portfolio`), met MySQL en de productcatalogus,
op `http://homelab:8102/api/v1/`. De database bouwt zich bij de eerste start op uit `db/` in die repo (ongeveer 75 s).
Mollie en mail staan uit. De klant-app volgt als `visualshopping-client`.

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
