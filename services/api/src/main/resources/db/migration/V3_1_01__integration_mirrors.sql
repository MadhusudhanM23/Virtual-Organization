-- Integration mirrors (REQ-040, REQ-041, blueprint section 3/14): records pulled from external source systems
-- (SIM-WMS / SIM-TMS in the MCD) are stored as labeled mirrors: they keep source_system, source_record_id, the source
-- version and timestamp, and are never silently overwritten (every applied version is kept in mirror_revisions;
-- a same-version/different-content record or an older version becomes an integration finding, not an overwrite).
-- Runs as the owner role; vo_app privileges come from the default privileges of V1_2_01.

CREATE TABLE integ.mirror_records (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    source_system varchar(40) NOT NULL,
    record_kind varchar(20) NOT NULL CHECK (record_kind IN ('ORDER', 'INVENTORY', 'SHIPMENT', 'TASK')),
    source_record_id varchar(200) NOT NULL,
    source_version bigint NOT NULL CHECK (source_version >= 1),
    source_updated_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    content_hash char(64) NOT NULL,
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    revisions int NOT NULL DEFAULT 1,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_applied_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, source_system, record_kind, source_record_id)
);
ALTER TABLE integ.mirror_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE integ.mirror_records FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integ.mirror_records
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX mirror_records_site_kind_idx ON integ.mirror_records (tenant_id, site_id, record_kind, last_applied_at DESC, id);

-- Append-only history of every applied version of a mirrored record.
CREATE TABLE integ.mirror_revisions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    mirror_id uuid NOT NULL REFERENCES integ.mirror_records (id),
    source_version bigint NOT NULL CHECK (source_version >= 1),
    source_updated_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    content_hash char(64) NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (mirror_id, source_version)
);
ALTER TABLE integ.mirror_revisions ENABLE ROW LEVEL SECURITY;
ALTER TABLE integ.mirror_revisions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integ.mirror_revisions
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE UPDATE, DELETE ON integ.mirror_revisions FROM vo_app;

-- Reconciliation differences and data-quality failures (REQ-041). One finding per (record, version, type): replays of
-- the same batch never duplicate a finding. Each finding is also announced as an INTEGRATION_FINDING_RECORDED event.
CREATE TABLE integ.findings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    source_system varchar(40) NOT NULL,
    record_kind varchar(20) NOT NULL CHECK (record_kind IN ('ORDER', 'INVENTORY', 'SHIPMENT', 'TASK')),
    source_record_id varchar(200) NOT NULL,
    source_version bigint NOT NULL,
    finding_type varchar(30) NOT NULL
        CHECK (finding_type IN ('DATA_QUALITY', 'STALE_VERSION', 'VERSION_CONFLICT', 'RECONCILIATION_DIFF')),
    severity varchar(10) NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'ERROR')),
    detail jsonb NOT NULL,
    status varchar(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED')),
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    detected_at timestamptz NOT NULL DEFAULT now(),
    event_id uuid,
    UNIQUE (tenant_id, source_system, record_kind, source_record_id, source_version, finding_type)
);
ALTER TABLE integ.findings ENABLE ROW LEVEL SECURITY;
ALTER TABLE integ.findings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integ.findings
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX findings_site_idx ON integ.findings (tenant_id, site_id, detected_at DESC, id);
