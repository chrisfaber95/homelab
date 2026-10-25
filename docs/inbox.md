# Task inbox (F-021)

Vanaf je telefoon een taak voor Claude op de box: **`http://homelab.tail4eb878.ts.net:8105`** (Tailscale aan).
Kies een repo, beschrijf wat er moet gebeuren of wat je wilt weten, en verstuur. Claude eindigt met een PR, een
antwoord op de pagina, één vraag terug, of een weigering met reden; bij elk daarvan krijg je een melding.

## Wat er gebeurt

```
telefoon ──tailnet──> homelab-inbox (inbox-runner) ── wachtrij ──sudo -u inbox──> homelab-inbox-task (inbox)
                          │                                                        Claude, auto mode, eigen werkkopie
                          │  <── git bundle met de commits van inbox/<id> (alleen data) ──┘
                          └── push inbox/<id> + PR (eigen token) ──> GitHub ──> jij reviewt en merget
```

1. **Repo's**: precies de repo's uit `config/repos.conf` (F-018) plus `homelab`. Andere repo's kun je niet kiezen.
2. **Wachtrij**: hooguit 2 taken tegelijk, nooit 2 op dezelfde repo; de rest wacht op volgorde en start vanzelf.
3. **Uitvoeren**: het account `inbox` clonet de repo in een eigen werkkopie (`/var/lib/inbox/work/<id>`), op branch
   `inbox/<id>`, en draait Claude Code in **auto mode**: de veiligheidscheck van Claude Code beoordeelt elke actie
   (nooit `bypassPermissions`). Wat die weigert, staat op de pagina ("N actie(s) geweigerd door auto mode").
4. **Afronden**: de inbox-dienst haalt de commits op als git-bundle, pusht ze zelf naar `inbox/<id>` en opent de PR.

## Uitkomsten

| Status | Wat |
| --- | --- |
| PR open | code-taak klaar: PR met samenvatting en commits; melding met de link |
| beantwoord | een vraag ("waarom ...", "wat doet ..."): antwoord op de pagina, geen commit, branch of PR |
| wacht op jou | Claude vond de taak te onduidelijk en stelde één vraag; antwoord op de pagina, dan gaat dezelfde taak verder |
| geweigerd | mag niet (bijvoorbeeld iets wat F-019 verbiedt); de reden staat op de pagina |
| gestopt bij limiet | 80 beurten of 30 minuten per ronde (`config/inbox.conf`); de commits staan op `inbox/<id>`, zonder PR |
| geannuleerd | jij annuleerde hem (knop op de pagina); daarna geen commits of pushes meer |
| mislukt | iets anders ging mis; de reden staat op de pagina |
| gemerged / PR gesloten | de PR is dicht; de werkkopie is opgeruimd |

**Terugsturen**: zet review-commentaar op de PR (gewoon commentaar, een review of opmerkingen bij regels). Binnen een
minuut gaat de taak terug in de rij; Claude gaat verder in dezelfde sessie en werkkopie, de inbox pusht naar dezelfde
PR, en je krijgt een melding. Alleen commentaar van `OWNER_GITHUB` (`chrisfaber95`) telt; andere accounts worden
genegeerd. Elke ronde krijgt een nieuwe limiet.

## Wat Claude in de inbox wel en niet kan

- **Niet**: pushen, een PR openen of mergen, naar `main` pushen. `inbox` heeft geen GitHub-token en geen `gh`-login;
  alleen de dienst (account `inbox-runner`) heeft een token, en die pusht alleen naar `inbox/<id>`. Dit wordt dus
  echt afgedwongen, anders dan bij Claudes eigen sessies (daar is het een afspraak, zie `docs/admin.md`).
- **Niet**: je dev-clones (`~/dev`), Claudes eigen bestanden (`/home/claude`) of de deploy-clones (`/srv/homelab`)
  wijzigen: `inbox` kan er niet bij.
- **Box-wijzigingen** (in de repo `homelab`): alleen via F-019. Wat op de vaste lijst staat, doet Claude meteen met
  `homelab-admin` (in het logboek als gebruiker `inbox`); al het andere wordt een `changes/`-map in de PR, die pas na
  jouw merge wordt uitgevoerd. Wat F-019 verbiedt, eindigt als "geweigerd".

## Toegang

De pagina antwoordt alleen aan apparaten op je tailnet die van jouw Tailscale-login zijn (gecontroleerd met
`tailscale whois`), niet aan de box zelf en niet via het LAN (ufw: 8100-8199 alleen via `tailscale0`). Formulieren
hebben een token en worden alleen vanaf de pagina zelf geaccepteerd (tegen CSRF).

## Beheer

- `homelab-inbox list`: alle taken. De geschiedenis staat in `/var/lib/homelab-inbox/inbox.db` en overleeft een
  herstart; een taak die liep tijdens een herstart, staat daarna op "mislukt" (verstuur hem opnieuw).
- Grenzen en aantallen: `config/inbox.conf` (PR, daarna `scripts/21-inbox.sh`).
- Tokens: `/etc/homelab/inbox-claude-token` (Claude Code voor `inbox`, van `claude setup-token`),
  `/etc/homelab/inbox-github-token` (GitHub voor `inbox-runner`: Contents en Pull requests read/write),
  `/etc/homelab/inbox-ntfy-token`. Verloopt er een, draai dan `scripts/21-inbox.sh` opnieuw: het vraagt opnieuw.
- Logs: `journalctl -u homelab-inbox`.
