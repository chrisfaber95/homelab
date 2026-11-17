#!/usr/bin/env bash
# homelab: H-023 (B2), the PostgreSQL database `inbox` for the Spring Boot backend that will run beside the old inbox.
# PostgreSQL from Ubuntu, unix socket only (Q2, Chris 2026-11-16: no TCP listener at all, `listen_addresses` empty).
# The role `inbox_app` owns the database `inbox` and nothing else: no superuser, no CREATEDB, no CREATEROLE, no
# password. It logs in over /var/run/postgresql by peer authentication, and only for the OS account the backend
# service runs as: `inbox-runner` (pg_ident map `homelab_inbox`, one pg_hba line for this database and this role).
# The cluster is shared with forge and ashfall (scripts/25-forge.sh, 27-ashfall.sh); this script touches none of
# their roles or databases, and never the old SQLite inbox (inbox.db). It ends with a round trip as the app role and
# a check that nothing of PostgreSQL listens on TCP, and fails loudly otherwise. Docs: docs/inbox-db.md.
# Run as: sudo bash <homelab-repo>/scripts/28-inbox-db.sh
# homelab-rerun: root scripts/28-inbox-db.sh
set -euo pipefail

APP_ROLE=inbox_app
APP_DB=inbox
SERVICE_USER=inbox-runner   # the account of homelab-inbox and (B3) homelab-inbox-backend.service
PG_MAP=homelab_inbox

if [[ $EUID -ne 0 ]]; then
  echo "Run with sudo." >&2
  exit 1
fi

# From /, never from root's or the caller's directory (the other accounts cannot read those).
as_postgres() { (cd / && runuser -u postgres -- "$@"); }
as_service() { (cd / && runuser -u "$SERVICE_USER" -- "$@"); }
pg_ready() {  # wait until the local cluster answers on its socket
  for _ in $(seq 1 15); do
    if as_postgres pg_isready -q; then return 0; fi
    sleep 2
  done
  echo "PostgreSQL does not answer on its unix socket" >&2
  return 1
}
# One place for "what does the running server say": `show`, not the file, whatever another config file says.
pg_show() { as_postgres psql -AtXc "show $1"; }

echo "== Account $SERVICE_USER"
# Normally made by scripts/21-inbox.sh (same command); here too, so the order of the scripts does not matter.
RUNNER_HOME=/var/lib/homelab-inbox
id "$SERVICE_USER" >/dev/null 2>&1 || useradd -r -m -d "$RUNNER_HOME" -s /usr/sbin/nologin -U "$SERVICE_USER"

echo "== PostgreSQL (Ubuntu package, unix socket only)"
# A new cluster takes its settings from createcluster.d, so its very first start is socket-only already. The same
# file as scripts/25-forge.sh writes: both scripts agree, and a re-run changes nothing.
install -d -m 755 -o root -g root /etc/postgresql-common/createcluster.d
printf '%s\n' '# homelab (scripts/25-forge.sh): unix socket only, no TCP port.' "listen_addresses = ''" \
  > /etc/postgresql-common/createcluster.d/homelab-socket-only.conf
chmod 644 /etc/postgresql-common/createcluster.d/homelab-socket-only.conf
if ! dpkg -s postgresql >/dev/null 2>&1; then
  apt-get update -q
  DEBIAN_FRONTEND=noninteractive apt-get install -y -q postgresql
fi
systemctl enable -q postgresql
systemctl is-active --quiet postgresql || systemctl start postgresql
pg_ready

# A cluster that existed before (or did not take the setting above): the same in its conf.d.
pg_config_file="$(pg_show config_file)"
[[ -n $pg_config_file ]] || { echo "PostgreSQL did not say where its config file is" >&2; exit 1; }
PG_CONF_DIR="$(dirname "$pg_config_file")/conf.d"
[[ -d $PG_CONF_DIR ]] || { echo "PostgreSQL has no conf.d directory at $PG_CONF_DIR" >&2; exit 1; }
PG_DROPIN="$PG_CONF_DIR/homelab-socket-only.conf"
if [[ ! -e $PG_DROPIN ]] || ! grep -qx "listen_addresses = ''" "$PG_DROPIN"; then
  printf '%s\n' '# homelab (scripts/25-forge.sh): unix socket only, no TCP port.' "listen_addresses = ''" > "$PG_DROPIN"
  chmod 644 "$PG_DROPIN"
fi
if [[ -n $(pg_show listen_addresses) ]]; then
  systemctl restart postgresql
  pg_ready
fi

echo "== Role $APP_ROLE and database $APP_DB"
# Rights are set on every run, so a role that somehow got more loses them again. No password: it cannot log in
# over TCP (there is none), and over the socket only peer authentication below lets it in.
as_postgres psql -AtXq -v ON_ERROR_STOP=1 <<SQL
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '$APP_ROLE') THEN
    CREATE ROLE $APP_ROLE;
  END IF;
END
\$\$;
ALTER ROLE $APP_ROLE LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
SQL
# CREATE DATABASE can't run inside a DO block; only when missing, never dropped or recreated.
if [[ -z $(as_postgres psql -AtXc "select 1 from pg_database where datname = '$APP_DB'") ]]; then
  as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c "CREATE DATABASE $APP_DB OWNER $APP_ROLE"
fi
as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c "ALTER DATABASE $APP_DB OWNER TO $APP_ROLE"
# Only the owner connects to it (forge, ashfall and PUBLIC do not); the superuser `postgres` always can.
as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c "REVOKE ALL ON DATABASE $APP_DB FROM PUBLIC"

echo "== Authentication: $SERVICE_USER may log in as $APP_ROLE over the socket (peer + map)"
HBA_FILE="$(pg_show hba_file)"
IDENT_FILE="$(pg_show ident_file)"
[[ -f $HBA_FILE && -f $IDENT_FILE ]] || { echo "cannot find pg_hba.conf or pg_ident.conf" >&2; exit 1; }
HBA_LINE="local   $APP_DB   $APP_ROLE   peer map=$PG_MAP"
IDENT_LINE="$PG_MAP   $SERVICE_USER   $APP_ROLE"
changed=0
if ! grep -qxF "$IDENT_LINE" "$IDENT_FILE"; then
  printf '# homelab (scripts/28-inbox-db.sh): the backend account may be the app role, and only that.\n%s\n' \
    "$IDENT_LINE" >> "$IDENT_FILE"
  changed=1
fi
if ! grep -qxF "$HBA_LINE" "$HBA_FILE"; then
  # First match wins, so the line goes before the first rule (the default `local all all peer` would otherwise
  # answer first and refuse the mapping). Written to a temp file next to the original, then moved over it.
  tmp="$(mktemp "$HBA_FILE.XXXXXX")"
  awk -v line="$HBA_LINE" '
    !done && $0 !~ /^[[:space:]]*(#|$)/ {
      print "# homelab (scripts/28-inbox-db.sh): the inbox backend, peer authentication through the map."
      print line
      done = 1
    }
    { print }
    END { if (!done) { print line } }' "$HBA_FILE" > "$tmp"
  chown --reference="$HBA_FILE" "$tmp"
  chmod --reference="$HBA_FILE" "$tmp"
  mv "$tmp" "$HBA_FILE"
  changed=1
fi
if [[ $changed -eq 1 ]]; then
  as_postgres psql -AtXq -v ON_ERROR_STOP=1 -c 'select pg_reload_conf()' >/dev/null
  sleep 1
fi
bad="$(as_postgres psql -AtXc "select count(*) from pg_hba_file_rules where error is not null")"
[[ $bad == 0 ]] || { echo "pg_hba.conf has $bad rule(s) with an error: fix it before going on" >&2; exit 1; }

echo "== Controle"
pg_ready
# Still socket only, in the running server and in the list of listeners. The list is read first, so a failing `ss`
# stops the script instead of looking like "no listener".
listen="$(pg_show listen_addresses)" || { echo "cannot ask PostgreSQL what it listens on" >&2; exit 1; }
[[ -z $listen ]] || { echo "PostgreSQL listens on '$listen': it must be unix socket only (Q2)." >&2; exit 1; }
listeners="$(ss -ltnpH)"
if grep -Eq 'postgres|:5432[[:space:]]' <<<"$listeners"; then
  echo "Something listens on PostgreSQL's TCP port: it must be unix socket only (Q2)." >&2
  exit 1
fi
# The app role has exactly the rights it should, and is a member of no other role.
rights="$(as_postgres psql -AtXc "select rolcanlogin, rolcreatedb, rolsuper, rolcreaterole, rolreplication, rolbypassrls, (select count(*) from pg_auth_members m where m.member = r.oid) from pg_roles r where rolname = '$APP_ROLE'")"
[[ $rights == "t|f|f|f|f|f|0" ]] || { echo "the PostgreSQL role $APP_ROLE has other rights than expected: $rights" >&2; exit 1; }
owner="$(as_postgres psql -AtXc "select pg_get_userbyid(datdba) from pg_database where datname = '$APP_DB'")"
[[ $owner == "$APP_ROLE" ]] || { echo "the database $APP_DB is owned by '$owner'" >&2; exit 1; }
# Round trip as the app role, as the backend's account, over the socket: temp table, write, read back, drop.
as_service true || { echo "cannot run anything as $SERVICE_USER" >&2; exit 1; }
who="$(as_service psql -AtXc 'select current_user' -U "$APP_ROLE" "$APP_DB")" \
  || { echo "$SERVICE_USER cannot log in to $APP_DB as $APP_ROLE over the socket" >&2; exit 1; }
[[ $who == "$APP_ROLE" ]] || { echo "$SERVICE_USER logs in to PostgreSQL as '$who'" >&2; exit 1; }
rt="$(as_service psql -AtXq -v ON_ERROR_STOP=1 -U "$APP_ROLE" "$APP_DB" <<'SQL'
CREATE TEMP TABLE homelab_roundtrip (id integer PRIMARY KEY, note text NOT NULL);
INSERT INTO homelab_roundtrip VALUES (1, 'round trip');
SELECT note FROM homelab_roundtrip WHERE id = 1;
DROP TABLE homelab_roundtrip;
SQL
)" || { echo "the round trip as $APP_ROLE failed" >&2; exit 1; }
[[ $rt == "round trip" ]] || { echo "the round trip read back '$rt'" >&2; exit 1; }
# The app role must not get further than its own database.
if (cd / && runuser -u "$SERVICE_USER" -- psql -AtXq -U "$APP_ROLE" -c 'select 1' postgres >/dev/null 2>&1); then
  echo "$APP_ROLE can connect to the database postgres: it may only use $APP_DB" >&2
  exit 1
fi
echo "inbox database ready: $APP_DB, role $APP_ROLE (peer, for $SERVICE_USER), no TCP listener"
