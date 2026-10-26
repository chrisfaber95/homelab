# Jobs en skills (F-022)

Terugkerend werk dat de box zelf doet, op een schema: **`http://homelab.tail4eb878.ts.net:8105/jobs`** (de inbox-pagina,
tab Jobs). Kleine tekstjobs draaien op het lokale model en kosten niets; jobs die tools, code of een repo nodig hebben,
draaien op Claude, met dezelfde regels als een inbox-taak.

## Een job maken

**Op de pagina**: typ wat je wilt, bijvoorbeeld "elke werkdag om 7:00, vertel me wat er vannacht misging". Claude stelt
het job-bestand op (zonder tools); je ziet het schema in woorden, de volgende drie keren, de soort, het model, de repo en
de skills. Pas het bestand zo nodig aan en kies **Bevestigen**: de job staat er meteen en draait vanaf zijn volgende
tijd. Wijzigen (in woorden of in het bestand), pauzeren, hervatten, verwijderen en **Nu draaien** gaan op de pagina van
de job.

**Als bestand**: de jobs staan in `jobs/<naam>.md` op de branch **`jobs`** van deze repo (niet op `main`). Die branch
wordt nooit gemerged; de inbox leest hem elke minuut en slaat er alles op wat je op de pagina bevestigt, als commit
("jobs: ... (bevestigd op de pagina vanaf <apparaat>)"). Een bestand met de hand bewerken kan op GitHub of in een clone,
op die branch. Een bestand met een fout draait niet; de fout staat op de pagina. Na een herbouw haalt de inbox de branch
gewoon weer op, dus alle jobs komen terug.

Alleen jij zet een job aan: Claude kan niet naar `jobs` pushen (in de inbox heeft Claude geen GitHub-toegang; in
Claudes eigen sessies houdt de pre-push-hook het tegen, net als bij `main`).

```
---
when: elke werkdag om 7:00            # in woorden, voor jou
schedule: "0 7 * * 1-5"               # cron: minuut uur dag maand weekdag (0 = zondag), tijdzone Europe/Amsterdam
kind: box-report                      # box-report | repo-chore | digest | script
model: local                          # local | claude
repo: claude-dashboard                # repo-chore (verplicht), script (standaard homelab)
skills: [deploy-debuggen]             # alleen bij model claude
script: scripts/iets.sh               # alleen bij script
args: [--kort]                        # optioneel, bij script
sources: [https://..., github:chrisfaber95/homelab]   # alleen bij digest
minutes: 10                           # hoeveel minuten handwerk een run je bespaart (voor F-024)
paused: true                          # optioneel
---
De instructie: wat de job doet en hoe het resultaat eruitziet.
```

Een schema met dag én weekdag (bijvoorbeeld `0 9 1 * 1`) draait zoals in cron als één van de twee klopt.

## De vier soorten

**box-report**: een rapport uit de staat van de box (de uitvoer van `homelab-overview`: checks, deploys, LLM-gebruik,
wijzigingen, backup).

```
---
when: elke werkdag om 7:00
schedule: "0 7 * * 1-5"
kind: box-report
model: local
minutes: 5
---
Meld alleen wat faalde of misging: per probleem één regel met wat en sinds wanneer, in hooguit vijf regels. Niets mis:
zeg dat in één zin.
```

**repo-chore**: een klus in een repo die eindigt in een PR (altijd Claude). Is er niets te doen, dan eindigt de run met
een antwoord en zonder PR.

```
---
when: elke zondag om 10:00
schedule: "0 10 * * 0"
kind: repo-chore
model: claude
repo: claude-dashboard
minutes: 20
---
Werk de npm-afhankelijkheden bij binnen hun huidige major-versie, draai de tests en open een PR als alles slaagt.
Noem in de samenvatting welke pakketten veranderden.
```

**digest**: bronnen ophalen en samenvatten. Een bron is een RSS- of Atom-feed, een webpagina (alleen de tekst), of
`github:eigenaar/repo` (commits en PR's van de laatste 24 uur, via het token van de inbox).

```
---
when: elke ochtend om 7:30
schedule: "30 7 * * *"
kind: digest
model: local
sources: [https://hnrss.org/frontpage, github:chrisfaber95/claude-dashboard]
minutes: 10
---
Geef de vijf interessantste onderwerpen in één regel elk, en wat er gisteren in claude-dashboard gebeurde.
```

**script**: een script uit de homelab-repo of een repo uit de F-018-lijst draaien en de uitvoer samenvatten. Het draait
in een verse clone van `main`, als het account `inbox` (geen root, geen GitHub-toegang), hooguit `JOB_SCRIPT_MINUTES`
minuten. Uitvoerbaar bestand: zo gestart; anders met `bash`.

```
---
when: elke maandag om 8:00
schedule: "0 8 * * 1"
kind: script
model: local
script: scripts/13-health-check.sh
minutes: 3
---
Vat de uitvoer samen: welke checks falen er, en waarom.
```

## Lokaal of Claude

Claude stelt het model voor, en jij kunt het vóór het bevestigen veranderen:

- **local**: de job vat alleen tekst samen of haalt er iets uit, en die tekst verzamelt de job zelf (box-report, digest,
  script). Gratis; geen Claude-call. Elke call staat in het F-017-gebruikslog met caller `job:<naam>` (`llm usage`).
  Het model ziet hooguit ongeveer 9.000 tekens; de rest wordt afgekapt. Het is een klein model: geef één duidelijke
  opdracht en de vorm van het antwoord.
- **claude**: alles wat tools, code of een repo nodig heeft (een repo-chore altijd). De job wordt een inbox-taak: hij
  staat in dezelfde rij (hooguit twee tegelijk, nooit twee op dezelfde repo), heeft dezelfde grens van beurten en tijd,
  eindigt code in een PR (nooit op `main`, nooit gemerged) en doet machinewijzigingen alleen via F-019. Bij een
  box-report of digest krijgt Claude de verzamelde tekst mee; een script draait Claude zelf in zijn werkkopie.

## Daglimiet voor Claude

Jobs mogen per dag (in Europe/Amsterdam) `JOB_CLAUDE_CAP` Claude-runs starten (standaard 10, in `config/inbox.conf`).
Daarboven wacht een Claude-job tot de volgende dag (hooguit één wachtende run per job) en krijg je die dag één melding.
Lokale jobs en je eigen inbox-taken tellen niet mee en wachten nooit. "Nu draaien" telt mee zoals een geplande run.

## Wanneer een job een melding stuurt

Alleen als hij mislukt, een PR opent of bijwerkt, je een vraag stelt, of op een grens stopt (de daglimiet, of de
grens van beurten en tijd). Al het andere, ook een gelukt rapport of digest, staat alleen op de pagina, in de
geschiedenis van de job (tijd, resultaat, link naar het antwoord of de PR).

**Schema en gemiste runs**: een job start binnen een minuut na zijn tijd. Stond de box uit, dan draait een gemiste job
één keer zodra de box weer aan is, niet één keer per gemist moment. Een nieuwe of hervatte job begint bij zijn
volgende tijd.

## Skills

Een skill is een vaste manier van werken die Claude hergebruikt: `skills/<naam>/SKILL.md` op `main` (het formaat van
Claude Code: kop met `name` en `description`, daaronder de instructies). Voor elke inbox-taak en Claude-job zet de inbox
alle gemergde skills klaar (in `~/.claude/skills` van `inbox`). Een job kan skills noemen (`skills:`); die gebruikt
Claude dan zeker.

**Voorstellen**: na een gelukte taak of Claude-job kan Claude voorstellen zijn aanpak als skill te bewaren. De inbox maakt
daar een PR van in deze repo (branch `skill/<naam>-<taak>`) en je krijgt een melding. Pas na jouw merge wordt hij
gebruikt; Claude kan hem niet mergen. Een gemergde skill verandert Claude nooit zelf: een verbetering komt als nieuwe PR
onder dezelfde naam. Zelf een skill schrijven of aanpassen kan ook, gewoon met een PR.

De tab **Skills** toont elke gemergde skill met zijn beschrijving en de jobs die hem gebruiken, en de voorstellen met
hun PR.

## Beheer

- `homelab-inbox jobs`: de jobs met model, schema, volgende run en laatste resultaat.
- Instellingen in `config/inbox.conf`: `JOBS_BRANCH`, `JOB_TIMEZONE`, `JOB_CLAUDE_CAP`, `JOB_SCRIPT_MINUTES` (PR, daarna
  `scripts/21-inbox.sh`).
- De geschiedenis van runs staat in `/var/lib/homelab-inbox/inbox.db` (naast de taken).
