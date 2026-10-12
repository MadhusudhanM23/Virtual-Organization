package io.vorg.api.integration;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.integration.core.IntegrationSyncService;
import io.vorg.api.integration.core.MirrorService.IngestSummary;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class IntegrationSyncIT extends AbstractApiIntegrationTest {

    @Autowired
    IntegrationSyncService sync;

    private long cursor(String system) throws Exception {
        var rows = ownerQuery("select next_batch from integ.sync_cursors where site_id = ? and source_system = ?",
                SITE_BLR, system);
        return rows.isEmpty() ? 0 : ((Number) rows.get(0).get("next_batch")).longValue();
    }

    @Test
    void syncAdvancesPersistedCursorsAndMirrorsAllKindsThroughTheApi() throws Exception {
        long wmsBefore = cursor("SIM-WMS");
        long tmsBefore = cursor("SIM-TMS");
        IngestSummary wmsTotal = null;
        for (int i = 0; i < 8; i++) {
            Map<String, IngestSummary> result = sync.syncOnce(TENANT_A, SITE_BLR);
            assertThat(result).containsKeys("SIM-WMS", "SIM-TMS");
            wmsTotal = result.get("SIM-WMS");
        }
        assertThat(wmsTotal).isNotNull();
        assertThat(cursor("SIM-WMS")).isEqualTo(wmsBefore + 8);
        assertThat(cursor("SIM-TMS")).isEqualTo(tmsBefore + 8);

        String wm = token(TENANT_A_SLUG, "wm.blr@demo.invalid");
        Set<String> kinds = new HashSet<>();
        for (String system : new String[] {"SIM-WMS", "SIM-TMS"}) {
            JsonNode all = ok("/api/integration/records?site_id=" + SITE_BLR + "&limit=200&source_system=" + system,
                    wm);
            for (JsonNode item : all.get("items")) {
                kinds.add(item.get("record_kind").asString());
                assertThat(item.get("data_mode").asString()).isEqualTo("SIMULATED");
                assertThat(item.get("source_system").asString()).isEqualTo(system);
                assertThat(item.get("source_version").asLong()).isGreaterThanOrEqualTo(1);
            }
        }
        assertThat(kinds).containsExactlyInAnyOrder("ORDER", "INVENTORY", "SHIPMENT", "TASK");

        JsonNode shipments = ok("/api/integration/records?site_id=" + SITE_BLR + "&kind=shipment&source_system=SIM-TMS",
                wm);
        assertThat(shipments.get("items")).hasSize(6);

        // Pagination: two pages of 2 must not overlap.
        JsonNode p1 = ok("/api/integration/records?site_id=" + SITE_BLR + "&limit=2&source_system=SIM-WMS", wm);
        assertThat(p1.get("items")).hasSize(2);
        String next = p1.get("next_cursor").asString();
        JsonNode p2 = ok("/api/integration/records?site_id=" + SITE_BLR + "&limit=2&source_system=SIM-WMS&cursor=" + next, wm);
        assertThat(p2.get("items")).hasSize(2);
        assertThat(p2.get("items").get(0).get("id").asString()).isNotEqualTo(p1.get("items").get(0).get("id").asString())
                .isNotEqualTo(p1.get("items").get(1).get("id").asString());

        // The seeded twin has IN-03 / OUT-02 free, the TMS says trucks are at them: surfaced, not hidden.
        JsonNode diffs = ok("/api/integration/findings?site_id=" + SITE_BLR + "&type=RECONCILIATION_DIFF", wm);
        assertThat(diffs.get("items").size()).isGreaterThanOrEqualTo(2);
        assertThat(diffs.get("items").get(0).get("severity").asString()).isEqualTo("WARNING");
        assertThat(diffs.get("items").get(0).get("data_mode").asString()).isEqualTo("SIMULATED");
    }

    @Test
    void accessFollowsThePolicyEngine() throws Exception {
        String path = "/api/integration/records?site_id=" + SITE_BLR;
        assertThat(get(path, null).statusCode()).isEqualTo(401);
        assertThat(get(path, token(TENANT_A_SLUG, "driver.blr@demo.invalid")).statusCode()).isEqualTo(403);
        assertThat(get(path, token(TENANT_A_SLUG, "admin@demo.invalid")).statusCode()).isEqualTo(403);
        assertThat(get(path, token(TENANT_A_SLUG, "analyst@demo.invalid")).statusCode()).isEqualTo(200);
        assertThat(get(path, token(TENANT_B_SLUG, "exec@demo.invalid")).statusCode()).isEqualTo(404);
        assertThat(get(path + "&limit=0", token(TENANT_A_SLUG, "wm.blr@demo.invalid")).statusCode()).isEqualTo(400);
        assertThat(get(path + "&kind=BOGUS", token(TENANT_A_SLUG, "wm.blr@demo.invalid")).statusCode())
                .isEqualTo(400);
    }

    private JsonNode ok(String path, String token) throws Exception {
        HttpResponse<String> res = get(path, token);
        assertThat(res.statusCode()).as("GET %s -> %s", path, res.body()).isEqualTo(200);
        return json(res);
    }
}
