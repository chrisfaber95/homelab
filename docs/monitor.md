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
