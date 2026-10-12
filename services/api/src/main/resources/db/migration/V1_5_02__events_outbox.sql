-- Event history, per-aggregate sequences, transactional outbox and consumer idempotency (ADR-02, ADR-03, ADR-23, §7).
-- Runs as the owner role (vo_owner).

-- Append-only event history, range-partitioned by occurred_at (daily partitions, ADR-23). A primary key on a
-- partitioned table must include the partition key; event_id uniqueness is enforced globally by evt.outbox
-- (event_id unique) and by UUIDv7 generation. Per-aggregate sequence uniqueness is enforced by evt.aggregate_sequences
-- (a unique index spanning partitions cannot exclude occurred_at).
CREATE TABLE evt.events (
    event_id uuid NOT NULL,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    aggregate_type varchar(60) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(100) NOT NULL,
    sequence bigint CHECK (sequence IS NULL OR sequence >= 1),
    occurred_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL,
    source varchar(20) NOT NULL
        CHECK (source IN ('API', 'CAMERA', 'DEVICE', 'SIMULATION', 'IMPORT', 'SYSTEM', 'HUMAN')),
    data_mode varchar(20) NOT NULL CHECK (data_mode IN ('LIVE', 'SIMULATED', 'SYNTHETIC', 'REPLAY', 'MIXED')),
    actor_type varchar(20) NOT NULL CHECK (actor_type IN ('HUMAN', 'SERVICE', 'DEVICE', 'AGENT')),
    actor_id varchar(100) NOT NULL,
    location jsonb,
    correlation_id uuid NOT NULL,
    causation_id uuid,
    schema_version int NOT NULL CHECK (schema_version >= 1),
    payload jsonb NOT NULL,
    confidence double precision CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    observation_status varchar(20)
        CHECK (observation_status IS NULL OR observation_status IN ('DETECTED', 'UNCONFIRMED', 'CONFIRMED', 'REJECTED')),
    PRIMARY KEY (event_id, occurred_at)
) PARTITION BY RANGE (occurred_at);

CREATE INDEX events_tenant_occurred_idx ON evt.events (tenant_id, occurred_at);
CREATE INDEX events_tenant_aggregate_idx ON evt.events (tenant_id, aggregate_type, aggregate_id);
CREATE INDEX events_event_id_idx ON evt.events (event_id);

ALTER TABLE evt.events ENABLE ROW LEVEL SECURITY;
ALTER TABLE evt.events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evt.events
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);

-- History is immutable: no runtime role may update or delete it; the outbox reader (vo_outbox) may only read.
REVOKE UPDATE, DELETE ON evt.events FROM vo_app;
REVOKE INSERT, UPDATE, DELETE ON evt.events FROM vo_outbox;

-- Catch-all so a late or far-future event is never rejected (it is accepted and flagged by the recorder).
CREATE TABLE evt.events_default PARTITION OF evt.events DEFAULT;
REVOKE ALL ON evt.events_default FROM PUBLIC, vo_app, vo_outbox;

-- Creates daily partitions (UTC days) from today - days_back to today + days_ahead. Idempotent. SECURITY DEFINER
-- so the runtime role (vo_app, not the table owner) can keep partitions created ahead; children are not granted to
-- any runtime role (access is always through the parent, so RLS on the parent cannot be bypassed via a partition).
CREATE OR REPLACE FUNCTION evt.ensure_event_partitions(days_back integer, days_ahead integer)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, evt
AS $$
DECLARE
    today date := (now() AT TIME ZONE 'UTC')::date;
    d date;
    part text;
    created integer := 0;
BEGIN
    IF days_back < 0 OR days_ahead < 0 OR days_back + days_ahead > 400 THEN
        RAISE EXCEPTION 'partition window out of range';
    END IF;
    FOR d IN SELECT g::date FROM generate_series(today - days_back, today + days_ahead, interval '1 day') AS g LOOP
        part := 'events_' || to_char(d, 'YYYYMMDD');
        IF to_regclass('evt.' || part) IS NULL THEN
            BEGIN
                EXECUTE format('CREATE TABLE evt.%I PARTITION OF evt.events FOR VALUES FROM (%L) TO (%L)',
                    part, (d::timestamp AT TIME ZONE 'UTC'), ((d + 1)::timestamp AT TIME ZONE 'UTC'));
                EXECUTE format('REVOKE ALL ON evt.%I FROM PUBLIC, vo_app, vo_outbox', part);
                created := created + 1;
            EXCEPTION WHEN check_violation THEN
                RAISE WARNING 'partition % not created: default partition holds overlapping rows', part;
            END;
        END IF;
    END LOOP;
    RETURN created;
END;
$$;
REVOKE ALL ON FUNCTION evt.ensure_event_partitions(integer, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION evt.ensure_event_partitions(integer, integer) TO vo_app;

SELECT evt.ensure_event_partitions(1, 7);

-- Per-aggregate monotonic sequence allocator (row lock held until commit serializes writers per aggregate).
CREATE TABLE evt.aggregate_sequences (
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    aggregate_type varchar(60) NOT NULL,
    aggregate_id uuid NOT NULL,
    last_sequence bigint NOT NULL CHECK (last_sequence >= 1),
    PRIMARY KEY (tenant_id, aggregate_type, aggregate_id)
);
ALTER TABLE evt.aggregate_sequences ENABLE ROW LEVEL SECURITY;
ALTER TABLE evt.aggregate_sequences FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evt.aggregate_sequences
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE DELETE ON evt.aggregate_sequences FROM vo_app;

-- Transactional outbox: written in the same transaction as the state change and the event (ADR-03). RLS is on, but
-- the publisher role vo_outbox (BYPASSRLS, evt schema only) reads across tenants.
CREATE TABLE evt.outbox (
    id bigserial PRIMARY KEY,
    event_id uuid NOT NULL,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    created_at timestamptz NOT NULL DEFAULT now(),
    available_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempts int NOT NULL DEFAULT 0,
    last_error text,
    status varchar(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD')),
    CONSTRAINT outbox_event_id_uk UNIQUE (event_id)
);
CREATE INDEX outbox_pending_idx ON evt.outbox (available_at, id) WHERE status = 'PENDING';
ALTER TABLE evt.outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE evt.outbox FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evt.outbox
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
-- The application only enqueues; delivery bookkeeping belongs to the publisher role.
REVOKE UPDATE, DELETE ON evt.outbox FROM vo_app;

-- Consumer idempotency: (consumer, event_id) recorded in the same transaction as the consumer's side effect.
CREATE TABLE evt.processed_events (
    consumer varchar(100) NOT NULL,
    event_id uuid NOT NULL,
    tenant_id uuid NOT NULL REFERENCES iam.tenants (id),
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
ALTER TABLE evt.processed_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE evt.processed_events FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON evt.processed_events
    USING (tenant_id = current_setting('app.tenant_id', true)::uuid)
    WITH CHECK (tenant_id = current_setting('app.tenant_id', true)::uuid);
REVOKE UPDATE, DELETE ON evt.processed_events FROM vo_app;
