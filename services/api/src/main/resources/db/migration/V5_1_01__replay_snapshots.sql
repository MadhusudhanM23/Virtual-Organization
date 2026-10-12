-- Replay snapshots (REQ-060, ADR-09, ADR-23, ADR-44): per-site checkpoints of the twin-owned, replay-authoritative state
-- (entity poses and status, task and incident lifecycle state, recent proximity alerts) plus the recorded, not
-- reconstructed context (dock states, world version). A snapshot is immutable once written: the application may insert
-- and prune rows but never update them. Runs as the owner role (ADR-28).

CREATE SCHEMA IF NOT EXISTS rpl;
GRANT USAGE ON SCHEMA rpl TO vo_app;

CREATE TABLE rpl.snapshots (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    -- High-water mark (ADR-45): the snapshot reflects every replay event with occurred_at <= as_of that was visible
    -- when it was taken; taken_at is the wall-clock instant the snapshot read the event history.
    as_of timestamptz NOT NULL,
    taken_at timestamptz NOT NULL,
    trigger varchar(12) NOT NULL CHECK (trigger IN ('SCHEDULED', 'ON_DEMAND')),
    -- GENESIS: read from the live projections (no earlier snapshot); DERIVED: previous snapshot + events (ADR-44).
    origin varchar(8) NOT NULL CHECK (origin IN ('GENESIS', 'DERIVED')),
    base_snapshot_id uuid,
    format_version int NOT NULL CHECK (format_version >= 1),
    world_version int,
    entity_count int NOT NULL,
    task_count int NOT NULL,
    incident_count int NOT NULL,
    alert_count int NOT NULL,
    events_applied int NOT NULL,
    -- SHA-256 (hex) of the canonical JSON state; the state itself is that JSON, gzip-compressed.
    state_sha256 char(64) NOT NULL,
    state_gzip bytea NOT NULL,
    size_bytes int NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((origin = 'GENESIS') = (base_snapshot_id IS NULL))
);
ALTER TABLE rpl.snapshots ENABLE ROW LEVEL SECURITY;
ALTER TABLE rpl.snapshots FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON rpl.snapshots
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX snapshots_site_as_of_idx ON rpl.snapshots (tenant_id, site_id, as_of DESC, id DESC);

-- Snapshots are written once and pruned by retention; never edited.
GRANT SELECT, INSERT, DELETE ON rpl.snapshots TO vo_app;

-- Late-event lookup (ADR-45): events committed after a snapshot was taken whose occurred_at lies before its as_of.
-- Created on the partitioned parent, so every existing and future daily partition gets it.
CREATE INDEX events_tenant_ingested_idx ON evt.events (tenant_id, ingested_at);
