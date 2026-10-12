-- Indexes for GET /api/events (REQ-045): newest-first keyset pagination per site, with the common narrowing filters
-- (aggregate, event type). location->>'site_id' is the site scope of an event (every Phase 3 event carries it).
-- Created on the partitioned parent, so every existing and future daily partition gets them. Runs as the owner role.

CREATE INDEX events_tenant_site_time_idx
    ON evt.events (tenant_id, (location ->> 'site_id'), occurred_at DESC, event_id DESC);
CREATE INDEX events_tenant_aggregate_time_idx
    ON evt.events (tenant_id, aggregate_id, occurred_at DESC, event_id DESC);
CREATE INDEX events_tenant_type_time_idx
    ON evt.events (tenant_id, event_type, occurred_at DESC, event_id DESC);
