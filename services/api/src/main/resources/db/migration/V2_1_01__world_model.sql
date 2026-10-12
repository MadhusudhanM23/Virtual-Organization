-- World model (REQ-020, REQ-023): buildings, floors, zones, docks, racks, twin entities, world versions.
-- Coordinates are site-local meters (+x east, +y north, +z up; origin = site south-west ground-floor corner) and
-- headings are degrees (0 = east, counter-clockwise positive); see .gsd/ARCHITECTURE.md "Coordinates".
-- Every row carries a data_mode (blueprint section 7 enum, ADR-27) so demo data can never pass as real data.
-- Runs as the owner role; vo_app privileges come from the default privileges of V1_2_01.

CREATE TABLE world.buildings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    name varchar(200) NOT NULL,
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE world.buildings ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.buildings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.buildings
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX buildings_site_idx ON world.buildings (tenant_id, site_id);

CREATE TABLE world.floors (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    building_id uuid NOT NULL REFERENCES world.buildings (id),
    name varchar(200) NOT NULL,
    level int NOT NULL,
    elevation_m numeric(8, 3) NOT NULL,
    min_x numeric(9, 3) NOT NULL,
    min_y numeric(9, 3) NOT NULL,
    max_x numeric(9, 3) NOT NULL,
    max_y numeric(9, 3) NOT NULL,
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (min_x < max_x AND min_y < max_y)
);
ALTER TABLE world.floors ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.floors FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.floors
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX floors_site_idx ON world.floors (tenant_id, site_id, level);
CREATE UNIQUE INDEX floors_building_level_uk ON world.floors (building_id, level);

CREATE TABLE world.zones (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    floor_id uuid NOT NULL REFERENCES world.floors (id),
    name varchar(200) NOT NULL,
    zone_type varchar(20) NOT NULL CHECK (zone_type IN ('RECEIVING', 'STORAGE', 'PICKING', 'PACKING', 'DISPATCH')),
    min_x numeric(9, 3) NOT NULL,
    min_y numeric(9, 3) NOT NULL,
    max_x numeric(9, 3) NOT NULL,
    max_y numeric(9, 3) NOT NULL,
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (min_x < max_x AND min_y < max_y)
);
ALTER TABLE world.zones ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.zones FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.zones
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX zones_site_floor_idx ON world.zones (tenant_id, site_id, floor_id);

CREATE TABLE world.docks (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    floor_id uuid NOT NULL REFERENCES world.floors (id),
    zone_id uuid REFERENCES world.zones (id),
    name varchar(100) NOT NULL,
    dock_type varchar(10) NOT NULL CHECK (dock_type IN ('INBOUND', 'OUTBOUND')),
    status varchar(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'OCCUPIED', 'CLOSED')),
    x numeric(9, 3) NOT NULL,
    y numeric(9, 3) NOT NULL,
    z numeric(8, 3) NOT NULL,
    heading_deg numeric(6, 3) NOT NULL CHECK (heading_deg >= 0 AND heading_deg < 360),
    width_m numeric(6, 3) NOT NULL CHECK (width_m > 0),
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE world.docks ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.docks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.docks
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX docks_site_idx ON world.docks (tenant_id, site_id, floor_id);
CREATE UNIQUE INDEX docks_site_name_uk ON world.docks (site_id, name);

-- Storage locations; kind RACK is the only kind in Phase 2 (bins/staging slots can extend the CHECK later).
CREATE TABLE world.storage_locations (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    floor_id uuid NOT NULL REFERENCES world.floors (id),
    zone_id uuid REFERENCES world.zones (id),
    kind varchar(10) NOT NULL DEFAULT 'RACK' CHECK (kind IN ('RACK')),
    code varchar(50) NOT NULL,
    x numeric(9, 3) NOT NULL,
    y numeric(9, 3) NOT NULL,
    z numeric(8, 3) NOT NULL,
    heading_deg numeric(6, 3) NOT NULL CHECK (heading_deg >= 0 AND heading_deg < 360),
    length_m numeric(6, 3) NOT NULL CHECK (length_m > 0),
    depth_m numeric(6, 3) NOT NULL CHECK (depth_m > 0),
    height_m numeric(6, 3) NOT NULL CHECK (height_m > 0),
    levels int NOT NULL CHECK (levels >= 1),
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE world.storage_locations ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.storage_locations FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.storage_locations
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX storage_locations_site_idx ON world.storage_locations (tenant_id, site_id, floor_id, id);
CREATE UNIQUE INDEX storage_locations_site_code_uk ON world.storage_locations (site_id, code);

-- Twin entities: assets (forklift, truck, pallet, robot), pseudonymous people (worker) and cameras, each with a
-- current pose (REQ-023: position, heading, occurred_at, provenance, spatial confidence) and an entity version.
-- Workers are pseudonymous: `code`/`name` are a pseudonym such as W-017; no personal data lives here (section 18).
CREATE TABLE world.entities (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    entity_type varchar(10) NOT NULL CHECK (entity_type IN ('FORKLIFT', 'TRUCK', 'PALLET', 'ROBOT', 'WORKER', 'CAMERA')),
    code varchar(50) NOT NULL,
    name varchar(200) NOT NULL,
    floor_id uuid REFERENCES world.floors (id),
    zone_id uuid REFERENCES world.zones (id),
    status varchar(30) NOT NULL,
    attributes jsonb NOT NULL DEFAULT '{}'::jsonb,
    pose_x numeric(9, 3) NOT NULL,
    pose_y numeric(9, 3) NOT NULL,
    pose_z numeric(8, 3) NOT NULL,
    pose_heading_deg numeric(6, 3) NOT NULL CHECK (pose_heading_deg >= 0 AND pose_heading_deg < 360),
    pose_occurred_at timestamptz NOT NULL,
    pose_source varchar(12) NOT NULL CHECK (pose_source IN ('API', 'CAMERA', 'DEVICE', 'SIMULATION', 'IMPORT', 'SYSTEM', 'HUMAN')),
    pose_data_mode varchar(10) NOT NULL CHECK (pose_data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    spatial_confidence numeric(4, 3) CHECK (spatial_confidence IS NULL OR (spatial_confidence >= 0 AND spatial_confidence <= 1)),
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    version int NOT NULL DEFAULT 1,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE world.entities ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.entities FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.entities
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE UNIQUE INDEX entities_site_code_uk ON world.entities (site_id, code);
CREATE INDEX entities_site_type_idx ON world.entities (tenant_id, site_id, entity_type, id);
CREATE INDEX entities_site_floor_idx ON world.entities (tenant_id, site_id, floor_id, id);
CREATE INDEX entities_site_xy_idx ON world.entities (tenant_id, site_id, pose_x, pose_y);

-- Monotonic revision of a site's world model; the content hash identifies exactly what a version contained.
CREATE TABLE world.world_versions (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    site_id uuid NOT NULL REFERENCES world.sites (id),
    version int NOT NULL CHECK (version >= 1),
    content_hash char(64) NOT NULL,
    label varchar(200) NOT NULL,
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, version)
);
ALTER TABLE world.world_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.world_versions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.world_versions
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX world_versions_site_idx ON world.world_versions (tenant_id, site_id, version DESC);

-- Canonical ID mapping (REQ-021, blueprint section 3): every adapter record keeps source_system, source_record_id and
-- the canonical id it resolves to. One external record maps to exactly one canonical id, and one canonical id has at
-- most one record per source system.
CREATE TABLE world.id_mappings (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    source_system varchar(60) NOT NULL,
    source_record_id varchar(200) NOT NULL,
    canonical_id uuid NOT NULL,
    canonical_kind varchar(30) NOT NULL,
    data_mode varchar(10) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, source_system, source_record_id),
    UNIQUE (tenant_id, source_system, canonical_id)
);
ALTER TABLE world.id_mappings ENABLE ROW LEVEL SECURITY;
ALTER TABLE world.id_mappings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON world.id_mappings
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
CREATE INDEX id_mappings_canonical_idx ON world.id_mappings (tenant_id, canonical_id);
