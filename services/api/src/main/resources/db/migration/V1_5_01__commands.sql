-- Command records: one row per accepted command, the idempotency anchor of the write path (ADR-02, ADR-03, §16).
-- Runs as the owner role (vo_owner); grants to vo_app come from the default privileges set in V1_2_01.

CREATE TABLE cmd.commands (
    command_id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    idempotency_key varchar(200) NOT NULL,
    actor_ref varchar(100) NOT NULL,
    command_type varchar(100) NOT NULL,
    payload jsonb NOT NULL,
    payload_hash varchar(64) NOT NULL,
    status varchar(20) NOT NULL CHECK (status IN ('ACCEPTED', 'SUCCEEDED', 'FAILED', 'REJECTED')),
    result jsonb,
    correlation_id uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
-- Same key within a tenant is the same command; the unique index is also what serializes concurrent duplicates.
CREATE UNIQUE INDEX commands_tenant_idempotency_uk ON cmd.commands (tenant_id, idempotency_key);
CREATE INDEX commands_tenant_created_idx ON cmd.commands (tenant_id, created_at);

ALTER TABLE cmd.commands ENABLE ROW LEVEL SECURITY;
ALTER TABLE cmd.commands FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cmd.commands
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- A command record is never deleted by the application.
REVOKE DELETE ON cmd.commands FROM vo_app;
