-- Scenarios and simulation runs (REQ-070..074, ADR-49, ADR-60). The `sim` schema and the `vo_sim` role already exist
-- (V1_2_01): vo_sim has default privileges on `sim` tables only, nothing on world/ops/evt/cmd. The application talks
-- to these two tables exclusively through a vo_sim-authenticated connection (see SimConfig), so a scenario or a
-- simulation run can never read or write live tenant state at the database level, independent of application code.

CREATE TABLE sim.scenarios (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    -- Not a foreign key: a FK to world.sites would require vo_sim to hold a privilege on the world schema to even
    -- insert (Postgres checks REFERENCES/USAGE on the referenced table at insert time, regardless of who defined the
    -- constraint), which is exactly the cross-schema access REQ-074 forbids. Existence/tenancy of site_id is checked
    -- in the application (ScenarioAccess, PolicyService -> SiteLookup) before a scenario is ever created.
    site_id uuid NOT NULL,
    name varchar(200) NOT NULL,
    description varchar(2000) NOT NULL DEFAULT '',
    -- Built-in baseline name (Plan 6.3 always uses "bengaluru-warehouse-01"; ADR-60). Not a foreign key: baselines are
    -- a code-level registry (io.vorg.simulation.warehouse.Baselines), not a database table.
    baseline_ref varchar(100) NOT NULL,
    overlay jsonb NOT NULL DEFAULT '{}'::jsonb,
    run_settings jsonb NOT NULL,
    -- The full canonical scenario document (schema vorg.simulation.scenario.v1) the engine accepts as-is on every run.
    scenario_json jsonb NOT NULL,
    affected_entities jsonb NOT NULL DEFAULT '[]'::jsonb,
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version int NOT NULL DEFAULT 1
);
ALTER TABLE sim.scenarios ENABLE ROW LEVEL SECURITY;
ALTER TABLE sim.scenarios FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sim.scenarios
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX scenarios_site_created_idx ON sim.scenarios (tenant_id, site_id, created_at DESC, id DESC);

CREATE TABLE sim.simulation_runs (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    site_id uuid NOT NULL, -- see sim.scenarios.site_id: deliberately not a foreign key to world.sites.
    scenario_id uuid NOT NULL REFERENCES sim.scenarios (id),
    status varchar(10) NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    requested_by uuid NOT NULL,
    engine_name varchar(50),
    engine_version varchar(50),
    -- The comparison document (schema vorg.simulation.comparison.v1), stored compactly once SUCCEEDED.
    results_json jsonb,
    results_hash char(64),
    error_message varchar(2000),
    created_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    finished_at timestamptz,
    version int NOT NULL DEFAULT 1,
    CHECK ((status = 'SUCCEEDED') = (results_json IS NOT NULL AND results_hash IS NOT NULL)),
    CHECK ((status = 'FAILED') = (error_message IS NOT NULL))
);
ALTER TABLE sim.simulation_runs ENABLE ROW LEVEL SECURITY;
ALTER TABLE sim.simulation_runs FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sim.simulation_runs
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX simulation_runs_scenario_idx ON sim.simulation_runs (tenant_id, scenario_id, created_at DESC, id DESC);
CREATE INDEX simulation_runs_site_idx ON sim.simulation_runs (tenant_id, site_id, created_at DESC, id DESC);
