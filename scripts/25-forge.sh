#!/usr/bin/env bash
# homelab: H-015, forge. Account `forge` (own HOME /var/lib/forge for its Claude CLI and gh logins), the state
# directory, and /etc/forge/env from config/forge.env (once, never overwritten). The unit itself comes from
# deploy/forge.service (scripts/18-projects.sh, which calls this script first so the account exists before the
# service starts) and the code from the `forge` line in config/repos.conf. Forge starts stopped after every restart.
# Also what forge needs to build its own projects (Chris, 2026-11-14: the world is a Rust server with a TypeScript
# client on PostgreSQL): a C toolchain, Rust (rustup from Ubuntu, the stable toolchain in forge's own home) and a
# local PostgreSQL that listens on its unix socket only, with a role `forge` that may create its own databases.
# Not done here, by hand once (needs a login): docs/projects.md, "forge".
# Draai als: sudo bash <homelab-repo>/scripts/25-forge.sh
# homelab-rerun: root scripts/25-forge.sh config/forge.env
set -euo pipefail

REPO="$(dirname "$(dirname "$(readlink -f "$0")")")"
if [[ $EUID -ne 0 ]]; then
  echo "Draai met sudo." >&2
  exit 1
fi

echo "== Account forge"
id forge >/dev/null 2>&1 || useradd -r -m -d /var/lib/forge -s /usr/sbin/nologin -U forge
install -d -m 700 -o forge -g forge /var/lib/forge /var/lib/forge/work

echo "== /etc/forge/env"
install -d -m 755 -o root -g root /etc/forge
[[ -e /etc/forge/env ]] || install -m 644 -o root -g root "$REPO/config/forge.env" /etc/forge/env

echo "== Build tools for forge's projects (C toolchain, Rust, PostgreSQL)"
# As forge and from /, never from root's or the caller's directory (forge cannot read those).
as_forge() { (cd / && runuser -u forge -- env HOME=/var/lib/forge "$@"); }
as_postgres() { (cd / && runuser -u postgres -- "$@"); }
pg_ready() {  # wait until the local cluster answers on its socket
  for _ in $(seq 1 15); do
    if as_postgres pg_isready -q; then return 0; fi
    sleep 2
  done
  echo "PostgreSQL does not answer on its unix socket" >&2
  return 1
}

# PostgreSQL comes first and is closed before anything else is fetched: unix socket only. Nothing listens on a TCP
# port, so there is nothing to reach from the LAN, the tailnet or a container; forge connects as itself over
# /var/run/postgresql (peer authentication, no password).
# A new cluster takes its settings from createcluster.d, so its very first start is socket-only already.
install -d -m 755 -o root -g root /etc/postgresql-common/createcluster.d
printf '%s\n' '# homelab (scripts/25-forge.sh): unix socket only, no TCP port.' "listen_addresses = ''" \
  > /etc/postgresql-common/createcluster.d/homelab-socket-only.conf
chmod 644 /etc/postgresql-common/createcluster.d/homelab-socket-only.conf
PACKAGES=(build-essential pkg-config libssl-dev rustup postgresql)
if ! dpkg -s "${PACKAGES[@]}" >/dev/null 2>&1; then
  apt-get update -q
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q "${PACKAGES[@]}"
fi
systemctl enable -q postgresql
systemctl is-active --quiet postgresql || systemctl start postgresql
pg_ready
# And for a cluster that existed before (or one that did not take the setting above): the same in its conf.d.
pg_config_file="$(as_postgres psql -AtXc 'show config_file')"
[[ -n $pg_config_file ]] || { echo "PostgreSQL did not say where its config file is" >&2; exit 1; }
PG_CONF_DIR="$(dirname "$pg_config_file")/conf.d"
[[ -d $PG_CONF_DIR ]] || { echo "PostgreSQL has no conf.d directory at $PG_CONF_DIR" >&2; exit 1; }
PG_DROPIN="$PG_CONF_DIR/homelab-socket-only.conf"
if [[ ! -e $PG_DROPIN ]] || ! grep -qx "listen_addresses = ''" "$PG_DROPIN"; then
  printf '%s\n' '# homelab (scripts/25-forge.sh): unix socket only, no TCP port.' "listen_addresses = ''" > "$PG_DROPIN"
  chmod 644 "$PG_DROPIN"
fi
listen="$(as_postgres psql -AtXc 'show listen_addresses')"
if [[ -n $listen ]]; then
  systemctl restart postgresql
  pg_ready
fi
# The role: may create (and so drop) its own databases, nothing more. No password: it cannot log in over TCP anyway.
# Set on every run, so a role that somehow got more rights loses them again.
as_postgres psql -AtXq -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'forge') THEN
    CREATE ROLE forge;
  END IF;
END
$$;
ALTER ROLE forge LOGIN CREATEDB NOSUPERUSER NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SQL

# Rust: Ubuntu's rustup puts cargo, rustc and the rest in /usr/bin as proxies; the toolchain itself lives in
# /var/lib/forge/.rustup and is fetched by rustup from static.rust-lang.org (as forge, never as root). Only when it
# is not there yet, so a re-run never changes the compiler under a running build; `rustup update` as forge is the
# manual way to move on.
if ! as_forge rustup which cargo >/dev/null 2>&1; then
  as_forge rustup toolchain install stable --profile minimal -c clippy -c rustfmt
  as_forge rustup default stable
fi

echo "== Controle"
id -u forge >/dev/null
as_forge cargo --version >/dev/null || { echo "forge has no working cargo" >&2; exit 1; }
as_forge cc --version >/dev/null || { echo "no C compiler for forge" >&2; exit 1; }
pg_ready
# `show` and not the file: it is what the running server really uses, whatever another config file says.
listen="$(as_postgres psql -AtXc 'show listen_addresses')" || { echo "cannot ask PostgreSQL what it listens on" >&2; exit 1; }
[[ -z $listen ]] || { echo "PostgreSQL listens on '$listen': it must be unix socket only." >&2; exit 1; }
# Nothing of PostgreSQL may listen on TCP (the socket is a file, not a port). The list is read first, so a failing
# `ss` stops the script instead of looking like "no listener".
listeners="$(ss -ltnpH)"
if grep -Eq 'postgres|:5432[[:space:]]' <<<"$listeners"; then
  echo "Something listens on PostgreSQL's TCP port: it must be unix socket only." >&2
  exit 1
fi
# The role has exactly the rights it should, and is a member of no other role.
rights="$(as_postgres psql -AtXc "select rolcanlogin, rolcreatedb, rolsuper, rolcreaterole, rolreplication, rolbypassrls, (select count(*) from pg_auth_members m where m.member = r.oid) from pg_roles r where rolname = 'forge'")"
[[ $rights == "t|t|f|f|f|f|0" ]] || { echo "the PostgreSQL role forge has other rights than expected: $rights" >&2; exit 1; }
# Forge reaches it as itself and can make and remove a database of its own.
who="$(as_forge psql -AtXc 'select current_user' postgres)" || { echo "forge cannot log in to PostgreSQL" >&2; exit 1; }
[[ $who == forge ]] || { echo "forge logs in to PostgreSQL as '$who'" >&2; exit 1; }
as_forge psql -AtXq -v ON_ERROR_STOP=1 -c 'DROP DATABASE IF EXISTS forge_selfcheck' postgres
as_forge psql -AtXq -v ON_ERROR_STOP=1 -c 'CREATE DATABASE forge_selfcheck' postgres
as_forge psql -AtXq -v ON_ERROR_STOP=1 -c 'DROP DATABASE forge_selfcheck' postgres
[[ $(stat -c %U:%a /var/lib/forge) == forge:700 ]]
[[ -s /etc/forge/env ]]
# Forge must never be told to bind a wildcard address, and never gets a key.
if grep -Eq '^(FORGE_BIND_HOST=(0\.0\.0\.0|::)|[A-Z_]*API_KEY=)' /etc/forge/env; then
  echo "/etc/forge/env binds a wildcard address or holds a key: fix it." >&2
  exit 1
fi
if sudo -u forge -H bash -c 'command -v claude' >/dev/null 2>&1; then
  echo "forge: claude CLI found."
else
  echo "forge: claude CLI not installed for the forge account yet (docs/projects.md, 'forge')."
fi
echo "forge account and config ready"
