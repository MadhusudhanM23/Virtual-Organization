-- Observation ingestion ledger (REQ-042): one row per accepted (source_system, observation_id) so a re-delivered
-- observation (retry, duplicate message) never produces a second position event. Pruned by the ingestion service after a
-- retention window (the event history, not this ledger, is the system of record). Runs as the owner role.

CREATE TABLE world.observation_ledger (
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    source_system varchar(60) NOT NULL,
    observation_id varchar(200) NOT NULL,
    entity_id uuid NOT NULL,
    occurred_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, source_system, observation_id)
);
ALTER TABLE world.observation_ledger ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.observation_ledger FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.observation_ledger
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX observation_ledger_ingested_idx ON world.observation_ledger (ingested_at);
CREATE INDEX observation_ledger_entity_idx ON world.observation_ledger (tenant_id, entity_id, occurred_at DESC);
