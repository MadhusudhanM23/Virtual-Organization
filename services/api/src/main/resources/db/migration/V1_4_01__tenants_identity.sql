-- Tenancy root, identity profiles, role assignments and refresh tokens (ADR-07, ADR-16, §18).
-- Runs as the owner role (vo_owner); grants are to vo_app (ADR-28).

CREATE TABLE iam.tenants (
    id uuid PRIMARY KEY,
    slug varchar(63) NOT NULL,
    name varchar(200) NOT NULL,
    demo_mode boolean NOT NULL DEFAULT false,
    llm_external_allowed boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX tenants_slug_uk ON iam.tenants (slug);
-- No tenant_id column (this table IS the tenant) and no secrets: readable by vo_app without RLS so a login
-- request can resolve its tenant slug before any tenant context exists.
REVOKE INSERT, UPDATE, DELETE ON iam.tenants FROM vo_app;
GRANT SELECT ON iam.tenants TO vo_app;

-- Separable identity store (§18): deleting/erasing a row here never touches business/event history, which
-- references actor_ref only.
CREATE TABLE iam.identity_profiles (
    actor_ref uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    email varchar(320) NOT NULL,
    display_name varchar(200) NOT NULL,
    password_hash varchar(200) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED', 'ERASED')),
    erased_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
-- Case-insensitive uniqueness per tenant without an extra extension dependency.
CREATE UNIQUE INDEX identity_profiles_tenant_email_uk ON iam.identity_profiles (tenant_id, lower(email));
ALTER TABLE iam.identity_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.identity_profiles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam.identity_profiles
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE TABLE iam.role_assignments (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    actor_ref uuid NOT NULL,
    role varchar(30) NOT NULL,
    scope_type varchar(10) NOT NULL CHECK (scope_type IN ('ORG', 'REGION', 'SITE', 'ASSIGNED')),
    scope_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE iam.role_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.role_assignments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam.role_assignments
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX role_assignments_actor_idx ON iam.role_assignments (tenant_id, actor_ref);

CREATE TABLE iam.refresh_tokens (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    actor_ref uuid NOT NULL,
    token_hash varchar(128) NOT NULL,
    family_id uuid NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    replaced_by uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE iam.refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE iam.refresh_tokens FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam.refresh_tokens
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX refresh_tokens_family_idx ON iam.refresh_tokens (tenant_id, family_id);
CREATE UNIQUE INDEX refresh_tokens_hash_uk ON iam.refresh_tokens (token_hash);
