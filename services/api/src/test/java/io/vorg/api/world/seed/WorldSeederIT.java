package io.vorg.api.world.seed;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.support.AbstractApiIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WorldSeederIT extends AbstractApiIntegrationTest {

    @Autowired
    WorldSeeder seeder;

    private long count(String table) throws Exception {
        return ownerCount("select count(*) from world." + table + " where site_id = ?", SITE_BLR);
    }

    private long countByType(String type) throws Exception {
        return ownerCount("select count(*) from world.entities where site_id = ? and entity_type = ?", SITE_BLR, type);
    }

    @Test
    void startupSeedsTheSyntheticBengaluruWorldForTheDemoTenantOnly() throws Exception {
        assertThat(count("buildings")).isEqualTo(1);
        assertThat(count("floors")).isEqualTo(2);
        assertThat(count("zones")).isEqualTo(7);
        assertThat(count("docks")).isEqualTo(6);
        assertThat(count("storage_locations")).isEqualTo(78);
        assertThat(count("entities")).isEqualTo(170);
        assertThat(countByType("FORKLIFT")).isEqualTo(10);
        assertThat(countByType("WORKER")).isEqualTo(30);
        assertThat(countByType("TRUCK")).isEqualTo(6);
        assertThat(countByType("PALLET")).isEqualTo(120);
        assertThat(countByType("CAMERA")).isEqualTo(4);
        assertThat(ownerCount("select count(*) from world.id_mappings where tenant_id = ?", TENANT_A)).isEqualTo(132);

        // Every row belongs to tenant A; tenant B has no world.
        for (String table : List.of("buildings", "floors", "zones", "docks", "storage_locations", "entities",
                "world_versions")) {
            assertThat(ownerCount("select count(*) from world." + table + " where site_id = ? and tenant_id <> ?",
                    SITE_BLR, TENANT_A)).as(table).isZero();
            assertThat(ownerCount("select count(*) from world." + table + " where tenant_id = ?", TENANT_B))
                    .as(table + " for tenant B").isZero();
        }
    }

    @Test
    void everythingIsLabelledSynthetic() throws Exception {
        for (String table : List.of("buildings", "floors", "zones", "docks", "storage_locations", "entities",
                "world_versions", "id_mappings")) {
            assertThat(ownerCount("select count(*) from world." + table + " where data_mode <> 'SYNTHETIC' "
                    + "and tenant_id = ?", TENANT_A)).as(table).isZero();
        }
        assertThat(ownerCount("select count(*) from world.entities where (pose_data_mode <> 'SYNTHETIC' "
                + "or pose_source <> 'SYSTEM') and tenant_id = ?", TENANT_A)).isZero();
        assertThat(ownerCount("select count(*) from world.world_versions where label not like 'SYNTHETIC%' "
                + "and tenant_id = ?", TENANT_A)).isZero();
    }

    @Test
    void rerunningIsIdempotentAndIdsAreStable() throws Exception {
        WorldSeedPlan plan = BengaluruWarehouse01.build(TENANT_A, SITE_BLR);
        UUID forklift = plan.entities().stream().filter(e -> e.code().equals("FL-01")).findFirst().orElseThrow().id();
        long before = count("entities");

        WorldSeeder.Result again = seeder.seed(plan);
        seeder.run(null);

        assertThat(again.seeded()).isFalse();
        assertThat(count("entities")).isEqualTo(before);
        assertThat(count("storage_locations")).isEqualTo(78);
        assertThat(ownerCount("select count(*) from world.world_versions where site_id = ?", SITE_BLR)).isEqualTo(1);
        assertThat(ownerQuery("select content_hash from world.world_versions where site_id = ?", SITE_BLR)
                .get(0).get("content_hash").toString().trim()).isEqualTo(plan.contentHash());
        assertThat(ownerCount("select count(*) from world.entities where id = ? and code = 'FL-01'", forklift))
                .isEqualTo(1);
    }

    @Test
    void storedValuesMatchThePlanExactly() throws Exception {
        WorldSeedPlan plan = BengaluruWarehouse01.build(TENANT_A, SITE_BLR);
        for (WorldSeedPlan.Entity e : plan.entities()) {
            List<Map<String, Object>> rows = ownerQuery("""
                    select pose_x, pose_y, pose_z, pose_heading_deg, status, attributes::text as attrs,
                           pose_occurred_at, spatial_confidence
                    from world.entities where id = ?
                    """, e.id());
            assertThat(rows).as(e.code()).hasSize(1);
            Map<String, Object> row = rows.get(0);
            assertThat(new java.math.BigDecimal(row.get("pose_x").toString())).as(e.code()).isEqualByComparingTo(e.x());
            assertThat(new java.math.BigDecimal(row.get("pose_y").toString())).isEqualByComparingTo(e.y());
            assertThat(new java.math.BigDecimal(row.get("pose_z").toString())).isEqualByComparingTo(e.z());
            assertThat(row.get("status")).isEqualTo(e.status());
            assertThat(row.get("attrs").toString()).contains("\"synthetic\": true");
        }
    }

    @Test
    void seedingForAMissingSiteIsSkippedNotCreated() throws Exception {
        UUID ghostSite = UUID.randomUUID();
        WorldSeeder.Result result = seeder.seed(BengaluruWarehouse01.build(TENANT_B, ghostSite));
        assertThat(result.seeded()).isFalse();
        assertThat(ownerCount("select count(*) from world.entities where site_id = ?", ghostSite)).isZero();
    }
}
