# Task inbox (F-021)

Vanaf je telefoon een taak voor Claude op de box: **`http://homelab.tail4eb878.ts.net:8105/taken`** (Tailscale aan;
de tab Taken van de homelab-pagina, `docs/dashboard.md`).
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
2. **Queue**: at most `MAX_RUNNING` tasks at once (4), and at most `MAX_PER_REPO` (3) on one repo; the rest waits in
   order and starts by itself. Each task has its own working copy and branch; the service's own copy of a repo is
   locked while one task uses it.
3. **Uitvoeren**: het account `inbox` clonet de repo in een eigen werkkopie (`/var/lib/inbox/work/<id>`), op branch
   `inbox/<id>`, en draait Claude Code in **auto mode**: de veiligheidscheck van Claude Code beoordeelt elke actie
   (nooit `bypassPermissions`). Wat die weigert, staat op de pagina ("N actie(s) geweigerd door auto mode").
4. **Afronden**: de inbox-dienst haalt de commits op als git-bundle, pusht ze zelf naar `inbox/<id>` en opent de PR.

## Uitkomsten

| Status | Wat |
| --- | --- |
| PR open | code-taak klaar: PR met samenvatting en commits; melding met de link |
| beantwoord | een vraag ("waarom ...", "wat doet ..."): antwoord op de pagina, geen commit, branch of PR; je kunt er op de pagina op reageren, dan gaat dezelfde sessie verder (nieuwe werkkopie) |
| wacht op jou | Claude vond de taak te onduidelijk en stelde één vraag; antwoord op de pagina, dan gaat dezelfde taak verder |
| geweigerd | mag niet (bijvoorbeeld iets wat F-019 verbiedt); de reden staat op de pagina |
| gestopt bij limiet | 80 beurten of 30 minuten per ronde (`config/inbox.conf`); de commits staan op `inbox/<id>`, zonder PR |
| geannuleerd | jij annuleerde hem (knop op de pagina); daarna geen commits of pushes meer |
| mislukt | iets anders ging mis; de reden staat op de pagina. **Retry** on the task page puts it back in the queue (see below) |
| gemerged / PR gesloten | de PR is dicht; de werkkopie is opgeruimd |

**Terugsturen**: zet review-commentaar op de PR (gewoon commentaar, een review of opmerkingen bij regels). Binnen een
minuut gaat de taak terug in de rij; Claude gaat verder in dezelfde sessie en werkkopie, de inbox pusht naar dezelfde
PR, en je krijgt een melding. Alleen commentaar van `OWNER_GITHUB` (`chrisfaber95`) telt; andere accounts worden
genegeerd. Elke ronde krijgt een nieuwe limiet.

**Restarts**: a restart of the inbox (for example after a merge that reinstalls it) stops the Claude of every
running task. When the inbox starts again, those tasks go back in the queue and their own session continues in the
same working copy, with a message that the last turn was cut off. The router and the planner start over. A task cut
off by `RESTART_ROUNDS` (2) restarts in a row fails ("onderbroken door een herstart"). A brief task whose PR review
was cut off is reviewed again.

**Retry**: a failed or cancelled task has a Retry button on its page. It goes back in the queue: in its own session
and working copy if that still exists, otherwise in a fresh copy of main (the task is told its earlier work is gone).
Tasks of the same build or message that were cancelled because of it ("niet gestart: de voorgaande taak ...") come
back too, in their order, and the build or message counts as running again.

**Follow-ups of a task that ended badly**: a follow-up doesn't start when its predecessor failed, was refused, was
cancelled, stopped at the limit or had its PR closed. Two things bring it back, in order, without running the
predecessor again:

- **Merge the predecessor's PR anyway.** The inbox sees the merge within a poll, sets the task to merged and requeues
  its follow-ups. Follow-ups cancelled before this feature existed don't come back this way.
- **Mark as fine, continue follow-ups** on the predecessor's page, when you don't need its work. The task keeps its
  status, so a build with such a task gets no automatic handoff. Follow-ups start from main: unmerged work of the
  predecessor isn't in them. Retry on that task clears the mark.

**Conflicts with main**: tasks on one repo run in parallel, so a PR can conflict with main once another one is merged.
When GitHub reports that, the inbox merges main into `inbox/<id>` in the task's working copy (`homelab-inbox-task
sync`). Without conflicts it pushes the merge right away. With conflicts the merge stays in progress and the task goes
back in the queue: its own session continues, gets the list of conflicted files, resolves them, commits and ends with
`KLAAR:` again; the inbox pushes to the same PR (a brief task is reviewed again). At most `CONFLICT_ROUNDS` (2) times
per task; after that, or if the merge fails otherwise, you get a notification.

## Automatisch doorsturen (repo "auto")

Kies je in het formulier **auto (homelab beslist)** in plaats van een repo, dan verdeelt een router jouw bericht:

1. Een korte Claude-run **zonder tools** (zelfde route als het opstellen van een job) krijgt je bericht, de repo's uit
   `config/repos.conf` plus `homelab` en per repo het begin van `CLAUDE.md` of `README.md`. Hij geeft JSON terug:
   per taak een repo, een zelfstandige opdracht, een reden en eventueel `after` (eerst die andere taak).
2. De inbox controleert dat: alleen repo's uit de lijst, hooguit `ROUTE_MAX_CHILDREN` (3) taken, geldige verwijzingen.
   Klopt het niet, dan mislukt het bericht (er wordt niets verzonnen). Zijn het er te veel of kan de router niet kiezen,
   dan krijg je één vraag ("wacht op jou") en gaat hij verder na je antwoord.
3. Elke taak wordt een gewone taak ("Inbox N", met `Onderdeel van bericht #M` en de reden): zelfde wachtrij, limieten,
   auto mode, PR en review-commentaar. Een taak met `after` start pas als zijn voorganger klaar is (antwoord of PR open);
   mislukt, weigert of stopt de voorganger, dan start hij niet.
4. Het bericht zelf krijgt de status **doorgestuurd**, met de taken eronder. Zodra alle taken klaar zijn, staat er een
   samenvatting met de PR's en krijg je één extra melding. Elke taak meldt zelf ook nog (PR, vraag, ...).
   Annuleren van het bericht annuleert alle taken die nog niet klaar zijn.

Taken die de box zelf raken (dienst, poort, pakket, firewall) stuurt de router naar `homelab`; daar gelden F-019 en
`changes/` zoals altijd. De router kan niets uitvoeren en heeft geen toegang tot repo's of tokens.

## Jobs en skills (F-022)

Op dezelfde pagina staan de tabs **Jobs** (geplande jobs, op het lokale model of op Claude) en **Skills** (vaste
manieren van werken die Claude hergebruikt). Een Claude-job loopt als een gewone taak door deze rij, met "job <naam>"
in de lijst; hij meldt alleen wat jou nodig heeft (geen melding bij een gewoon antwoord). Na een gelukte taak kan
Claude een skill voorstellen: dat wordt een PR in de homelab-repo die jij merget of sluit. Alles hierover:
`docs/jobs.md`.

## Geheugen (F-023)

Elke taak en Claude-job begint met wat Claude over jou, de box en de repo weet, en kan eerdere taken doorzoeken. Corrigeer
je Claude in review-commentaar of een antwoord, dan bewaart hij dat als voorkeur. Alles staat op de tab **Geheugen**,
waar je het bewerkt of wist, en op de branch `memory` van de privé repo `homelab-data`. Alles hierover: `docs/memory.md`.

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
- Code: `bin/homelab-inbox` is the entry point; the parts are modules in `bin/homelab_inbox/` (map in `CLAUDE.md`).
  `scripts/21-inbox.sh` installs them next to it.
- Logs: `journalctl -u homelab-inbox`.
