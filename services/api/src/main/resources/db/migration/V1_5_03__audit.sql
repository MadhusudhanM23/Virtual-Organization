-- Append-only audit log (§18, ADR-28). Runs as the owner role (vo_owner).
-- vo_app already holds only SELECT, INSERT on the audit schema (default privileges in V1_2_01); the trigger below
-- additionally rejects UPDATE, DELETE and TRUNCATE for every role, including the owner.

CREATE TABLE audit.audit_log (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor_ref varchar(100) NOT NULL,
    actor_type varchar(20) NOT NULL CHECK (actor_type IN ('HUMAN', 'SERVICE', 'DEVICE', 'AGENT')),
    action varchar(100) NOT NULL,
    resource_type varchar(60) NOT NULL,
    resource_id uuid,
    decision varchar(10) NOT NULL CHECK (decision IN ('ALLOWED', 'DENIED')),
    reason text,
    command_id uuid,
    correlation_id uuid,
    details jsonb
);
CREATE INDEX audit_log_tenant_occurred_idx ON audit.audit_log (tenant_id, occurred_at);
CREATE INDEX audit_log_tenant_actor_idx ON audit.audit_log (tenant_id, actor_ref);

ALTER TABLE audit.audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit.audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit.audit_log
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE FUNCTION audit.reject_modification() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit.audit_log is append-only: % is not permitted', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$;

CREATE TRIGGER audit_log_no_update_delete
    BEFORE UPDATE OR DELETE ON audit.audit_log
    FOR EACH ROW EXECUTE FUNCTION audit.reject_modification();
CREATE TRIGGER audit_log_no_truncate
    BEFORE TRUNCATE ON audit.audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_modification();

REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_log FROM vo_app;
