-- Camera registry and vision observations (REQ-080, REQ-083, REQ-084; ADR-64/65/66). A camera is registered per site,
-- linked to its CAMERA twin entity, with its pose, the floor calibration (control points, never a stream secret), the
-- class map (which detector classes become which twin entity types) and the local media it plays. Observations are the
-- privacy-safe records posted by services/vision: no frames, crops, faces or biometric data exist anywhere in this
-- schema. Runs as the owner role (ADR-28); RLS is forced on every table.

CREATE SCHEMA IF NOT EXISTS cam;
GRANT USAGE ON SCHEMA cam TO vo_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cam GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO vo_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA cam GRANT USAGE, SELECT ON SEQUENCES TO vo_app;

CREATE TABLE cam.cameras (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    entity_id uuid NOT NULL REFERENCES world.entities (id),
    code varchar(50) NOT NULL,
    name varchar(200) NOT NULL,
    floor_id uuid REFERENCES world.floors (id),
    zone_id uuid REFERENCES world.zones (id),
    status varchar(12) NOT NULL CHECK (status IN ('ACTIVE', 'PLACEHOLDER', 'DISABLED')),
    pose_x numeric(9, 3) NOT NULL,
    pose_y numeric(9, 3) NOT NULL,
    pose_z numeric(8, 3) NOT NULL,
    pose_heading_deg numeric(6, 3) NOT NULL CHECK (pose_heading_deg >= 0 AND pose_heading_deg < 360),
    -- {image_width, image_height, floor_z_m, points: [{u, v, x, y}], method, note} or null (uncalibrated).
    calibration jsonb,
    -- {"<detector class>": "<twin entity type>"}; only mapped classes are associated with twin entities.
    class_map jsonb NOT NULL DEFAULT '{}'::jsonb,
    feed_kind varchar(12) NOT NULL CHECK (feed_kind IN ('RECORDED', 'SYNTHETIC', 'NONE')),
    media_name varchar(200),
    media_kind varchar(10) CHECK (media_kind IS NULL OR media_kind IN ('VIDEO', 'STILL')),
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    retention_days int NOT NULL DEFAULT 7 CHECK (retention_days BETWEEN 1 AND 365),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE cam.cameras ENABLE ROW LEVEL SECURITY;
ALTER TABLE cam.cameras FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cam.cameras
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE UNIQUE INDEX cameras_site_code_uk ON cam.cameras (site_id, code);
CREATE UNIQUE INDEX cameras_entity_uk ON cam.cameras (tenant_id, entity_id);
CREATE INDEX cameras_site_idx ON cam.cameras (tenant_id, site_id, code);

-- One row per accepted observation; (camera, observation_id) is the duplicate guard of re-delivered batches.
CREATE TABLE cam.observations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    camera_id uuid NOT NULL REFERENCES cam.cameras (id),
    session_id varchar(64) NOT NULL,
    observation_id varchar(200) NOT NULL,
    track_id int NOT NULL CHECK (track_id >= 0),
    object_class varchar(40) NOT NULL,
    identity_status varchar(12) NOT NULL CHECK (identity_status IN ('PROVISIONAL', 'STABLE')),
    confidence numeric(4, 3) NOT NULL CHECK (confidence >= 0 AND confidence <= 1),
    observed_at timestamptz NOT NULL,
    media_time_s numeric(10, 3) NOT NULL CHECK (media_time_s >= 0),
    frame_index int NOT NULL CHECK (frame_index >= 0),
    bbox_x numeric(6, 4) NOT NULL,
    bbox_y numeric(6, 4) NOT NULL,
    bbox_w numeric(6, 4) NOT NULL,
    bbox_h numeric(6, 4) NOT NULL,
    spatial_status varchar(24) NOT NULL
        CHECK (spatial_status IN ('MAPPED', 'OUTSIDE_CALIBRATED_ZONE', 'UNCALIBRATED')),
    floor_x numeric(9, 3),
    floor_y numeric(9, 3),
    floor_z numeric(8, 3),
    speed_mps numeric(7, 3),
    heading_deg numeric(6, 3),
    entity_id uuid REFERENCES world.entities (id),
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    detector varchar(80) NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((spatial_status = 'MAPPED') = (floor_x IS NOT NULL AND floor_y IS NOT NULL))
);
ALTER TABLE cam.observations ENABLE ROW LEVEL SECURITY;
ALTER TABLE cam.observations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cam.observations
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE UNIQUE INDEX observations_camera_obs_uk ON cam.observations (tenant_id, camera_id, observation_id);
CREATE INDEX observations_camera_time_idx ON cam.observations (tenant_id, camera_id, observed_at, id);
CREATE INDEX observations_ingested_idx ON cam.observations (ingested_at);
-- Observations are immutable; the retention job deletes them (DELETE stays granted for that).
REVOKE UPDATE ON cam.observations FROM vo_app;
