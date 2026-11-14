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

**Als bestand**: de jobs staan in `jobs/<naam>.md` op de branch **`jobs`** van de privé repo
`homelab-data` (`DATA_REPO`; niet in deze repo, die public mag). Die branch
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
kind: box-report                      # box-report | repo-chore | digest | script | repo-watch
model: local                          # local | claude
repo: claude-dashboard                # repo-chore (verplicht), script (standaard homelab)
skills: [deploy-debuggen]             # alleen bij model claude
script: scripts/iets.sh               # alleen bij script
args: [--kort]                        # optioneel, bij script
sources: [https://..., github:chrisfaber95/homelab]   # alleen bij digest
deliver: conversation                # optioneel: page (standaard) | ntfy | conversation; zie "Resultaat bezorgen"
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
  staat in dezelfde rij (`MAX_RUNNING` at once, `MAX_PER_REPO` per repo), heeft dezelfde grens van beurten en tijd,
  eindigt code in een PR (nooit op `main`, nooit gemerged) en doet machinewijzigingen alleen via F-019. Bij een
  box-report of digest krijgt Claude de verzamelde tekst mee; een script draait Claude zelf in zijn werkkopie.

## Daglimiet voor Claude

Jobs mogen per dag (in Europe/Amsterdam) `JOB_CLAUDE_CAP` Claude-runs starten (standaard 10, in `config/inbox.conf`).
Daarboven wacht een Claude-job tot de volgende dag (hooguit één wachtende run per job) en krijg je die dag één melding.
Lokale jobs en je eigen inbox-taken tellen niet mee en wachten nooit. "Nu draaien" telt mee zoals een geplande run.

## Wanneer een job een melding stuurt

Alleen als hij mislukt, een PR opent of bijwerkt, je een vraag stelt, of op een grens stopt (de daglimiet, of de
grens van beurten en tijd). Een gelukt rapport of digest staat standaard alleen op de pagina, in de geschiedenis van
de job (tijd, resultaat, link naar het antwoord of de PR). Wil je het resultaat ook ontvangen, zet dan `deliver`.

## Resultaat bezorgen (`deliver`, H-017)

`deliver` in de kop zegt waar een **gelukt** resultaat heen gaat:

- `page` (standaard, ook als het veld ontbreekt): alleen de pagina. Een job zonder `deliver` gedraagt zich precies als
  voorheen; bestaande bestanden hoeven niet te veranderen.
- `ntfy`: een push met het resultaat (hooguit 4000 tekens; ntfy zelf houdt 4096 bytes aan) en een link naar de run. De
  volledige tekst staat ook in het gesprek.
- `conversation`: een melding in het gesprek (H-016, daar leest Discord), met de volledige tekst en een link naar de
  run. De tekst wordt gefilterd zoals elk bericht in het gesprek (geheimen vervangen).

De link gaat naar `/run/<id>` (lokale job) of `/task/<id>` (Claude-job). Bezorgen komt **erbij**: de meldingen bij een
fout, PR, vraag of limiet blijven zoals ze waren. Een Claude-job bezorgt zijn antwoord (`ANTWOORD:`); eindigt hij in een
PR, dan is dat de PR-melding. Mislukt het bezorgen zelf, dan blijft de run `ok` en komt er een regel in het journal.

**Niets te melden.** Een job met `deliver: ntfy` of `conversation` moet in zijn eigen instructie zeggen wanneer er niets
te melden is, en dan met **`NOTHING TO REPORT`** als eerste regel antwoorden (hoofdletters en leestekens eromheen
tellen niet mee). Zo'n resultaat wordt niet bezorgd; de run en zijn resultaat staan wel in de geschiedenis. Zonder die
marker in de instructie is het bestand ongeldig (de fout staat op de pagina). De regels uit de instructie met de marker
toont de pagina van de job onder "Niets te melden". Het is dus geen verborgen regel in de code.

```
---
when: elke ochtend om 7:30
schedule: "30 7 * * *"
kind: digest
model: local
sources: [github:chrisfaber95/homelab]
deliver: conversation
---
Som op wat er gisteren in de repo gebeurde, één regel per punt. Gebeurde er niets: antwoord alleen met
NOTHING TO REPORT als eerste regel.
```

**Schema en gemiste runs**: een job start binnen een minuut na zijn tijd. Stond de box uit, dan draait een gemiste job
één keer zodra de box weer aan is, niet één keer per gemist moment. Een nieuwe of hervatte job begint bij zijn
volgende tijd.

## Catalogus (H-017)

De tab Jobs toont onder "Catalogus" kant-en-klare agents. Een agent is een gewoon jobbestand (zelfde kop en instructie,
inclusief `deliver` en de `NOTHING TO REPORT`-regel) in **`agents/<naam>.md` in deze repo**; de inbox leest ze uit zijn
kopie van `main` (zoals de skills), dus na een herbouw is de catalogus er weer. Het bestand mag geen privébronnen of
geheimen bevatten (de repo is public). Elke regel toont wat de agent doet (de eerste alinea van de instructie), het
schema, het model en waar hij bezorgt. Een kapot bestand verschijnt als regel met zijn fout; de andere agents blijven
zichtbaar.

**Aanzetten** maakt alleen een concept: je ziet het jobbestand (met `name:` erin), past schema, bronnen of repos aan en
kiest **Bevestigen**, net als bij een opgesteld concept. Pas dan staat de job op de `jobs`-branch; ervoor draait en
bestaat er niets. Daarna is het een gewone job (pauzeren, wijzigen, nu draaien, verwijderen) en toont de catalogus hem
als "staat aan" (een job met dezelfde naam als de agent). Verwijder je de job, dan kun je de agent weer aanzetten.
Een agent met een lege plek (bijvoorbeeld de bronnen van een digest) hoort die in het concept te laten invullen:
verzin geen lijst; het concept toont de fout tot jij hem invult. De eerste agents (alle drie lokaal en `deliver: conversation`, elk met zijn eigen "niets te melden"):

| Agent | Wanneer | Doet | Niets te melden |
|---|---|---|---|
| `morning-digest` | elke ochtend 7:30 | de vijf interessantste items uit je bronnen | geen nieuw item, of geen enkele bron laadde |
| `box-report` | werkdagen 7:00 | de staat van de box in hooguit acht regels | geen check, deploy of backup mislukt of achterstallig |
| `repo-watcher` | elke ochtend 8:00 | nieuwe commits, PR's en releases van je repo's | geen enkele activiteit sinds de dag van de vorige run |

`morning-digest` en `repo-watcher` leveren `sources: []`: het bestand is dan geldig voor de catalogus, maar het concept
toont "sources nodig" tot jij de bronnen (RSS of pagina's) of repo's (`github:eigenaar/repo`) invult. Er is geen
verzonnen lijst; de bronnen van de digest zijn jouw keuze.

**repo-watch** is een eigen soort: `sources` bevat alleen `github:eigenaar/repo`. Per repo haalt de run de commits,
PR's (nieuw of bijgewerkt: open, gemerged of gesloten) en releases (geen concepten) op sinds het begin van de dag (in
de tijdzone van de inbox) van de vorige geslaagde run van die job; zonder vorige run sinds het begin van gisteren, nooit
verder dan 31 dagen terug. Dat is het enige geheugen. Heeft een repo niets, dan staat er "geen activiteit"; staat dat
overal, dan antwoordt de job `NOTHING TO REPORT` en wordt er niets bezorgd. De PR-regels tellen ook PR's die alleen een
reactie kregen (`updated_at`). Code: `watch_repo`, `since_day` en `previous_run_time` in `jobs.py`; tests in
`tests/test_inbox_agents.py`. Voorbeeld van een kaal agentbestand: `agents/box-report.md`.

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
- Instellingen in `config/inbox.conf`: `DATA_REPO`, `JOBS_BRANCH`, `JOB_TIMEZONE`, `JOB_CLAUDE_CAP`, `JOB_SCRIPT_MINUTES` (PR, daarna
  `scripts/21-inbox.sh`).
- De geschiedenis van runs staat in `/var/lib/homelab-inbox/inbox.db` (naast de taken).
