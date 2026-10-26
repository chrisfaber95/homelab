#!/usr/bin/env bash
# homelab: F-021, de task inbox.
# - Account inbox: hier draaien de taken (Claude in auto mode). Geen GitHub-toegang, geen toegang tot /home/claude.
# - Account inbox-runner: de pagina en de wachtrij (dienst homelab-inbox); pusht inbox/<id>-branches en opent PR's met
#   een eigen token, en mag precies één opdracht als inbox draaien (homelab-inbox-task, via sudo).
# - Drie tokens: Claude Code voor inbox (claude setup-token), GitHub voor inbox-runner, ntfy voor inbox-runner.
# - Jobs en skills (F-022) zitten in dezelfde dienst; de jobs staan op de branch jobs van de homelab-repo.
# Opnieuw draaien na een wijziging aan bin/homelab-inbox*, bin/homelab-admin, bin/homelab-health of config/inbox.conf.
# Draai als: sudo bash /var/lib/homelab/repo/scripts/21-inbox.sh
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
DEST=/usr/local/lib/homelab

if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi
for f in deploy admin inbox; do
  # shellcheck disable=SC1090
  source "$REPO/config/$f.conf"
done

echo "== Pakketten (jobs, F-022: YAML-koppen en het kopiëren van skills)"
dpkg -s python3-yaml rsync >/dev/null 2>&1 || apt-get install -y -qq python3-yaml rsync

echo "== Accounts en mappen"
getent group inbox-share >/dev/null || groupadd -r inbox-share
id inbox >/dev/null 2>&1 || useradd -r -m -d "$TASK_HOME" -s /bin/bash -U inbox
id inbox-runner >/dev/null 2>&1 || useradd -r -m -d "$RUNNER_HOME" -s /usr/sbin/nologin -U inbox-runner
usermod -a -G inbox-share inbox
usermod -a -G inbox-share inbox-runner
install -d -m 2750 -o inbox-runner -g inbox-share "$RUNNER_HOME" "$RUNNER_HOME/repos" "$RUNNER_HOME/share"
install -d -m 2750 -o inbox -g inbox-share "$TASK_HOME" "$TASK_HOME/out"
install -d -m 700 -o inbox -g inbox "$TASK_HOME/work"

echo "== Claude Code voor inbox"
if [[ ! -x "$TASK_HOME/.local/bin/claude" ]]; then
  (cd / && sudo -u inbox -H bash -c 'curl -fsSL https://claude.ai/install.sh | bash')
fi
"$TASK_HOME/.local/bin/claude" --version

echo "== Programma's naar $DEST"
install -d -m 755 "$DEST/bin" "$DEST/config"
for f in homelab-inbox homelab-inbox-task homelab-admin homelab-health; do
  install -m 755 "$REPO/bin/$f" "$DEST/bin/$f"
done
for f in inbox.conf admin.conf deploy.conf llm.conf; do install -m 644 "$REPO/config/$f" "$DEST/config/$f"; done
ln -sfn "$DEST/bin/homelab-inbox" /usr/local/bin/homelab-inbox

echo "== sudo: inbox-runner mag alleen homelab-inbox-task als inbox draaien"
cat > /etc/sudoers.d/homelab-inbox.tmp <<EOF
# homelab (F-021): de inbox-dienst start taken als inbox; verder niets.
inbox-runner ALL=(inbox) NOPASSWD: $DEST/bin/homelab-inbox-task
EOF
visudo -cf /etc/sudoers.d/homelab-inbox.tmp
chmod 440 /etc/sudoers.d/homelab-inbox.tmp
mv /etc/sudoers.d/homelab-inbox.tmp /etc/sudoers.d/homelab-inbox

ask_token() { # ask_token <bestand> <eigenaar> <uitleg> <testfunctie>
  local file=$1 owner=$2 help=$3 test=$4 t
  for attempt in 1 2 3; do
    if [[ -s $file ]]; then chown "$owner:$owner" "$file"; chmod 600 "$file"; fi
    if [[ -s $file ]] && $test; then echo "Het token in $file werkt."; return 0; fi
    (( attempt == 3 )) && { echo "Het token voor $file werkt na drie pogingen niet." >&2; exit 1; }
    [[ -s $file ]] && echo "Het token in $file werkt niet."
    echo "$help"
    # Een lang token valt in de terminal vaak over twee regels en komt dan met een regeleinde mee: lees regels tot een
    # lege regel en plak ze aan elkaar.
    echo "Plak het token (je ziet het niet) en druk daarna twee keer op Enter:"
    t=""
    while IFS= read -r -s line </dev/tty; do
      line=$(tr -d '[:space:]' <<<"$line")
      [[ -z $line && -n $t ]] && break
      t+=$line
    done
    echo
    echo "Ontvangen: ${#t} tekens, begint met '${t:0:12}'."
    (umask 077 && printf '%s\n' "$t" > "$file")
    unset t
  done
}
claude_works() {
  # Het token komt binnen het proces van inbox uit het bestand, niet op de opdrachtregel (die ziet iedereen in ps).
  local out err
  err=$(mktemp)
  out=$(cd / && sudo -u inbox env HOME="$TASK_HOME" bash -c 'CLAUDE_CODE_OAUTH_TOKEN=$(cat /etc/homelab/inbox-claude-token) \
    exec ~/.local/bin/claude -p "Antwoord met alleen het woord: ok" --permission-mode auto' 2>"$err") || true
  if grep -qiw ok <<<"$out"; then rm -f "$err"; return 0; fi
  echo "Claude Code gaf: $(tail -n 5 "$err" | tr '\n' ' ')$(tail -n 3 <<<"$out" | tr '\n' ' ')"
  rm -f "$err"
  return 1
}
github_works() {
  [[ $(curl -s -o /dev/null -w '%{http_code}' -H "Authorization: Bearer $(cat /etc/homelab/inbox-github-token)" \
       "https://api.github.com/repos/$HOMELAB_REPO") == 200 ]]
}

echo "== Tokens"
ask_token /etc/homelab/inbox-claude-token inbox \
  "Claude Code voor inbox: draai ergens (bijvoorbeeld op Windows, of als claude op de box) 'claude setup-token' en plak het token (begint met sk-ant-oat01-, ruim 100 tekens)." \
  claude_works
ask_token /etc/homelab/inbox-github-token inbox-runner \
  "GitHub voor de inbox: een fine-grained token, All repositories, Contents en Pull requests: Read and write." \
  github_works
if [[ ! -s /etc/homelab/inbox-ntfy-token ]]; then
  t=$(runuser -u ntfy -- /usr/local/bin/ntfy token --config /etc/ntfy/server.yml add homelab-bot 2>&1 | grep -o 'tk_[a-z0-9]*')
  [[ -n $t ]] || { echo "Geen ntfy-token gekregen." >&2; exit 1; }
  (umask 077 && printf '%s\n' "$t" > /etc/homelab/inbox-ntfy-token)
  unset t
fi
chown inbox-runner:inbox-runner /etc/homelab/inbox-ntfy-token
chmod 600 /etc/homelab/inbox-ntfy-token

echo "== Dienst homelab-inbox"
cat > /etc/systemd/system/homelab-inbox.service <<EOF
[Unit]
Description=homelab-inbox: taken voor Claude vanaf de telefoon (F-021)
After=network-online.target tailscaled.service ntfy.service
Wants=network-online.target

[Service]
User=inbox-runner
Group=inbox-runner
SupplementaryGroups=inbox-share
UMask=0027
ExecStart=/usr/bin/python3 $DEST/bin/homelab-inbox serve
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable homelab-inbox
systemctl restart homelab-inbox homelab-admin

echo "== Controle"
systemctl is-active homelab-inbox
for _ in $(seq 15); do ss -Htln "sport = :$INBOX_PORT" | grep -q . && break; sleep 1; done
ss -Htln "sport = :$INBOX_PORT" | grep -q . || { echo "homelab-inbox luistert niet op $INBOX_PORT." >&2; exit 1; }
# Vanaf de box zelf hoort de pagina geweigerd te worden (alleen jouw apparaten op het tailnet).
code=$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$INBOX_PORT/")
[[ $code == 403 ]] || { echo "De pagina antwoordt vanaf de box zelf met $code (verwacht 403)." >&2; exit 1; }
# inbox kan niet bij GitHub-gegevens of bij claude.
for f in /etc/homelab/inbox-github-token /home/claude/.config/gh/hosts.yml /home/claude/dev; do
  if sudo -u inbox test -r "$f"; then echo "inbox kan $f lezen." >&2; exit 1; fi
done
if sudo -u inbox test -w /srv/homelab/repos; then echo "inbox kan in de deploy-clones schrijven." >&2; exit 1; fi
sudo -u inbox-runner sudo -n -u inbox "$DEST/bin/homelab-inbox-task" cleanup 0
echo "Klaar. Op je telefoon: http://$TAILNET_NAME:$INBOX_PORT"
