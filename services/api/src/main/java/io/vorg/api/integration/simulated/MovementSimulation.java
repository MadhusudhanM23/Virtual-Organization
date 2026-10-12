package io.vorg.api.integration.simulated;

import io.vorg.api.integration.simulated.AisleGraph.Box;
import io.vorg.api.integration.simulated.AisleGraph.Node;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.UUID;

/**
 * Deterministic movement of forklifts, workers and trucks inside the warehouse (REQ-042). Pure: no clock, no database,
 * no global randomness. The state after N calls of {@link #step} with the same {@code dt} sequence is a function of the
 * seed, the lane graph and the initial entities only, so the same seed reproduces the same trajectory (tested).
 *
 * <p>Behaviour (speeds are typical, not measured):
 * <ul>
 *   <li><b>Forklifts</b> pick a random lane node, drive there along the shortest lane path at 1.8 to 2.6 m/s, dwell 5 to
 *       12 s (status LOADING) or sometimes 10 to 25 s (IDLE), and repeat. {@link #directTo} sends one to a task location
 *       instead.</li>
 *   <li><b>Workers</b> do the same inside their home zone at 0.9 to 1.4 m/s with longer dwells (status WORKING).</li>
 *   <li><b>Trucks</b> with a planned dock cycle WAITING, ARRIVING (yard approach to the dock at 2.5 to 3.5 m/s), DOCKED
 *       and DEPARTING back to the queue point; trucks that start docked stay docked.</li>
 * </ul>
 * Each entity owns an independent random stream derived from (seed, entity code), so adding or removing an entity never
 * changes the others.
 */
public final class MovementSimulation {

    public static final String SOURCE_TAG = "movement";

    /** Planned yard approach of a waiting truck. */
    public record TruckPlan(double dockX, double approachY) {}

    /** Initial entity: type FORKLIFT, WORKER or TRUCK; {@code home} restricts a worker to its zone (null: anywhere). */
    public record Spec(UUID id, String code, String type, double x, double y, double headingDeg, String status,
            Box home, TruckPlan truck) {}

    /** Entity state after a step; {@code speedMps} is the current speed (0 while dwelling). */
    public record State(UUID id, String code, String type, double x, double y, double z, double headingDeg,
            double speedMps, String status) {}

    private enum Phase { DWELL, MOVING, STATIC }

    private static final class Mover {
        final Spec spec;
        final SplittableRandom rnd;
        double x;
        double y;
        double heading;
        double speed;
        String status;
        Phase phase;
        double dwellLeft;
        final Deque<double[]> waypoints = new ArrayDeque<>();
        String arrivalStatus;
        double arrivalDwell;
        double[] queuePoint;
        int truckStage; // 0 waiting, 1 arriving, 2 docked, 3 departing

        Mover(Spec spec, SplittableRandom rnd) {
            this.spec = spec;
            this.rnd = rnd;
            this.x = spec.x();
            this.y = spec.y();
            this.heading = spec.headingDeg();
            this.status = spec.status();
        }
    }

    private final AisleGraph graph;
    private final List<Mover> movers = new ArrayList<>();
    private final Map<UUID, Mover> byId = new LinkedHashMap<>();
    private double time;

    public MovementSimulation(long seed, AisleGraph graph, List<Spec> specs) {
        this.graph = graph;
        List<Spec> sorted = new ArrayList<>(specs);
        sorted.sort(Comparator.comparing(Spec::code));
        for (Spec s : sorted) {
            Mover m = new Mover(s, SimRandom.of(seed, SOURCE_TAG + "/" + s.type(), s.code().hashCode()));
            if ("TRUCK".equals(s.type())) {
                initTruck(m);
            } else {
                m.phase = Phase.DWELL;
                m.dwellLeft = m.rnd.nextDouble() * 4.0; // staggered start
            }
            movers.add(m);
            byId.put(s.id(), m);
        }
    }

    /** Seconds of simulated time elapsed. */
    public double time() {
        return time;
    }

    /** Advances every entity by {@code dtSeconds} (> 0) and returns the new state of all of them, ordered by code. */
    public List<State> step(double dtSeconds) {
        if (!(dtSeconds > 0) || !Double.isFinite(dtSeconds)) {
            throw new IllegalArgumentException("dt must be a positive finite number of seconds");
        }
        for (Mover m : movers) {
            advance(m, dtSeconds);
        }
        time += dtSeconds;
        return snapshot();
    }

    public List<State> snapshot() {
        List<State> out = new ArrayList<>(movers.size());
        for (Mover m : movers) {
            out.add(new State(m.spec.id(), m.spec.code(), m.spec.type(), m.x, m.y, 0.0, m.heading,
                    m.phase == Phase.MOVING ? m.speed : 0.0, m.status));
        }
        return out;
    }

    /**
     * Sends a forklift or worker to a location (typically a task end); it drives there along the lanes, dwells with
     * {@code arrivalStatus} for {@code dwellSeconds}, then resumes wandering. No effect on trucks or unknown ids.
     */
    public void directTo(UUID entityId, double x, double y, String arrivalStatus, double dwellSeconds) {
        Mover m = byId.get(entityId);
        if (m == null || "TRUCK".equals(m.spec.type())) {
            return;
        }
        Node from = m.waypoints.isEmpty() ? graph.nearest(m.x, m.y) : graph.nearest(m.waypoints.peekFirst()[0],
                m.waypoints.peekFirst()[1]);
        Node to = graph.nearest(x, y);
        double[] keep = m.waypoints.isEmpty() ? null : m.waypoints.peekFirst();
        m.waypoints.clear();
        if (keep != null) {
            m.waypoints.add(keep);
        }
        for (Node n : graph.path(from.index(), to.index())) {
            m.waypoints.add(new double[] {n.x(), n.y()});
        }
        m.waypoints.add(new double[] {x, y});
        m.arrivalStatus = arrivalStatus;
        m.arrivalDwell = dwellSeconds;
        startLeg(m);
    }

    // ---- behaviour ----

    private void initTruck(Mover m) {
        TruckPlan plan = m.spec.truck();
        if (plan == null) {
            m.phase = Phase.STATIC;
            return;
        }
        m.queuePoint = new double[] {m.spec.x(), m.spec.y()};
        m.truckStage = 0;
        m.status = "WAITING";
        m.phase = Phase.DWELL;
        m.dwellLeft = 10.0 + m.rnd.nextDouble() * 20.0;
    }

    private void advance(Mover m, double dt) {
        if (m.phase == Phase.STATIC) {
            return;
        }
        double left = dt;
        // A step can finish a dwell and start moving within the same dt, so loop until time is used up.
        for (int guard = 0; guard < 64 && left > 1e-9; guard++) {
            if (m.phase == Phase.DWELL) {
                if (m.dwellLeft > left) {
                    m.dwellLeft -= left;
                    m.speed = 0;
                    return;
                }
                left -= m.dwellLeft;
                m.dwellLeft = 0;
                onDwellEnd(m);
                continue;
            }
            // MOVING
            double[] target = m.waypoints.peekFirst();
            if (target == null) {
                onArrive(m);
                continue;
            }
            double dx = target[0] - m.x;
            double dy = target[1] - m.y;
            double dist = Math.hypot(dx, dy);
            double reach = m.speed * left;
            if (dist > 1e-9) {
                m.heading = headingOf(dx, dy);
            }
            if (reach >= dist) {
                m.x = target[0];
                m.y = target[1];
                m.waypoints.pollFirst();
                left -= m.speed > 0 ? dist / m.speed : left;
                if (m.waypoints.isEmpty()) {
                    onArrive(m);
                }
            } else {
                m.x += dx / dist * reach;
                m.y += dy / dist * reach;
                left = 0;
            }
        }
    }

    private void onDwellEnd(Mover m) {
        if ("TRUCK".equals(m.spec.type())) {
            truckNext(m);
            return;
        }
        if (m.arrivalStatus != null) {
            m.arrivalStatus = null;
        }
        chooseWanderLeg(m);
    }

    private void onArrive(Mover m) {
        m.speed = 0;
        if ("TRUCK".equals(m.spec.type())) {
            truckNext(m);
            return;
        }
        if (m.arrivalStatus != null) {
            m.status = m.arrivalStatus;
            m.dwellLeft = m.arrivalDwell;
            m.arrivalStatus = null;
        } else if ("FORKLIFT".equals(m.spec.type())) {
            if (m.rnd.nextInt(4) == 0) {
                m.status = "IDLE";
                m.dwellLeft = 10.0 + m.rnd.nextDouble() * 15.0;
            } else {
                m.status = "LOADING";
                m.dwellLeft = 5.0 + m.rnd.nextDouble() * 7.0;
            }
        } else {
            m.status = "WORKING";
            m.dwellLeft = 8.0 + m.rnd.nextDouble() * 22.0;
        }
        m.phase = Phase.DWELL;
    }

    private void chooseWanderLeg(Mover m) {
        boolean worker = "WORKER".equals(m.spec.type());
        List<Node> candidates = graph.nodesWithin(worker ? m.spec.home() : null);
        if (candidates.isEmpty()) {
            candidates = graph.nodes();
        }
        Node here = graph.nearestWithin(m.x, m.y, worker ? m.spec.home() : null);
        Node dest = candidates.get(m.rnd.nextInt(candidates.size()));
        m.waypoints.clear();
        // First reach the lane network (a straight leg to the nearest node), then follow the lanes.
        m.waypoints.add(new double[] {here.x(), here.y()});
        for (Node n : graph.path(here.index(), dest.index())) {
            if (n.index() != here.index()) {
                m.waypoints.add(new double[] {n.x(), n.y()});
            }
        }
        startLeg(m);
    }

    private void startLeg(Mover m) {
        boolean worker = "WORKER".equals(m.spec.type());
        m.speed = worker ? 0.9 + m.rnd.nextDouble() * 0.5 : 1.8 + m.rnd.nextDouble() * 0.8;
        m.status = worker ? "WALKING" : "MOVING";
        m.phase = Phase.MOVING;
    }

    private void truckNext(Mover m) {
        TruckPlan plan = m.spec.truck();
        switch (m.truckStage) {
            case 0 -> { // waiting -> arriving
                m.waypoints.clear();
                m.waypoints.add(new double[] {plan.dockX(), m.queuePoint[1]});
                m.waypoints.add(new double[] {plan.dockX(), plan.approachY()});
                m.truckStage = 1;
                m.status = "ARRIVING";
                m.speed = 2.5 + m.rnd.nextDouble();
                m.phase = Phase.MOVING;
            }
            case 1 -> { // arrived -> docked
                m.truckStage = 2;
                m.status = "DOCKED";
                m.dwellLeft = 60.0 + m.rnd.nextDouble() * 60.0;
                m.phase = Phase.DWELL;
            }
            case 2 -> { // docked -> departing
                m.waypoints.clear();
                m.waypoints.add(new double[] {plan.dockX(), m.queuePoint[1]});
                m.waypoints.add(new double[] {m.queuePoint[0], m.queuePoint[1]});
                m.truckStage = 3;
                m.status = "DEPARTING";
                m.speed = 2.5 + m.rnd.nextDouble();
                m.phase = Phase.MOVING;
            }
            default -> { // back at the queue point
                m.truckStage = 0;
                m.status = "WAITING";
                m.dwellLeft = 20.0 + m.rnd.nextDouble() * 30.0;
                m.phase = Phase.DWELL;
            }
        }
    }

    static double headingOf(double dx, double dy) {
        double deg = Math.toDegrees(Math.atan2(dy, dx));
        return deg < 0 ? deg + 360.0 : deg;
    }
}
