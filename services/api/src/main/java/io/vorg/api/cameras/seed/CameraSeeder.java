package io.vorg.api.cameras.seed;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;

import io.vorg.api.platform.tenancy.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the SYNTHETIC camera registry of Bengaluru Warehouse 01 (Plan 7.3) from
 * {@code seed/cameras-bengaluru-warehouse-01.json}: one registry row per seeded CAMERA twin entity, with the demo
 * calibration, class map and local media name. Local/demo/test profiles only, never a migration; runs after the world
 * seed, idempotent (fixed name-based ids, {@code on conflict do nothing}). A camera entity that is missing is skipped
 * and reported, nothing is invented.
 */
@Component
@Order(3)
@Profile({"local", "demo", "test"})
public class CameraSeeder implements ApplicationRunner {

    static final String RESOURCE = "seed/cameras-bengaluru-warehouse-01.json";
    private static final Logger LOG = LoggerFactory.getLogger(CameraSeeder.class);

    private record CamEntity(UUID id, String name, UUID floorId, UUID zoneId, BigDecimal x, BigDecimal y,
            BigDecimal z, BigDecimal heading) {}

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final TransactionTemplate tx;

    public CameraSeeder(JdbcClient jdbc, JsonMapper mapper, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    public static UUID idOf(UUID siteId, String code) {
        return UUID.nameUUIDFromBytes(("vorg-seed/cameras/v1/" + siteId + "/" + code)
                .getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        JsonNode doc;
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            doc = mapper.readTree(in);
        }
        int seeded = TenantContext.callWith(TENANT_A, () -> tx.execute(status -> seed(doc)));
        LOG.info("Camera registry for Bengaluru Warehouse 01: {} new rows (SYNTHETIC demo registry)", seeded);
    }

    private int seed(JsonNode doc) {
        int seeded = 0;
        for (JsonNode c : doc.get("cameras")) {
            String code = c.get("code").asString();
            Optional<CamEntity> entity = jdbc.sql("""
                    select id, name, floor_id, zone_id, pose_x, pose_y, pose_z, pose_heading_deg from world.entities
                    where site_id = :s and code = :code and entity_type = 'CAMERA'
                    """).param("s", SITE_BLR).param("code", code).query((rs, n) -> new CamEntity(
                            rs.getObject("id", UUID.class), rs.getString("name"), rs.getObject("floor_id", UUID.class),
                            rs.getObject("zone_id", UUID.class), rs.getBigDecimal("pose_x"), rs.getBigDecimal("pose_y"),
                            rs.getBigDecimal("pose_z"), rs.getBigDecimal("pose_heading_deg"))).optional();
            if (entity.isEmpty()) {
                LOG.warn("Camera entity {} not found in site {}; registry row skipped", code, SITE_BLR);
                continue;
            }
            CamEntity e = entity.get();
            JsonNode cal = c.get("calibration");
            seeded += jdbc.sql("""
                    insert into cam.cameras (id, tenant_id, site_id, entity_id, code, name, floor_id, zone_id, status,
                        pose_x, pose_y, pose_z, pose_heading_deg, calibration, class_map, feed_kind, media_name,
                        media_kind, data_mode, retention_days)
                    values (:id, :t, :s, :e, :code, :name, :floor, :zone, :status, :x, :y, :z, :h,
                        cast(:cal as jsonb), cast(:map as jsonb), :feed, :media, :kind, :dm, :ret)
                    on conflict do nothing
                    """).param("id", idOf(SITE_BLR, code)).param("t", TENANT_A).param("s", SITE_BLR)
                    .param("e", e.id()).param("code", code).param("name", e.name()).param("floor", e.floorId())
                    .param("zone", e.zoneId()).param("status", c.get("status").asString()).param("x", e.x())
                    .param("y", e.y()).param("z", e.z()).param("h", e.heading())
                    .param("cal", cal == null || cal.isNull() ? null : mapper.writeValueAsString(cal))
                    .param("map", mapper.writeValueAsString(c.get("class_map")))
                    .param("feed", c.get("feed_kind").asString())
                    .param("media", c.path("media_name").isNull() ? null : c.get("media_name").asString())
                    .param("kind", c.path("media_kind").isNull() ? null : c.get("media_kind").asString())
                    .param("dm", c.get("data_mode").asString()).param("ret", c.get("retention_days").asInt())
                    .update();
        }
        return seeded;
    }
}
