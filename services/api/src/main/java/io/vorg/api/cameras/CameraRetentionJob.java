package io.vorg.api.cameras;

import io.vorg.api.platform.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention of vision observations (REQ-084, ADR-65): every {@code vo.vision.prune-interval}, for each tenant with its
 * tenant bound, observations older than their camera's {@code retention_days} are deleted. The batch events and the
 * position events stay (the event history is the system of record and holds no image data).
 */
@Component
public class CameraRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(CameraRetentionJob.class);

    private final JdbcClient jdbc;
    private final VisionIngestService ingest;

    public CameraRetentionJob(JdbcClient jdbc, VisionIngestService ingest) {
        this.jdbc = jdbc;
        this.ingest = ingest;
    }

    @Scheduled(fixedDelayString = "${vo.vision.prune-interval:1h}", initialDelayString = "${vo.vision.prune-interval:1h}")
    void prune() {
        List<UUID> tenants;
        try {
            tenants = jdbc.sql("select id from iam.tenants order by id").query(UUID.class).list();
        } catch (RuntimeException ex) {
            LOG.warn("Vision retention run skipped: {}", ex.toString());
            return;
        }
        for (UUID tenant : tenants) {
            try {
                int removed = TenantContext.callWith(tenant, ingest::prune);
                if (removed > 0) {
                    LOG.info("Vision retention: {} observations removed for tenant {}", removed, tenant);
                }
            } catch (RuntimeException ex) {
                LOG.warn("Vision retention failed for tenant {}: {}", tenant, ex.toString());
            }
        }
    }
}
