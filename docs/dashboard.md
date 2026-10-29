# Homelab-pagina (F-024)

Eén pagina voor alles: **`http://homelab.tail4eb878.ts.net:8105`** (Tailscale aan). Het overzicht bovenaan zegt in één
zin of alles in orde is, wat er nu draait en welke job hierna komt. De tabs **Taken** (F-021), **Jobs** (F-022),
**Geheugen** (F-023), **Skills** en **SSH** (een SSH-sleutel aanvragen, `docs/admin.md`) zitten op dezelfde pagina. Het oude overzicht op poort 8104 verwijst hierheen.

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
wijzigingen. Stijl en script: `web/app.css` en `web/app.js`.

## Beheer

Alles zit in de dienst `homelab-inbox`; na een wijziging aan `bin/homelab-inbox`, `web/` of de config:
`sudo bash /var/lib/homelab/repo/scripts/21-inbox.sh`. Logs van taken en runs staan in
`/var/lib/homelab-inbox/logs/` (één bestand per taak of run). Testen zonder root: zie de docstring van
`bin/homelab-inbox`, plus `MONITOR_DIR`, `DEPLOY_STATE` en `BACKUP_STATUS` voor de gezondheid.
