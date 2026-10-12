package io.vorg.api.integration.core;

import io.vorg.api.platform.live.LiveProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link IntegrationSyncService} for the configured site while {@code vo.live.enabled=true}: one batch per
 * {@code vo.live.sync.interval} (default 15 s), so the mirrors, findings and adapter-created tasks grow at a steady,
 * small rate.
 */
@Component
@ConditionalOnProperty(name = "vo.live.enabled", havingValue = "true")
public class IntegrationSyncScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(IntegrationSyncScheduler.class);

    private final IntegrationSyncService sync;
    private final LiveProperties live;

    public IntegrationSyncScheduler(IntegrationSyncService sync, LiveProperties live) {
        this.sync = sync;
        this.live = live;
        if (live.tenantId() == null || live.siteId() == null) {
            throw new IllegalStateException("vo.live.enabled requires vo.live.tenant-id and vo.live.site-id");
        }
    }

    @Scheduled(fixedDelayString = "${vo.live.sync.interval:15s}", initialDelayString = "${vo.live.sync.interval:15s}")
    void tick() {
        try {
            sync.syncOnce(live.tenantId(), live.siteId()).forEach((system, summary) -> LOG.debug(
                    "Integration sync {}: created={} updated={} duplicates={} findings={}", system, summary.created(),
                    summary.updated(), summary.duplicates(), summary.findings()));
        } catch (RuntimeException ex) {
            LOG.warn("Integration sync failed: {}", ex.toString());
        }
    }
}
