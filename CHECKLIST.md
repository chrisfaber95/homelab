# homelab: installatie-checklist

Van een lege SSD tot een box waarop Claude Code het overneemt (F-016). Tijdelijk gebouwd op de oude laptop
(7e gen i7, GTX 1050 2 GB) zolang de PSU van de desktop stuk is; daarna gaat de SSD naar de desktop.
Stappen met **(laptop)** zijn alleen voor die tussenfase. Stappen met **(desktop)** pas na de verhuizing.

Versies (Ubuntu 24.04, driver 580) komen uit `research/hardware-and-local-llm.md` en zijn niet opnieuw
gecontroleerd: kijk bij elke stap of er een nieuwere point release is.

## 0. Voorbereiden op de Windows-pc

- [ ] Zet alles wat je wilt houden van de 250 GB SSD ergens anders neer; hij wordt gewist.
- [ ] Controleer dat de SSD in de laptop past (2,5" SATA, of M.2 van hetzelfde type: SATA of NVMe).
- [ ] Download de Ubuntu Server 24.04.x LTS ISO van ubuntu.com.
- [ ] Schrijf de ISO met Rufus naar een USB-stick: partitieschema **GPT**, doelsysteem **UEFI (non CSM)**.
- [ ] Zorg voor een SSH-sleutel: `Test-Path $env:USERPROFILE\.ssh\id_ed25519.pub`, anders `ssh-keygen -t ed25519`.
      Zet de publieke sleutel op GitHub (Settings → SSH and GPG keys), dan kan de installer hem ophalen.
- [ ] Tailscale draait op de Windows-pc en op je telefoon, met MagicDNS aan.
- [ ] Noteer je LAN-subnet (`ipconfig`, bijvoorbeeld `192.168.1.0/24`).

## 1. Laptop klaarmaken **(laptop)**

- [ ] Haal de eigen schijf van de laptop eruit (of koppel hem los), zodat de installer de bootloader niet op de
      verkeerde schijf zet. Plaats de 250 GB SSD.
- [ ] Netwerkkabel erin, lader erin.
- [ ] BIOS/UEFI-setup:
  - [ ] Bootmodus **UEFI**, CSM/Legacy **uit**.
  - [ ] Secure Boot **uit**.
  - [ ] SATA-modus **AHCI** (niet "RAID On" / Intel RST, anders ziet de installer de SSD niet).
  - [ ] Hybride graphics op de standaardinstelling laten.
- [ ] Boot van de USB-stick (meestal F12 of Esc voor het bootmenu).

## 2. Ubuntu Server installeren

- [ ] Taal en toetsenbord naar keuze; type **Ubuntu Server** (niet minimized). "Search for third-party drivers"
      **uit**: de NVIDIA-driver komt in stap 7.
- [ ] Netwerk: de kabel krijgt een adres via DHCP. Geen proxy, standaard mirror.
- [ ] Opslag: **Use an entire disk** → de 250 GB SSD, **Set up this disk as an LVM group** aan,
      **Encrypt with LUKS uit** (de box moet zonder toetsaanslag opstarten).
- [ ] In het overzicht: `ubuntu-lv` → Edit → grootte op het maximum (standaard krijgt hij maar ~100 GB).
- [ ] Profiel: servernaam **`homelab`**, je eigen gebruikersnaam en een wachtwoord (voor `sudo`).
- [ ] Ubuntu Pro overslaan.
- [ ] SSH: **Install OpenSSH server** aan, **Import SSH key → from GitHub** met je GitHub-naam,
      **Allow password authentication over SSH uit**.
- [ ] Featured snaps: niets aanvinken (Docker komt later als rootless Docker, F-018).
- [ ] Na de installatie: USB-stick eruit, reboot.

## 3. Eerste boot: updates en controles

Log in op de laptop zelf of via `ssh <naam>@<lan-ip>` vanaf Windows.

- [ ] `sudo apt update && sudo apt full-upgrade -y && sudo reboot`
- [ ] UEFI-boot: `ls /sys/firmware/efi` bestaat.
- [ ] Fallback-bootloader (de boot-entry van de laptop gaat niet mee naar de desktop):
      `ls /boot/efi/EFI/BOOT/` toont `BOOTX64.EFI`. Zo niet: `sudo dpkg-reconfigure grub-efi-amd64` en kies **Yes**
      bij "Force extra installation to the EFI removable media path".
- [ ] Rootvolume op volle grootte: `df -h /` toont ~230 GB. Zo niet:
      `sudo lvextend -r -l +100%FREE /dev/ubuntu-vg/ubuntu-lv`
- [ ] Tijdzone: `sudo timedatectl set-timezone Europe/Amsterdam`

## 4. Netwerk dat de verhuizing overleeft

Doe dit op het toetsenbord van de laptop, niet via SSH: een fout hier kost je de verbinding.

- [ ] `ls /etc/netplan/` en kijk in het bestand (meestal `50-cloud-init.yaml`): daar staat de interfacenaam
      van de laptop (bijvoorbeeld `enp0s31f6`). Die naam is op de desktop anders.
- [ ] Laat cloud-init netplan niet meer overschrijven:
      `echo 'network: {config: disabled}' | sudo tee /etc/cloud/cloud.cfg.d/99-disable-network-config.cfg`
- [ ] Verplaats het oude bestand: `sudo mv /etc/netplan/50-cloud-init.yaml /root/`
- [ ] Maak `/etc/netplan/01-homelab.yaml` (`sudo nano`):
      ```yaml
      network:
        version: 2
        ethernets:
          wired:
            match:
              name: "en*"
            dhcp4: true
      ```
- [ ] `sudo chmod 600 /etc/netplan/01-homelab.yaml`
- [ ] `sudo netplan try`: werkt het netwerk nog (`ping -c 3 1.1.1.1` in een tweede console, Alt+F2), bevestig met
      Enter; anders draait netplan het na 120 s zelf terug.

## 5. Tailscale en SSH

- [ ] `curl -fsSL https://tailscale.com/install.sh | sh`
- [ ] `sudo tailscale up`: open de URL die hij toont op de Windows-pc en log in.
- [ ] In de Tailscale-admin (Machines → homelab → ...): **Disable key expiry**, anders valt de box na een paar
      maanden van je tailnet en kun je er niet meer bij.
- [ ] SSH alleen met sleutel, ook als cloud-init iets anders heeft neergezet:
      ```bash
      sudo tee /etc/ssh/sshd_config.d/10-homelab.conf <<'EOF'
      PasswordAuthentication no
      KbdInteractiveAuthentication no
      PermitRootLogin no
      EOF
      sudo sshd -t && sudo systemctl restart ssh
      ```
- [ ] Controle: `sudo sshd -T | grep -Ei 'passwordauth|kbdinteractive|permitroot'` toont drie keer `no`.
- [ ] Op Windows in `%USERPROFILE%\.ssh\config`:
      ```
      Host homelab
        HostName homelab
        User <jouwnaam>
      Host homelab-claude
        HostName homelab
        User claude
      ```
- [ ] `ssh homelab` werkt vanaf Windows over Tailscale.

## 6. Firewall

Houd tijdens deze stap een SSH-sessie open en test met een tweede sessie voordat je de eerste sluit.

- [ ] Laat Tailscale ufw niet omzeilen. Standaard zet het een ACCEPT voor alles op `tailscale0` vóór de regels van
      ufw, en dan is elke poort open voor het tailnet:
      `sudo tailscale set --netfilter-mode=nodivert`
- [ ] Vervang het subnet door dat van jou:
      ```bash
      sudo ufw default deny incoming
      sudo ufw default allow outgoing
      sudo ufw allow from 192.168.1.0/24 to any port 22 proto tcp comment 'SSH LAN'
      sudo ufw allow in on tailscale0 to any port 22 proto tcp comment 'SSH tailnet'
      sudo ufw enable
      ```
- [ ] `sudo ufw status verbose` toont alleen poort 22; een tweede `ssh homelab` werkt nog.
- [ ] Test met een tijdelijke dienst (`timeout 60 python3 -m http.server 8099 --bind 0.0.0.0`) dat poort 8099 dicht
      is, zowel op het LAN-adres als op het Tailscale-adres van de box.

## 7. NVIDIA-driver 580

De GTX 1050 is Pascal net als de GTX 1080, dus deze driver blijft na de verhuizing staan.

- [ ] `sudo apt install -y ubuntu-drivers-common`
- [ ] `sudo ubuntu-drivers list --gpgpu`: zoek de `nvidia-driver-580-server`-regel (580 is de laatste tak voor Pascal).
- [ ] `sudo ubuntu-drivers install --gpgpu nvidia:580-server`
- [ ] `sudo apt install -y nvidia-utils-580-server` (voor `nvidia-smi`)
- [ ] `sudo reboot`
- [ ] `nvidia-smi` toont de GTX 1050 met ~2048 MiB en geen processen.

## 8. Geheugen

- [ ] `swapon --show`: is er geen swap, of minder dan 8 GB:
      ```bash
      sudo swapoff -a
      sudo rm -f /swap.img
      sudo fallocate -l 8G /swap.img
      sudo chmod 600 /swap.img
      sudo mkswap /swap.img
      sudo swapon /swap.img
      grep -q '^/swap.img' /etc/fstab || echo '/swap.img none swap sw 0 0' | sudo tee -a /etc/fstab
      ```
- [ ] `echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-homelab.conf && sudo sysctl --system`

## 9. Klep dicht, box blijft aan **(laptop)**

Een apart bestand, zodat je het na de verhuizing in één keer weghaalt.

- [ ] ```bash
      sudo mkdir -p /etc/systemd/logind.conf.d
      sudo tee /etc/systemd/logind.conf.d/laptop-lid.conf <<'EOF'
      [Login]
      HandleLidSwitch=ignore
      HandleLidSwitchExternalPower=ignore
      HandleLidSwitchDocked=ignore
      EOF
      sudo reboot
      ```
- [ ] Klep dicht: `ssh homelab` werkt nog.

## 10. Account voor Claude (zonder sudo)

Claude draait onder een eigen account dat niet in de `sudo`-groep zit; jouw account houdt sudo.

- [ ] ```bash
      sudo useradd -m -s /bin/bash claude
      sudo install -d -m 700 -o claude -g claude /home/claude/.ssh
      sudo install -m 600 -o claude -g claude ~/.ssh/authorized_keys /home/claude/.ssh/authorized_keys
      sudo apt install -y git gh tmux
      ```
- [ ] `sudo -l -U claude` zegt dat `claude` geen sudo mag.
- [ ] `ssh homelab-claude` werkt vanaf Windows.

## 11. Claude Code en GitHub (als `claude`)

- [ ] `ssh homelab-claude`, dan `tmux` (zodat de sessie blijft lopen als SSH wegvalt).
- [ ] `curl -fsSL https://claude.ai/install.sh | bash`, daarna `claude` → `/login`: open de URL op de Windows-pc.
- [ ] `gh auth login` (GitHub.com, HTTPS, login with a web browser: de code invoeren op de pc), dan `gh auth setup-git`.
      Claude pusht naar branches en opent PR's, niet naar `main` (F-018; de pre-push-hook komt in stap 13).
- [ ] `gh repo clone <een privé-repo van jou>` werkt.
- [ ] `git config --global user.name` / `user.email` instellen.

## 12. Eindcontrole op de laptop

- [ ] `ssh homelab` werkt over Tailscale thuis, en vanaf een ander netwerk (bijvoorbeeld de pc op de hotspot van je telefoon).
- [ ] Inloggen met wachtwoord wordt geweigerd:
      `ssh -o PubkeyAuthentication=no -o PreferredAuthentications=password homelab` → `Permission denied (publickey)`.
- [ ] Vanaf het LAN antwoordt alleen poort 22: `Test-NetConnection <lan-ip> -Port 22` is `True`; `sudo ss -tlnp`
      toont geen andere luisterende poorten op het LAN (alles behalve 22 is toch dicht door ufw).
- [ ] `sudo reboot`: de box komt terug zonder toetsaanslag, `ssh homelab` en `nvidia-smi` werken.
- [ ] Als `claude` faalt `sudo -v`.

## 13. Daarna neemt Claude op de box het over

Root-stappen schrijft Claude als script in de repo; jij voert ze uit met je eigen account.

- [ ] De `homelab`-repo op GitHub aanmaken en deze checklist erin zetten.
- [ ] De health check (F-016): driver/GPU, netwerk, schijf, pending reboot; de verwachte GPU en VRAM uit één
      instelling (`EXPECTED_GPU` in `config/health.conf`: GTX 1050 / 2048 MiB nu, GTX 1080 / 8192 MiB na de verhuizing).
      Installeren met je eigen account: `sudo bash /home/claude/homelab/scripts/13-health-check.sh`.
      Daarna `homelab-health`: één regel per check, exit 0 als alles in orde is, 1 bij een FAIL.
- [ ] F-017 (Ollama, `llm`): `sudo bash /home/claude/homelab/scripts/17-llm.sh`. Installeert Ollama (versie en hash
      in `config/llm.conf`), de helper `llm-helper` op poort 8017 (ufw: alleen `tailscale0`) en het model.
      Op de laptop `llama3.1:8b`, bewust grotendeels op de CPU (zie `docs/llm.md`). Controle: `echo test | llm summarize`
      werkt, en vanaf Windows met Tailscale **uit** is `Test-NetConnection <lan-ip> -Port 8017` `False`.
      Zie `docs/llm.md` voor `llm` op Windows.
- [ ] F-018 (projecten, rootless Docker, Node): zie `docs/projects.md`.
  - [ ] Op GitHub een fine-grained token: All repositories, Contents = Read-only.
  - [ ] `sudo bash /home/claude/homelab/scripts/18-projects.sh` (vraagt om het token; controleert dat het niet kan pushen).
  - [ ] Als `claude`: `bash /home/claude/homelab/scripts/18-claude.sh` (rootless Docker en de pre-push-hook).
  - [ ] `homelab-deploy status`: `homelab` en `claude-dashboard` staan er, de dashboard draait.
  - [ ] Dashboard koppelen: `ssh -N -L 18101:127.0.0.1:8101 homelab`, `https://localhost:18101`, apparaat aanmaken;
        daarna `https://homelab:8101` op de telefoon.
  - [ ] Vanaf Windows met Tailscale **uit**: `Test-NetConnection <lan-ip> -Port 8101` is `False`.
  - [ ] VS Code Remote-SSH naar `homelab`, een repo clonen in `~/dev` en `npm ci && npm test` draaien.
- [ ] F-019 (machinewijzigingen door Claude): zie `docs/admin.md`.
  - [ ] Op GitHub het token van `deploy` bewerken: ook *Pull requests: Read-only* (voor de PR-meldingen).
  - [ ] `sudo bash /home/claude/homelab/scripts/19-admin.sh` (de eerste keer; daarna vanuit `/var/lib/homelab/repo`).
        Kies het wachtwoord voor je ntfy-login. Daarna past de box `changes/0001` toe (kernel en NVIDIA niet meer
        via unattended-upgrades).
  - [ ] ntfy-app op de telefoon: server `http://homelab.tail4eb878.ts.net:8102` (de volledige naam), je gebruiker, topic `homelab`; de testmelding komt binnen.
  - [ ] `homelab-admin changes` toont `0001` als toegepast.
  - [ ] Test: een PR die SSH via Tailscale blokkeert mergen, niet bevestigen; binnen 10 minuten werkt SSH weer en
        komt er een melding "teruggedraaid".

- [ ] F-020 (bewaking, overzicht, backups): zie `docs/monitor.md`.
  - [ ] `sudo bash /var/lib/homelab/repo/scripts/20-monitor.sh` (maakt meteen een backup en een eerste check).
  - [ ] `http://homelab.tail4eb878.ts.net:8104` op de telefoon toont het overzicht.
  - [ ] Test: een gedeployde dienst stoppen (`sudo systemctl stop homelab-app-claude-dashboard`): binnen 10 minuten
        een melding, en na `start` een melding "weer in orde".
  - [ ] De externe 2 TB USB-HDD als backupschijf: aansluiten, dan `sudo bash /var/lib/homelab/repo/scripts/20-usb-disk.sh`
        (toont de USB-schijven) en `sudo bash /var/lib/homelab/repo/scripts/20-usb-disk.sh <UUID>` met de partitie
        die je kiest. Zet de oude backups over en draait `20-monitor.sh`. Op de desktop gaat de HDD mee; de UUID blijft.
  - [ ] Later: de offline-melding (healthchecks.io, `/etc/homelab/heartbeat-url`).

## 14. Verhuizen naar de desktop **(desktop)**

- [ ] Nieuwe PSU erin. BIOS/UEFI van de desktop:
  - [ ] Bootmodus **UEFI**, CSM **uit**, Secure Boot **uit**.
  - [ ] SATA-modus **AHCI**.
  - [ ] **Restore on AC power loss** → Power On.
- [ ] Laptop uit, SSD naar de desktop, netwerkkabel erin, opstarten (zonder monitor als het kan).
- [ ] `ssh homelab` werkt over Tailscale. Lukt het niet: monitor erop en kijk met `ip a` of de kabelinterface een adres heeft.
- [ ] `nvidia-smi` toont de GTX 1080 met ~8192 MiB en geen processen.
- [ ] `sudo rm /etc/systemd/logind.conf.d/laptop-lid.conf`
- [ ] In `config/health.conf`: `EXPECTED_GPU="NVIDIA GeForce GTX 1080, 8192 MiB"` (commit en push), dan
      `sudo bash /home/claude/homelab/scripts/13-health-check.sh`; `homelab-health` slaagt.
- [ ] `sudo reboot`, en daarna stekker eruit en er weer in: de box komt beide keren terug, de health check slaagt en
      `homelab-deploy status` toont alle diensten draaiend.
- [ ] F-017: in `config/llm.conf` `LLM_NUM_CTX=8192` (het model `llama3.1:8b` blijft; commit en push), dan
      `sudo bash /home/claude/homelab/scripts/17-llm.sh`; `ollama ps` en `llm usage` tonen 100% GPU.
