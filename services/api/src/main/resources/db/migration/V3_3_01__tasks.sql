-- Warehouse tasks (REQ-043): the lifecycle ASSIGNED -> ACCEPTED -> STARTED <-> PAUSED -> COMPLETED / FAILED / CANCELLED,
-- changed only by commands that also record a TASK_* state-delta event in the same transaction. Tasks created from a
-- source system keep source_system / source_record_id (unique per tenant) so a re-delivered record never creates a
-- second task. Issues reported against a task never change its status. Runs as the owner role.

CREATE TABLE ops.tasks (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    task_type varchar(10) NOT NULL CHECK (task_type IN ('PICK', 'PUTAWAY', 'DOCK')),
    status varchar(12) NOT NULL
        CHECK (status IN ('ASSIGNED', 'ACCEPTED', 'STARTED', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    priority int NOT NULL DEFAULT 2 CHECK (priority BETWEEN 1 AND 3),
    title varchar(300) NOT NULL,
    source_system varchar(40),
    source_record_id varchar(200),
    assigned_actor_ref uuid,
    assigned_entity_id uuid REFERENCES world.entities (id),
    pallet_entity_id uuid REFERENCES world.entities (id),
    from_label varchar(100) NOT NULL,
    from_x numeric(9, 3) NOT NULL,
    from_y numeric(9, 3) NOT NULL,
    to_label varchar(100) NOT NULL,
    to_x numeric(9, 3) NOT NULL,
    to_y numeric(9, 3) NOT NULL,
    status_reason varchar(500),
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    started_at timestamptz,
    completed_at timestamptz,
    CHECK ((source_system IS NULL) = (source_record_id IS NULL))
);
ALTER TABLE ops.tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ops.tasks
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE UNIQUE INDEX tasks_source_uk ON ops.tasks (tenant_id, source_system, source_record_id)
    WHERE source_system IS NOT NULL;
CREATE INDEX tasks_site_status_idx ON ops.tasks (tenant_id, site_id, status, created_at DESC, id DESC);
CREATE INDEX tasks_assignee_idx ON ops.tasks (tenant_id, assigned_actor_ref, created_at DESC);
CREATE INDEX tasks_entity_idx ON ops.tasks (tenant_id, assigned_entity_id, status);

CREATE TABLE ops.task_issues (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    task_id uuid NOT NULL REFERENCES ops.tasks (id),
    issue_type varchar(30) NOT NULL
        CHECK (issue_type IN ('BLOCKED', 'DAMAGED_GOODS', 'WRONG_LOCATION', 'EQUIPMENT_FAULT', 'SAFETY_CONCERN', 'OTHER')),
    description varchar(1000) NOT NULL,
    reported_by uuid NOT NULL,
    event_id uuid,
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE ops.task_issues ENABLE ROW LEVEL SECURITY;
ALTER TABLE ops.task_issues FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON ops.task_issues
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX task_issues_task_idx ON ops.task_issues (tenant_id, task_id, created_at);
-- Issues are a record of what was reported: never edited or removed by the application.
REVOKE UPDATE, DELETE ON ops.task_issues FROM vo_app;
