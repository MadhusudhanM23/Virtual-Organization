package io.vorg.api.platform.live;

import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import java.util.Set;
import java.util.UUID;

/**
 * One spatial/typed subscription of a socket. {@code floorId}, {@code bbox}, {@code entityTypes} and
 * {@code eventTypes} are optional narrowing filters; the site is mandatory and authorized when the subscription is
 * created. Pose events (any {@code *_POSITION_UPDATED}) are delivered as coalesced {@code poses}; every other event
 * (and a pose event type only when named explicitly in {@code eventTypes}) is delivered as an {@code event}.
 */
public record Subscription(String id, UUID siteId, UUID floorId, Bbox bbox, Set<String> entityTypes,
        Set<String> eventTypes, boolean poses, boolean events) {

    public static final String POSE_SUFFIX = "_POSITION_UPDATED";

    /** Axis-aligned box in the site frame (meters). */
    public record Bbox(double minX, double minY, double maxX, double maxY) {
        boolean contains(double x, double y) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY;
        }
    }

    public Subscription {
        entityTypes = Set.copyOf(entityTypes);
        eventTypes = Set.copyOf(eventTypes);
    }

    public static boolean isPose(String eventType) {
        return eventType.endsWith(POSE_SUFFIX);
    }

    /** The entity type of a pose event type, e.g. FORKLIFT_POSITION_UPDATED to FORKLIFT. */
    public static String entityTypeOf(String eventType) {
        return eventType.substring(0, eventType.length() - POSE_SUFFIX.length());
    }

    private boolean inSpace(EventLocation loc) {
        if (loc == null || !siteId.equals(loc.siteId())) {
            return false;
        }
        if (floorId != null && !floorId.equals(loc.floorId())) {
            return false;
        }
        if (bbox != null) {
            return loc.position() != null && bbox.contains(loc.position().x(), loc.position().y());
        }
        return true;
    }

    /** True when the event should be delivered as a (coalesced) pose update. */
    public boolean wantsPose(EventEnvelope e) {
        if (!poses || !isPose(e.eventType()) || e.location() == null || e.location().position() == null
                || !inSpace(e.location())) {
            return false;
        }
        return entityTypes.isEmpty() || entityTypes.contains(entityTypeOf(e.eventType()));
    }

    /** True when the event should be delivered as a discrete event message. */
    public boolean wantsEvent(EventEnvelope e) {
        if (!events || e.location() == null || !siteId.equals(e.location().siteId())) {
            return false;
        }
        if (!eventTypes.isEmpty()) {
            return eventTypes.contains(e.eventType()) && (!isPose(e.eventType()) || inSpace(e.location()));
        }
        return !isPose(e.eventType());
    }
}
