-- Safety incidents (REQ-050/051/054): the incident aggregate with its ADR-18 lifecycle, the alerts merged into it, its
-- evidence list, notes and advisory emergency-stop requests. State changes are made only by commands / the alert consumer,
-- which record an INCIDENT_* state-delta event in the same transaction. Runs as the owner role (ADR-28).

CREATE SCHEMA IF NOT EXISTS inc;
GRANT USAGE ON SCHEMA inc TO vo_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA inc GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO vo_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA inc GRANT USAGE, SELECT ON SEQUENCES TO vo_app;

CREATE TABLE inc.incidents (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    incident_type varchar(30) NOT NULL CHECK (incident_type IN ('PROXIMITY')),
    status varchar(12) NOT NULL
        CHECK (status IN ('DETECTED', 'UNCONFIRMED', 'CONFIRMED', 'REJECTED', 'RESOLVED', 'CLOSED')),
    severity varchar(10) NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    title varchar(300) NOT NULL,
    forklift_entity_id uuid NOT NULL REFERENCES world.entities (id),
    worker_entity_id uuid NOT NULL REFERENCES world.entities (id),
    forklift_code varchar(50) NOT NULL,
    worker_code varchar(50) NOT NULL,
    floor_id uuid REFERENCES world.floors (id),
    zone_id uuid REFERENCES world.zones (id),
    x numeric(9, 3) NOT NULL,
    y numeric(9, 3) NOT NULL,
    z numeric(8, 3) NOT NULL,
    camera_entity_id uuid REFERENCES world.entities (id),
    camera_code varchar(50),
    min_distance_m numeric(8, 3) NOT NULL,
    alert_count int NOT NULL DEFAULT 1,
    detected_at timestamptz NOT NULL,
    last_alert_at timestamptz NOT NULL,
    assigned_actor_ref uuid,
    status_reason varchar(500),
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    source varchar(12) NOT NULL CHECK (source IN ('API', 'CAMERA', 'DEVICE', 'SIMULATION', 'IMPORT', 'SYSTEM', 'HUMAN')),
    confidence numeric(4, 3) CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    confirmed_at timestamptz,
    resolved_at timestamptz,
    closed_at timestamptz
);
ALTER TABLE inc.incidents ENABLE ROW LEVEL SECURITY;
ALTER TABLE inc.incidents FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inc.incidents
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX incidents_site_detected_idx ON inc.incidents (tenant_id, site_id, detected_at DESC, id DESC);
CREATE INDEX incidents_site_status_idx ON inc.incidents (tenant_id, site_id, status, detected_at DESC);
CREATE INDEX incidents_pair_idx ON inc.incidents (tenant_id, site_id, forklift_entity_id, worker_entity_id, status);

-- The alerts (PROXIMITY_ALERT_RAISED events) merged into an incident; one row per alert event.
CREATE TABLE inc.incident_alerts (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    incident_id uuid NOT NULL REFERENCES inc.incidents (id),
    alert_event_id uuid NOT NULL,
    alert_kind varchar(12) NOT NULL,
    severity varchar(10) NOT NULL,
    distance_m numeric(8, 3) NOT NULL,
    occurred_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (incident_id, alert_event_id)
);
ALTER TABLE inc.incident_alerts ENABLE ROW LEVEL SECURITY;
ALTER TABLE inc.incident_alerts FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inc.incident_alerts
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX incident_alerts_incident_idx ON inc.incident_alerts (tenant_id, incident_id, occurred_at);
CREATE UNIQUE INDEX incident_alerts_event_uk ON inc.incident_alerts (tenant_id, alert_event_id);

-- Evidence: links to events, pose snapshots and the camera reference with its focus target. detail carries the
-- truth labels (data_mode, source) of the evidence; nothing here is a recording.
CREATE TABLE inc.incident_evidence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    incident_id uuid NOT NULL REFERENCES inc.incidents (id),
    kind varchar(20) NOT NULL CHECK (kind IN ('EVENT_LINK', 'POSE_SNAPSHOT', 'CAMERA_REFERENCE')),
    ref_key varchar(200) NOT NULL,
    occurred_at timestamptz NOT NULL,
    detail jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (incident_id, kind, ref_key)
);
ALTER TABLE inc.incident_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE inc.incident_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inc.incident_evidence
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX incident_evidence_incident_idx ON inc.incident_evidence (tenant_id, incident_id, occurred_at, id);
REVOKE UPDATE, DELETE ON inc.incident_evidence FROM vo_app;

CREATE TABLE inc.incident_notes (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    incident_id uuid NOT NULL REFERENCES inc.incidents (id),
    author_actor_ref uuid NOT NULL,
    body varchar(1000) NOT NULL,
    event_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE inc.incident_notes ENABLE ROW LEVEL SECURITY;
ALTER TABLE inc.incident_notes FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inc.incident_notes
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX incident_notes_incident_idx ON inc.incident_notes (tenant_id, incident_id, created_at);
REVOKE UPDATE, DELETE ON inc.incident_notes FROM vo_app;

-- Advisory emergency-stop requests (ADR-05, ADR-21): a record that someone asked an external control system to stop an
-- asset. Nothing in this platform stops a machine; the request needs on-site human confirmation.
CREATE TABLE inc.estop_requests (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    incident_id uuid NOT NULL REFERENCES inc.incidents (id),
    target_entity_id uuid NOT NULL REFERENCES world.entities (id),
    target_entity_code varchar(50) NOT NULL,
    requested_by uuid NOT NULL,
    reason varchar(500) NOT NULL,
    status varchar(30) NOT NULL CHECK (status IN ('REQUESTED_ADVISORY')),
    event_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE inc.estop_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE inc.estop_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inc.estop_requests
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX estop_requests_incident_idx ON inc.estop_requests (tenant_id, incident_id, created_at);
REVOKE UPDATE, DELETE ON inc.estop_requests FROM vo_app;
