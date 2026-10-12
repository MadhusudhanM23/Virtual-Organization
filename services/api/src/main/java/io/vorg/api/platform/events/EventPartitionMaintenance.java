package io.vorg.api.platform.events;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps daily {@code evt.events} partitions created ahead of time (ADR-23): once at startup and then on a schedule.
 * The SQL function is idempotent and SECURITY DEFINER; a failure here never stops the application because the default
 * partition still accepts every event.
 */
@Component
public class EventPartitionMaintenance implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(EventPartitionMaintenance.class);

    private final JdbcClient jdbc;
    private final int daysAhead;

    public EventPartitionMaintenance(JdbcClient jdbc, @Value("${vo.events.partitions.days-ahead:7}") int daysAhead) {
        this.jdbc = jdbc;
        this.daysAhead = daysAhead;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensurePartitions();
    }

    @Scheduled(cron = "${vo.events.partitions.cron:0 15 * * * *}", zone = "UTC")
    public void ensurePartitions() {
        try {
            Integer created = jdbc.sql("select evt.ensure_event_partitions(1, :ahead)").param("ahead", daysAhead)
                    .query(Integer.class).single();
            if (created != null && created > 0) {
                LOG.info("Created {} evt.events daily partition(s)", created);
            }
        } catch (RuntimeException ex) {
            LOG.error("Event partition maintenance failed; events continue to land in the default partition", ex);
        }
    }
}
