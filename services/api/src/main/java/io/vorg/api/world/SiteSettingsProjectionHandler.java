package io.vorg.api.world;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.outbox.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Demo consumer proving the end of the slice (command, state, outbox, event, handler): it logs the change (field names
 * only) and counts it. Real projections arrive with the phases that own them; the handler is idempotent because the
 * poller guarantees one successful invocation per (consumer, event).
 */
@Component
public class SiteSettingsProjectionHandler implements EventHandler {

    private static final Logger LOG = LoggerFactory.getLogger(SiteSettingsProjectionHandler.class);

    private final Counter handled;

    public SiteSettingsProjectionHandler(MeterRegistry meters) {
        this.handled = Counter.builder("vo.site.settings.projected")
                .description("SITE_SETTINGS_UPDATED events handled by the demo projection").register(meters);
    }

    @Override
    public String consumer() {
        return "world.site-settings-projection";
    }

    @Override
    public boolean supports(String eventType) {
        return SiteSettingsService.EVENT_TYPE.equals(eventType);
    }

    @Override
    public void handle(EventEnvelope event) {
        LOG.info("Site settings updated: site={} sequence={} changed_fields={}", event.aggregateId(), event.sequence(),
                event.payload().get("changed_fields"));
        handled.increment();
    }
}
