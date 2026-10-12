package io.vorg.api.twin;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_B;
import static io.vorg.api.platform.identity.seed.DemoIds.SITE_MUM;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A_SLUG;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B_SLUG;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractApiIntegrationTest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class TwinQueryIT extends AbstractApiIntegrationTest {

    private static final String SITE = "site_id=" + SITE_BLR;

    private String wm() throws Exception {
        return token(TENANT_A_SLUG, "wm.blr@demo.invalid");
    }

    private JsonNode ok(String path, String token) throws Exception {
        HttpResponse<String> res = get(path, token);
        assertThat(res.statusCode()).as("GET %s -> %s", path, res.body()).isEqualTo(200);
        assertThat(res.headers().firstValue("X-Correlation-Id")).isPresent();
        return json(res);
    }

    private void status(String path, String token, int expected) throws Exception {
        HttpResponse<String> res = get(path, token);
        assertThat(res.statusCode()).as("GET %s -> %s", path, res.body()).isEqualTo(expected);
        if (expected >= 400 && expected != 401) {
            assertThat(res.headers().firstValue("Content-Type").orElse("")).contains("application/problem+json");
        }
    }

    // ---- world / version ----

    @Test
    void worldDescribesTheSyntheticSiteWithCoordinateSystemAndCounts() throws Exception {
        JsonNode world = ok("/api/twin/world?" + SITE, wm());
        assertThat(world.get("site").get("name").asString()).isEqualTo("Bengaluru Warehouse 01");
        assertThat(world.get("site").get("unit_system").asString()).isEqualTo("METRIC");
        assertThat(world.get("data_mode").asString()).isEqualTo("SYNTHETIC");
        assertThat(world.get("world_version").get("version").asInt()).isEqualTo(1);
        assertThat(world.get("world_version").get("content_hash").asString()).hasSize(64);
        assertThat(world.get("world_version").get("label").asString()).startsWith("SYNTHETIC");
        JsonNode cs = world.get("coordinate_system");
        assertThat(cs.get("units").asString()).isEqualTo("meters");
        assertThat(cs.get("heading_zero").asString()).isEqualTo("east");
        assertThat(cs.get("heading_positive").asString()).isEqualTo("counter_clockwise");
        assertThat(world.get("buildings")).hasSize(1);
        assertThat(world.get("floors")).hasSize(2);
        assertThat(world.get("zones")).hasSize(7);
        assertThat(world.get("docks")).hasSize(6);
        assertThat(world.get("racks")).hasSize(78);
        JsonNode counts = world.get("entity_counts");
        assertThat(counts.get("FORKLIFT").asInt()).isEqualTo(10);
        assertThat(counts.get("WORKER").asInt()).isEqualTo(30);
        assertThat(counts.get("TRUCK").asInt()).isEqualTo(6);
        assertThat(counts.get("PALLET").asInt()).isEqualTo(120);
        assertThat(counts.get("CAMERA").asInt()).isEqualTo(4);
        assertThat(counts.has("ROBOT")).isFalse();
        // The ground floor starts at elevation 0 and the mezzanine slab is at 5 m.
        assertThat(world.get("floors").get(0).get("elevation_m").asDouble()).isZero();
        assertThat(world.get("floors").get(1).get("elevation_m").asDouble()).isEqualTo(5.0);
        // Coarse: entities themselves are not embedded.
        assertThat(world.has("entities")).isFalse();
        exportSample("world.json", world);
    }

    @Test
    void versionEndpointReturnsTheLatestWorldVersion() throws Exception {
        JsonNode version = ok("/api/twin/world/version?" + SITE, wm());
        assertThat(version.get("version").asInt()).isEqualTo(1);
        assertThat(version.get("data_mode").asString()).isEqualTo("SYNTHETIC");
        assertThat(version.get("content_hash").asString())
                .isEqualTo(ok("/api/twin/world?" + SITE, wm()).get("world_version").get("content_hash").asString());
    }

    @Test
    void structureEndpointsFilterByFloorAndType() throws Exception {
        String token = wm();
        JsonNode world = ok("/api/twin/world?" + SITE, token);
        String ground = world.get("floors").get(0).get("id").asString();
        String mezz = world.get("floors").get(1).get("id").asString();

        assertThat(ok("/api/twin/floors?" + SITE, token).get("items")).hasSize(2);
        assertThat(ok("/api/twin/zones?" + SITE, token).get("items")).hasSize(7);
        assertThat(ok("/api/twin/zones?" + SITE + "&floor_id=" + mezz, token).get("items")).hasSize(1);
        assertThat(ok("/api/twin/zones?" + SITE + "&zone_type=STORAGE", token).get("items")).hasSize(3);
        assertThat(ok("/api/twin/docks?" + SITE, token).get("items")).hasSize(6);
        assertThat(ok("/api/twin/docks?" + SITE + "&floor_id=" + mezz, token).get("items")).isEmpty();
        assertThat(ok("/api/twin/racks?" + SITE + "&floor_id=" + mezz, token).get("items")).hasSize(8);
        assertThat(ok("/api/twin/racks?" + SITE + "&floor_id=" + ground, token).get("items")).hasSize(70);
        status("/api/twin/zones?" + SITE + "&zone_type=GARAGE", token, 400);
    }

    @Test
    void racksPaginateByCursorWithoutGapsOrDuplicates() throws Exception {
        String token = wm();
        Set<String> seen = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = ok("/api/twin/racks?" + SITE + "&limit=30" + (cursor == null ? "" : "&cursor=" + cursor),
                    token);
            for (JsonNode rack : page.get("items")) {
                assertThat(seen.add(rack.get("id").asString())).isTrue();
            }
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asString();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(seen).hasSize(78);
        assertThat(pages).isEqualTo(3);
        // A rack-only bbox: the first rack column of storage A.
        JsonNode inBox = ok("/api/twin/racks?" + SITE + "&bbox=0,0,13,70", token);
        assertThat(inBox.get("items")).isNotEmpty();
        for (JsonNode rack : inBox.get("items")) {
            assertThat(rack.get("position").get("x").asDouble()).isBetween(0.0, 13.0);
        }
    }

    // ---- entities ----

    @Test
    void entitiesFilterByType() throws Exception {
        String token = wm();
        JsonNode forklifts = ok("/api/twin/entities?" + SITE + "&type=FORKLIFT", token);
        assertThat(forklifts.get("items")).hasSize(10);
        assertThat(forklifts.get("next_cursor").isNull()).isTrue();
        for (JsonNode e : forklifts.get("items")) {
            assertThat(e.get("entity_type").asString()).isEqualTo("FORKLIFT");
            assertThat(e.get("data_mode").asString()).isEqualTo("SYNTHETIC");
            assertThat(e.get("pose").get("source").asString()).isEqualTo("SYSTEM");
            assertThat(e.get("pose").get("data_mode").asString()).isEqualTo("SYNTHETIC");
            assertThat(e.get("pose").get("heading_deg").asDouble()).isGreaterThanOrEqualTo(0).isLessThan(360);
            assertThat(e.has("tenant_id")).isFalse();
        }
        assertThat(ok("/api/twin/entities?" + SITE + "&type=forklift,worker", token).get("items")).hasSize(40);
        assertThat(ok("/api/twin/entities?" + SITE + "&type=ROBOT", token).get("items")).isEmpty();
        JsonNode cameras = ok("/api/twin/entities?" + SITE + "&type=CAMERA", token);
        assertThat(cameras.get("items")).hasSize(4);
        assertThat(cameras.get("items").get(0).get("attributes").get("placeholder").asBoolean()).isTrue();
        // Workers are pseudonymous.
        for (JsonNode w : ok("/api/twin/entities?" + SITE + "&type=WORKER&limit=100", token).get("items")) {
            assertThat(w.get("code").asString()).matches("W-\\d{3}");
            assertThat(w.get("attributes").has("pseudonym")).isTrue();
        }
    }

    @Test
    void entitiesPaginateAcrossAllPages() throws Exception {
        String token = wm();
        List<String> ids = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = ok("/api/twin/entities?" + SITE + "&limit=50" + (cursor == null ? "" : "&cursor=" + cursor),
                    token);
            page.get("items").forEach(e -> ids.add(e.get("id").asString()));
            cursor = page.get("next_cursor").isNull() ? null : page.get("next_cursor").asString();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(ids).hasSize(170).doesNotHaveDuplicates().isSorted();
        assertThat(pages).isEqualTo(4);
        exportSample("entity-list.json", ok("/api/twin/entities?" + SITE + "&type=FORKLIFT&limit=3", token));
    }

    @Test
    void entitiesFilterByFloorZoneAndBbox() throws Exception {
        String token = wm();
        JsonNode world = ok("/api/twin/world?" + SITE, token);
        String mezz = world.get("floors").get(1).get("id").asString();
        JsonNode onMezz = ok("/api/twin/entities?" + SITE + "&floor_id=" + mezz, token);
        assertThat(onMezz.get("items")).hasSize(1);
        assertThat(onMezz.get("items").get(0).get("code").asString()).isEqualTo("CAM-04");

        String receiving = null;
        for (JsonNode zone : world.get("zones")) {
            if (zone.get("zone_type").asString().equals("RECEIVING")) {
                receiving = zone.get("id").asString();
            }
        }
        JsonNode inReceiving = ok("/api/twin/entities?" + SITE + "&zone_id=" + receiving + "&limit=500", token);
        assertThat(ownerCount("select count(*) from world.entities where zone_id = ?::uuid", receiving))
                .isEqualTo(inReceiving.get("items").size());
        assertThat(inReceiving.get("items").size()).isGreaterThan(20); // pallets, workers and a camera

        // bbox: the receiving strip. Edge poses are inclusive; compare against SQL ground truth.
        JsonNode inBox = ok("/api/twin/entities?" + SITE + "&bbox=0,0,60,16&limit=500", token);
        long expected = ownerCount("""
                select count(*) from world.entities where site_id = ?
                and pose_x between 0 and 60 and pose_y between 0 and 16
                """, SITE_BLR);
        assertThat(inBox.get("items").size()).isEqualTo(expected);
        for (JsonNode e : inBox.get("items")) {
            assertThat(e.get("pose").get("x").asDouble()).isBetween(0.0, 60.0);
            assertThat(e.get("pose").get("y").asDouble()).isBetween(0.0, 16.0);
        }
        // Combined filters narrow further: forklifts never stand in the receiving strip.
        assertThat(ok("/api/twin/entities?" + SITE + "&bbox=0,0,60,16&type=FORKLIFT", token).get("items")).isEmpty();
    }

    @Test
    void invalidParametersReturnProblemWithFieldErrors() throws Exception {
        String token = wm();
        for (String bad : List.of("&type=DRONE", "&bbox=1,2,3", "&bbox=5,0,1,10", "&bbox=a,b,c,d", "&limit=0",
                "&limit=501", "&cursor=%25%25", "&floor_id=not-a-uuid")) {
            status("/api/twin/entities?" + SITE + bad, token, 400);
        }
        HttpResponse<String> res = get("/api/twin/entities?" + SITE + "&type=DRONE&limit=0", token);
        assertThat(json(res).get("errors")).hasSize(2);
        status("/api/twin/entities", token, 400); // site_id is required
    }

    @Test
    void entityDetailShowsProvenanceVersionAndCanonicalIds() throws Exception {
        String token = wm();
        JsonNode pallets = ok("/api/twin/entities?" + SITE + "&type=PALLET&limit=1", token);
        String palletId = pallets.get("items").get(0).get("id").asString();
        JsonNode pallet = ok("/api/entities/" + palletId, token);
        assertThat(pallet.get("id").asString()).isEqualTo(palletId);
        assertThat(pallet.get("version").asInt()).isEqualTo(1);
        assertThat(pallet.get("data_mode").asString()).isEqualTo("SYNTHETIC");
        assertThat(pallet.get("pose").get("occurred_at").asString()).isEqualTo("2026-10-09T00:00:00Z");
        assertThat(pallet.get("pose").get("spatial_confidence").asDouble()).isEqualTo(1.0);
        assertThat(pallet.get("floor_name").asString()).isEqualTo("Ground floor");
        assertThat(pallet.get("zone_name").isNull()).isFalse();
        assertThat(pallet.get("external_ids")).hasSize(1);
        assertThat(pallet.get("external_ids").get(0).get("source_system").asString()).isEqualTo("SIM_WMS");
        assertThat(pallet.get("external_ids").get(0).get("source_record_id").asString()).startsWith("LPN-");
        exportSample("entity-detail.json", pallet);

        String cameraId = ok("/api/twin/entities?" + SITE + "&type=CAMERA&limit=1", token)
                .get("items").get(0).get("id").asString();
        JsonNode camera = ok("/api/entities/" + cameraId, token);
        assertThat(camera.get("external_ids")).isEmpty();
        assertThat(camera.get("attributes").get("stream_url").isNull()).isTrue();

        status("/api/entities/018f0000-0000-7000-8000-ffffffffffff", token, 404);
        status("/api/entities/not-a-uuid", token, 400);
    }

    // ---- authorization and tenancy ----

    @Test
    void authorizedRolesSeeTheWorldAndOthersDoNot() throws Exception {
        String path = "/api/twin/world?" + SITE;
        for (String user : List.of("exec", "analyst", "safety", "wm.blr", "regional.south")) {
            status(path, token(TENANT_A_SLUG, user + "@demo.invalid"), 200);
        }
        status(path, token(TENANT_A_SLUG, "driver.blr@demo.invalid"), 403);
        // Platform Administrator has technical authority only: no business-data world.
        status(path, token(TENANT_A_SLUG, "admin@demo.invalid"), 403);
        status(path, null, 401);
        status("/api/entities/018f0000-0000-7000-8000-ffffffffffff", null, 401);
    }

    @Test
    void scopeLimitsWhichSitesAreVisible() throws Exception {
        String mumbai = "/api/twin/world?site_id=" + SITE_MUM;
        // Out-of-scope site -> 403 (and an audited denial); in-scope but no world modelled -> 404.
        status(mumbai, wm(), 403);
        status(mumbai, token(TENANT_A_SLUG, "regional.south@demo.invalid"), 403);
        status(mumbai, token(TENANT_A_SLUG, "exec@demo.invalid"), 404);
        status("/api/twin/entities?site_id=" + SITE_MUM, wm(), 403);
        assertThat(ownerCount("select count(*) from audit.audit_log where action = 'VIEW_OPERATIONAL_STATE' "
                + "and decision = 'DENIED' and resource_id = ?", SITE_MUM)).isGreaterThanOrEqualTo(1);
    }

    @Test
    void tenantsNeverSeeEachOthersWorld() throws Exception {
        String exec = token(TENANT_A_SLUG, "exec@demo.invalid");
        String entityOfA = ok("/api/twin/entities?" + SITE + "&type=FORKLIFT&limit=1", exec)
                .get("items").get(0).get("id").asString();

        String execB = token(TENANT_B_SLUG, "exec@demo.invalid");
        status("/api/twin/world?" + SITE, execB, 404);
        status("/api/twin/entities?" + SITE, execB, 404);
        status("/api/twin/racks?" + SITE, execB, 404);
        status("/api/entities/" + entityOfA, execB, 404);
        // Tenant B's own site has no world model yet.
        status("/api/twin/world?site_id=" + SITE_B, execB, 404);
        assertThat(ok("/api/twin/entities?site_id=" + SITE_B, execB).get("items")).isEmpty();
    }

    // ---- sample export (cross-language contract, see tests/contract/README.md) ----

    private void exportSample(String name, JsonNode body) throws Exception {
        Path dir = Path.of("..", "..", "tests", "contract", "world-samples").toAbsolutePath().normalize();
        assertThat(dir.getParent().getParent().getParent().resolve("packages").resolve("twin-schema")).exists();
        Files.createDirectories(dir);
        String text = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body).replace("\r\n", "\n") + "\n";
        Files.writeString(dir.resolve(name), text, StandardCharsets.UTF_8);
    }
}
