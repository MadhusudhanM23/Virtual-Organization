package io.vorg.api.world.seed;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;

import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.world.CanonicalIdService;
import io.vorg.api.world.seed.WorldSeedPlan.Dock;
import io.vorg.api.world.seed.WorldSeedPlan.Entity;
import io.vorg.api.world.seed.WorldSeedPlan.ExternalRef;
import io.vorg.api.world.seed.WorldSeedPlan.Floor;
import io.vorg.api.world.seed.WorldSeedPlan.Rack;
import io.vorg.api.world.seed.WorldSeedPlan.Zone;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the SYNTHETIC "Bengaluru Warehouse 01" world (REQ-024) into the demo tenant's Bengaluru site. Active only in the
 * {@code local}, {@code demo} and {@code test} profiles and never a migration. Runs after the {@code DemoSeeder} (which
 * creates the tenant and site), as the runtime role through RLS, in one transaction, so it is tenant-scoped by
 * construction. Idempotent: every row has a fixed id and is inserted with {@code on conflict do nothing}; re-running
 * adds nothing, and an already-seeded site whose stored world hash differs is reported, never overwritten.
 */
@Component
@Order(2)
@Profile({"local", "demo", "test"})
public class WorldSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(WorldSeeder.class);
    private static final String DM = WorldSeedPlan.DATA_MODE;

    /** What a seeding call did. */
    public record Result(boolean seeded, String contentHash, int entities) {}

    private final JdbcClient jdbc;
    private final CanonicalIdService canonicalIds;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;

    public WorldSeeder(JdbcClient jdbc, CanonicalIdService canonicalIds, JsonMapper mapper,
            PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.canonicalIds = canonicalIds;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        Result result = seed(BengaluruWarehouse01.build(TENANT_A, SITE_BLR));
        LOG.info("World seed for Bengaluru Warehouse 01: {} (SYNTHETIC, hash {}, {} entities)",
                result.seeded() ? "applied" : "already present or skipped", result.contentHash(), result.entities());
    }

    /** Applies a plan for its own tenant and site. Safe to call repeatedly. */
    public Result seed(WorldSeedPlan plan) {
        return TenantContext.callWith(plan.tenantId(), () -> tx.execute(status -> apply(plan)));
    }

    private Result apply(WorldSeedPlan plan) {
        UUID tenant = plan.tenantId();
        UUID site = plan.siteId();
        boolean siteExists = jdbc.sql("select count(*) from world.sites where id = :s and tenant_id = :t")
                .param("s", site).param("t", tenant).query(Long.class).single() > 0;
        if (!siteExists) {
            LOG.warn("Site {} does not exist for tenant {}; world seed skipped", site, tenant);
            return new Result(false, plan.contentHash(), 0);
        }
        Optional<String> existing = jdbc.sql("""
                select content_hash from world.world_versions where site_id = :s and tenant_id = :t and version = 1
                """).param("s", site).param("t", tenant).query(String.class).optional();
        if (existing.isPresent()) {
            if (!existing.get().equals(plan.contentHash())) {
                LOG.warn("Site {} already holds world version 1 with hash {}; seed plan hash is {}. Not overwriting "
                        + "(reset the demo database to apply a new seed version).", site, existing.get(),
                        plan.contentHash());
            }
            return new Result(false, existing.get(), 0);
        }

        jdbc.sql("""
                insert into world.buildings (id, tenant_id, site_id, name, data_mode)
                values (:id, :t, :s, :name, :dm) on conflict do nothing
                """).param("id", plan.building().id()).param("t", tenant).param("s", site)
                .param("name", plan.building().name()).param("dm", DM).update();

        for (Floor f : plan.floors()) {
            jdbc.sql("""
                    insert into world.floors (id, tenant_id, site_id, building_id, name, level, elevation_m,
                        min_x, min_y, max_x, max_y, data_mode)
                    values (:id, :t, :s, :b, :name, :level, :elev, :x0, :y0, :x1, :y1, :dm) on conflict do nothing
                    """).param("id", f.id()).param("t", tenant).param("s", site).param("b", plan.building().id())
                    .param("name", f.name()).param("level", f.level()).param("elev", f.elevationM())
                    .param("x0", f.bounds().minX()).param("y0", f.bounds().minY()).param("x1", f.bounds().maxX())
                    .param("y1", f.bounds().maxY()).param("dm", DM).update();
        }
        for (Zone z : plan.zones()) {
            jdbc.sql("""
                    insert into world.zones (id, tenant_id, site_id, floor_id, name, zone_type,
                        min_x, min_y, max_x, max_y, data_mode)
                    values (:id, :t, :s, :f, :name, :type, :x0, :y0, :x1, :y1, :dm) on conflict do nothing
                    """).param("id", z.id()).param("t", tenant).param("s", site).param("f", z.floorId())
                    .param("name", z.name()).param("type", z.zoneType()).param("x0", z.bounds().minX())
                    .param("y0", z.bounds().minY()).param("x1", z.bounds().maxX()).param("y1", z.bounds().maxY())
                    .param("dm", DM).update();
        }
        for (Dock d : plan.docks()) {
            jdbc.sql("""
                    insert into world.docks (id, tenant_id, site_id, floor_id, zone_id, name, dock_type, status,
                        x, y, z, heading_deg, width_m, data_mode)
                    values (:id, :t, :s, :f, :zone, :name, :type, :status, :x, :y, :z, :h, :w, :dm)
                    on conflict do nothing
                    """).param("id", d.id()).param("t", tenant).param("s", site).param("f", d.floorId())
                    .param("zone", d.zoneId()).param("name", d.name()).param("type", d.dockType())
                    .param("status", d.status()).param("x", d.x()).param("y", d.y()).param("z", d.z())
                    .param("h", d.headingDeg()).param("w", d.widthM()).param("dm", DM).update();
        }
        for (Rack r : plan.racks()) {
            jdbc.sql("""
                    insert into world.storage_locations (id, tenant_id, site_id, floor_id, zone_id, kind, code,
                        x, y, z, heading_deg, length_m, depth_m, height_m, levels, data_mode)
                    values (:id, :t, :s, :f, :zone, 'RACK', :code, :x, :y, :z, :h, :len, :dep, :hgt, :levels, :dm)
                    on conflict do nothing
                    """).param("id", r.id()).param("t", tenant).param("s", site).param("f", r.floorId())
                    .param("zone", r.zoneId()).param("code", r.code()).param("x", r.x()).param("y", r.y())
                    .param("z", r.z()).param("h", r.headingDeg()).param("len", r.lengthM()).param("dep", r.depthM())
                    .param("hgt", r.heightM()).param("levels", r.levels()).param("dm", DM).update();
        }
        OffsetDateTime poseTime = OffsetDateTime.ofInstant(plan.poseOccurredAt(), ZoneOffset.UTC);
        for (Entity e : plan.entities()) {
            jdbc.sql("""
                    insert into world.entities (id, tenant_id, site_id, entity_type, code, name, floor_id, zone_id,
                        status, attributes, pose_x, pose_y, pose_z, pose_heading_deg, pose_occurred_at, pose_source,
                        pose_data_mode, spatial_confidence, data_mode, created_at, updated_at)
                    values (:id, :t, :s, :type, :code, :name, :f, :zone, :status, cast(:attrs as jsonb), :x, :y, :z,
                        :h, :at, :src, :dm, :conf, :dm, :at, :at)
                    on conflict do nothing
                    """).param("id", e.id()).param("t", tenant).param("s", site).param("type", e.type())
                    .param("code", e.code()).param("name", e.name()).param("f", e.floorId())
                    .param("zone", e.zoneId()).param("status", e.status()).param("attrs", json(e))
                    .param("x", e.x()).param("y", e.y()).param("z", e.z()).param("h", e.headingDeg())
                    .param("at", poseTime).param("src", WorldSeedPlan.POSE_SOURCE).param("conf", e.spatialConfidence())
                    .param("dm", DM).update();
        }
        for (ExternalRef ref : plan.externalRefs()) {
            canonicalIds.register(ref.mappingId(), tenant, ref.sourceSystem(), ref.sourceRecordId(),
                    ref.canonicalId(), ref.canonicalKind(), DataMode.SYNTHETIC);
        }
        jdbc.sql("""
                insert into world.world_versions (id, tenant_id, site_id, version, content_hash, label, data_mode,
                    created_at)
                values (:id, :t, :s, 1, :hash, :label, :dm, :at) on conflict do nothing
                """).param("id", SeedIds.of(BengaluruWarehouse01.NAMESPACE + "/" + site + "/world-version/1"))
                .param("t", tenant).param("s", site).param("hash", plan.contentHash()).param("label", plan.label())
                .param("dm", DM).param("at", poseTime).update();
        return new Result(true, plan.contentHash(), plan.entities().size());
    }

    private String json(Entity e) {
        return mapper.writeValueAsString(e.attributes());
    }
}
