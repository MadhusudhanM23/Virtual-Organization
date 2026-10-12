-- Minimal organization -> region -> site hierarchy that authorization scopes are evaluated against (§4, ADR-28).

CREATE TABLE world.organizations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    name varchar(200) NOT NULL,
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE world.organizations ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.organizations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.organizations
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

CREATE TABLE world.regions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    organization_id uuid NOT NULL REFERENCES world.organizations (id),
    name varchar(200) NOT NULL,
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE world.regions ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.regions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.regions
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX regions_org_idx ON world.regions (tenant_id, organization_id);

CREATE TABLE world.sites (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    region_id uuid NOT NULL REFERENCES world.regions (id),
    name varchar(200) NOT NULL,
    kind varchar(20) NOT NULL DEFAULT 'WAREHOUSE' CHECK (kind IN ('WAREHOUSE')),
    time_zone varchar(50) NOT NULL,
    locale varchar(10) NOT NULL,
    currency varchar(3) NOT NULL,
    unit_system varchar(10) NOT NULL CHECK (unit_system IN ('METRIC', 'IMPERIAL')),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE world.sites ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.sites FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.sites
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX sites_region_idx ON world.sites (tenant_id, region_id);
