-- Recommendations and approvals (REQ-090, REQ-092, REQ-093, REQ-094; BLUEPRINT section 9; ADR-61). The `rec` schema
-- and vo_app's usage/default DML already exist (V1_2_01). A recommendation is the section 9 decision package; its
-- approval is a separate aggregate with its own lifecycle (PENDING -> APPROVED | REJECTED | EXPIRED). State changes are
-- made only by commands, which record a RECOMMENDATION_* / APPROVAL_* event in the same transaction. Rows are never
-- deleted (no DELETE grant): history is part of the audit trail. Runs as the owner role (ADR-28).

CREATE TABLE rec.recommendations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    type varchar(40) NOT NULL CHECK (type IN ('DOCK_REASSIGNMENT')),
    status varchar(10) NOT NULL
        CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED', 'EXPIRED', 'STALE', 'EXECUTING', 'VERIFIED', 'PARTIAL',
                          'FAILED')),
    rationale varchar(4000) NOT NULL,
    evidence_refs jsonb NOT NULL,
    feasibility jsonb NOT NULL,
    objective_before jsonb NOT NULL,
    objective_after jsonb NOT NULL,
    constraints jsonb NOT NULL,
    affected_entities jsonb NOT NULL,
    baseline_entity_versions jsonb NOT NULL,
    policy_versions jsonb NOT NULL,
    execution_plan jsonb NOT NULL,
    requires_approval jsonb NOT NULL,
    expires_at timestamptz NOT NULL,
    -- The approval created with the proposal (same transaction); deferred so both rows can be inserted in either order.
    approval_id uuid NOT NULL UNIQUE,
    -- Provenance of the proposal: the SUCCEEDED simulation run (and its scenario) it was generated from. Not foreign
    -- keys: sim.* is the vo_sim schema (ADR-60) and the recommendation module only reads it.
    simulation_run_id uuid NOT NULL,
    scenario_id uuid NOT NULL,
    proposed_by uuid NOT NULL,
    supersedes_id uuid REFERENCES rec.recommendations (id),
    -- Reserved for execution (Plan 8.3); null until executed.
    execution jsonb,
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE rec.recommendations ENABLE ROW LEVEL SECURITY;
ALTER TABLE rec.recommendations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON rec.recommendations
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX recommendations_site_created_idx ON rec.recommendations (tenant_id, site_id, created_at DESC, id DESC);
CREATE INDEX recommendations_site_status_idx ON rec.recommendations (tenant_id, site_id, status, created_at DESC);
CREATE INDEX recommendations_run_idx ON rec.recommendations (tenant_id, simulation_run_id, created_at DESC, id DESC);

CREATE TABLE rec.approvals (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    recommendation_id uuid NOT NULL UNIQUE REFERENCES rec.recommendations (id),
    status varchar(10) NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    approval_class varchar(20) NOT NULL CHECK (approval_class IN ('STANDARD', 'SENSITIVE')),
    separation_of_duties boolean NOT NULL,
    -- The approval policy evaluated when the approval was requested (name, version and the roles whose
    -- ALLOW_SUBJECT_TO_POLICY cell it accepts), so a decision is checked against what the proposer was shown.
    policy_name varchar(60) NOT NULL,
    policy_version int NOT NULL,
    approver_roles jsonb NOT NULL,
    requested_by uuid NOT NULL,
    decided_by uuid,
    decided_at timestamptz,
    reason varchar(500),
    expires_at timestamptz NOT NULL,
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR reason IS NOT NULL)
);
ALTER TABLE rec.approvals ENABLE ROW LEVEL SECURITY;
ALTER TABLE rec.approvals FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON rec.approvals
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX approvals_site_created_idx ON rec.approvals (tenant_id, site_id, created_at DESC, id DESC);
CREATE INDEX approvals_site_status_idx ON rec.approvals (tenant_id, site_id, status, created_at DESC);

ALTER TABLE rec.recommendations ADD CONSTRAINT recommendations_approval_fk
    FOREIGN KEY (approval_id) REFERENCES rec.approvals (id) DEFERRABLE INITIALLY DEFERRED;

-- Append/advance only: no row of either table is ever deleted by the application.
REVOKE DELETE ON rec.recommendations, rec.approvals FROM vo_app;
