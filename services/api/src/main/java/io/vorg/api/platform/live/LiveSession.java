package io.vorg.api.platform.live;

import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.identity.Subject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.web.socket.WebSocketSession;

/**
 * Server-side state of one socket: the authenticated subject, its token expiry, its subscriptions and the outbound
 * buffers. Poses are coalesced to the latest per (subscription, entity); discrete events wait in a bounded queue
 * whose overflow drops the oldest and is reported to the client as a gap so it can resync over REST.
 */
final class LiveSession {

    /** One drained batch: coalesced poses and events per subscription, plus dropped-event counts. */
    record Batch(Map<String, List<EventEnvelope>> poses, List<Map.Entry<String, EventEnvelope>> events,
            Map<String, Integer> gaps, long coalesced) {
        boolean isEmpty() {
            return poses.isEmpty() && events.isEmpty() && gaps.isEmpty();
        }
    }

    final WebSocketSession socket;
    final Instant connectedAt = Instant.now();
    final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    volatile Subject subject;
    volatile Instant expiresAt;
    volatile Instant lastInbound = Instant.now();
    volatile Instant lastPing = Instant.now();
    volatile Instant lastRecheck = Instant.now();
    volatile boolean closing;

    private final int maxQueuedEvents;
    private final Map<String, Map<UUID, EventEnvelope>> poses = new LinkedHashMap<>();
    private final ArrayDeque<Map.Entry<String, EventEnvelope>> events = new ArrayDeque<>();
    private final Map<String, Integer> gaps = new LinkedHashMap<>();
    private long coalesced;

    LiveSession(WebSocketSession socket, int maxQueuedEvents) {
        this.socket = socket;
        this.maxQueuedEvents = maxQueuedEvents;
    }

    boolean authenticated() {
        return subject != null;
    }

    synchronized void offerPose(String subId, EventEnvelope e) {
        Map<UUID, EventEnvelope> bySub = poses.computeIfAbsent(subId, k -> new LinkedHashMap<>());
        EventEnvelope previous = bySub.get(e.aggregateId());
        if (previous != null) {
            coalesced++;
            if (previous.occurredAt().isAfter(e.occurredAt())) {
                return; // never replace a newer pose with an older one
            }
            bySub.remove(e.aggregateId()); // re-insert so iteration order follows recency
        }
        bySub.put(e.aggregateId(), e);
    }

    synchronized void offerEvent(String subId, EventEnvelope e) {
        if (events.size() >= maxQueuedEvents) {
            events.pollFirst();
            gaps.merge(subId, 1, Integer::sum);
        }
        events.addLast(Map.entry(subId, e));
    }

    synchronized void forget(String subId) {
        poses.remove(subId);
        events.removeIf(en -> en.getKey().equals(subId));
        gaps.remove(subId);
    }

    synchronized Batch drain() {
        if (poses.isEmpty() && events.isEmpty() && gaps.isEmpty()) {
            return new Batch(Map.of(), List.of(), Map.of(), 0);
        }
        Map<String, List<EventEnvelope>> p = new LinkedHashMap<>();
        poses.forEach((k, v) -> p.put(k, new ArrayList<>(v.values())));
        Batch b = new Batch(p, new ArrayList<>(events), new LinkedHashMap<>(gaps), coalesced);
        poses.clear();
        events.clear();
        gaps.clear();
        coalesced = 0;
        return b;
    }
}
