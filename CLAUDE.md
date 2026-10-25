# homelab

De oude desktop (i7-4790K, 16 GB, GTX 1080 8 GB) als headless Ubuntu-box voor een lokale LLM, projecten en
Claude Code. Tijdelijk draait hij op de oude laptop (7e gen i7, 24 GB, GTX 1050 Mobile 2 GB) zolang de PSU van
de desktop stuk is; daarna gaat de SSD (250 GB) naar de desktop.

Het waarom en wat staat in de briefs: `~/homelab-product/` (VISION, ROADMAP, briefs F-016 t/m F-024, research).
Dat is een kopie; het origineel staat in `team-hq` op de Windows-pc en wordt daar bijgewerkt.

## Regels

- Je draait als `claude`, zonder sudo. Vraag nooit om sudo of een wachtwoord. Elke stap die root nodig heeft
  wordt een script in `scripts/` dat de gebruiker (`chris`) draait met `sudo bash scripts/<naam>.sh`.
  Schrijf scripts idempotent, met `set -euo pipefail`, en laat ze zelf controleren of het gelukt is.
- Alles wat op de box staat moet uit deze repo te herbouwen zijn: elke wijziging staat in `CHECKLIST.md` of in een
  script. Een wijziging die je niet vastlegt, bestaat na een herbouw niet meer.
- Niets bereikbaar vanaf internet. Vanaf het LAN alleen SSH; diensten alleen via Tailscale.
- ufw filtert ook het tailnet (Tailscale staat op `--netfilter-mode=nodivert`); een nieuwe dienst krijgt een
  eigen `ufw allow in on tailscale0 to any port <p>`-regel. Docker-poorten omzeilen ufw: bind ze aan `127.0.0.1`
  of aan het Tailscale-adres.
- De NVIDIA-driver (580-server) en de kernel blijven handwerk van de gebruiker.
- Laptopfase: verwacht GTX 1050 / 2048 MiB; na de verhuizing GTX 1080 / 8192 MiB. Leg dat vast in één
  instelling, niet in code.
- Push nooit naar `main` (ook niet van deze repo): werk op een branch, push die en open een PR; de gebruiker merget.
  Een globale pre-push-hook in `~/.config/git/hooks` houdt dat tegen; zet hem niet uit. `gh pr merge` doe je niet.
- Root-scripts laat je de gebruiker draaien vanuit de deploy-clone `/srv/homelab/repos/homelab/scripts/` (dat is
  gemergde `main`), niet vanuit je eigen werkkopie.

## Stand (2026-10-25)

Checklist stap 0 t/m 11 zijn klaar en gecontroleerd:
- Ubuntu Server 24.04, UEFI met fallback-bootloader, LVM op de volle SSD, 8 GB swap, `vm.swappiness=10`.
- Netplan matcht `en*` (overleeft de verhuizing); cloud-init-netwerk staat uit.
- Tailscale: `homelab` = `100.85.70.34`, key expiry uit. SSH alleen met sleutel; wachtwoord geweigerd.
- ufw: alleen 22 vanaf `192.168.1.0/24` en via `tailscale0`; getest dat andere poorten dicht zijn op LAN en tailnet.
- NVIDIA 580.178.04 met kant-en-klare kernelmodules (`linux-modules-nvidia-580-server-generic`, geen DKMS);
  `nvidia-smi` werkt.
- Account `claude` zonder sudo; Claude Code, `gh` (ingelogd als `chrisfaber95`, HTTPS) en git-identiteit staan.
- Alleen-laptop: `/etc/systemd/logind.conf.d/laptop-lid.conf` (klep dicht laat de box aan); weghalen na de verhuizing.

- Repo: privé `chrisfaber95/homelab`, branch `main`.
- Health check: `bin/homelab-health`, verwachte GPU in `config/health.conf` (`EXPECTED_GPU`). Draait ook
  rechtstreeks uit de repo; `scripts/13-health-check.sh` kopieert hem naar `/usr/local/lib/homelab`.
  `/home/claude` is 750, dus scripts geef je de gebruiker met hun volledige pad.
- F-018: `bin/homelab-deploy` (sync-lus als dienst `homelab-deploy` onder `deploy`, `homelab-deploy status`),
  lijst in `config/repos.conf`, units in `deploy/`; uitleg in `docs/projects.md`. Deploy-clones in
  `/srv/homelab/repos`, je eigen dev-clones in `~/dev`. Testen kan met lokale bare repo's en
  `DEPLOY_ROOT`/`HOMELAB_REPO`/`SYSTEMCTL`/`TOKEN_FILE` (zie de docstring); `DOCKER_HOST` voor je eigen Docker.
  Het journal van systeemdiensten kun je niet lezen (geen groep `adm`): kijk in `/srv/homelab/state/`.
- F-017: `llm/helper.py` (OpenAI-API, poort 8017) en `bin/llm`; instellingen in `config/llm.conf`; uitleg in
  `docs/llm.md`. Installatie: `scripts/17-llm.sh` (nog door de gebruiker te draaien). Testen zonder root kan met een
  eigen `ollama serve` op een andere poort (`/dev/nvidia*` is 0666) en de helper met `OLLAMA_URL`/`LLM_PORT`/`LLM_DB`.

Nog door de gebruiker te testen: SSH vanaf een ander netwerk, en de klep dicht.

## Volgende werk

1. F-017: de gebruiker draait `scripts/17-llm.sh` opnieuw (model `llama3.1:8b`) en test `llm` vanaf Windows.
   Op de laptop draait het model bewust grotendeels op de CPU (keuze van de gebruiker); de brief eist 100% GPU
   voor de desktop.
2. F-018 staat (2026-10-25): `homelab-deploy` synct `homelab` en `claude-dashboard`; de dashboard draait als
   `homelab-app-claude-dashboard` op `https://homelab:8101` en ziet Claudes sessies. Rootless Docker voor `deploy` en
   `claude`; de compose-deploy is getest met lokale repo's en Claudes Docker. Nog door de gebruiker: apparaat koppelen,
   LAN-test van 8101, Remote-SSH, reboot en stroom eruit.
3. Daarna de ROADMAP-volgorde: F-019, F-020, ...

Open vragen die je niet zelf beslist:
- De externe 2 TB HDD en de 250 GB SSD: vraag van de lead in F-016, nog niet beantwoord.
- Beantwoord (2026-10-25): Claude pusht naar branches en opent PR's, niet naar `main`. De dashboard op de box toont
  sessies en mag Claude-sessies starten.
