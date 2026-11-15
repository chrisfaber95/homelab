#!/usr/bin/env bash
# homelab: H-015, ashfall-world (forge's world) on the box. The account `ashfall` the unit runs as, and in the local
# PostgreSQL (socket only, set up by scripts/25-forge.sh) the role `ashfall` with its database `ashfall`. The role logs
# in as itself over the unix socket (peer authentication, no password), owns only its own database and can't create
# others. It is separate from the role `forge`, so forge's builds and tests never touch the live world.
# The unit is deploy/ashfall-world.service (scripts/18-projects.sh calls this script before it installs the units),
# the code comes from the `ashfall-world` line in config/repos.conf, the build from deploy/ashfall-world.build.
# Run as: sudo bash <homelab-repo>/scripts/27-ashfall.sh
# homelab-rerun: root scripts/27-ashfall.sh
set -euo pipefail

if [[ $EUID -ne 0 ]]; then
  echo "Run with sudo." >&2
  exit 1
fi

as_postgres() { (cd / && runuser -u postgres -- "$@"); }
as_ashfall() { (cd / && runuser -u ashfall -- "$@"); }

echo "== Account ashfall"
id ashfall >/dev/null 2>&1 || useradd -r -M -d /nonexistent -s /usr/sbin/nologin -U ashfall

echo "== PostgreSQL: role and database ashfall"
systemctl is-active --quiet postgresql || { echo "PostgreSQL is not running: run scripts/25-forge.sh first" >&2; exit 1; }
for _ in $(seq 1 15); do as_postgres pg_isready -q && break; sleep 2; done
as_postgres pg_isready -q
# The rights are set on every run, so a role that somehow got more loses them again.
as_postgres psql -AtXq -v ON_ERROR_STOP=1 <<'SQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'ashfall') THEN
    CREATE ROLE ashfall;
  END IF;
END
$$;
ALTER ROLE ashfall LOGIN NOCREATEDB NOSUPERUSER NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SQL
# CREATE DATABASE can't run inside a DO block; create it only when it is missing, never drop or recreate it.
if [[ -z $(as_postgres psql -AtXc "select 1 from pg_database where datname = 'ashfall'") ]]; then
  as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c 'CREATE DATABASE ashfall OWNER ashfall'
fi
as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c 'ALTER DATABASE ashfall OWNER TO ashfall'

echo "== Check"
id -u ashfall >/dev/null
rights="$(as_postgres psql -AtXc "select rolcanlogin, rolcreatedb, rolsuper, rolcreaterole, rolreplication, rolbypassrls, (select count(*) from pg_auth_members m where m.member = r.oid) from pg_roles r where rolname = 'ashfall'")"
[[ $rights == "t|f|f|f|f|f|0" ]] || { echo "the PostgreSQL role ashfall has other rights than expected: $rights" >&2; exit 1; }
owner="$(as_postgres psql -AtXc "select pg_get_userbyid(datdba) from pg_database where datname = 'ashfall'")"
[[ $owner == ashfall ]] || { echo "the database ashfall is owned by '$owner'" >&2; exit 1; }
who="$(as_ashfall psql -AtXc 'select current_user' ashfall)" || { echo "ashfall cannot log in to its database" >&2; exit 1; }
[[ $who == ashfall ]] || { echo "ashfall logs in to PostgreSQL as '$who'" >&2; exit 1; }
# Still socket only (25-forge.sh sets it; a world database is no reason to open a port).
[[ -z $(as_postgres psql -AtXc 'show listen_addresses') ]] || { echo "PostgreSQL listens on TCP: it must be socket only" >&2; exit 1; }
echo "ashfall account and database ready"
