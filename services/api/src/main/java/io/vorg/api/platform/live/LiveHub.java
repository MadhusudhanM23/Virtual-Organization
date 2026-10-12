package io.vorg.api.platform.live;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.vorg.api.platform.authz.SiteAccess;
import io.vorg.api.platform.events.EnvelopeCodec;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.outbox.EventHandler;
import io.vorg.api.platform.tenancy.TenantContext;
import io.vorg.api.platform.web.ApiProblemException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.JsonNode;

/**
 * The live hub (BLUEPRINT live twin, ADR-37): an outbox {@link EventHandler} (consumer {@code live.fanout}) that fans
 * each committed event out to the sockets whose tenant and subscriptions match, plus the per-socket flusher.
 *
 * <p>Tenant isolation: an event reaches a socket only when {@code event.tenantId == subject.tenantId}; the check is
 * made in {@link #fanout} before any subscription is consulted, and subscriptions are authorized (site view) when
 * created and re-checked every {@link #RECHECK} and on re-authentication. Output is flushed every
 * {@code flush-interval}: poses are coalesced to the latest per entity, events are bounded (gap on overflow).
 */
@Component
public class LiveHub implements EventHandler {

    public static final String CONSUMER = "live.fanout";
    static final int CLOSE_UNAUTHENTICATED = 4401;
    static final int CLOSE_BAD_PROTOCOL = 4400;
    static final int CLOSE_AUTH_TIMEOUT = 4408;
    static final int CLOSE_IDLE = 4409;
    static final int CLOSE_SLOW = 4429;
    static final Duration RECHECK = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(LiveHub.class);

    private final LiveProperties.Ws cfg;
    private final EnvelopeCodec codec;
    private final SiteAccess access;
    private final MeterRegistry meters;
    private final Set<LiveSession> sessions = ConcurrentHashMap.newKeySet();
    private final Timer lag;
    private final Counter coalescedCounter;
    private ScheduledExecutorService scheduler;

    public LiveHub(LiveProperties props, EnvelopeCodec codec, SiteAccess access, MeterRegistry meters) {
        this.cfg = props.ws();
        this.codec = codec;
        this.access = access;
        this.meters = meters;
        Gauge.builder("vo.live.ws.connections", sessions, Set::size).description("Open live sockets").register(meters);
        this.lag = Timer.builder("vo.live.ws.lag")
                .description("Time from event ingestion to the socket write").publishPercentileHistogram()
                .minimumExpectedValue(Duration.ofMillis(1)).maximumExpectedValue(Duration.ofMinutes(1))
                .register(meters);
        this.coalescedCounter = Counter.builder("vo.live.ws.poses.coalesced")
                .description("Pose updates replaced by a newer pose before being sent").register(meters);
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "live-hub-flusher");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::tick, cfg.flushInterval().toMillis(), cfg.flushInterval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    // ---- EventHandler -------------------------------------------------------------------------------------------

    @Override
    public String consumer() {
        return CONSUMER;
    }

    @Override
    public boolean supports(String eventType) {
        return !sessions.isEmpty();
    }

    @Override
    public void handle(EventEnvelope event) {
        try {
            fanout(event);
        } catch (RuntimeException ex) {
            LOG.warn("Live fan-out failed for event {}: {}", event.eventId(), ex.getClass().getSimpleName());
        }
    }

    /** Offers one committed event to every matching socket of the event's own tenant. */
    public void fanout(EventEnvelope e) {
        for (LiveSession s : sessions) {
            if (s.closing || !s.authenticated() || !e.tenantId().equals(s.subject.tenantId())) {
                continue;
            }
            for (Subscription sub : s.subscriptions.values()) {
                if (sub.wantsPose(e)) {
                    s.offerPose(sub.id(), e);
                } else if (sub.wantsEvent(e)) {
                    s.offerEvent(sub.id(), e);
                }
            }
        }
    }

    // ---- sessions -------------------------------------------------------------------------------------------------

    void register(LiveSession s) {
        sessions.add(s);
    }

    void unregister(LiveSession s) {
        s.closing = true;
        sessions.remove(s);
    }

    int connections() {
        return sessions.size();
    }

    void close(LiveSession s, int code, String reason) {
        if (s.closing) {
            return;
        }
        s.closing = true;
        meters.counter("vo.live.ws.closed", "code", String.valueOf(code)).increment();
        try {
            s.socket.close(new CloseStatus(code, reason));
        } catch (Exception ex) {
            LOG.debug("Closing live socket failed: {}", ex.getClass().getSimpleName());
        }
    }

    void send(LiveSession s, Map<String, Object> message) {
        send(s, codec.write(message), String.valueOf(message.get("type")));
    }

    private void send(LiveSession s, String json, String type) {
        if (s.closing) {
            return;
        }
        try {
            s.socket.sendMessage(new TextMessage(json));
            meters.counter("vo.live.ws.messages", "direction", "out", "type", type).increment();
        } catch (Exception ex) {
            close(s, CLOSE_SLOW, "send_failed");
        }
    }

    // ---- authorization --------------------------------------------------------------------------------------------

    /** Null when the socket's subject may view the site, otherwise the error code (forbidden or not_found). */
    String authorize(LiveSession s, UUID siteId) {
        try {
            TenantContext.callWith(s.subject.tenantId(), () -> {
                access.requireView(s.subject, siteId);
                return Boolean.TRUE;
            });
            return null;
        } catch (ApiProblemException ex) {
            return ex.status() == HttpStatus.NOT_FOUND ? "not_found" : "forbidden";
        }
    }

    // ---- flusher --------------------------------------------------------------------------------------------------

    private void tick() {
        try {
            Instant now = Instant.now();
            for (LiveSession s : sessions) {
                housekeeping(s, now);
                flush(s);
            }
        } catch (RuntimeException ex) {
            LOG.warn("Live hub tick failed: {}", ex.getClass().getSimpleName());
        }
    }

    private void housekeeping(LiveSession s, Instant now) {
        if (s.closing) {
            return;
        }
        if (!s.authenticated()) {
            if (Duration.between(s.connectedAt, now).compareTo(cfg.authTimeout()) > 0) {
                close(s, CLOSE_AUTH_TIMEOUT, "auth_timeout");
            }
            return;
        }
        if (!now.isBefore(s.expiresAt)) {
            close(s, CLOSE_UNAUTHENTICATED, "token_expired");
            return;
        }
        if (Duration.between(s.lastInbound, now).compareTo(cfg.idleTimeout()) > 0) {
            close(s, CLOSE_IDLE, "idle_timeout");
            return;
        }
        if (Duration.between(s.lastPing, now).compareTo(cfg.heartbeat()) >= 0) {
            s.lastPing = now;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "ping");
            m.put("server_time", now);
            m.put("expires_at", s.expiresAt);
            send(s, m);
        }
        if (Duration.between(s.lastRecheck, now).compareTo(RECHECK) >= 0) {
            s.lastRecheck = now;
            recheck(s);
        }
    }

    /** Drops subscriptions the subject may no longer view (role change, erasure) and tells the client. */
    void recheck(LiveSession s) {
        for (Subscription sub : List.copyOf(s.subscriptions.values())) {
            String denied = authorize(s, sub.siteId());
            if (denied != null && s.subscriptions.remove(sub.id()) != null) {
                s.forget(sub.id());
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("type", "unsubscribed");
                m.put("id", sub.id());
                m.put("reason", denied);
                send(s, m);
            }
        }
    }

    void flush(LiveSession s) {
        if (s.closing || !s.authenticated()) {
            return;
        }
        LiveSession.Batch batch = s.drain();
        if (batch.isEmpty()) {
            return;
        }
        if (batch.coalesced() > 0) {
            coalescedCounter.increment(batch.coalesced());
        }
        Instant now = Instant.now();
        batch.gaps().forEach((sub, n) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "gap");
            m.put("sub", sub);
            m.put("dropped", n);
            send(s, m);
        });
        batch.poses().forEach((sub, list) -> {
            if (list.isEmpty()) {
                return;
            }
            List<Map<String, Object>> items = list.stream().map(LiveHub::poseItem).toList();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "poses");
            m.put("sub", sub);
            m.put("items", items);
            send(s, m);
            list.forEach(e -> recordLag(e, now));
        });
        for (Map.Entry<String, EventEnvelope> en : batch.events()) {
            JsonNode node = codec.toNode(en.getValue());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "event");
            m.put("sub", en.getKey());
            m.put("event", node);
            send(s, m);
            recordLag(en.getValue(), now);
        }
    }

    private void recordLag(EventEnvelope e, Instant now) {
        Instant from = e.ingestedAt() != null ? e.ingestedAt() : e.occurredAt();
        lag.record(Duration.between(from, now).abs());
    }

    static Map<String, Object> poseItem(EventEnvelope e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("entity_id", e.aggregateId());
        m.put("entity_type", Subscription.entityTypeOf(e.eventType()));
        m.put("event_id", e.eventId());
        m.put("occurred_at", e.occurredAt());
        m.put("floor_id", e.location().floorId());
        m.put("zone_id", e.location().zoneId());
        m.put("x", e.location().position().x());
        m.put("y", e.location().position().y());
        m.put("z", e.location().position().z());
        m.put("heading_deg", e.location().headingDeg());
        if (e.payload() != null && e.payload().hasNonNull("status")) {
            m.put("status", e.payload().get("status").asString());
        }
        m.put("source", e.source().name());
        m.put("data_mode", e.dataMode().name());
        m.put("confidence", e.confidence());
        return m;
    }
}
