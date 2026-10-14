-- Plan 9.1 (REQ-101, REQ-106, ADR-65): one row per AI assistant exchange, recorded by services/ai through
-- POST /api/ai/exchanges with the caller's own token. Only redacted text is ever stored (or none, when the gateway's
-- retention is "none"); raw secrets never reach this table (the API also rejects a body that still contains a secret
-- pattern). Rows are append-only: no UPDATE or DELETE grant. Runs as the owner role (ADR-28).

CREATE TABLE ai.exchanges (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    actor_ref uuid NOT NULL,
    client_exchange_id uuid NOT NULL,
    conversation_id uuid NOT NULL,
    provider varchar(20) NOT NULL CHECK (provider IN ('offline', 'openai')),
    model varchar(100) NOT NULL,
    external boolean NOT NULL,
    provider_reason varchar(60) NOT NULL,
    retention varchar(10) NOT NULL CHECK (retention IN ('redacted', 'none')),
    question_sha256 char(64) NOT NULL,
    question_text varchar(8000),
    answer_text varchar(16000),
    prompt_tokens int NOT NULL CHECK (prompt_tokens >= 0),
    completion_tokens int NOT NULL CHECK (completion_tokens >= 0),
    token_count_method varchar(30) NOT NULL,
    tool_calls jsonb NOT NULL,
    citations jsonb NOT NULL,
    redactions jsonb NOT NULL,
    flags jsonb NOT NULL,
    grounded boolean NOT NULL,
    latency_ms int NOT NULL CHECK (latency_ms >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (retention = 'redacted' OR (question_text IS NULL AND answer_text IS NULL))
);
ALTER TABLE ai.exchanges ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai.exchanges FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ai.exchanges
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE UNIQUE INDEX exchanges_client_id_uq ON ai.exchanges (tenant_id, client_exchange_id);
CREATE INDEX exchanges_actor_site_created_idx ON ai.exchanges (tenant_id, actor_ref, site_id, created_at DESC, id DESC);
CREATE INDEX exchanges_conversation_idx ON ai.exchanges (tenant_id, conversation_id, created_at);

REVOKE UPDATE, DELETE ON ai.exchanges FROM vo_app;
