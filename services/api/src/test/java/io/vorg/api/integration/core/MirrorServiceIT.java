package io.vorg.api.integration.core;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.integration.core.MirrorService.IngestSummary;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.support.AbstractApiIntegrationTest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MirrorServiceIT extends AbstractApiIntegrationTest {

    private static final Instant T = Instant.parse("2026-10-12T10:00:00Z");

    @Autowired
    MirrorService mirrors;

    @Autowired
    JdbcClient jdbc;

    /** Test adapter with a unique source system per test so tests never see each other's records. */
    private static SourceAdapter adapter(String system) {
        return new SourceAdapter() {
            @Override
            public String sourceSystem() {
                return system;
            }

            @Override
            public DataMode dataMode() {
                return DataMode.SIMULATED;
            }

            @Override
            public Set<RecordKind> kinds() {
                return Set.of(RecordKind.values());
            }

            @Override
            public SourceBatch fetch(AdapterContext context) {
                throw new UnsupportedOperationException();
            }
        };
    }

    private static Map<String, Object> inventory(int onHand) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sku", "SKU-1");
        p.put("location_code", "A-01-01");
        p.put("on_hand", onHand);
        return p;
    }

    private static SourceRecord inv(String id, long version, int onHand) {
        return new SourceRecord(RecordKind.INVENTORY, id, version, T, inventory(onHand));
    }

    private static SourceRecord shipment(String id, long version, String state, String dock) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("truck_code", "TR-09");
        p.put("state", state);
        p.put("dock_name", dock);
        return new SourceRecord(RecordKind.SHIPMENT, id, version, T, p);
    }

    private IngestSummary ingest(SourceAdapter a, SourceRecord... records) {
        return mirrors.ingest(TENANT_A, SITE_BLR, a, new SourceBatch(a.sourceSystem(), 0, List.of(records)));
    }

    private static String system() {
        return "T-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    void createUpdateAndDuplicateKeepEveryVersion() throws Exception {
        SourceAdapter a = adapter(system());
        IngestSummary first = ingest(a, inv("I-1", 1, 10));
        assertThat(first.created()).isEqualTo(1);
        IngestSummary dup = ingest(a, inv("I-1", 1, 10));
        assertThat(dup.duplicates()).isEqualTo(1);
        assertThat(dup.created() + dup.updated()).isZero();
        IngestSummary upd = ingest(a, inv("I-1", 2, 25));
        assertThat(upd.updated()).isEqualTo(1);

        Map<String, Object> row = ownerQuery("""
                select source_version, revisions, data_mode, payload->>'on_hand' as on_hand
                from integ.mirror_records where source_system = ?
                """, a.sourceSystem()).get(0);
        assertThat(((Number) row.get("source_version")).longValue()).isEqualTo(2);
        assertThat(((Number) row.get("revisions")).intValue()).isEqualTo(2);
        assertThat(row.get("data_mode")).isEqualTo("SIMULATED");
        assertThat(row.get("on_hand")).isEqualTo("25");
        // Version 1 is still there: a mirror is never silently overwritten.
        assertThat(ownerCount("""
                select count(*) from integ.mirror_revisions r join integ.mirror_records m on m.id = r.mirror_id
                where m.source_system = ?
                """, a.sourceSystem())).isEqualTo(2);
    }

    @Test
    void appliedRecordsAreAnnouncedAsLabeledSimulatedEvents() throws Exception {
        SourceAdapter a = adapter(system());
        ingest(a, inv("I-9", 1, 5));
        Map<String, Object> event = ownerQuery("""
                select e.source, e.data_mode, e.actor_type, e.actor_id, e.sequence, e.payload->>'source_system' as sys,
                       e.location->>'site_id' as site
                from evt.events e where e.event_type = 'MIRROR_RECORD_UPDATED' and e.payload->>'source_system' = ?
                """, a.sourceSystem()).get(0);
        assertThat(event.get("source")).isEqualTo("SIMULATION");
        assertThat(event.get("data_mode")).isEqualTo("SIMULATED");
        assertThat(event.get("actor_type")).isEqualTo("SERVICE");
        assertThat(event.get("actor_id")).isEqualTo("svc:adapter:" + a.sourceSystem());
        assertThat(((Number) event.get("sequence")).longValue()).isEqualTo(1);
        assertThat(event.get("site")).isEqualTo(SITE_BLR.toString());
    }

    @Test
    void staleVersionAndSameVersionConflictAreFindingsNotOverwrites() throws Exception {
        SourceAdapter a = adapter(system());
        ingest(a, inv("I-2", 5, 50));
        IngestSummary stale = ingest(a, inv("I-2", 3, 99));
        assertThat(stale.stale()).isEqualTo(1);
        IngestSummary conflict = ingest(a, inv("I-2", 5, 51));
        assertThat(conflict.conflicts()).isEqualTo(1);
        // Replaying the same stale record does not create a second finding.
        ingest(a, inv("I-2", 3, 99));

        assertThat(ownerQuery("select payload->>'on_hand' as v from integ.mirror_records where source_system = ?",
                a.sourceSystem()).get(0).get("v")).isEqualTo("50");
        assertThat(ownerQuery("""
                select finding_type, count(*) as n from integ.findings where source_system = ?
                group by finding_type order by finding_type
                """, a.sourceSystem()))
                .extracting(m -> m.get("finding_type") + "=" + m.get("n"))
                .containsExactly("STALE_VERSION=1", "VERSION_CONFLICT=1");
        assertThat(ownerCount("""
                select count(*) from evt.events where event_type = 'INTEGRATION_FINDING_RECORDED'
                and payload->>'source_system' = ?
                """, a.sourceSystem())).isEqualTo(2);
    }

    @Test
    void invalidRecordsAreRejectedWithADataQualityFinding() throws Exception {
        SourceAdapter a = adapter(system());
        IngestSummary s = ingest(a, inv("I-3", 1, -5));
        assertThat(s.rejected()).isEqualTo(1);
        assertThat(ownerCount("select count(*) from integ.mirror_records where source_system = ?",
                a.sourceSystem())).isZero();
        Map<String, Object> f = ownerQuery("""
                select finding_type, severity, detail->>'outcome' as outcome from integ.findings
                where source_system = ?
                """, a.sourceSystem()).get(0);
        assertThat(f.get("finding_type")).isEqualTo("DATA_QUALITY");
        assertThat(f.get("severity")).isEqualTo("ERROR");
        assertThat(f.get("outcome")).isEqualTo("REJECTED");
    }

    @Test
    void shipmentAtADockTheTwinShowsFreeIsAReconciliationDifference() throws Exception {
        SourceAdapter a = adapter(system());
        // IN-01 is OCCUPIED in the seeded twin, IN-03 is AVAILABLE.
        IngestSummary ok = ingest(a, shipment("TRIP-A", 1, "AT_DOCK", "IN-01"));
        assertThat(ok.reconciliationDiffs()).isZero();
        IngestSummary diff = ingest(a, shipment("TRIP-B", 1, "UNLOADING", "IN-03"));
        assertThat(diff.reconciliationDiffs()).isEqualTo(1);
        IngestSummary moving = ingest(a, shipment("TRIP-C", 1, "ARRIVING", "IN-03"));
        assertThat(moving.reconciliationDiffs()).isZero();
        Map<String, Object> f = ownerQuery("""
                select finding_type, severity, detail->>'twin_dock_status' as twin from integ.findings
                where source_system = ?
                """, a.sourceSystem()).get(0);
        assertThat(f.get("finding_type")).isEqualTo("RECONCILIATION_DIFF");
        assertThat(f.get("twin")).isEqualTo("AVAILABLE");
    }

    @Test
    void mirrorsAreInvisibleToOtherTenants() throws Exception {
        SourceAdapter a = adapter(system());
        ingest(a, inv("I-4", 1, 1));
        long asTenantB = TenantContext.callWith(TENANT_B, () -> jdbc
                .sql("select count(*) from integ.mirror_records where source_system = :s")
                .param("s", a.sourceSystem()).query(Long.class).single());
        long asTenantA = TenantContext.callWith(TENANT_A, () -> jdbc
                .sql("select count(*) from integ.mirror_records where source_system = :s")
                .param("s", a.sourceSystem()).query(Long.class).single());
        assertThat(asTenantB).isZero();
        assertThat(asTenantA).isEqualTo(1);
    }
}
