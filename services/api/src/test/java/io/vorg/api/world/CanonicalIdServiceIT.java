package io.vorg.api.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.vorg.api.platform.events.DataMode;
import io.vorg.api.support.AbstractIntegrationTest;
import io.vorg.api.world.CanonicalIdService.CanonicalIdException;
import io.vorg.api.world.CanonicalIdService.Mapping;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CanonicalIdServiceIT extends AbstractIntegrationTest {

    @Autowired
    CanonicalIdService ids;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void tenants() throws Exception {
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        try (Connection owner = POSTGRES.createConnection("")) {
            for (UUID t : new UUID[] {tenantA, tenantB}) {
                try (PreparedStatement ps = owner.prepareStatement(
                        "insert into iam.tenants (id, slug, name) values (?, ?, ?)")) {
                    ps.setObject(1, t);
                    ps.setString(2, "cid-" + t);
                    ps.setString(3, "t");
                    ps.executeUpdate();
                }
            }
        }
    }

    @Test
    void registersAndResolvesBothDirections() {
        UUID canonical = UUID.randomUUID();
        Mapping m = ids.register(tenantA, "SIM_WMS", "PAL-0001", canonical, "PALLET", DataMode.SYNTHETIC);
        assertThat(m.canonicalId()).isEqualTo(canonical);
        assertThat(m.dataMode()).isEqualTo(DataMode.SYNTHETIC);

        assertThat(ids.resolve(tenantA, "SIM_WMS", "PAL-0001")).contains(m);
        assertThat(ids.resolve(tenantA, "SIM_WMS", "PAL-9999")).isEmpty();
        ids.register(tenantA, "SIM_TMS", "SHP-77", canonical, "PALLET", DataMode.SIMULATED);
        assertThat(ids.externalIdsOf(tenantA, canonical)).extracting(Mapping::sourceSystem)
                .containsExactly("SIM_TMS", "SIM_WMS");
    }

    @Test
    void registrationIsIdempotentForTheSameMapping() {
        UUID canonical = UUID.randomUUID();
        Mapping first = ids.register(tenantA, "SIM_WMS", "PAL-1", canonical, "PALLET", DataMode.SYNTHETIC);
        Mapping again = ids.register(tenantA, "SIM_WMS", "PAL-1", canonical, "PALLET", DataMode.SYNTHETIC);
        assertThat(again).isEqualTo(first);
        assertThat(ids.externalIdsOf(tenantA, canonical)).hasSize(1);
    }

    @Test
    void conflictingMappingsAreRejectedNotRepointed() {
        UUID canonical = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        ids.register(tenantA, "SIM_WMS", "PAL-2", canonical, "PALLET", DataMode.SYNTHETIC);
        assertThatThrownBy(() -> ids.register(tenantA, "SIM_WMS", "PAL-2", other, "PALLET", DataMode.SYNTHETIC))
                .isInstanceOf(CanonicalIdException.class).hasMessageContaining("already mapped");
        assertThatThrownBy(() -> ids.register(tenantA, "SIM_WMS", "PAL-3", canonical, "PALLET", DataMode.SYNTHETIC))
                .isInstanceOf(CanonicalIdException.class).hasMessageContaining("already has a different record");
        assertThat(ids.resolve(tenantA, "SIM_WMS", "PAL-2").orElseThrow().canonicalId()).isEqualTo(canonical);
    }

    @Test
    void mappingsAreTenantScoped() {
        UUID canonicalA = UUID.randomUUID();
        UUID canonicalB = UUID.randomUUID();
        ids.register(tenantA, "SIM_WMS", "PAL-5", canonicalA, "PALLET", DataMode.SYNTHETIC);
        assertThat(ids.resolve(tenantB, "SIM_WMS", "PAL-5")).isEmpty();
        // The same external key can map independently in another tenant.
        ids.register(tenantB, "SIM_WMS", "PAL-5", canonicalB, "PALLET", DataMode.SYNTHETIC);
        assertThat(ids.resolve(tenantA, "SIM_WMS", "PAL-5").orElseThrow().canonicalId()).isEqualTo(canonicalA);
        assertThat(ids.resolve(tenantB, "SIM_WMS", "PAL-5").orElseThrow().canonicalId()).isEqualTo(canonicalB);
        assertThat(ids.externalIdsOf(tenantB, canonicalA)).isEmpty();
    }

    @Test
    void rejectsInvalidInput() {
        UUID c = UUID.randomUUID();
        assertThatThrownBy(() -> ids.register(tenantA, "sim wms", "x", c, "PALLET", DataMode.SYNTHETIC))
                .isInstanceOf(CanonicalIdException.class);
        assertThatThrownBy(() -> ids.register(tenantA, "SIM_WMS", " ", c, "PALLET", DataMode.SYNTHETIC))
                .isInstanceOf(CanonicalIdException.class);
        assertThatThrownBy(() -> ids.register(tenantA, "SIM_WMS", "x".repeat(201), c, "PALLET", DataMode.SYNTHETIC))
                .isInstanceOf(CanonicalIdException.class);
    }
}
