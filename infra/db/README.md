# Database (local development)

PostgreSQL 16 with PostGIS runs as the `postgres` service of [`infra/docker/compose.yaml`](../docker/compose.yaml)
(image tag recorded in [`.gsd/STACK.md`](../../.gsd/STACK.md)). This folder documents the database and holds any
scripts that Flyway does **not** own.

**Flyway owns the schema.** Schemas, roles, grants, tables and row-level security policies are created only by
migrations in `services/api/src/main/resources/db/migration/`, named `V{phase}_{plan}_{seq}__{description}.sql`
(ADR-28). Applied migrations are never edited. There are deliberately no `docker-entrypoint-initdb.d` init scripts:
anything created outside Flyway would not exist in Testcontainers, CI or any other environment.

## Roles (ADR-28)

| Role | Created by | Used by | Rules |
|---|---|---|---|
| `vo_owner` | The container, from `POSTGRES_USER` | Flyway migrations (and test fixtures) | Owns all tables. The image makes it a superuser; that is acceptable **only inside the local dev container**. The API never connects as `vo_owner` at runtime. |
| `vo_app` | Flyway (`V1_2_01__baseline_schemas_and_roles.sql`, Plan 1.2) | API runtime | Non-superuser, no `BYPASSRLS`; forced RLS applies to every tenant table; `SELECT`/`INSERT` only on `audit.*`. |
| `vo_outbox` | Flyway | Outbox publisher only | `BYPASSRLS`; grants limited to the `evt` schema. |
| `vo_sim` | Flyway | Simulation runs | Writes only the `sim` schema. |

Passwords come from `.env` (`POSTGRES_PASSWORD`, `VO_APP_DB_PASSWORD`, `VO_OUTBOX_DB_PASSWORD`,
`VO_SIM_DB_PASSWORD`; dev placeholders, see [`.env.example`](../../.env.example)) and reach the role-creation
migration as Flyway placeholders.

## Row-level security

RLS is enforced for `vo_app`. Every tenant table carries `tenant_id uuid not null` and, per
[`.gsd/ARCHITECTURE.md`](../../.gsd/ARCHITECTURE.md):

```sql
ALTER TABLE … ENABLE ROW LEVEL SECURITY;
ALTER TABLE … FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON …
  USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
  WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
```

The API binds the tenant on connection checkout (`set_config('app.tenant_id', …)`) and resets it on release. With no
tenant bound, RLS returns nothing. Superusers and `BYPASSRLS` roles skip RLS entirely, which is why the application
must never run as `vo_owner` or `vo_outbox`.

## Schemas (ADR-28)

One schema per module, created by Flyway: `iam`, `world`, `ops`, `evt`, `audit`, `cmd`, `sim`, `rec`, `kpi`, `ai`,
`integ`. Demo seed data is not a migration; it is a deterministic seeder active only in the `local`/`demo` profiles.

## Extensions

On first start (empty volume) the PostGIS image creates these extensions in `POSTGRES_DB`: `postgis`,
`postgis_topology`, `postgis_tiger_geocoder` and `fuzzystrmatch`. Migrations that need PostGIS should still use
`CREATE EXTENSION IF NOT EXISTS postgis`, so they also work where PostGIS is installed but not yet enabled.

## Common tasks

Open a `psql` shell (same command in PowerShell and Bash; in Git Bash on Windows, prefix it with `winpty` if you see
"the input device is not a TTY"):

```
docker exec -it vorg-postgres-1 psql -U vo_owner -d virtual_org
```

Reset local data. `down -v` deletes the `vorg_postgres-data` volume; the next `up` re-initializes the database:

```
docker compose -f infra/docker/compose.yaml --env-file .env down -v
docker compose -f infra/docker/compose.yaml --env-file .env up -d
```
