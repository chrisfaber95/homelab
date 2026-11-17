# Inbox database (H-023, task B2)

PostgreSQL database `inbox` for the Spring Boot backend that will run beside the old inbox
([`docs/architecture/inbox-split.md`](architecture/inbox-split.md)). Nothing uses it yet and the old SQLite inbox
(`inbox.db`) is not touched: this script only prepares an empty database.

Installer: `scripts/28-inbox-db.sh` (root, idempotent, `# homelab-rerun: root scripts/28-inbox-db.sh`).
Run it from root's clone: `sudo bash /var/lib/homelab/repo/scripts/28-inbox-db.sh`.

## What it sets up

| What | Value |
| --- | --- |
| Package | `postgresql` from Ubuntu (the cluster is shared with forge and ashfall, `scripts/25-forge.sh`, `27-ashfall.sh`) |
| Reachability (Q2) | unix socket only: `listen_addresses = ''`, no TCP listener at all. Backend connects through the socket (junixsocket for pgjdbc, B1/B4), socket directory `/var/run/postgresql` |
| Database | `inbox`, owned by `inbox_app`; `CONNECT` is revoked from `PUBLIC`, so forge and ashfall cannot open it |
| App role | `inbox_app`: `LOGIN`, `NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS`, member of no role, no password. Rights are set again on every run |
| Who may use it | the OS account **`inbox-runner`** (the account of `homelab-inbox` and, from B3, `homelab-inbox-backend.service`). Peer authentication: `pg_ident.conf` map `homelab_inbox` (`inbox-runner` → `inbox_app`) and one `pg_hba.conf` line `local inbox inbox_app peer map=homelab_inbox`, placed before the default rules. Nobody else gets in as `inbox_app` |

The backend therefore connects as `inbox_app` to `inbox` with no password; the unit must run as `inbox-runner`
(a `DynamicUser` would have a different name and be refused). Flyway runs as that same role and owns the schema, so
the app role needs nothing beyond owning its database.

## Checks (the script fails loudly when one fails)

1. PostgreSQL answers on its socket and `show listen_addresses` is empty.
2. `ss -ltnpH` shows no `postgres` process and nothing on port 5432.
3. `inbox_app` has exactly the rights above, and owns `inbox`.
4. `pg_hba.conf` has no rule with an error.
5. Round trip as `inbox-runner` → `inbox_app` over the socket: create a temp table, insert a row, read it back, drop
   it. Nothing is left behind (temp tables vanish with the session).
6. `inbox_app` cannot connect to the database `postgres`.

## Rollback

Revert the commit. The empty database and role may stay (nothing uses them); to remove them by hand as the
`postgres` user: `DROP DATABASE inbox; DROP ROLE inbox_app;` and delete the two lines marked
`homelab (scripts/28-inbox-db.sh)` from `pg_hba.conf` and `pg_ident.conf`.

## Loading the old data (H-023, task B5)

The one-off `import-sqlite` command of `backend/` fills the empty tables from `inbox.db` (read-only, one
transaction, row counts compared, rolled back on a mismatch). How to run it and its exit codes: `backend/README.md`,
section "One-off import of `inbox.db`". It needs this script (B2), the installed backend jar (B3) and the Flyway
baseline (B4), and must run as `inbox-runner`.
