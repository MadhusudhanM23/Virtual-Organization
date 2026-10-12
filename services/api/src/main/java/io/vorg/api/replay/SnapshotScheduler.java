package io.vorg.api.replay;

import io.vorg.api.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic snapshots (REQ-060, ADR-23, ADR-44) while {@code vo.replay.snapshots.enabled=true} (the {@code local} profile):
 * every {@code vo.replay.snapshots.interval} (default 5 min) one snapshot per site that has a world model, for every
 * tenant, each with its tenant bound; then retention pruning. A failure for one site never stops the others.
 */
@Component
@ConditionalOnProperty(name = "vo.replay.snapshots.enabled", havingValue = "true")
public class SnapshotScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotScheduler.class);

    private final SnapshotService snapshots;
    private final ReplayStore store;

    public SnapshotScheduler(SnapshotService snapshots, ReplayStore store) {
        this.snapshots = snapshots;
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${vo.replay.snapshots.interval:5m}",
            initialDelayString = "${vo.replay.snapshots.initial-delay:30s}")
    void tick() {
        List<UUID> tenants;
        try {
            tenants = store.tenants();
        } catch (RuntimeException ex) {
            LOG.warn("Replay snapshot run skipped: {}", ex.toString());
            return;
        }
        for (UUID tenant : tenants) {
            List<UUID> sites = TenantContext.callWith(tenant, store::sitesWithWorld);
            for (UUID site : sites) {
                try {
                    ReplayStore.SnapshotMeta m = snapshots.snapshot(tenant, site, SnapshotService.SCHEDULED);
                    int pruned = TenantContext.callWith(tenant, () -> snapshots.prune(site));
                    LOG.info("Replay snapshot {} site={} origin={} as_of={} events_applied={} size_bytes={} pruned={}",
                            m.id(), site, m.origin(), m.asOf(), m.eventsApplied(), m.sizeBytes(), pruned);
                } catch (RuntimeException ex) {
                    LOG.warn("Replay snapshot failed for site {}: {}", site, ex.toString());
                }
            }
        }
    }
}
