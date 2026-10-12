package io.vorg.api.platform.events.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Drives {@link OutboxPoller} on a fixed delay. Disabled with {@code vo.outbox.poller.enabled=false} (tests drive it). */
@Component
@ConditionalOnProperty(name = "vo.outbox.poller.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPollerScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxPollerScheduler.class);

    private final OutboxPoller poller;
    private final int batchSize;

    public OutboxPollerScheduler(OutboxPoller poller, @Value("${vo.outbox.batch-size:50}") int batchSize) {
        this.poller = poller;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${vo.outbox.poll-interval:200ms}")
    void tick() {
        try {
            // Drain while full batches keep arriving, then wait for the next tick.
            while (poller.pollOnce() >= batchSize) {
                // continue
            }
        } catch (RuntimeException ex) {
            LOG.warn("Outbox poll failed: {}", ex.getClass().getSimpleName());
        }
    }
}
