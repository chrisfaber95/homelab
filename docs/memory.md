# Geheugen (F-023)

Claude in de inbox onthoudt hoe jij dingen wilt, wat er op de box en in je repo's draait, en wat eerdere taken deden.
Alles staat als leesbare bestanden in deze repo en op de pagina: **`http://homelab.tail4eb878.ts.net:8105/memory`**
(tab Geheugen). Niets wordt achter je rug onthouden: de eigen auto memory van Claude Code staat voor de inbox uit.

## De drie soorten

| Soort | Waar | Wat |
| --- | --- | --- |
| voorkeur | `voorkeuren/<over>/<naam>.md` | hoe jij dingen wilt ("kleine PR's", "antwoord in korte regels"); `<over>` is `algemeen` of een repo |
| feit | `feiten/<over>/<naam>.md` | iets over de box of een repo (een dienst, een build- of deploystap); `<over>` is `box` of een repo |
| geschiedenis | `geschiedenis/<jjjj-mm>/taak-<id>.md` | één per afgeronde inbox-taak of Claude-job: wat er gevraagd werd, het resultaat, de status en de links |

Elk bestand begint met een kop: `over`, `toegevoegd`, `gewijzigd`, `bron` (de taak, job of het PR-commentaar waar het
uit komt, of "door jou toegevoegd") en `link`; een geschiedenis-item ook `status`, `pr` en `job`. Daaronder de tekst.

## Waar het staat

Op de branch **`memory`** van deze repo (niet op `main`; hij wordt nooit gemerged). Alleen de inbox schrijft erop,
met zijn eigen token, voor Claude en voor jou. Elke wijziging is een commit die zegt wie (auteur `Claude
(homelab-inbox)`, `homelab-inbox` voor de geschiedenis, of jij "via de pagina") en waarom (de taak, het commentaar, of
"door jou op de pagina vanaf <apparaat>"). Claude zelf kan niet naar `memory` pushen: in de inbox heeft Claude geen
GitHub-toegang, en in Claudes eigen sessies houdt de pre-push-hook het tegen. Na een herbouw haalt de inbox de branch
gewoon weer op: het geheugen is dan compleet terug.

## Hoe Claude het gebruikt

- **Bij elke inbox-taak en Claude-job** staan in de systeemprompt: alle voorkeuren voor `algemeen` en voor de repo van
  de taak, alle feiten over `box` en over die repo, en de laatste `MEMORY_RECENT` geschiedenis-items van die repo
  (titel, datum, link). De hele geschiedenis staat als map naast de taak; Claude doorzoekt hem met grep. Vraag je naar
  iets van eerder ("wanneer faalde de deploy het laatst?"), dan antwoordt Claude met de datum en de link van die taak.
- **Lokale jobs** (het lokale model) en de F-017-helper gebruiken geen geheugen.
- Het geheugen wordt vlak voor elke ronde opgehaald: wat jij wist of verandert, geldt meteen voor de volgende taak.

## Hoe Claude leert

Onder zijn laatste bericht kan Claude items bewaren, met een blok per item:

```
GEHEUGEN: voorkeur algemeen kleine-prs
Houd PR's klein: één onderwerp per PR.
GEHEUGEN: feit claude-dashboard deploy
De deploy draait npm ci en dan docker compose up.
```

- Een **voorkeur** als je Claude corrigeert in review-commentaar op een PR van de inbox of een job, of in een antwoord
  op zijn vraag, en dat ook voor later geldt. De bron is dan dat commentaar (met link) of je antwoord.
- Een **feit** als een taak iets blijvends ontdekt. Bestaat er al een item met die naam, dan wordt het bijgewerkt (de
  datum van toevoegen blijft) in plaats van dat er een tweede bijkomt.
- De inbox controleert elk blok: onderwerp `algemeen`/`box` of een repo uit de lijst, hooguit 1500 tekens, en **nooit
  iets wat op een token, wachtwoord of sleutel lijkt**. Wat niet klopt, wordt niet bewaard; de reden staat bij de taak.
- De **geschiedenis** schrijft de inbox zelf na elke afgeronde ronde, en werkt hem bij als de PR gemerged of gesloten
  wordt. Iets wat op een geheim lijkt, wordt daarin vervangen door `[verborgen]`. Wis je het item van een taak, dan komt
  het niet terug.

## Bekijken en bewerken

**Op de pagina** (tab Geheugen): alles per soort en onderwerp, met een zoekvak (alle woorden moeten voorkomen). Bij een
item kun je de tekst bewerken of het wissen; onderaan voeg je zelf een voorkeur of feit toe. Ook daar wordt niets
bewaard wat op een geheim lijkt.

**In de repo**: het geheugen staat in een eigen privé repo (`DATA_REPO`, `chrisfaber95/homelab-data`), niet in de
homelab-repo, die public mag. Clone daar de branch `memory` en bewerk of wis bestanden; push vanaf je eigen pc (niet via Claude). De inbox
haalt de branch elke minuut op, en vlak voor elke taak. Op de box: `homelab-inbox memory [zoekwoorden]`.

## Opnieuw beginnen

- Eén item: wissen op de pagina.
- Een deel (bijvoorbeeld alle geschiedenis): wis de map in een clone van `memory` en push.
- Alles: verwijder de branch `memory` van `homelab-data` op GitHub (Branches → memory → verwijderen). Binnen een minuut is het geheugen
  van de inbox leeg; de volgende taak begint opnieuw.

## Instellingen

In `config/inbox.conf`: `DATA_REPO` (privé; `21-inbox.sh` weigert een public repo), `MEMORY_BRANCH` (standaard `memory`) en `MEMORY_RECENT` (standaard 10). Na een wijziging (PR):
`scripts/21-inbox.sh`.
