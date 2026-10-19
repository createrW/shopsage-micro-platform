#!/usr/bin/env bash
# Database-per-service on one Postgres instance (ADR-0001): for each service, create a login role
# shopsage_<svc> and a database shopsage_<svc> owned by it, then restrict CONNECT to that role, so a
# service physically cannot read another service's data (cross-service joins are impossible).
#
# Run by the postgres image's entrypoint from /docker-entrypoint-initdb.d, ONCE, on an empty data
# volume, as POSTGRES_USER against a socket-only temporary server. Passwords come from
# <SVC>_DB_PASSWORD (see .env.example). To re-run: ./scripts/dev-down.sh --volumes.
set -euo pipefail

readonly SERVICES=(user inventory order payment)

# One psql process per statement: CREATE DATABASE cannot run inside a transaction block, so it is
# never bundled with other statements. SQL is fed on stdin rather than -c because psql only
# interpolates :'var' / :"var" in scripts, and that interpolation quotes identifiers and the
# password literal correctly whatever characters they contain.
run_sql() {
  local name=$1 password=$2 sql=$3
  psql -v ON_ERROR_STOP=1 --no-psqlrc --quiet \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -v name="$name" -v pw="$password" <<<"$sql"
}

for svc in "${SERVICES[@]}"; do
  name="shopsage_${svc}"
  password_var="${svc^^}_DB_PASSWORD"
  password="${!password_var:-$name}"

  echo "init: creating role and database ${name}"
  # \gexec runs the generated statement only when the role/database is missing (idempotent).
  run_sql "$name" "$password" \
    "SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'name', :'pw')
     WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'name') \gexec"
  run_sql "$name" "$password" \
    "SELECT format('CREATE DATABASE %I OWNER %I', :'name', :'name')
     WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = :'name') \gexec"
  # New databases grant CONNECT to PUBLIC by default; take it back so only the owner gets in.
  run_sql "$name" "$password" "REVOKE CONNECT ON DATABASE :\"name\" FROM PUBLIC;"
  run_sql "$name" "$password" "GRANT CONNECT ON DATABASE :\"name\" TO :\"name\";"
done

echo "init: databases ready: ${SERVICES[*]/#/shopsage_}"
