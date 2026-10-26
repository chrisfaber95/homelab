# homelab

Een oude desktop als headless Ubuntu-server die zichzelf grotendeels beheert, samen met Claude Code. Op de box
draaien een lokale LLM, mijn eigen projecten (met automatische deploys) en een AI-agent die ik vanaf mijn telefoon
taken geef. Elke wijziging aan de machine gaat via een pull request die ik zelf merge.

Alles op de box is uit deze repo opnieuw op te bouwen: een installatie-checklist, idempotente scripts, configuratie
en een logboek van machinewijzigingen.

## Waarom

Ik wilde onderzoeken hoe ver je een AI-agent een echte machine kunt laten beheren **zonder hem root te geven**.
Daarvoor heb je duidelijke grenzen nodig: wat de agent zelf mag, wat langs een review moet, en hoe je terugkomt als
een wijziging misgaat.

## Architectuur

```
                 telefoon / Windows-pc
                         │  Tailscale (WireGuard), niets open naar internet
                         ▼
┌──────────────────────────── homelab (Ubuntu Server 24.04) ────────────────────────────┐
│                                                                                       │
│  homelab-inbox ──► Claude Code (account inbox, auto mode, geen GitHub-toegang)        │
│   taken, jobs,          │ commits als git-bundle                                      │
│   geheugen              ▼                                                             │
│                   push + PR met eigen token ──────────────────────────► GitHub        │
│                                                                           │ ik merge  │
│  homelab-admin (root) ◄── elke 30 s fetch van main ◄──────────────────────┤           │
│   voert changes/*/apply.sh uit, met vangnet voor firewall en netwerk      │           │
│                                                                           │           │
│  homelab-deploy (account deploy) ◄── elke 20 s, read-only token ◄─────────┘           │
│   bouwt en herstart projecten (systemd of rootless Docker)                            │
│                                                                                       │
│  Ollama + llm-helper (OpenAI-compatibele API)   homelab-monitor + homelab-backup      │
│                                                       │                               │
└───────────────────────────────────────────────────────┼───────────────────────────────┘
                                                        ▼
                                        ntfy: pushmeldingen naar de telefoon
```

## Onderdelen

| Onderdeel | Wat het doet | Uitleg |
| --- | --- | --- |
| Basis | Ubuntu Server op LVM, SSH alleen met sleutel, ufw, Tailscale, NVIDIA-driver | [`CHECKLIST.md`](CHECKLIST.md) |
| Lokale LLM | Ollama met `llama3.1:8b` en een kleine helper (`summarize`, `extract` als JSON) met een OpenAI-compatibele API en gebruikslog | [`docs/llm.md`](docs/llm.md) |
| Deploys | pull-based GitOps: een push naar `main` van een project staat binnen ~20 s live, met release-mappen en terugvallen op de vorige versie | [`docs/projects.md`](docs/projects.md) |
| Machinewijzigingen | een root-dienst met een vaste lijst directe acties; al het andere als `apply.sh` in een PR, met automatisch terugdraaien bij een lockout | [`docs/admin.md`](docs/admin.md) |
| Bewaking en backups | health checks elke 2 minuten, meldingen bij storing en herstel, een overzichtspagina en dagelijkse backups van de staat die niet in git staat | [`docs/monitor.md`](docs/monitor.md) |
| Task inbox | vanaf de telefoon een taak voor Claude; het resultaat is een PR, een antwoord of een vraag terug | [`docs/inbox.md`](docs/inbox.md) |
| Jobs en skills | geplande taken, beschreven in gewone taal; kleine jobs op het lokale model, grotere op Claude | [`docs/jobs.md`](docs/jobs.md) |
| Geheugen | voorkeuren, feiten en taakgeschiedenis als leesbare, bewerkbare bestanden in een privé repo | [`docs/memory.md`](docs/memory.md) |

## Veiligheidsontwerp

- **Niets bereikbaar vanaf internet.** Vanaf het LAN alleen SSH; alle diensten alleen via Tailscale. ufw filtert
  ook het tailnet, en Docker-poorten zijn aan `127.0.0.1` of het Tailscale-adres gebonden (Docker omzeilt ufw).
- **Gescheiden accounts.** De agent heeft geen sudo. Deploys draaien als `deploy`, taken uit de inbox als `inbox`,
  zonder GitHub-token en zonder toegang tot de rest van de box. Alleen de dienst die de PR opent heeft een token.
- **Review als grens.** Claude pusht nooit naar `main` en merget niet (een pre-push-hook dwingt dat af). Een merge
  is de goedkeuring; pas daarna voert root iets uit.
- **Vangnet bij netwerkwijzigingen.** Na een wijziging aan firewall of netwerk krijg ik een melding met een
  bevestigknop. Bevestig ik niet binnen 5 minuten, dan wordt de wijziging teruggedraaid.
- **Geheimen nooit in git.** Tokens staan in `/etc/homelab` (alleen leesbaar voor het juiste account) en worden
  meegenomen in de backup, niet in de repo. Jobs en geheugen staan in een aparte privé repo.
- **Vastgepinde downloads.** Binaries van buiten Ubuntu (Ollama, ntfy) worden met versie en SHA256 gecontroleerd.

## Repo-indeling

```
bin/        de diensten en commando's (Python en Bash)
config/     alle instellingen, één bestand per onderdeel
scripts/    idempotente installatiescripts per onderdeel (NN-naam.sh)
changes/    machinewijzigingen als apply.sh (en eventueel revert.sh), uitgevoerd na de merge
deploy/     systemd-units van projecten
llm/        de LLM-helper
docs/       uitleg per onderdeel
```

`CLAUDE.md` bevat de regels die Claude Code op de box volgt, en is daarmee ook de beste samenvatting van hoe alles
samenhangt.

## Stand

Draait op een tijdelijke laptop (i7 7e generatie, GTX 1050 2 GB) tot de desktop (i7-4790K, GTX 1080 8 GB) een nieuwe
voeding heeft. Daarna verhuist de SSD; het verschil zit in één instelling (`config/health.conf`, `config/llm.conf`).

Gebouwd met Claude Code als pair programmer: ik bepaal wat er komt en review elke PR.
