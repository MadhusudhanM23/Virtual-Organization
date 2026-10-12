package io.vorg.api.platform.live;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.identity.Subject;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LiveSessionTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();
    private static final UUID SITE = UUID.randomUUID();
    private static final UUID FLOOR = UUID.randomUUID();

    private static EventEnvelope event(UUID tenant, UUID entity, String type, Instant at, double x, double y) {
        return new EventEnvelope(UUID.randomUUID(), tenant, "FORKLIFT", entity, type, null, at, at,
                EventSource.SIMULATION, DataMode.SIMULATED, ActorType.SERVICE, "svc", new EventLocation(SITE, FLOOR,
                        null, new EventLocation.Position(x, y, 0), 0.0), UUID.randomUUID(), null, 1,
                tools.jackson.databind.json.JsonMapper.builder().build().createObjectNode(), 1.0, null);
    }

    private static Subscription sub(String id, Set<String> types, Subscription.Bbox bbox) {
        return new Subscription(id, SITE, null, bbox, types, Set.of(), true, true);
    }

    @Test
    void posesAreCoalescedToTheLatestPerEntity() {
        LiveSession s = new LiveSession(null, 10);
        UUID entity = UUID.randomUUID();
        Instant t = Instant.now();
        s.offerPose("a", event(TENANT_A, entity, "FORKLIFT_POSITION_UPDATED", t, 1, 1));
        s.offerPose("a", event(TENANT_A, entity, "FORKLIFT_POSITION_UPDATED", t.plusSeconds(1), 2, 2));
        s.offerPose("a", event(TENANT_A, entity, "FORKLIFT_POSITION_UPDATED", t.minusSeconds(5), 9, 9));
        LiveSession.Batch batch = s.drain();
        assertThat(batch.poses().get("a")).hasSize(1);
        assertThat(batch.poses().get("a").get(0).location().position().x()).isEqualTo(2);
        assertThat(batch.coalesced()).isEqualTo(2);
        assertThat(s.drain().isEmpty()).isTrue();
    }

    @Test
    void eventQueueIsBoundedAndReportsGaps() {
        LiveSession s = new LiveSession(null, 3);
        for (int i = 0; i < 5; i++) {
            s.offerEvent("a", event(TENANT_A, UUID.randomUUID(), "TASK_STARTED", Instant.now(), 0, 0));
        }
        LiveSession.Batch batch = s.drain();
        assertThat(batch.events()).hasSize(3);
        assertThat(batch.gaps().get("a")).isEqualTo(2);
    }

    @Test
    void subscriptionFiltersByTypeAndBox() {
        EventEnvelope forklift = event(TENANT_A, UUID.randomUUID(), "FORKLIFT_POSITION_UPDATED", Instant.now(), 5, 5);
        assertThat(sub("a", Set.of(), null).wantsPose(forklift)).isTrue();
        assertThat(sub("a", Set.of("WORKER"), null).wantsPose(forklift)).isFalse();
        assertThat(sub("a", Set.of(), new Subscription.Bbox(0, 0, 10, 10)).wantsPose(forklift)).isTrue();
        assertThat(sub("a", Set.of(), new Subscription.Bbox(6, 6, 10, 10)).wantsPose(forklift)).isFalse();
        assertThat(sub("a", Set.of(), null).wantsEvent(forklift)).as("pose events are not discrete events").isFalse();
        EventEnvelope task = event(TENANT_A, UUID.randomUUID(), "TASK_STARTED", Instant.now(), 5, 5);
        assertThat(sub("a", Set.of(), null).wantsEvent(task)).isTrue();
        assertThat(new Subscription("a", SITE, null, null, Set.of(), Set.of("TASK_FAILED"), true, true)
                .wantsEvent(task)).isFalse();
    }

    @Test
    void fanoutNeverCrossesTenants() {
        LiveHub hub = new LiveHub(new LiveProperties(false, null, null, null, new LiveProperties.Sync(null),
                new LiveProperties.Movement(null, 1, 4, 0.5, 5, 1, false), new LiveProperties.Ws(
                        java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(15),
                        java.time.Duration.ofSeconds(45), java.time.Duration.ofMillis(100), 10, 8, "*")),
                null, null, new SimpleMeterRegistry());
        LiveSession a = new LiveSession(null, 10);
        a.subject = new Subject(UUID.randomUUID(), TENANT_A, "a", List.of());
        a.subscriptions.put("s", sub("s", Set.of(), null));
        LiveSession b = new LiveSession(null, 10);
        b.subject = new Subject(UUID.randomUUID(), TENANT_B, "b", List.of());
        // Same site id on purpose: only the tenant check can keep tenant B out.
        b.subscriptions.put("s", sub("s", Set.of(), null));
        hub.register(a);
        hub.register(b);
        hub.fanout(event(TENANT_A, UUID.randomUUID(), "FORKLIFT_POSITION_UPDATED", Instant.now(), 1, 1));
        hub.fanout(event(TENANT_A, UUID.randomUUID(), "TASK_STARTED", Instant.now(), 1, 1));
        assertThat(a.drain().isEmpty()).isFalse();
        assertThat(b.drain().isEmpty()).isTrue();
    }
}
