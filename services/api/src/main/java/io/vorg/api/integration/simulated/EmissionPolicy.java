package io.vorg.api.integration.simulated;

import io.vorg.api.integration.simulated.MovementSimulation.State;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rate control between the movement simulation and the database (REQ-042: position events must not flood the event
 * store). An entity is selected for emission on a tick only if
 * <ul>
 *   <li>at least {@code emitEveryTicks} ticks passed since its last emission, and</li>
 *   <li>it moved at least {@code minMoveM} meters, or turned at least {@code minTurnDeg} degrees, or changed status,
 *       or has come to rest at a pose that differs from the last emitted one (so a stopped entity settles exactly).</li>
 * </ul>
 * Exception: an entity that comes to a stop (speed 0 after a published non-zero speed) is published on that tick,
 * whatever the spacing, so consumers such as the proximity rule never see a stopped forklift as moving for several
 * seconds (ADR-56). An entity that stands still emits nothing. With the defaults (every 2nd tick, 0.25 m, 5 degrees) a moving entity emits
 * at most once per 2 ticks, i.e. 0.5 Hz at a 1 s tick. Initial poses are the baseline, so nothing is emitted for an
 * entity that has not moved since the world was seeded or the last run.
 */
public final class EmissionPolicy {

    private record Last(double x, double y, double heading, double speed, String status, long tick) {}

    private final int emitEveryTicks;
    private final double minMoveM;
    private final double minTurnDeg;
    private final Map<UUID, Last> last = new HashMap<>();

    public EmissionPolicy(int emitEveryTicks, double minMoveM, double minTurnDeg) {
        this.emitEveryTicks = Math.max(1, emitEveryTicks);
        this.minMoveM = minMoveM;
        this.minTurnDeg = minTurnDeg;
    }

    /** Sets the pose each entity is considered to have already published. */
    public void baseline(List<State> states) {
        for (State s : states) {
            last.put(s.id(), new Last(s.x(), s.y(), s.headingDeg(), s.speedMps(), s.status(), Long.MIN_VALUE / 2));
        }
    }

    /** The subset of {@code states} to publish on {@code tick}; updates the per-entity baseline. */
    public List<State> select(long tick, List<State> states) {
        List<State> out = new ArrayList<>();
        for (State s : states) {
            Last l = last.get(s.id());
            if (l == null) {
                last.put(s.id(), new Last(s.x(), s.y(), s.headingDeg(), s.speedMps(), s.status(), tick));
                out.add(s);
                continue;
            }
            // Coming to a stop is published at once: a stale "moving" pose would misstate the entity for seconds.
            boolean cameToRest = s.speedMps() == 0.0 && l.speed() > 0.0;
            if (tick - l.tick() < emitEveryTicks && !cameToRest) {
                continue;
            }
            double moved = Math.hypot(s.x() - l.x(), s.y() - l.y());
            double turned = Math.abs(((s.headingDeg() - l.heading() + 540.0) % 360.0) - 180.0);
            boolean statusChanged = !s.status().equals(l.status());
            boolean atRest = s.speedMps() == 0.0 && (moved > 0.0 || turned > 0.0);
            if (moved >= minMoveM || turned >= minTurnDeg || statusChanged || atRest || cameToRest) {
                last.put(s.id(), new Last(s.x(), s.y(), s.headingDeg(), s.speedMps(), s.status(), tick));
                out.add(s);
            }
        }
        return out;
    }
}
