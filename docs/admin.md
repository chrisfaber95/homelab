# Machinewijzigingen door Claude (F-019)

Claude heeft op de box geen sudo en geen root-shell. Machinewijzigingen gaan langs twee wegen, allebei via de
root-dienst `homelab-admin`:

```
Claude ──homelab-admin <actie> --reason──> /run/homelab-admin.sock ──> homelab-admin (root)   direct pad: vaste lijst
Claude ──branch + PR: changes/NNNN-naam/apply.sh──> GitHub ──jij merget──> main
                                                                │  elke 30 s: fetch naar /var/lib/homelab/repo (van root)
                                                                ▼
                                                  homelab-admin voert apply.sh uit ── vangnet ── health check ── ntfy
```

## Direct pad (zonder te vragen)

| Actie | Wat mag | Wat niet |
| --- | --- | --- |
| `service start\|stop\|restart <unit>` | elke bestaande `.service` | `ssh`, `tailscaled`, `ufw`, netwerk (`systemd-networkd`, `systemd-resolved`, `NetworkManager`), `dbus`, `polkit`, `systemd-logind`, `user@…`, `homelab-admin`, `ntfy` |
| `package install <naam>…` | pakketten (en hun afhankelijkheden) uit Ubuntu's eigen repo's | andere bronnen (Docker, NodeSource), kernel en NVIDIA, lokale `.deb`, opties, iets dat apt zou verwijderen |
| `port open\|close <poort>[/tcp\|udp]` | openen op `tailscale0`; sluiten wat `homelab-admin` zelf opende | poort 22, regels voor het LAN of andere interfaces, regels die niet van `homelab-admin` zijn |
| `security-updates` | openstaande updates uit `<release>-security` | kernel en NVIDIA (die worden overgeslagen en gemeld) |

Elke actie heeft een reden nodig (`--reason "..."`); die gaat met tijd, gebruiker, actie en resultaat naar het
logboek. Daarna draait `homelab-health`; faalt die, dan krijg je een melding. Een directe actie wordt bij een falende
health check niet teruggedraaid (een pakket terugdraaien is rommelig); je krijgt alleen de melding.

Waarom deze lijst: dit zijn de kleine dingen die anders op jou wachten, en geen ervan kan je buitensluiten. SSH,
Tailscale, de firewall buiten het tailnet en het netwerk staan er bewust niet op.

**Vastleggen.** Een pakket of poort dat blijvend nodig is, hoort in `config/machine.conf`
(`package <naam>`, `port <poort>/<proto>`). `homelab-admin` legt alles daaruit aan, ook na een herbouw. `homelab-admin
status` meldt elke directe wijziging die er nog niet in staat.

**Een actie toevoegen of weghalen**: in `bin/homelab-admin` (`instant()` en de functie van de actie, plus de
beschermde lijsten bovenaan), via een PR; daarna `sudo bash /var/lib/homelab/repo/scripts/19-admin.sh`.

## Goedkeuringspad (via een PR)

Alles wat niet op de lijst staat: firewallregels buiten tailnet-poorten, nieuwe of gewijzigde systemd-units,
netplan, DNS, Tailscale-instellingen, pakketten verwijderen.

1. Claude maakt `changes/<NNNN-naam>/apply.sh` (root, idempotent, `set -euo pipefail`; de eerste commentaarregel is
   de samenvatting), eventueel met `revert.sh` voor wat het vangnet niet zelf terugzet, en opent een PR.
2. Binnen een minuut krijg je een melding met de titel en een knop naar de PR.
3. **Mergen = goedkeuren.** Binnen een minuut haalt `homelab-admin` main op in zijn eigen clone
   (`/var/lib/homelab/repo`, alleen root kan die wijzigen) en voert hij `apply.sh` uit.
4. Elke wijziging wordt één keer uitgevoerd. Een mislukte wijziging gaat pas opnieuw als `apply.sh` verandert.

## Install scripts after a merge

A merge that changes an install script, or a file it installs, is also the approval to run that script again. Each
script in `scripts/` names its account and inputs in a header line:

```
# homelab-rerun: root bin/homelab-inbox bin/homelab-inbox-task ... web config/inbox.conf ...
```

- **`root`** or **`claude`**: once the new main is in root's clone, `homelab-admin` runs the script as that account
  (root with `SUDO_USER=chris`; `claude` via `runuser` with its user session). It's the same flow as a change: a
  snapshot first, the [safety net](#vangnet) if the firewall, network or SSH changed, then `homelab-health` and a
  notification ("ran", "mislukt" or "Bevestig"). A failed script only runs again when its inputs change again.
- **`manual`**: only a notification with the command to run yourself. These scripts are `05-09-base.sh` (SSH and
  firewall), `19-admin.sh` (it restarts `homelab-admin` itself) and `22-claude-chris.sh`. `19-admin.sh` only asks when the script
  itself changes: `20-monitor.sh` and `21-inbox.sh` already install `bin/homelab-admin` and its config, and
  `homelab-admin` then restarts itself.
- No header line: never run automatically.

| Script | Runs as | Inputs (besides the script) |
| --- | --- | --- |
| `13-health-check.sh` | root | `bin/homelab-health`, `config/health.conf` |
| `17-llm.sh` | root | `bin/llm`, `llm/`, `config/llm.conf` |
| `18-projects.sh` | root | `bin/homelab-deploy`, `config/deploy.conf`, `deploy/` (new or changed units) |
| `18-claude.sh` | claude | `claude/`, `config/claude.conf` |
| `20-monitor.sh` | root | the `bin/` and `config/` files it installs |
| `21-inbox.sh` | root | the `bin/`, `web/` and `config/` files it installs |
| `05-09-base.sh`, `19-admin.sh`, `22-claude-chris.sh` | manual | |

Scripts run one at a time, in name order, never while a confirmation is open. Prompts for a missing token or password
can't be answered (there is no terminal), so that run fails with a notification: run the script yourself once. The
first time `homelab-admin` sees a script, it only remembers its state and assumes you ran it. Results:
`homelab-admin changes` (kind `script`); `homelab-admin status` lists failed scripts.

Adding a script: give it the header line and list every repo path it copies. A forgotten path means a merge of that
file doesn't reach the box. `homelab-admin` runs scripts with `HOMELAB_RERUN=1`, so a script must never restart
`homelab-admin` when that is set: the restart would kill the run. `homelab-admin` restarts itself once the run has been
recorded, if the script replaced its program file. A run that stops anyway counts as failed ("interrupted") and isn't
retried in a loop.

**Review accordingly:** after a merge, changes to `bin/`, `web/`, `deploy/`, `claude/` and `config/` are live within
a minute, just like `changes/`.

## Vangnet

Vóór elke goedgekeurde wijziging bewaart de box `/etc/ufw`, `/etc/netplan`, `/etc/ssh`, `/etc/systemd/resolved.conf(.d)`
en `/etc/systemd/network`. Is daar na `apply.sh` iets anders:

- De box kijkt zelf of Tailscale verbonden is, SSH luistert en de SSH-regel op `tailscale0` er nog is. Zo niet:
  meteen terug.
- Anders krijg je een melding: **binnen 5 minuten bevestigen**. Dat kan met de knop *Bevestig* in de melding (je
  telefoon maakt dan zelf een verbinding met de box over het tailnet, dus dat bewijst dat hij bereikbaar is) of via SSH
  met `sudo homelab-admin confirm <id>`. Bevestig alleen als SSH naar `homelab` echt werkt.
- Geen bevestiging op tijd: de bewaarde bestanden gaan terug, ufw, netplan, resolved of ssh worden herladen (alleen
  wat veranderd was), `revert.sh` draait, en je krijgt een melding.

Claude kan niet bevestigen: de socket weigert het voor `claude`, en de knop werkt niet vanaf de box zelf en vraagt een
sleutel die alleen in de melding staat.

Tailscale-instellingen (`tailscale set ...`) staan niet in die bestanden: zet de omkering in `revert.sh`.

## Meldingen (ntfy)

Een eigen ntfy-server op `http://homelab.tail4eb878.ts.net:8102`, alleen via het tailnet, iedereen moet inloggen. Op
je telefoon: de ntfy-app → server `http://homelab.tail4eb878.ts.net:8102` toevoegen (precies zo: op een iPhone moet
hij gelijk zijn aan `base-url` van de server) met je gebruiker (het wachtwoord koos je in `19-admin.sh`) →
abonneren op `homelab`. Tailscale moet op je telefoon aan staan. Je krijgt:

- een nieuwe PR op homelab (met een knop naar de PR);
- een bevestigingsvraag, een teruggedraaide of mislukte wijziging;
- een falende health check na een wijziging;
- om 19:00 het dagoverzicht: wat er veranderde (of niets), wat nog niet in de repo staat, en welke kernel- en
  NVIDIA-updates op jou wachten.

Alleen de box kan publiceren (gebruiker `homelab-bot`, token in `/etc/homelab/ntfy-token`, alleen root); jij kunt
alleen lezen. PR-meldingen gebruiken het token van `deploy`, dat daarvoor ook *Pull requests: Read-only* nodig heeft.

## SSH-sleutels via de homelab-pagina

Een nieuw apparaat laten inloggen als `chris` (`SSH_KEY_USER` in `config/admin.conf`), zonder eerst ergens anders in te
loggen: tab **SSH** op `http://homelab.tail4eb878.ts.net:8105/ssh`.

```
pagina (inbox-runner, alleen jouw apparaten) ──sshkey──> homelab-admin (root) ── melding met vingerafdruk ──> telefoon
                                                                    ▲                                         │
                                                                    └─────────── Bevestig (binnen 5 min) ─────┘
                                                                    └─> ~chris/.ssh/authorized_keys (aanvullen)
```

1. Plak de **publieke** sleutel (de inhoud van `~/.ssh/id_ed25519.pub`) en geef hem een naam (het apparaat).
2. `homelab-admin` controleert hem met `ssh-keygen`: precies één sleutel, geen opties (`command=`, `from=`, ...), geen
   privésleutel, RSA minstens 2048 bits. Er gebeurt nog niets met `authorized_keys`.
3. Je krijgt een melding met de naam, de vingerafdruk en het apparaat waarvandaan het kwam. Vergelijk de vingerafdruk
   met `ssh-keygen -lf ~/.ssh/id_ed25519.pub` en druk op **Bevestig** (of via SSH: `sudo homelab-admin confirm key-...`).
4. Pas dan komt de sleutel erbij, met commentaar `<naam> (homelab-pagina <datum>)`; `.ssh` 700, het bestand 600, van
   `chris`. Niet bevestigd binnen `CONFIRM_SECONDS`: de aanvraag vervalt en er verandert niets.

Waarom zo: een sleutel bij `chris` is sudo, dus root. Alleen het account `inbox-runner` (de pagina, die met
`tailscale whois` controleert dat het van jouw apparaten komt) mag een sleutel aanvragen; `claude` en `inbox` worden
geweigerd (en gelogd). Bevestigen kan alleen met de knop, die niet werkt vanaf de box zelf en een sleutel vraagt die
alleen in de melding staat, of met sudo. Claude kan zich dus geen toegang geven, ook niet via de pagina; en een
gestolen laptop op je tailnet kan het niet zonder je telefoon. Hooguit 3 aanvragen tegelijk.

Weghalen doe je zelf in `~chris/.ssh/authorized_keys` (sleutels van de pagina hebben "homelab-pagina" in hun
commentaar). Het bestand zit in de dagelijkse backup (`config/backup.list`). Alles staat in het logboek
(`homelab-admin changes`, soort `sshkey`) en onderaan de tab SSH.

## Inzien

```
homelab-admin changes -n 30    # alles, nieuwste eerst: direct, goedgekeurd, bevestigd, teruggedraaid, geweigerd
homelab-admin status           # wacht er iets op bevestiging, wat mislukte, wat staat nog niet in de repo
```

Het logboek: `/var/log/homelab/changes.jsonl` (één JSON-regel per gebeurtenis).

## Kernel en NVIDIA

`changes/0001` zet kernel- en NVIDIA-pakketten op de blacklist van unattended-upgrades; ook `security-updates` slaat
ze over. Het dagoverzicht meldt welke wachten. Installeer ze zelf (`sudo apt upgrade`, dan `sudo reboot`) en kijk
daarna met `homelab-health` of de GPU er nog is.

## Bewuste afwijking van de brief

De brief vraagt dat Claude niet naar `main` kan pushen of mergen. Claudes GitHub-login kan dat technisch wel (scope
`repo`; zonder GitHub Pro kan een privé-repo `main` niet afschermen). Jouw keuze (2026-10-25): het blijft een
afspraak (CLAUDE.md) plus de pre-push-hook. Een merge naar `main` van homelab betekent root-acties op de box; let bij
het reviewen dus op `changes/` en `scripts/`.

Since 2026-10-30 (your choice): Claude merges its own PRs on the product repos itself (the repos in
`config/repos.conf` except homelab; rules in `claude/CLAUDE.md`). homelab itself stays merge-by-you: a homelab merge
runs scripts as root.
