package io.vorg.api.integration.simulated;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.integration.core.AdapterContext;
import io.vorg.api.integration.core.RecordKind;
import io.vorg.api.integration.core.RecordValidator;
import io.vorg.api.integration.core.SourceRecord;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.world.seed.BengaluruWarehouse01;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SimulatedAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-10-12T10:00:00Z");
    private static final SimWorld WORLD = SimWorld.fromPlan(BengaluruWarehouse01.build(TENANT_A, SITE_BLR));
    private static final SimWorldProvider PROVIDER = (tenant, site) -> WORLD;

    private static List<SourceRecord> all(SimulatedWmsAdapter wms, SimulatedTmsAdapter tms, int batches) {
        List<SourceRecord> out = new ArrayList<>();
        for (int b = 0; b < batches; b++) {
            AdapterContext ctx = new AdapterContext(TENANT_A, SITE_BLR, b, NOW);
            out.addAll(wms.fetch(ctx).records());
            out.addAll(tms.fetch(ctx).records());
        }
        return out;
    }

    @Test
    void sameSeedYieldsIdenticalRecords() {
        List<SourceRecord> a = all(new SimulatedWmsAdapter(7, 0, PROVIDER), new SimulatedTmsAdapter(PROVIDER), 40);
        List<SourceRecord> b = all(new SimulatedWmsAdapter(7, 0, PROVIDER), new SimulatedTmsAdapter(PROVIDER), 40);
        assertThat(a).isNotEmpty().isEqualTo(b);
    }

    @Test
    void aBatchIsAPureFunctionOfSeedAndBatchNumber() {
        SimulatedWmsAdapter wms = new SimulatedWmsAdapter(7, 0, PROVIDER);
        AdapterContext ctx = new AdapterContext(TENANT_A, SITE_BLR, 25, NOW);
        // Generating other batches in between must not change batch 25.
        var first = wms.fetch(ctx);
        wms.fetch(new AdapterContext(TENANT_A, SITE_BLR, 3, NOW));
        assertThat(wms.fetch(ctx)).isEqualTo(first);
    }

    @Test
    void differentSeedsDiffer() {
        List<SourceRecord> a = all(new SimulatedWmsAdapter(7, 0, PROVIDER), new SimulatedTmsAdapter(PROVIDER), 12);
        List<SourceRecord> b = all(new SimulatedWmsAdapter(8, 0, PROVIDER), new SimulatedTmsAdapter(PROVIDER), 12);
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void everyRecordIsStructurallyValidAndVersionsOnlyGrow() {
        List<SourceRecord> records = all(new SimulatedWmsAdapter(7, 0, PROVIDER), new SimulatedTmsAdapter(PROVIDER),
                60);
        Map<String, Long> last = new HashMap<>();
        for (SourceRecord r : records) {
            assertThat(RecordValidator.validate(r)).as(r.sourceRecordId()).isEmpty();
            String key = r.kind() + "/" + r.sourceRecordId();
            Long previous = last.put(key, r.sourceVersion());
            if (previous != null) {
                assertThat(r.sourceVersion()).as(key).isGreaterThan(previous);
            }
        }
        assertThat(records).extracting(SourceRecord::kind).contains(RecordKind.ORDER, RecordKind.INVENTORY,
                RecordKind.SHIPMENT, RecordKind.TASK);
    }

    @Test
    void adaptersAreLabeledSimulatedWithTheRequiredSourceSystems() {
        assertThat(new SimulatedWmsAdapter(7, 0, PROVIDER).sourceSystem()).isEqualTo("SIM-WMS");
        assertThat(new SimulatedTmsAdapter(PROVIDER).sourceSystem()).isEqualTo("SIM-TMS");
        assertThat(new SimulatedWmsAdapter(7, 0, PROVIDER).dataMode()).isEqualTo(DataMode.SIMULATED);
        assertThat(new SimulatedTmsAdapter(PROVIDER).dataMode()).isEqualTo(DataMode.SIMULATED);
    }

    @Test
    void orderLifecycleAdvancesOneOrderThroughThreeVersions() {
        SimulatedWmsAdapter wms = new SimulatedWmsAdapter(7, 0, PROVIDER);
        List<String> statuses = new ArrayList<>();
        for (int b = 0; b <= 6; b++) {
            wms.fetch(new AdapterContext(TENANT_A, SITE_BLR, b, NOW)).records().stream()
                    .filter(r -> r.sourceRecordId().equals("ORD-00001"))
                    .forEach(r -> statuses.add(r.sourceVersion() + ":" + r.payload().get("status")));
        }
        assertThat(statuses).containsExactly("1:RELEASED", "2:IN_PROGRESS", "3:COMPLETED");
    }

    @Test
    void faultInjectionProducesAnInvalidInventoryRecordOnlyWhenEnabled() {
        long invalid = all(new SimulatedWmsAdapter(7, 4, PROVIDER), new SimulatedTmsAdapter(PROVIDER), 20).stream()
                .filter(r -> !RecordValidator.validate(r).isEmpty()).count();
        assertThat(invalid).isEqualTo(5); // batches 3, 7, 11, 15, 19
    }

    @Test
    void shipmentsReferenceRealDocksAndTrucks() {
        SimulatedTmsAdapter tms = new SimulatedTmsAdapter(PROVIDER);
        var records = tms.fetch(new AdapterContext(TENANT_A, SITE_BLR, 0, NOW)).records();
        assertThat(records).hasSize(6);
        for (SourceRecord r : records) {
            assertThat(WORLD.trucks()).anyMatch(t -> t.code().equals(r.payload().get("truck_code")));
            assertThat(WORLD.dock(String.valueOf(r.payload().get("dock_name")))).isNotNull();
        }
    }
}
