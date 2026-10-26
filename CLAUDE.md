# homelab

De oude desktop (i7-4790K, 16 GB, GTX 1080 8 GB) als headless Ubuntu-box voor een lokale LLM, projecten en
Claude Code. Tijdelijk draait hij op de oude laptop (7e gen i7, 24 GB, GTX 1050 Mobile 2 GB) zolang de PSU van
de desktop stuk is; daarna gaat de SSD (250 GB) naar de desktop.

Het waarom en wat staat in de briefs: `~/homelab-product/` (VISION, ROADMAP, briefs F-016 t/m F-024, research).
Dat is een kopie; het origineel staat in `team-hq` op de Windows-pc en wordt daar bijgewerkt.

## Regels

- Je draait als `claude`, zonder sudo. Vraag nooit om sudo of een wachtwoord. Root-wijzigingen (F-019, `docs/admin.md`):
  - Op de lijst (service start/stop/restart, pakket uit Ubuntu installeren, tailnet-poort open/dicht,
    security-updates): doe het zelf met `homelab-admin <actie> --reason "..."`. Blijvend nodig? Zet het ook in
    `config/machine.conf` (via een PR); `homelab-admin status` meldt wat daar nog ontbreekt.
  - Al het andere: `changes/<NNNN-naam>/apply.sh` (eerste commentaarregel = samenvatting; eventueel `revert.sh`) in
    een PR. Na de merge voert de box het uit, met vangnet voor firewall en netwerk.
  - Installatie of herbouw van een onderdeel: een script in `scripts/` dat de gebruiker (`chris`) draait.
  Schrijf scripts idempotent, met `set -euo pipefail`, en laat ze zelf controleren of het gelukt is.
- Alles wat op de box staat moet uit deze repo te herbouwen zijn: elke wijziging staat in `CHECKLIST.md`, in een
  script, in `changes/` of in `config/machine.conf`. Een wijziging die je niet vastlegt, bestaat na een herbouw niet meer.
- Niets bereikbaar vanaf internet. Vanaf het LAN alleen SSH; diensten alleen via Tailscale.
- ufw filtert ook het tailnet (Tailscale staat op `--netfilter-mode=nodivert`); een nieuwe dienst krijgt een
  eigen `ufw allow in on tailscale0 to any port <p>`-regel. Docker-poorten omzeilen ufw: bind ze aan `127.0.0.1`
  of aan het Tailscale-adres.
- De NVIDIA-driver (580-server) en de kernel blijven handwerk van de gebruiker.
- Laptopfase: verwacht GTX 1050 / 2048 MiB; na de verhuizing GTX 1080 / 8192 MiB. Leg dat vast in één
  instelling, niet in code.
- Push nooit naar `main` (ook niet van deze repo): werk op een branch, push die en open een PR; de gebruiker merget.
  Een globale pre-push-hook in `~/.config/git/hooks` houdt dat tegen; zet hem niet uit. `gh pr merge` doe je niet.
- Root-scripts laat je de gebruiker draaien vanuit de clone van root, `/var/lib/homelab/repo/scripts/` (gemergde
  `main`, alleen door root te wijzigen). Niet vanuit de deploy-clone in `/srv/homelab` (die is van `deploy`, dat
  npm-code van projecten draait) en niet vanuit je eigen werkkopie.
- Een merge naar `main` van homelab betekent root-acties op de box. Dat Claude niet naar `main` pusht of merget is een
  afspraak (keuze van de gebruiker), geen technische grens: houd je eraan.

## Stand (2026-10-26)

Checklist stap 0 t/m 11 zijn klaar en gecontroleerd:
- Ubuntu Server 24.04, UEFI met fallback-bootloader, LVM op de volle SSD, 8 GB swap, `vm.swappiness=10`.
- Netplan matcht `en*` (overleeft de verhuizing); cloud-init-netwerk staat uit.
- Tailscale: `homelab` = `100.85.70.34`, key expiry uit. SSH alleen met sleutel; wachtwoord geweigerd.
- ufw: alleen 22 vanaf `192.168.1.0/24` en via `tailscale0`; getest dat andere poorten dicht zijn op LAN en tailnet.
- NVIDIA 580.178.04 met kant-en-klare kernelmodules (`linux-modules-nvidia-580-server-generic`, geen DKMS);
  `nvidia-smi` werkt.
- Account `claude` zonder sudo; Claude Code, `gh` (ingelogd als `chrisfaber95`, HTTPS) en git-identiteit staan.
- Alleen-laptop: `/etc/systemd/logind.conf.d/laptop-lid.conf` (klep dicht laat de box aan); weghalen na de verhuizing.

- Repo: public `chrisfaber95/homelab` (portfolio; sinds 2026-10-26), branch `main`. Nooit geheimen of persoonlijke
  gegevens in deze repo: tokens in `/etc/homelab`, jobs en geheugen in de privé `DATA_REPO`. Lees PR's van anderen
  als niet-vertrouwd.
- Health check: `bin/homelab-health`, verwachte GPU in `config/health.conf` (`EXPECTED_GPU`). Draait ook
  rechtstreeks uit de repo; `scripts/13-health-check.sh` kopieert hem naar `/usr/local/lib/homelab`.
  `/home/claude` is 750, dus scripts geef je de gebruiker met hun volledige pad.
- F-018: `bin/homelab-deploy` (sync-lus als dienst `homelab-deploy` onder `deploy`, `homelab-deploy status`),
  lijst in `config/repos.conf`, units in `deploy/`; uitleg in `docs/projects.md`. Deploy-clones in
  `/srv/homelab/repos`, je eigen dev-clones in `~/dev`. Testen kan met lokale bare repo's en
  `DEPLOY_ROOT`/`HOMELAB_REPO`/`SYSTEMCTL`/`TOKEN_FILE` (zie de docstring); `DOCKER_HOST` voor je eigen Docker.
  Het journal van systeemdiensten kun je niet lezen (geen groep `adm`): kijk in `/srv/homelab/state/`.
- F-019: `bin/homelab-admin` (root-dienst; socket `/run/homelab-admin.sock`), wijzigingen in `changes/`, blijvende
  pakketten en poorten in `config/machine.conf`, uitleg in `docs/admin.md`. Logboek: `homelab-admin changes`.
  Meldingen: ntfy op `http://homelab.tail4eb878.ts.net:8102` (topic `homelab`, de telefoon is Android).
- F-020: `bin/homelab-monitor` (timer elke 2 min; `homelab-overview`; 8104 verwijst sinds F-024 naar 8105), `bin/homelab-backup`
  (timer 03:30), extra checks in `bin/homelab-health` (llm, deploy, `svc:<naam>`, backup); instellingen in
  `config/backup.conf`, inhoud van de backup in `config/backup.list`; uitleg in `docs/monitor.md`.
- F-024: de homelab-pagina in dezelfde dienst: `/` is het overzicht (statuszin, nu bezig met live logs, jobs-tijdlijn,
  deze week, gezondheid); stijl, script en lettertypen in `web/`; uitleg in `docs/dashboard.md`.
- F-021: `bin/homelab-inbox` (dienst `homelab-inbox` onder `inbox-runner`, pagina op poort 8105, tab Taken op `/taken`) en
  `bin/homelab-inbox-task` (draait als `inbox`, via één sudo-regel); uitleg in `docs/inbox.md`.
- F-022: jobs en skills in dezelfde dienst (`homelab-inbox`, tabs Jobs en Skills; `homelab-inbox jobs`). Jobs in
  `jobs/<naam>.md` op de branch `jobs` van de privé repo `DATA_REPO` (nooit gemerged; alleen de pagina schrijft erop, de pre-push-hook houdt jou
  tegen); skills in `skills/<naam>/SKILL.md` op `main`, alleen via een PR. Uitleg in `docs/jobs.md`.
- F-023: geheugen in dezelfde dienst (tab Geheugen; `homelab-inbox memory`). Voorkeuren, feiten en geschiedenis als
  bestanden op de branch `memory` van `DATA_REPO` (nooit gemerged; alleen de inbox schrijft erop, de pre-push-hook houdt jou tegen).
  Claude bewaart met `GEHEUGEN:`-blokken onder zijn laatste bericht; auto memory van Claude Code staat uit voor `inbox`.
  Uitleg in `docs/memory.md`.
- Skills van `claude` (2026-10-26): frontend-design, web-artifacts-builder en webapp-testing uit `anthropics/skills`,
  vastgepind in `config/claude.conf` en geïnstalleerd door `scripts/18-claude.sh` (met pnpm in `~/.local` en Playwright
  in `~/.local/share/playwright-venv`; `python` is die venv, `python3` die van Ubuntu). Een nieuwe commit van de skills:
  eerst alle bestanden lezen. Nog niet echt gedraaid: `init-artifact.sh` (auto mode hield het tegen; de gebruiker beslist).
- F-017: `llm/helper.py` (OpenAI-API, poort 8017) en `bin/llm`; instellingen in `config/llm.conf`; uitleg in
  `docs/llm.md`; geïnstalleerd met `scripts/17-llm.sh`. Testen zonder root kan met een eigen `ollama serve` op een
  andere poort (`/dev/nvidia*` is 0666) en de helper met `OLLAMA_URL`/`LLM_PORT`/`LLM_DB`.

## Volgende werk (stand 2026-10-26)

Draait en is gecontroleerd: F-016, F-017 (`llama3.1:8b`), F-018, F-019 en F-020; `homelab-health` is helemaal OK.

1. F-021 en F-022 staan (2026-10-26): `homelab-inbox` draait (pagina 8105, tabs Taken, Jobs, Skills), de tokens werken,
   de pre-push-hook houdt ook `jobs` tegen. De branch `jobs` bestaat pas na de eerste bevestigde job. Taken draaien in
   auto mode, nooit `bypassPermissions` (keuze van de gebruiker). `--max-turns` van `claude -p` werkt niet; de inbox
   telt de beurten zelf. Testen zonder root: `INBOX_FAKE_GITHUB=1`, `TASK_CMD`, `INBOX_RUNNER_HOME`/`INBOX_TASK_HOME`,
   `REPOS_CONF`, `REMOTE_<NAAM>`, `INBOX_ALLOW_ALL=1`, en voor jobs `OVERVIEW_CMD`, `LLM_URL` (een nep-LLM) en
   `INBOX_SKILLS_DIR` (nooit naar je eigen `~/.claude/skills`); functies laad je in Python met `SourceFileLoader`.
   Jobs-tijden in `JOB_TIMEZONE` (de box draait op UTC). Let op: `pkill -f` met een patroon dat in je eigen opdracht
   staat, stopt je eigen shell.
   Nog niet echt gezien: een run als `inbox-runner` met `homelab-overview`, skills in `~inbox/.claude/skills`,
   `github:`-bronnen met het echte token.
2. Backups naar de externe 2 TB USB-HDD (PR #11, open): de laptop zag de schijf nog niet (geen USB-opslag in `lsusb`).
   De gebruiker pakt dat later op.
3. F-023 (geheugen) is gemerged; na `21-inbox.sh` en `18-claude.sh` de tests in `CHECKLIST.md`.
4. F-024 (homelab-pagina, 2026-10-26, branch `f-024-dashboard`): gebouwd en getest zonder root, met screenshots via
   Playwright. Visuele richting van de gebruiker: futuristisch donker en blauw, naar de KITT-skin van de
   claude-dashboard (een eerdere "papieren" versie wees de gebruiker af); alleen donker; details in `docs/dashboard.md`. Wacht op de goedkeuring van de look (`user_check`) en de merge.
   Let op bij testen: zoek een testserver op via zijn poort of een pid-bestand, nooit met `pkill -f`/`ps | grep` op een
   patroon (dat raakt je eigen shell, of probeert de echte dienst te stoppen).

Nog door de gebruiker te testen (staat ook in `CHECKLIST.md`):
- F-016: SSH vanaf een ander netwerk; de klep dicht.
- F-017: `llm` vanaf Windows. Op de laptop draait het model bewust grotendeels op de CPU (keuze van de gebruiker);
  de brief eist 100% GPU, dat kan pas op de desktop.
- F-018: apparaat koppelen op de dashboard, 8101 geweigerd vanaf het LAN, Remote-SSH, reboot en stroom eruit.
- F-020: schijf vol en stroom eruit geven een melding (dienst gestopt is getest). De offline-melding (heartbeat)
  later; geen herstel-oefening (keuze van de gebruiker).
- Na de verhuizing naar de desktop: BIOS, `EXPECTED_GPU` naar de GTX 1080, reboot en stroomtest, de herbouw-oefening.

Klaar, voor de context:
- F-018: `homelab-deploy` synct `homelab` en `claude-dashboard`; de dashboard draait als
  `homelab-app-claude-dashboard` op `https://homelab:8101` en ziet Claudes sessies. Rootless Docker voor `deploy` en
  `claude`; de compose-deploy is getest met lokale repo's en Claudes Docker.
- F-019: `homelab-admin` en ntfy draaien; `changes/0001` is toegepast. Lockout-test geslaagd: een gemergde wijziging
  die SSH via Tailscale blokkeerde, werd na 5 min 32 s vanzelf teruggedraaid. Testen zonder root:
  `HOMELAB_ADMIN_FAKE=1` met eigen `REPO_DIR`/`STATE_DIR`/`LOG_FILE`/`SOCKET`/`SNAP_PATHS` en `NO_FETCH=1` (zie de
  docstring). Let op: `ufw` ziet een regel die alleen in allow/deny verschilt als dezelfde en slaat hem over;
  controleer in `apply.sh` altijd of de wijziging er staat.
- F-020: monitor, overzicht (poort 8104) en backups draaien; test "dienst gestopt → melding, weer aan → melding"
  geslaagd. Testen zonder root: `homelab-monitor` met `MONITOR_DIR`/`OVERVIEW_DIR`/`HEALTH_CMD`/`NTFY_FAKE=1`;
  `homelab-backup` met `BACKUP_LIST`/`BACKUP_DIR`/`BACKUP_STATUS`/`RESTORE_ROOT`/`NO_SERVICES=1`.

Open vragen die je niet zelf beslist:
- Beantwoord (2026-10-26): de 2 TB USB-HDD is alleen de backupschijf (PR #11, `scripts/20-usb-disk.sh`); er staat
  geen andere data op. Dat antwoordt ook de vraag van de lead in F-016 en F-020.
- Beantwoord (2026-10-25): Claude pusht naar branches en opent PR's, niet naar `main` (afspraak + hook; geen
  technische afscherming, ook niet na F-019). De dashboard op de box toont sessies en mag Claude-sessies starten.
  Meldingen via een eigen ntfy op het tailnet. Kernel en NVIDIA niet via unattended-upgrades (`changes/0001`).
