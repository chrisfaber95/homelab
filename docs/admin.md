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

Een eigen ntfy-server op `http://homelab:8102`, alleen via het tailnet, iedereen moet inloggen. Op je telefoon:
de ntfy-app → server `http://homelab:8102` toevoegen met je gebruiker (het wachtwoord koos je in `19-admin.sh`) →
abonneren op `homelab`. Tailscale moet op je telefoon aan staan. Je krijgt:

- een nieuwe PR op homelab (met een knop naar de PR);
- een bevestigingsvraag, een teruggedraaide of mislukte wijziging;
- een falende health check na een wijziging;
- om 19:00 het dagoverzicht: wat er veranderde (of niets), wat nog niet in de repo staat, en welke kernel- en
  NVIDIA-updates op jou wachten.

Alleen de box kan publiceren (gebruiker `homelab-bot`, token in `/etc/homelab/ntfy-token`, alleen root); jij kunt
alleen lezen. PR-meldingen gebruiken het token van `deploy`, dat daarvoor ook *Pull requests: Read-only* nodig heeft.

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
