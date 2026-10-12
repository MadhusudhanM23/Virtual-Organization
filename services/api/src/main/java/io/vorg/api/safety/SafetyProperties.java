package io.vorg.api.safety;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Safety settings, {@code vo.safety.*}. The proximity numbers are <b>demo examples, not real-world safety limits</b>
 * (BLUEPRINT section 9): the platform is advisory and never a safety-rated function (ADR-05).
 *
 * <p>{@code proximity}: a worker and a moving forklift on one floor closer than {@code thresholdM} raise an alert; the
 * pair is considered apart again only beyond {@code thresholdM + hysteresisM}; a pair that was apart re-alerts only
 * after {@code cooldown} of event time; a pose older than {@code maxPoseAge} (relative to the event being evaluated) is
 * ignored. {@code incident.mergeWindow}: a new alert for the same pair within this event-time window of the previous
 * alert joins the still-open incident instead of opening another. {@code demo.stepInterval}: pause between the staged
 * demo observations (zero stages the whole approach synchronously, used by tests).
 */
@ConfigurationProperties(prefix = "vo.safety")
public record SafetyProperties(@DefaultValue Proximity proximity, @DefaultValue Incident incident,
        @DefaultValue Demo demo) {

    public record Proximity(
            @DefaultValue("3.0") double thresholdM,
            @DefaultValue("1.0") double hysteresisM,
            @DefaultValue("1.5") double criticalM,
            @DefaultValue("0.3") double minForkliftSpeedMps,
            @DefaultValue("20s") Duration cooldown,
            @DefaultValue("10s") Duration maxPoseAge) {}

    public record Incident(@DefaultValue("15m") Duration mergeWindow) {}

    public record Demo(@DefaultValue("1s") Duration stepInterval, @DefaultValue("90s") Duration holdDuration) {}
}
