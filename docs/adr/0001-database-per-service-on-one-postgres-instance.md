# ADR-0001: Database per service on one PostgreSQL instance

- **Status:** Accepted (2026-10-18)
- **Deciders:** Yi Zhao

## Context

Four services (user, inventory, order, payment) each own their data. Microservice data ownership only holds if one
service cannot read or join another's tables. At the same time the whole stack — four services, Kafka, Redis and the
observability stack — must run on a 16 GB laptop and on a free-tier ARM VM. Options considered:

1. One database, one schema per service — cheapest, but PostgreSQL allows cross-schema joins, so isolation is a convention.
2. One PostgreSQL instance per service — strongest isolation, but four Postgres processes (shared_buffers,
   connections, WAL and background workers each) - roughly 4x the 768 MiB budget on a 16 GB laptop.
3. **One instance, one logical database and one login role per service.**

## Decision

Option 3. A single `postgres:16.15-alpine` container hosts `shopsage_user`, `shopsage_inventory`, `shopsage_order` and
`shopsage_payment`, each owned by a role of the same name. `configs/postgres/init/01-create-databases.sh` creates them on
first boot and runs `REVOKE CONNECT ... FROM PUBLIC` plus `GRANT CONNECT` to the owner only, so a service's credentials
cannot even open another service's database.

Each service owns its schema through Flyway (`classpath:db/migration`); Hibernate only validates (`ddl-auto: validate`).
Data another service needs travels in Kafka events, never through SQL.

## Consequences

- **Positive:** PostgreSQL has no cross-database joins, so coupling through the database is impossible rather than
  discouraged. Same memory cost locally as schemas.
- **Positive:** moving a service to its own instance (or a managed database) changes only `DB_URL`, `DB_USERNAME` and
  `DB_PASSWORD` — no code change.
- **Negative:** the instance is a shared failure and capacity domain: one service's runaway query or connection storm
  affects all four. Mitigated locally by `max_connections=200` and per-service Hikari pools (`DB_POOL_SIZE`, default 20);
  in Kubernetes the chart can split instances.
- **Negative:** no cross-service reporting queries; analytics would need an event-fed read model.
- **Neutral:** the init script only runs on an empty volume; changing passwords later requires `./scripts/dev-down.sh -v`
  (data loss, acceptable for a dev stack).
