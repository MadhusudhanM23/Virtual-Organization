package io.vorg.api.world.seed;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.world.seed.WorldSeedPlan.Entity;
import io.vorg.api.world.seed.WorldSeedPlan.Rack;
import io.vorg.api.world.seed.WorldSeedPlan.Zone;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Determinism and layout invariants of the SYNTHETIC "Bengaluru Warehouse 01" seed (no database needed). */
class WorldSeedPlanTest {

    /**
     * Pinned content hash of seed v1. If this fails the seed changed: that is a new seed version (bump NAMESPACE/LABEL,
     * update docs/scenarios/bengaluru-warehouse-01.md), never a silent edit.
     */
    static final String GOLDEN_HASH = "9d15dd6f0c2dcf94648b440f0d9e5db400225c77bee2ca6bac11600cdb967af6";

    private static WorldSeedPlan plan() {
        return BengaluruWarehouse01.build(TENANT_A, SITE_BLR);
    }

    @Test
    void samePlanEveryRunWithTheSameContentHash() {
        WorldSeedPlan first = plan();
        WorldSeedPlan second = plan();
        assertThat(second).isEqualTo(first);
        assertThat(second.contentHash()).isEqualTo(first.contentHash()).hasSize(64);
        assertThat(first.contentHash()).isEqualTo(GOLDEN_HASH);
    }

    @Test
    void independentOfDefaultLocaleAndOtherTestsOrdering() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"));
            assertThat(plan().contentHash()).isEqualTo(GOLDEN_HASH);
            Locale.setDefault(Locale.forLanguageTag("de-DE"));
            assertThat(plan().contentHash()).isEqualTo(GOLDEN_HASH);
        } finally {
            Locale.setDefault(before);
        }
    }

    @Test
    void identifiersAreFixedAndUniqueAndVersion7Shaped() {
        WorldSeedPlan p = plan();
        assertThat(SeedIds.of("a")).isEqualTo(SeedIds.of("a")).isNotEqualTo(SeedIds.of("b"));
        assertThat(SeedIds.of("a").version()).isEqualTo(7);
        assertThat(SeedIds.of("a").variant()).isEqualTo(2);

        Set<UUID> all = new HashSet<>();
        all.add(p.building().id());
        p.floors().forEach(f -> all.add(f.id()));
        p.zones().forEach(z -> all.add(z.id()));
        p.docks().forEach(d -> all.add(d.id()));
        p.racks().forEach(r -> all.add(r.id()));
        p.entities().forEach(e -> all.add(e.id()));
        int expected = 1 + p.floors().size() + p.zones().size() + p.docks().size() + p.racks().size()
                + p.entities().size();
        assertThat(all).hasSize(expected);
        assertThat(p.entities().stream().map(Entity::code).collect(Collectors.toSet())).hasSize(p.entities().size());
    }

    @Test
    void meetsRequirementCounts() {
        WorldSeedPlan p = plan();
        assertThat(p.zones().stream().map(Zone::zoneType).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("RECEIVING", "STORAGE", "PICKING", "PACKING", "DISPATCH");
        assertThat(p.zones().size()).isGreaterThanOrEqualTo(5);
        assertThat(p.docks().size()).isGreaterThanOrEqualTo(3);
        assertThat(p.racks()).isNotEmpty();
        assertThat(p.floors()).hasSize(2);
        assertThat(p.count("FORKLIFT")).isEqualTo(10);
        assertThat(p.count("WORKER")).isEqualTo(30);
        assertThat(p.count("TRUCK")).isEqualTo(6);
        assertThat(p.count("PALLET")).isEqualTo(120);
        assertThat(p.count("CAMERA")).isEqualTo(4);
        assertThat(p.entities()).hasSize(170);
        assertThat(p.racks()).hasSize(78);
        assertThat(p.externalRefs()).hasSize(132);
    }

    @Test
    void layoutIsConsistent() {
        WorldSeedPlan p = plan();
        Map<UUID, Zone> zones = p.zones().stream().collect(Collectors.toMap(Zone::id, Function.identity()));
        Map<UUID, WorldSeedPlan.Floor> floors = p.floors().stream()
                .collect(Collectors.toMap(WorldSeedPlan.Floor::id, Function.identity()));

        // Zones lie within their floor and ground-floor zones do not overlap.
        for (Zone z : p.zones()) {
            WorldSeedPlan.Floor f = floors.get(z.floorId());
            assertThat(f).isNotNull();
            assertThat(f.bounds().contains(z.bounds().minX(), z.bounds().minY())).isTrue();
            assertThat(f.bounds().contains(z.bounds().maxX(), z.bounds().maxY())).isTrue();
        }
        List<Zone> ground = p.zones().stream().filter(z -> z.floorId().equals(p.floors().get(0).id())).toList();
        for (int i = 0; i < ground.size(); i++) {
            for (int j = i + 1; j < ground.size(); j++) {
                assertThat(overlap(ground.get(i), ground.get(j))).as("%s vs %s", ground.get(i).name(),
                        ground.get(j).name()).isFalse();
            }
        }

        // Racks and zoned entities sit inside their zone; docks touch the building edge.
        for (Rack r : p.racks()) {
            assertThat(zones.get(r.zoneId()).bounds().contains(r.x(), r.y())).as(r.code()).isTrue();
        }
        for (Entity e : p.entities()) {
            if (e.zoneId() != null) {
                assertThat(zones.get(e.zoneId()).bounds().contains(e.x(), e.y())).as(e.code()).isTrue();
            }
            assertThat(e.headingDeg()).isBetween(BigDecimal.ZERO, new BigDecimal("359.999"));
            assertThat(e.x().scale()).isEqualTo(3);
        }
        for (WorldSeedPlan.Dock d : p.docks()) {
            boolean southOrNorthWall = d.y().compareTo(BigDecimal.ZERO) == 0 || d.y().compareTo(new BigDecimal("80")) == 0;
            assertThat(southOrNorthWall).as(d.name()).isTrue();
        }

        // Racks never overlap (footprint length along x, depth along y; all headings are 0).
        for (int i = 0; i < p.racks().size(); i++) {
            Rack a = p.racks().get(i);
            for (int j = i + 1; j < p.racks().size(); j++) {
                Rack b = p.racks().get(j);
                if (!a.floorId().equals(b.floorId())) {
                    continue;
                }
                boolean overlapX = a.x().subtract(b.x()).abs().compareTo(a.lengthM()) < 0;
                boolean overlapY = a.y().subtract(b.y()).abs().compareTo(a.depthM()) < 0;
                assertThat(overlapX && overlapY).as("%s overlaps %s", a.code(), b.code()).isFalse();
            }
        }
    }

    @Test
    void everythingIsSyntheticAndPseudonymousAndNothingIsInvented() {
        WorldSeedPlan p = plan();
        assertThat(WorldSeedPlan.DATA_MODE).isEqualTo("SYNTHETIC");
        assertThat(p.label()).startsWith("SYNTHETIC");
        for (Entity e : p.entities()) {
            assertThat(e.attributes()).containsEntry("synthetic", true);
        }
        p.entities().stream().filter(e -> e.type().equals("WORKER")).forEach(e -> {
            assertThat(e.code()).matches("W-\\d{3}");
            assertThat(e.name()).isEqualTo("Worker " + e.code());
            assertThat(e.attributes().keySet()).containsExactlyInAnyOrder("synthetic", "pseudonym");
        });
        p.entities().stream().filter(e -> e.type().equals("CAMERA")).forEach(e -> {
            assertThat(e.attributes()).containsEntry("placeholder", true);
            assertThat(e.attributes()).containsEntry("stream_url", null).containsEntry("calibration", null);
        });
    }

    @Test
    void rackSlotsAreNotDoubleBooked() {
        Set<String> slots = new HashSet<>();
        for (Entity e : plan().entities()) {
            if ("PALLET".equals(e.type()) && "STORED".equals(e.status())) {
                String key = e.attributes().get("rack_code") + "/" + e.attributes().get("level") + "/"
                        + e.attributes().get("slot");
                assertThat(slots.add(key)).as(key).isTrue();
            }
        }
        assertThat(slots).hasSize(80);
    }

    @Test
    void externalRefsPointAtRealEntitiesAndDocks() {
        WorldSeedPlan p = plan();
        Set<UUID> canonical = new HashSet<>();
        p.entities().forEach(e -> canonical.add(e.id()));
        p.docks().forEach(d -> canonical.add(d.id()));
        assertThat(p.externalRefs()).allSatisfy(r -> assertThat(canonical).contains(r.canonicalId()));
        assertThat(p.externalRefs().stream().map(r -> r.sourceSystem() + ":" + r.sourceRecordId())
                .collect(Collectors.toSet())).hasSize(p.externalRefs().size());
        assertThat(p.externalRefs().stream().map(WorldSeedPlan.ExternalRef::sourceSystem).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("SIM_WMS", "SIM_TMS");
    }

    private static boolean overlap(Zone a, Zone b) {
        WorldSeedPlan.Rect x = a.bounds();
        WorldSeedPlan.Rect y = b.bounds();
        return x.minX().compareTo(y.maxX()) < 0 && x.maxX().compareTo(y.minX()) > 0
                && x.minY().compareTo(y.maxY()) < 0 && x.maxY().compareTo(y.minY()) > 0;
    }
}
