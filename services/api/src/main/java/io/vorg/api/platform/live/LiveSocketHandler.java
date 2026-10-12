package io.vorg.api.platform.live;

import io.micrometer.core.instrument.MeterRegistry;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.identity.SubjectService;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Protocol of {@code /ws/live} (docs/api/websocket.md). The access token travels in the first message
 * ({@code auth}), never in the URL, and is never logged. Client messages: auth, reauth, subscribe, unsubscribe, pong,
 * ping. A protocol or authentication failure closes the socket with a 44xx code; a refused subscription only returns
 * an {@code error} message.
 */
@Component
public class LiveSocketHandler extends TextWebSocketHandler {

    private static final Logger LOG = LoggerFactory.getLogger(LiveSocketHandler.class);
    private static final int MAX_MESSAGE_CHARS = 8192;
    private static final int MAX_ID_CHARS = 64;

    private final LiveHub hub;
    private final JwtDecoder jwtDecoder;
    private final SubjectService subjects;
    private final JsonMapper mapper;
    private final MeterRegistry meters;
    private final LiveProperties.Ws cfg;
    private final Map<String, LiveSession> bySocketId = new ConcurrentHashMap<>();

    public LiveSocketHandler(LiveHub hub, JwtDecoder jwtDecoder, SubjectService subjects, JsonMapper mapper,
            MeterRegistry meters, LiveProperties props) {
        this.hub = hub;
        this.jwtDecoder = jwtDecoder;
        this.subjects = subjects;
        this.mapper = mapper;
        this.meters = meters;
        this.cfg = props.ws();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession socket) {
        WebSocketSession safe = new ConcurrentWebSocketSessionDecorator(socket, 5000, 512 * 1024);
        LiveSession s = new LiveSession(safe, cfg.maxQueuedEvents());
        bySocketId.put(socket.getId(), s);
        hub.register(s);
        meters.counter("vo.live.ws.opened").increment();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession socket, CloseStatus status) {
        LiveSession s = bySocketId.remove(socket.getId());
        if (s != null) {
            hub.unregister(s);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession socket, Throwable ex) {
        LiveSession s = bySocketId.get(socket.getId());
        if (s != null) {
            LOG.debug("Live socket transport error: {}", ex.getClass().getSimpleName());
            hub.close(s, LiveHub.CLOSE_SLOW, "transport_error");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession socket, TextMessage message) {
        LiveSession s = bySocketId.get(socket.getId());
        if (s == null || s.closing) {
            return;
        }
        s.lastInbound = Instant.now();
        meters.counter("vo.live.ws.messages", "direction", "in").increment();
        if (message.getPayloadLength() > MAX_MESSAGE_CHARS) {
            hub.close(s, LiveHub.CLOSE_BAD_PROTOCOL, "message_too_large");
            return;
        }
        JsonNode node;
        try {
            node = mapper.readTree(message.getPayload());
        } catch (RuntimeException ex) {
            hub.close(s, LiveHub.CLOSE_BAD_PROTOCOL, "invalid_json");
            return;
        }
        String type = text(node, "type");
        if (type == null) {
            hub.close(s, LiveHub.CLOSE_BAD_PROTOCOL, "missing_type");
            return;
        }
        if (!s.authenticated() && !"auth".equals(type)) {
            hub.close(s, LiveHub.CLOSE_UNAUTHENTICATED, "auth_required");
            return;
        }
        switch (type) {
            case "auth", "reauth" -> authenticate(s, node, "reauth".equals(type));
            case "subscribe" -> subscribe(s, node);
            case "unsubscribe" -> unsubscribe(s, node);
            case "pong" -> { /* lastInbound already refreshed */ }
            case "ping" -> hub.send(s, Map.of("type", "pong"));
            default -> reply(s, "error", null, "bad_request", "Unknown message type.");
        }
    }

    private void authenticate(LiveSession s, JsonNode node, boolean reauth) {
        String token = text(node, "token");
        if (token == null) {
            hub.close(s, LiveHub.CLOSE_UNAUTHENTICATED, "token_required");
            return;
        }
        Jwt jwt;
        Subject subject;
        try {
            jwt = jwtDecoder.decode(token);
            UUID tenantId = UUID.fromString(jwt.getClaimAsString("tid"));
            UUID actorRef = UUID.fromString(jwt.getSubject());
            subject = subjects.load(tenantId, actorRef).orElse(null);
        } catch (RuntimeException ex) {
            hub.close(s, LiveHub.CLOSE_UNAUTHENTICATED, "invalid_token");
            return;
        }
        if (subject == null || jwt.getExpiresAt() == null) {
            hub.close(s, LiveHub.CLOSE_UNAUTHENTICATED, "invalid_token");
            return;
        }
        if (reauth && s.authenticated() && (!s.subject.actorRef().equals(subject.actorRef())
                || !s.subject.tenantId().equals(subject.tenantId()))) {
            hub.close(s, LiveHub.CLOSE_UNAUTHENTICATED, "identity_changed");
            return;
        }
        s.subject = subject;
        s.expiresAt = jwt.getExpiresAt();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", reauth ? "reauthenticated" : "ready");
        m.put("heartbeat_s", cfg.heartbeat().toSeconds());
        m.put("expires_at", s.expiresAt);
        m.put("server_time", Instant.now());
        hub.send(s, m);
        if (reauth) {
            hub.recheck(s);
        }
    }

    private void subscribe(LiveSession s, JsonNode node) {
        String id = text(node, "id");
        if (id == null || id.isBlank() || id.length() > MAX_ID_CHARS) {
            reply(s, "error", null, "bad_request", "A subscription id (max 64 chars) is required.");
            return;
        }
        UUID siteId = uuid(text(node, "site_id"));
        if (siteId == null) {
            reply(s, "error", id, "bad_request", "site_id must be a UUID.");
            return;
        }
        UUID floorId = node.hasNonNull("floor_id") ? uuid(text(node, "floor_id")) : null;
        if (node.hasNonNull("floor_id") && floorId == null) {
            reply(s, "error", id, "bad_request", "floor_id must be a UUID.");
            return;
        }
        Subscription.Bbox bbox = null;
        if (node.hasNonNull("bbox")) {
            JsonNode b = node.get("bbox");
            if (!b.hasNonNull("min_x") || !b.hasNonNull("min_y") || !b.hasNonNull("max_x") || !b.hasNonNull("max_y")
                    || !b.get("min_x").isNumber() || !b.get("min_y").isNumber() || !b.get("max_x").isNumber()
                    || !b.get("max_y").isNumber()) {
                reply(s, "error", id, "bad_request", "bbox needs numeric min_x, min_y, max_x, max_y.");
                return;
            }
            bbox = new Subscription.Bbox(b.get("min_x").asDouble(), b.get("min_y").asDouble(),
                    b.get("max_x").asDouble(), b.get("max_y").asDouble());
        }
        if (!s.subscriptions.containsKey(id) && s.subscriptions.size() >= cfg.maxSubscriptions()) {
            reply(s, "error", id, "too_many_subscriptions", "Subscription limit reached.");
            return;
        }
        String denied = hub.authorize(s, siteId);
        if (denied != null) {
            reply(s, "error", id, denied, "forbidden".equals(denied)
                    ? "You are not allowed to view this site's operational state." : "The site was not found.");
            return;
        }
        Subscription sub = new Subscription(id, siteId, floorId, bbox, strings(node, "entity_types"),
                strings(node, "event_types"), bool(node, "poses"), bool(node, "events"));
        s.subscriptions.put(id, sub);
        s.forget(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "subscribed");
        m.put("id", id);
        m.put("site_id", siteId);
        hub.send(s, m);
    }

    private void unsubscribe(LiveSession s, JsonNode node) {
        String id = text(node, "id");
        if (id != null && s.subscriptions.remove(id) != null) {
            s.forget(id);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "unsubscribed");
        m.put("id", id);
        m.put("reason", "client");
        hub.send(s, m);
    }

    private void reply(LiveSession s, String type, String id, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("code", code);
        m.put("message", message);
        hub.send(s, m);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static boolean bool(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asBoolean(true);
    }

    private static Set<String> strings(JsonNode node, String field) {
        Set<String> out = new HashSet<>();
        JsonNode v = node.get(field);
        if (v != null && v.isArray()) {
            v.forEach(e -> {
                if (e.isString() && !e.asString().isBlank() && e.asString().length() <= 64) {
                    out.add(e.asString());
                }
            });
        }
        return out;
    }

    private static UUID uuid(String s) {
        try {
            return s == null ? null : UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
