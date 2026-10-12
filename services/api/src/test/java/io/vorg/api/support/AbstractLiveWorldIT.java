package io.vorg.api.support;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;

import io.vorg.api.world.seed.BengaluruWarehouse01;
import io.vorg.api.world.seed.WorldSeedPlan;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base for integration tests that move entities (observations, the movement source). The database is shared by every
 * integration test in the JVM and the Phase 2 tests assert the pristine SYNTHETIC seed, so these tests restore every
 * entity to its seeded state (pose, provenance, status, version) before and after each test.
 */
public abstract class AbstractLiveWorldIT extends AbstractApiIntegrationTest {

    @BeforeEach
    void restoreBefore() throws Exception {
        restoreSeedWorld();
    }

    @AfterEach
    void restoreAfter() throws Exception {
        restoreSeedWorld();
    }

    protected void restoreSeedWorld() throws Exception {
        WorldSeedPlan plan = BengaluruWarehouse01.build(TENANT_A, SITE_BLR);
        OffsetDateTime at = OffsetDateTime.ofInstant(plan.poseOccurredAt(), ZoneOffset.UTC);
        for (WorldSeedPlan.Entity e : plan.entities()) {
            ownerUpdate("""
                    update world.entities set pose_x = ?, pose_y = ?, pose_z = ?, pose_heading_deg = ?,
                        pose_occurred_at = ?, pose_source = 'SYSTEM', pose_data_mode = 'SYNTHETIC',
                        spatial_confidence = ?, status = ?, floor_id = ?, zone_id = ?, version = 1,
                        updated_at = created_at
                    where id = ?
                    """, e.x(), e.y(), e.z(), e.headingDeg(), at, e.spatialConfidence(), e.status(), e.floorId(),
                    e.zoneId(), e.id());
        }
    }
}
