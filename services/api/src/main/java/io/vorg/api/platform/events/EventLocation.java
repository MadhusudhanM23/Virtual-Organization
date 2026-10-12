package io.vorg.api.platform.events;

import java.util.UUID;

/** Site/zone coordinates of an event (site-local right-handed frame in meters: +x east, +y north, +z up). */
public record EventLocation(UUID siteId, UUID floorId, UUID zoneId, Position position, Double headingDeg) {

    public record Position(double x, double y, double z) {}

    public static EventLocation site(UUID siteId) {
        return new EventLocation(siteId, null, null, null, null);
    }
}
