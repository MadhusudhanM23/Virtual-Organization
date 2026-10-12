package io.vorg.api.replay;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.replay.ReplayModel.ReplayState;
import io.vorg.api.replay.ReplayStore.SnapshotMeta;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Snapshot service (REQ-060, ADR-44): genesis, derived chain, storage integrity, retention, tenant isolation. */
class SnapshotServiceIT extends AbstractReplayIT {

    @Autowired
    ReplayStore store;

    @Autowired
    ReplayCodec codec;

    @Test
    void genesisThenDerivedSnapshotsAreStoredCompactlyAndVerifiably() throws Exception {
        SnapshotMeta genesis = snapshot();
        assertThat(genesis.origin()).isEqualTo("GENESIS");
        assertThat(genesis.baseSnapshotId()).isNull();
        assertThat(genesis.asOf()).isEqualTo(genesis.takenAt());
        long entities = ownerCount("select count(*) from world.entities where site_id = ?", SITE_BLR);
        assertThat(genesis.entityCount()).isEqualTo((int) entities);
        assertThat(genesis.worldVersion()).isNotNull();

        Instant t0 = now().plusMillis(20);
        observe("FL-09", t0, 15, 45, "MOVING");
        drain();
        waitPast(t0);
        SnapshotMeta derived = snapshot();
        assertThat(derived.origin()).isEqualTo("DERIVED");
        assertThat(derived.baseSnapshotId()).isEqualTo(genesis.id());
        assertThat(derived.eventsApplied()).isGreaterThanOrEqualTo(1);
        assertThat(derived.asOf()).isAfter(genesis.asOf());

        Map<String, Object> row = ownerQuery("select state_gzip, state_sha256, size_bytes, tenant_id, trigger"
                + " from rpl.snapshots where id = ?", derived.id()).get(0);
        byte[] gz = (byte[]) row.get("state_gzip");
        byte[] json = ReplayCodec.gunzip(gz);
        assertThat(ReplayCodec.sha256(json)).isEqualTo(((String) row.get("state_sha256")).trim());
        assertThat(gz.length).isEqualTo(row.get("size_bytes")).isLessThan(json.length);
        assertThat(row.get("tenant_id")).isEqualTo(TENANT_A);
        assertThat(row.get("trigger")).isEqualTo("ON_DEMAND");
        ReplayState state = codec.readState(json);
        assertThat(codec.canonical(state)).isEqualTo(json); // canonical form round-trips byte for byte
        assertThat(state.entities().stream().filter(e -> e.id().equals(entityId("FL-09"))).findFirst().orElseThrow()
                .pose().x()).isEqualTo(15.0);
        assertThat(state.recorded().docks()).isNotEmpty();
    }

    @Test
    void retentionKeepsTheNewestAndSnapshotsAreTenantIsolated() throws Exception {
        SnapshotMeta old = snapshot();
        SnapshotMeta newest = snapshot();
        ownerUpdate("update rpl.snapshots set as_of = as_of - interval '8 days' where id = ?", old.id());
        int pruned = TenantContext.callWith(TENANT_A, () -> snapshots.prune(SITE_BLR));
        assertThat(pruned).isEqualTo(1);
        assertThat(ownerCount("select count(*) from rpl.snapshots where id = ?", newest.id())).isEqualTo(1);
        ownerUpdate("update rpl.snapshots set as_of = as_of - interval '30 days' where id = ?", newest.id());
        assertThat(TenantContext.callWith(TENANT_A, () -> snapshots.prune(SITE_BLR))).isZero(); // newest stays
        assertThat(TenantContext.callWith(TENANT_B, () -> store.count(SITE_BLR))).isZero();     // RLS
        assertThat(TenantContext.callWith(TENANT_A, () -> store.count(SITE_BLR))).isEqualTo(1);
    }

    @Test
    void snapshotsAreImmutableForTheRuntimeRole() throws Exception {
        snapshot();
        long updatable = ownerCount("select count(*) from information_schema.role_table_grants where grantee = 'vo_app'"
                + " and table_schema = 'rpl' and table_name = 'snapshots' and privilege_type = 'UPDATE'");
        assertThat(updatable).isZero();
    }
}
