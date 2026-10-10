-- Baseline schemas and database roles (ADR-28). Runs as the owner role (vo_owner / POSTGRES_USER).
-- Role passwords come from Flyway placeholders; roles are cluster-wide, so creation is idempotent.

CREATE SCHEMA IF NOT EXISTS iam;
CREATE SCHEMA IF NOT EXISTS world;
CREATE SCHEMA IF NOT EXISTS ops;
CREATE SCHEMA IF NOT EXISTS evt;
CREATE SCHEMA IF NOT EXISTS audit;
CREATE SCHEMA IF NOT EXISTS cmd;
CREATE SCHEMA IF NOT EXISTS sim;
CREATE SCHEMA IF NOT EXISTS rec;
CREATE SCHEMA IF NOT EXISTS kpi;
CREATE SCHEMA IF NOT EXISTS ai;
CREATE SCHEMA IF NOT EXISTS integ;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'vo_app') THEN
        CREATE ROLE vo_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'vo_outbox') THEN
        CREATE ROLE vo_outbox LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'vo_sim') THEN
        CREATE ROLE vo_sim LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
    END IF;
END
$$;

ALTER ROLE vo_app PASSWORD '${app_password}';
ALTER ROLE vo_outbox PASSWORD '${outbox_password}';
ALTER ROLE vo_sim PASSWORD '${sim_password}';

-- Schema usage.
GRANT USAGE ON SCHEMA iam, world, ops, evt, audit, cmd, sim, rec, kpi, ai, integ TO vo_app;
GRANT USAGE ON SCHEMA evt TO vo_outbox;
GRANT USAGE ON SCHEMA sim TO vo_sim;

-- Default privileges for objects the owner creates later.
-- vo_app: DML on every schema except audit (append-only: SELECT, INSERT).
ALTER DEFAULT PRIVILEGES IN SCHEMA iam, world, ops, evt, cmd, sim, rec, kpi, ai, integ
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO vo_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA iam, world, ops, evt, cmd, sim, rec, kpi, ai, integ
    GRANT USAGE, SELECT ON SEQUENCES TO vo_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit
    GRANT SELECT, INSERT ON TABLES TO vo_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA audit
    GRANT USAGE, SELECT ON SEQUENCES TO vo_app;

-- vo_outbox: evt schema only.
ALTER DEFAULT PRIVILEGES IN SCHEMA evt
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO vo_outbox;
ALTER DEFAULT PRIVILEGES IN SCHEMA evt
    GRANT USAGE, SELECT ON SEQUENCES TO vo_outbox;

-- vo_sim: sim schema only.
ALTER DEFAULT PRIVILEGES IN SCHEMA sim
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO vo_sim;
ALTER DEFAULT PRIVILEGES IN SCHEMA sim
    GRANT USAGE, SELECT ON SEQUENCES TO vo_sim;
