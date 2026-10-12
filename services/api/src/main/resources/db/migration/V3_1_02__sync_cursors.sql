-- Adapter sync cursors: the next batch number each source system is asked for, per site, so a restart resumes where
-- the simulated source left off instead of replaying history as duplicates. Runs as the owner role.

CREATE TABLE integ.sync_cursors (
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    source_system varchar(40) NOT NULL,
    next_batch bigint NOT NULL CHECK (next_batch >= 0),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, site_id, source_system)
);
ALTER TABLE integ.sync_cursors ENABLE ROW LEVEL SECURITY;
ALTER TABLE integ.sync_cursors FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integ.sync_cursors
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
