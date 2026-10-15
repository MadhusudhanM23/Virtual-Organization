-- Field sync (REQ-120, BLUEPRINT section 12, ADR-75..77): what the offline Operator PWA uploads when it reconnects.
--  * task_evidence: a photo captured offline (EXIF/GPS already stripped by the client; the server rejects a file that
--    still carries EXIF/GPS). Proof of delivery stays PENDING_SYNC on the device until this row exists. Uploads are
--    idempotent per (task, client_ref). captured_at is the device clock; uploaded_at the server's.
--  * field_records: inventory scans / inventory exceptions (the authoritative quantity is never overwritten), urgent
--    advisory records (never executed) and sync conflicts (a later command that was not applied).
-- Runs as the owner role; forced RLS; rows are never edited or removed by the application.

CREATE TABLE ops.task_evidence (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    task_id uuid NOT NULL REFERENCES ops.tasks (id),
    client_ref uuid NOT NULL,
    content_type varchar(20) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'image/webp')),
    size_bytes int NOT NULL CHECK (size_bytes BETWEEN 1 AND 2097152),
    sha256 char(64) NOT NULL,
    content bytea NOT NULL,
    note varchar(300),
    captured_at timestamptz NOT NULL,
    uploaded_by uuid NOT NULL,
    event_id uuid,
    uploaded_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, task_id, client_ref)
);
ALTER TABLE ops.task_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.task_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ops.task_evidence
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX task_evidence_task_idx ON ops.task_evidence (tenant_id, task_id, uploaded_at);
REVOKE UPDATE, DELETE ON ops.task_evidence FROM vo_app;

CREATE TABLE ops.field_records (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    kind varchar(24) NOT NULL
        CHECK (kind IN ('INVENTORY_SCAN', 'INVENTORY_EXCEPTION', 'URGENT', 'SYNC_CONFLICT')),
    task_id uuid REFERENCES ops.tasks (id),
    payload jsonb NOT NULL,
    captured_at timestamptz NOT NULL,
    reported_by uuid NOT NULL,
    event_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE ops.field_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.field_records FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ops.field_records
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX field_records_site_idx ON ops.field_records (tenant_id, site_id, kind, created_at DESC);
REVOKE UPDATE, DELETE ON ops.field_records FROM vo_app;
