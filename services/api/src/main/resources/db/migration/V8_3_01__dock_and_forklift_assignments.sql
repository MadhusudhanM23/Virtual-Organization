-- Dock and forklift assignment projections, simulated-adapter reassignment overrides and execution bookkeeping
-- (Plan 8.3, REQ-091/095/096, ADR-63). Runs as the owner role (ADR-28); vo_app privileges come from the default
-- privileges of V1_2_01 (ops, integ, rec).
--
-- Stale detection (ADR-63) compares versions that change ONLY when a plan target materially changes: an assignment's
-- `version` is bumped on first sight and whenever its dock changes, never for a re-delivered or state-only record.

-- Trip -> dock, projected from SIM-TMS SHIPMENT mirror records (`dock_name`).
CREATE TABLE ops.dock_assignments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    trip_code varchar(100) NOT NULL,
    truck_code varchar(100) NOT NULL,
    direction varchar(10) NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    dock_code varchar(100) NOT NULL,
    -- The source the assignment was last observed from (e.g. SIM-TMS) and that record's source version.
    source varchar(40) NOT NULL,
    source_version bigint NOT NULL,
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    -- The DOCK_ASSIGNMENT_CHANGED event of the last dock change (the "observed" evidence of verification).
    last_event_id uuid,
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, site_id, trip_code)
);
ALTER TABLE ops.dock_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.dock_assignments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ops.dock_assignments
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX dock_assignments_site_dock_idx ON ops.dock_assignments (tenant_id, site_id, dock_code);
REVOKE DELETE ON ops.dock_assignments FROM vo_app;

-- Forklift -> dock station, projected from SIM-WMS FORKLIFT_STATION mirror records.
CREATE TABLE ops.forklift_assignments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    forklift_code varchar(100) NOT NULL,
    dock_code varchar(100) NOT NULL,
    source varchar(40) NOT NULL,
    source_version bigint NOT NULL,
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    last_event_id uuid,
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, site_id, forklift_code)
);
ALTER TABLE ops.forklift_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.forklift_assignments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ops.forklift_assignments
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE DELETE ON ops.forklift_assignments FROM vo_app;

-- What the simulated "external" TMS/WMS were told (AdapterCommandPort, simulated implementation). The simulated
-- adapters read these on every fetch, so the next batch carries the new dock. `version` counts the changes of a row.
CREATE TABLE integ.sim_tms_overrides (
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    trip_code varchar(100) NOT NULL,
    dock_code varchar(100) NOT NULL,
    version int NOT NULL DEFAULT 1 CHECK (version >= 1 AND version < 1000),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, site_id, trip_code)
);
ALTER TABLE integ.sim_tms_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE integ.sim_tms_overrides FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integ.sim_tms_overrides
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE DELETE ON integ.sim_tms_overrides FROM vo_app;

CREATE TABLE integ.sim_wms_overrides (
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    forklift_code varchar(100) NOT NULL,
    dock_code varchar(100) NOT NULL,
    version int NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, site_id, forklift_code)
);
ALTER TABLE integ.sim_wms_overrides ENABLE ROW LEVEL SECURITY;
ALTER TABLE integ.sim_wms_overrides FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integ.sim_wms_overrides
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE DELETE ON integ.sim_wms_overrides FROM vo_app;

-- New mirror record kind: a forklift's dock station as reported by SIM-WMS.
ALTER TABLE integ.mirror_records DROP CONSTRAINT mirror_records_record_kind_check;
ALTER TABLE integ.mirror_records ADD CONSTRAINT mirror_records_record_kind_check
    CHECK (record_kind IN ('ORDER', 'INVENTORY', 'SHIPMENT', 'TASK', 'FORKLIFT_STATION'));
ALTER TABLE integ.findings DROP CONSTRAINT findings_record_kind_check;
ALTER TABLE integ.findings ADD CONSTRAINT findings_record_kind_check
    CHECK (record_kind IN ('ORDER', 'INVENTORY', 'SHIPMENT', 'TASK', 'FORKLIFT_STATION'));

-- Verification bookkeeping of an executing recommendation: the SIM-TMS sync cursor when execution started, so the
-- verification timeout is measured in adapter batches (ADR-63). Not part of the wire shape.
ALTER TABLE rec.recommendations ADD COLUMN verify_from_batch bigint;
