package io.vorg.api.kpi;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_B;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractLiveWorldIT;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class KpiApiIT extends AbstractLiveWorldIT {

    private static final String SITE = "site_id=" + SITE_BLR;

    private String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    private JsonNode ok(String path, String token) throws Exception {
        HttpResponse<String> res = get(path, token);
        assertThat(res.statusCode()).as("%s -> %s", path, res.body()).isEqualTo(200);
        return json(res);
    }

    private JsonNode kpi(JsonNode list, String id) {
        for (JsonNode k : list.get("items")) {
            if (id.equals(k.get("id").asString())) {
                return k;
            }
        }
        throw new AssertionError("KPI not returned: " + id);
    }

    @Test
    void definitionsDescribeEveryKpi() throws Exception {
        JsonNode defs = ok("/api/kpis/definitions", wm());
        assertThat(defs.get("items")).hasSize(KpiRegistry.all().size());
        JsonNode first = defs.get("items").get(0);
        assertThat(first.propertyNames()).contains("id", "name", "description", "formula", "unit", "grain",
                "source_tables", "data_mode", "freshness");
        assertThat(defs.get("windows").toString()).contains("24h");
        assertThat(get("/api/kpis/definitions", null).statusCode()).isEqualTo(401);
    }

    @Test
    void valuesMatchTheStoredSeededWorld() throws Exception {
        JsonNode list = ok("/api/kpis?" + SITE, wm());
        assertThat(list.get("items")).hasSize(KpiRegistry.all().size());
        assertThat(list.get("computed_at").asString()).isNotBlank();

        assertThat(kpi(list, "pallets_stored").get("value").asDouble()).isEqualTo(80.0);
        assertThat(kpi(list, "pallets_staged").get("value").asDouble()).isEqualTo(40.0);
        assertThat(kpi(list, "trucks_docked").get("value").asDouble()).isEqualTo(4.0);
        assertThat(kpi(list, "trucks_waiting").get("value").asDouble()).isEqualTo(2.0);
        assertThat(kpi(list, "pallets_stored").get("data_mode").asString()).isEqualTo("SYNTHETIC");
        assertThat(kpi(list, "pallets_stored").get("source").toString()).contains("world.entities");

        long forklifts = ownerCount("select count(*) from world.entities where site_id = ? and entity_type = 'FORKLIFT'",
                SITE_BLR);
        long active = ownerCount("""
                select count(*) from world.entities where site_id = ? and entity_type = 'FORKLIFT'
                  and status in ('MOVING', 'LOADING')
                """, SITE_BLR);
        assertThat(kpi(list, "forklift_utilization").get("value").asDouble())
                .isEqualTo(Math.round(100.0 * active / forklifts * 100.0) / 100.0);
        assertThat(kpi(list, "forklift_utilization").get("sample_size").asLong()).isEqualTo(forklifts);

        long usable = ownerCount("select count(*) from world.docks where site_id = ? and status <> 'CLOSED'", SITE_BLR);
        long occupied = ownerCount("select count(*) from world.docks where site_id = ? and status = 'OCCUPIED'",
                SITE_BLR);
        assertThat(kpi(list, "dock_utilization").get("value").asDouble())
                .isEqualTo(Math.round(100.0 * occupied / usable * 100.0) / 100.0);

        long openTasks = ownerCount("""
                select count(*) from ops.tasks where site_id = ? and status in ('ASSIGNED', 'ACCEPTED', 'PAUSED')
                """, SITE_BLR);
        long allTasks = ownerCount("select count(*) from ops.tasks where site_id = ?", SITE_BLR);
        JsonNode open = kpi(list, "tasks_open");
        if (allTasks == 0) {
            assertThat(open.get("value").isNull()).isTrue();
            assertThat(open.get("reason").asString()).isNotBlank();
        } else {
            assertThat(open.get("value").asLong()).isEqualTo(openTasks);
        }
    }

    @Test
    void emptySourceYieldsNullValueWithReasonNotZero() throws Exception {
        // Tenant B has a site with no tasks, mirrors, findings or events of its own.
        String exec = token(TENANT_B_SLUG, "exec@demo.invalid");
        JsonNode list = ok("/api/kpis?site_id=" + SITE_B, exec);
        JsonNode open = kpi(list, "tasks_open");
        assertThat(open.get("value").isNull()).isTrue();
        assertThat(open.get("reason").asString()).contains("no task");
        assertThat(kpi(list, "orders_open").get("value").isNull()).isTrue();
        assertThat(kpi(list, "event_throughput").get("value").isNull()).isTrue();
    }

    @Test
    void authorizationAndTenantIsolation() throws Exception {
        String driver = token(TENANT_A_SLUG, "driver.blr@demo.invalid");
        assertThat(get("/api/kpis/event_throughput/series?" + SITE, driver).statusCode()).isEqualTo(403);
        assertThat(get("/api/analytics/heatmap?" + SITE, driver).statusCode()).isEqualTo(403);
        assertThat(get("/api/analytics/order-flow?" + SITE, driver).statusCode()).isEqualTo(403);
        assertThat(get("/api/kpis?" + SITE, null).statusCode()).isEqualTo(401);

        String analyst = token(TENANT_A_SLUG, "analyst@demo.invalid");
        assertThat(get("/api/analytics/order-flow?" + SITE, analyst).statusCode()).isEqualTo(200);

        String tenantB = token(TENANT_B_SLUG, "exec@demo.invalid");
        for (String path : new String[] {"/api/kpis?" + SITE, "/api/kpis/event_throughput/series?" + SITE,
                "/api/analytics/heatmap?" + SITE, "/api/analytics/order-flow?" + SITE}) {
            assertThat(get(path, tenantB).statusCode()).as(path).isEqualTo(404);
        }
        assertThat(get("/api/kpis/event_throughput/series?site_id=" + SITE_B + "&window=2y", tenantB).statusCode())
                .isEqualTo(400);
    }

    @Test
    void seriesBucketsCountRealRows() throws Exception {
        String token = wm();
        JsonNode s = ok("/api/kpis/event_throughput/series?" + SITE + "&window=1h", token);
        assertThat(s.get("points")).hasSize(12);
        long inPoints = 0;
        for (JsonNode p : s.get("points")) {
            inPoints += p.get("sample_size").asLong();
        }
        assertThat(inPoints).isEqualTo(s.get("sample_size").asLong());
        long inDb = ownerCount("""
                select count(*) from evt.events where (location ->> 'site_id') = ?
                  and occurred_at >= now() - interval '1 hour' - interval '5 seconds'
                """, SITE_BLR.toString());
        assertThat(inPoints).isLessThanOrEqualTo(inDb);
        // A state KPI has no history to bucket.
        assertThat(get("/api/kpis/dock_utilization/series?" + SITE, token).statusCode()).isEqualTo(422);
        assertThat(get("/api/kpis/unknown_kpi/series?" + SITE, token).statusCode()).isEqualTo(404);
    }

    @Test
    void heatmapBinsPositionEventsIntoCells() throws Exception {
        String token = wm();
        JsonNode world = ok("/api/twin/world?" + SITE, token);
        String floor = world.get("floors").get(0).get("id").asString();
        double minX = world.get("floors").get(0).get("bounds").get("min_x").asDouble();
        double minY = world.get("floors").get(0).get("bounds").get("min_y").asDouble();
        UUID robot = UUID.randomUUID();
        OffsetDateTime at = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
        // Three ROBOT positions in cell (col 2, row 3) at 5 m cells, one in another cell.
        double[][] points = {{minX + 11, minY + 16}, {minX + 12, minY + 17}, {minX + 14, minY + 19},
                {minX + 31, minY + 6}};
        for (double[] p : points) {
            insertPosition(robot, floor, p[0], p[1], at);
        }
        try {
            JsonNode h = ok("/api/analytics/heatmap?" + SITE + "&floor_id=" + floor
                    + "&window=15m&cell_size=5&entity_type=ROBOT", token);
            assertThat(h.get("total_samples").asLong()).isEqualTo(4);
            assertThat(h.get("max_count").asLong()).isEqualTo(3);
            assertThat(h.get("data_mode").asString()).isEqualTo("SIMULATED");
            assertThat(h.get("cells")).hasSize(2);
            JsonNode hot = h.get("cells").get(0).get("count").asLong() == 3 ? h.get("cells").get(0)
                    : h.get("cells").get(1);
            assertThat(hot.get("col").asInt()).isEqualTo(2);
            assertThat(hot.get("row").asInt()).isEqualTo(3);
            assertThat(hot.get("entities").asInt()).isEqualTo(1);
            assertThat(h.get("cols").asInt()).isPositive();
            assertThat(get("/api/analytics/heatmap?" + SITE + "&cell_size=0.1", token).statusCode()).isEqualTo(400);
            assertThat(get("/api/analytics/heatmap?" + SITE + "&entity_type=DRAGON", token).statusCode())
                    .isEqualTo(400);
            JsonNode none = ok("/api/analytics/heatmap?" + SITE + "&window=15m&entity_type=CAMERA", token);
            assertThat(none.get("cells")).isEmpty();
            assertThat(none.get("reason").asString()).isNotBlank();
        } finally {
            ownerUpdate("delete from evt.events where aggregate_id = ?", robot);
        }
    }

    private void insertPosition(UUID entity, String floor, double x, double y, OffsetDateTime at) throws Exception {
        String location = "{\"site_id\":\"" + SITE_BLR + "\",\"floor_id\":\"" + floor + "\",\"position\":{\"x\":" + x
                + ",\"y\":" + y + ",\"z\":0.0},\"heading_deg\":0.0}";
        ownerUpdate("""
                insert into evt.events (event_id, tenant_id, aggregate_type, aggregate_id, event_type, occurred_at,
                    ingested_at, source, data_mode, actor_type, actor_id, location, correlation_id, schema_version,
                    payload)
                values (?, ?, 'ROBOT', ?, 'ROBOT_POSITION_UPDATED', ?, ?, 'SIMULATION', 'SIMULATED', 'SERVICE',
                    'svc:test', cast(? as jsonb), ?, 1, '{}'::jsonb)
                """, UUID.randomUUID(), TENANT_A, entity, at, at, location, UUID.randomUUID());
    }

    @Test
    void orderFlowCountsMirrorRevisionsInTheWindow() throws Exception {
        String token = wm();
        JsonNode before = ok("/api/analytics/order-flow?" + SITE + "&window=1h", token);
        UUID mirror = UUID.randomUUID();
        String hash = "a".repeat(64);
        ownerUpdate("""
                insert into integ.mirror_records (id, tenant_id, site_id, source_system, record_kind, source_record_id,
                    source_version, source_updated_at, payload, content_hash, data_mode, revisions)
                values (?, ?, ?, 'SIM-WMS', 'ORDER', ?, 2, now(), cast(? as jsonb), ?, 'SIMULATED', 2)
                """, mirror, TENANT_A, SITE_BLR, "KPI-IT-" + mirror, "{\"order_type\":\"INBOUND\",\"status\":\"COMPLETED\"}",
                hash);
        ownerUpdate("""
                insert into integ.mirror_revisions (id, tenant_id, mirror_id, source_version, source_updated_at, payload,
                    content_hash, ingested_at)
                values (?, ?, ?, 1, now(), cast(? as jsonb), ?, now()), (?, ?, ?, 2, now(), cast(? as jsonb), ?, now())
                """, UUID.randomUUID(), TENANT_A, mirror, "{\"order_type\":\"INBOUND\",\"status\":\"RELEASED\"}", hash,
                UUID.randomUUID(), TENANT_A, mirror, "{\"order_type\":\"INBOUND\",\"status\":\"COMPLETED\"}", hash);
        try {
            JsonNode after = ok("/api/analytics/order-flow?" + SITE + "&window=1h", token);
            assertThat(inbound(after, "released") - inbound(before, "released")).isEqualTo(1);
            assertThat(inbound(after, "completed") - inbound(before, "completed")).isEqualTo(1);
            assertThat(inbound(after, "in_progress") - inbound(before, "in_progress")).isZero();
            assertThat(after.get("data_mode").asString()).isIn("SIMULATED", "MIXED");
            assertThat(after.get("buckets")).hasSize(12);
            assertThat(after.get("backlog").toString()).contains("COMPLETED");
            JsonNode kpis = ok("/api/kpis?" + SITE + "&window=1h", token);
            assertThat(kpi(kpis, "orders_completed").get("value").asLong()).isGreaterThanOrEqualTo(1);
        } finally {
            ownerUpdate("delete from integ.mirror_revisions where mirror_id = ?", mirror);
            ownerUpdate("delete from integ.mirror_records where id = ?", mirror);
        }
    }

    private static long inbound(JsonNode flow, String stage) {
        long n = 0;
        for (JsonNode b : flow.get("buckets")) {
            n += b.get("inbound").get(stage).asLong();
        }
        return n;
    }
}
