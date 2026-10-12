package io.vorg.api.incidents;

import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.outbox.EventHandler;
import io.vorg.api.safety.ProximityAlertHandler;
import org.springframework.stereotype.Component;

/**
 * Outbox consumer of {@code PROXIMITY_ALERT_RAISED}: opens an incident for the pair or merges the alert into the pair's
 * open incident (REQ-050, REQ-051). It runs after the alert is already committed and fanned out, so the incident never
 * delays the immediate alert. Idempotent through the consumer ledger and the unique alert row.
 */
@Component
public class IncidentAlertHandler implements EventHandler {

    public static final String CONSUMER = "incidents.from-alerts";

    private final IncidentService incidents;

    public IncidentAlertHandler(IncidentService incidents) {
        this.incidents = incidents;
    }

    @Override
    public String consumer() {
        return CONSUMER;
    }

    @Override
    public boolean supports(String eventType) {
        return ProximityAlertHandler.ALERT_EVENT.equals(eventType);
    }

    @Override
    public void handle(EventEnvelope event) {
        incidents.onAlert(event);
    }
}
