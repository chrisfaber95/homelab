# Bewaking, overzicht en backups (F-020)

## Wat er bewaakt wordt

`homelab-monitor check` draait elke 2 minuten (systemd-timer `homelab-monitor.timer`) en voert `homelab-health` uit:

| Check | Wat |
| --- | --- |
| `gpu`, `nvidia-mod` | driver en GPU zoals in `config/health.conf`; NVIDIA-modules voor elke geïnstalleerde kernel |
| `wired`, `route`, `dns`, `internet`, `tailscale`, `ssh` | netwerk |
| `disk`, `swap` | rootfilesystem (waarschuwen vanaf 80%, falen vanaf 90%), swap |
| `reboot` | een reboot wacht |
| `llm` | de LLM-helper (F-017) antwoordt |
| `deploy` | de sync-lus van F-018 loopt en geen sync of deploy faalt |
| `svc:<naam>` | elke gedeployde service of compose-repo draait (compose: alle containers `running`, niet `unhealthy`) |
| `backup`, `backup-disk` | de laatste geslaagde backup is jonger dan 26 uur; de backupmap bestaat en heeft minstens 10% vrij |

## Meldingen

Via ntfy (topic `homelab`, zie `docs/admin.md`):

- een check die **twee rondes achter elkaar** (4 minuten) WARN of FAIL is: één melding met de naam van de check en
  wat er mis is; een korte hapering van één ronde geeft niets;
- zodra hij weer OK is: één melding "weer in orde";
- daartussen geen herhaling; het dagoverzicht van 19:00 noemt elke check die niet in orde is, met sinds wanneer.

**Een melding stilzetten** kan niet per check; los de oorzaak op, of zet de drempel in `config/health.conf` of
`config/backup.conf` (via een PR). Wil je even niets: in de ntfy-app het topic dempen.

**Box uit of offline** (nog niet ingericht): de box pingt na elke check de URL in `/etc/homelab/heartbeat-url`, als
die er is. Maak bijvoorbeeld bij healthchecks.io een check met periode 5 minuten en grace 8 minuten, laat die melden
(e-mail, of ntfy.sh), en zet de ping-URL in dat bestand:
`echo 'https://hc-ping.com/<uuid>' | sudo install -m 600 /dev/stdin /etc/homelab/heartbeat-url`.

## Overzicht

```
homelab-overview
```

Per check de status en sinds wanneer; per repo de draaiende commit, de laatste sync en deploy; LLM-gebruik van de
laatste 7 dagen per caller en job; de 5 laatste machinewijzigingen; de laatste backup en de vrije ruimte.

Dezelfde gegevens staan op de homelab-pagina, **`http://homelab.tail4eb878.ts.net:8105`** (F-024,
`docs/dashboard.md`), onder Gezondheid: alleen lezen, hooguit 2 minuten oud, zonder geheimen. De oude pagina op 8104
verwijst daarheen.

## Backups

`homelab-backup run` draait elke nacht om 03:30 (`homelab-backup.timer`; staat de box dan uit, dan bij de volgende
start). Eén `homelab-<datum>.tar.gz` in `BACKUP_DIR`, 14 dagen bewaard (de nieuwste blijft altijd). Map en bestanden
zijn alleen voor root: Claude kan ze niet lezen.

**Wat erin zit** (`config/backup.list`): de Tailscale-identiteit, de SSH-hostkeys, de logins van Claude Code en `gh`
(en Claudes geheugen), de tokens in `/etc/homelab` (deploy, ntfy, heartbeat), de ntfy-gebruikers, de gegevens van de
claude-dashboard (gekoppelde apparaten), het LLM-gebruikslog, het logboek van machinewijzigingen en de staat van
`homelab-admin`. SQLite-bestanden worden met de backup-API gekopieerd, zodat ze heel zijn.
Also the Obsidian second-brain vault (`/srv/obsidian/second-brain`, with its git history). After a restore, link its
nightly timer again as `claude`: see "Nightly sync" in the vault's `CLAUDE.md`. The code map (`/srv/obsidian/code-map`)
is not backed up: the vault's `bin/code-map.sh` rebuilds it.

Also the PostgreSQL database `inbox` (H-023, `scripts/28-inbox-db.sh`): the line `pg_dump: inbox` in `config/backup.list`
makes `homelab-backup run` write `homelab-<date>.inbox.dump` next to the tar.gz: `pg_dump --format=custom`, run as the
`postgres` user over the unix socket (there is no TCP listener), first written to `.homelab-<date>.inbox.dump.tmp`
(0600) and then renamed, so a half-written dump never carries the final name. Same folder, same schedule (03:30),
same mode (`0600`, folder `0700`) and same retention as the tar.gz (14 days, the newest dump always stays). If the
database does not exist yet it is listed under `missing` in the status; a failing `pg_dump` fails the whole run, so
the health check notices. `homelab-backup list` shows the dumps too.

**Wat er niet in zit**: alles wat de repo herbouwt (programma's, configuratie, diensten), de Ollama-modellen (opnieuw
downloaden), Docker-images en -volumes, en dev-clones (wat telt, is gepusht).

**Waar**: voorlopig `/var/backups/homelab` op de SSD zelf (`BACKUP_DIR` in `config/backup.conf`). Dat beschermt
tegen een vergissing of een kapotte installatie, **niet tegen een kapotte SSD**. Naar de USB-HDD: de schijf mounten
(root, met `nofail`), `BACKUP_DIR` aanpassen via een PR, en `scripts/20-monitor.sh` opnieuw draaien.

```
sudo homelab-backup list        # de backups en de laatste run
sudo homelab-backup run         # nu een backup
```

## Terugzetten na een herbouw

1. Kopieer een backup van de oude schijf, of van de USB-HDD, naar de nieuwe box (bijvoorbeeld met `scp` via het
   LAN naar je eigen map).
2. Volg de checklist en de scripts tot en met `scripts/20-monitor.sh`. Inloggen bij Tailscale mag; de oude
   identiteit vervangt de nieuwe.
3. `sudo homelab-backup restore <bestand>`: stopt Tailscale, ntfy, de LLM-helper, homelab-admin en de dashboard,
   zet de bestanden terug (met eigenaar en rechten), start ze weer en herstart `ssh` (voor de oude hostkeys).
4. Controle: `tailscale status` toont de box als `homelab` met het oude adres (haal een dubbele `homelab-1` weg in
   de Tailscale-admin); `ssh homelab` vanaf Windows geeft geen hostkey-waarschuwing; `claude` en `gh auth status` zijn ingelogd; `llm usage` en `homelab-admin changes` tonen de
   oude geschiedenis; `homelab-health` slaagt.

Het is een gewone tar.gz: zonder het script kan het ook met `sudo tar -xzpf <bestand> -C /` (diensten eerst stoppen).

## Restoring the inbox database (H-023)

The `.dump` is not part of `homelab-backup restore` (that only unpacks the tar). Restore it by hand with
`pg_restore`, always into a **fresh** database first, so a bad dump never replaces a working one. As root:

```
D=/var/backups/homelab/homelab-<date>.inbox.dump
sudo runuser -u postgres -- pg_restore --list "$D" | head          # the dump is readable, lists 9+ tables
sudo runuser -u postgres -- createdb -O inbox_app inbox_restore_test
sudo runuser -u postgres -- pg_restore --dbname inbox_restore_test --no-owner --role inbox_app --exit-on-error "$D"
```

(`/var/backups/homelab` is 0700 root: if `postgres` cannot read the file, use `sudo cp "$D" /tmp/x.dump && sudo chown postgres /tmp/x.dump`,
or pipe it: `sudo cat "$D" | sudo runuser -u postgres -- pg_restore --dbname inbox_restore_test --no-owner --role inbox_app --exit-on-error`.)

Compare with the live database, then drop the test database:

```
sudo runuser -u postgres -- psql -d inbox_restore_test -c "select relname, n_live_tup from pg_stat_user_tables order by 1"
sudo runuser -u postgres -- psql -d inbox -c "select relname, n_live_tup from pg_stat_user_tables order by 1"
sudo runuser -u postgres -- dropdb inbox_restore_test
```

**Real restore after a rebuild**: run `scripts/28-inbox-db.sh` (empty `inbox` and role), stop the backend, then
`pg_restore --clean --if-exists --no-owner --role inbox_app --dbname inbox "$D"` as `postgres`, start the backend again.
Flyway sees its own history table in the dump and does not run `V1` twice.

### Restore test checklist (once, on the box)

- [ ] `sudo homelab-backup run` creates `homelab-<date>.inbox.dump` (mode `-rw-------`, no `.tmp` file left) and the status says `ok`.
- [ ] `pg_restore --list` on it works.
- [ ] Restore into `inbox_restore_test` finishes without errors.
- [ ] Table list and row counts in `inbox_restore_test` match `inbox` (the 9 tables of `V1__baseline.sql`, once B4/B5 have run).
- [ ] `inbox_restore_test` is dropped again (`\l` shows no leftovers).

Result of the test (fill in, date and who):

| Date | By | Dump file | Result |
| --- | --- | --- | --- |
| _not tested yet_ | | | |
