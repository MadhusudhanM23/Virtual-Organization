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
 * <p>Behaviour (speeds and distances are typical simulation assumptions, not measured; ADR-56):
 * <ul>
 *   <li><b>Forklifts</b> pick a random lane node, drive there along the shortest lane path at 1.8 to 2.6 m/s, dwell 5 to
 *       12 s (status LOADING) or sometimes 10 to 25 s (IDLE), and repeat. {@link #directTo} sends one to a task location
 *       instead. A forklift <b>yields to pedestrians</b>: while a worker stands within {@value #YIELD_RADIUS_M} m of its
 *       position or of the next {@value #YIELD_LOOKAHEAD_M} m of its route it stops (status unchanged, speed 0), for at
 *       most {@value #MAX_YIELD_S} s at a time, then drives on regardless.</li>
 *   <li><b>Workers</b> whose home zone has floor space at least {@value #SPOT_CLEARANCE_M} m from every forklift lane
 *       (receiving, packing, picking, dispatch) walk between such work spots at 0.9 to 1.4 m/s and dwell there (status
 *       WORKING). They keep to the clear area they are in (a walk never comes closer than {@value #WALK_CLEARANCE_M} m
 *       to a lane) except for an occasional trip to another clear area of the zone (1 leg in
 *       {@value #CROSS_AREA_ONE_IN}), which crosses a lane the shortest way. Workers of a zone without such space (the
 *       storage aisles) walk the aisle lanes and dwell at a rack face, {@value #RACK_FACE_OFFSET_M} m off the aisle
 *       centre line.</li>
 *   <li><b>Trucks</b> with a planned dock cycle WAITING, ARRIVING (yard approach to the dock at 2.5 to 3.5 m/s), DOCKED
 *       and DEPARTING back to the queue point; trucks that start docked stay docked.</li>
 * </ul>
 * Each entity owns an independent random stream derived from (seed, entity code). The only interaction is the forklift
 * yield, which reads the worker positions at the start of the step: removing a forklift or a truck never changes any other
 * entity, removing a worker can change the forklifts' timing.
 */
public final class MovementSimulation {

    public static final String SOURCE_TAG = "movement";

    /** A forklift stops while a worker is this close to it or to its route ahead (m). */
    static final double YIELD_RADIUS_M = 4.0;
    /** A yielding forklift drives on only when no worker is within this distance of it (m). */
    static final double RESUME_RADIUS_M = 7.5;
    /** How far along its route a forklift looks for workers (m). */
    static final double YIELD_LOOKAHEAD_M = 6.0;
    /** Longest a forklift waits for a worker before it drives on (s). */
    static final double MAX_YIELD_S = 60.0;
    /** A worker about to walk in a lane waits while a moving forklift is this close (m). */
    static final double WORKER_WAIT_RADIUS_M = 12.0;
    /** How far along its path a worker looks for the lane band (m). */
    static final double WORKER_LOOKAHEAD_M = 3.0;
    /** Longest a worker waits for forklifts before it walks on (s). */
    static final double MAX_WORKER_WAIT_S = 90.0;
    /** Minimum distance of a pedestrian work spot from every forklift lane (m). */
    static final double SPOT_CLEARANCE_M = 4.5;
    /** Minimum lane distance along a walk inside one clear area (m). */
    static final double WALK_CLEARANCE_M = 4.0;
    /** One worker leg in this many goes to another clear area of the zone, crossing a lane. */
    static final int CROSS_AREA_ONE_IN = 8;
    /** Offset of a storage worker's dwell point from the aisle centre line, towards a rack face (m). */
    static final double RACK_FACE_OFFSET_M = 1.8;
    private static final double SPOT_GRID_M = 1.0;
    private static final double SPOT_INSET_M = 0.5;
    private static final double SAMPLE_M = 0.5;

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
        boolean yielding;
        double yieldedFor;
        Spots spots; // workers only; empty when the home zone has no clear floor space

        Mover(Spec spec, SplittableRandom rnd) {
            this.spec = spec;
            this.rnd = rnd;
            this.x = spec.x();
            this.y = spec.y();
            this.heading = spec.headingDeg();
            this.status = spec.status();
        }
    }

    /**
     * Pedestrian work spots of one home zone: grid points at least {@link #SPOT_CLEARANCE_M} from every lane, grouped
     * into clear areas (4-connected components of the grid). Racks always lie within 3 m of an aisle lane, so no spot is
     * ever inside a rack.
     */
    private static final class Spots {
        final List<double[]> points = new ArrayList<>();
        final List<Integer> area = new ArrayList<>();
        final List<List<Integer>> byArea = new ArrayList<>();

        boolean isEmpty() {
            return points.isEmpty();
        }

        int nearest(double x, double y, int inArea) {
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < points.size(); i++) {
                if (inArea >= 0 && area.get(i) != inArea) {
                    continue;
                }
                double d = Math.hypot(points.get(i)[0] - x, points.get(i)[1] - y);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }

        static Spots of(AisleGraph graph, Box home) {
            Spots s = new Spots();
            if (home == null) {
                return s;
            }
            int nx = (int) Math.floor((home.maxX() - home.minX() - 2 * SPOT_INSET_M) / SPOT_GRID_M) + 1;
            int ny = (int) Math.floor((home.maxY() - home.minY() - 2 * SPOT_INSET_M) / SPOT_GRID_M) + 1;
            if (nx <= 0 || ny <= 0) {
                return s;
            }
            int[] index = new int[nx * ny];
            java.util.Arrays.fill(index, -1);
            for (int j = 0; j < ny; j++) {
                for (int i = 0; i < nx; i++) {
                    double x = home.minX() + SPOT_INSET_M + i * SPOT_GRID_M;
                    double y = home.minY() + SPOT_INSET_M + j * SPOT_GRID_M;
                    if (graph.laneDistance(x, y) >= SPOT_CLEARANCE_M) {
                        index[j * nx + i] = s.points.size();
                        s.points.add(new double[] {x, y});
                        s.area.add(-1);
                    }
                }
            }
            // Label the clear areas (breadth-first over grid neighbours), in grid order: deterministic.
            for (int start = 0; start < index.length; start++) {
                if (index[start] < 0 || s.area.get(index[start]) >= 0) {
                    continue;
                }
                int label = s.byArea.size();
                List<Integer> members = new ArrayList<>();
                Deque<Integer> queue = new ArrayDeque<>();
                queue.add(start);
                s.area.set(index[start], label);
                while (!queue.isEmpty()) {
                    int cell = queue.poll();
                    members.add(index[cell]);
                    int ci = cell % nx;
                    int cj = cell / nx;
                    int[][] around = {{ci - 1, cj}, {ci + 1, cj}, {ci, cj - 1}, {ci, cj + 1}};
                    for (int[] a : around) {
                        if (a[0] < 0 || a[0] >= nx || a[1] < 0 || a[1] >= ny) {
                            continue;
                        }
                        int k = a[1] * nx + a[0];
                        if (index[k] >= 0 && s.area.get(index[k]) < 0) {
                            s.area.set(index[k], label);
                            queue.add(k);
                        }
                    }
                }
                s.byArea.add(members);
            }
            return s;
        }
    }

    private final AisleGraph graph;
    private final List<Mover> movers = new ArrayList<>();
    private final Map<UUID, Mover> byId = new LinkedHashMap<>();
    private final Map<Box, Spots> spotsByHome = new LinkedHashMap<>();
    private final List<Mover> workers = new ArrayList<>();
    private double[][] workerPositions = new double[0][];
    private final List<Mover> forklifts = new ArrayList<>();
    private double[][] forkliftPositions = new double[0][];
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
                if ("WORKER".equals(s.type())) {
                    m.spots = spotsByHome.computeIfAbsent(s.home() == null ? new Box(0, 0, 0, 0) : s.home(),
                            h -> Spots.of(graph, s.home()));
                    workers.add(m);
                } else if ("FORKLIFT".equals(s.type())) {
                    forklifts.add(m);
                }
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
        // Forklifts and workers react to each other's state at the start of the step, whatever the update order.
        workerPositions = new double[workers.size()][];
        for (int i = 0; i < workers.size(); i++) {
            Mover w = workers.get(i);
            workerPositions[i] = new double[] {w.x, w.y, w.phase == Phase.MOVING && !w.yielding ? 1 : 0};
        }
        forkliftPositions = new double[forklifts.size()][];
        for (int i = 0; i < forklifts.size(); i++) {
            Mover f = forklifts.get(i);
            forkliftPositions[i] = new double[] {f.x, f.y, f.phase == Phase.MOVING && !f.yielding ? 1 : 0};
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
                    m.phase == Phase.MOVING && !m.yielding ? m.speed : 0.0, m.status));
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

    /**
     * Puts a forklift or worker at a pose and keeps it there for {@code holdSeconds} (status {@code status}), then it
     * resumes wandering from there. Used by the demo controls so a staged situation does not teleport afterwards. No
     * effect on trucks or unknown ids.
     */
    public void place(UUID entityId, double x, double y, double headingDeg, String status, double holdSeconds) {
        Mover m = byId.get(entityId);
        if (m == null || "TRUCK".equals(m.spec.type())) {
            return;
        }
        m.waypoints.clear();
        m.x = x;
        m.y = y;
        m.heading = headingDeg;
        m.speed = 0;
        m.status = status;
        m.phase = Phase.DWELL;
        m.dwellLeft = Math.max(0.0, holdSeconds);
        m.arrivalStatus = null;
        m.arrivalDwell = 0;
        m.yielding = false;
        m.yieldedFor = 0;
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
                m.yielding = false;
                onArrive(m);
                continue;
            }
            boolean forklift = "FORKLIFT".equals(m.spec.type());
            if (forklift || "WORKER".equals(m.spec.type())) {
                if (forklift ? workerAhead(m) : forkliftMovingNear(m)) {
                    if (m.yieldedFor < (forklift ? MAX_YIELD_S : MAX_WORKER_WAIT_S)) {
                        m.yielding = true;
                        m.yieldedFor += left;
                        return;
                    }
                } else {
                    m.yieldedFor = 0;
                }
                m.yielding = false;
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
        m.yieldedFor = 0;
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
        if (worker && !m.spots.isEmpty()) {
            chooseSpotLeg(m);
            return;
        }
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
        if (worker) {
            double[] face = rackFace(m, dest);
            if (face != null) {
                m.waypoints.add(face);
            }
        }
        startLeg(m);
    }

    /**
     * A worker with clear floor space walks to another work spot of the clear area it is in; one leg in
     * {@link #CROSS_AREA_ONE_IN} goes to another clear area instead, leaving its own area at the point nearest to the
     * destination so the lane is crossed the shortest way. A walk that would come closer than {@link #WALK_CLEARANCE_M}
     * to a lane is redrawn (a few times; otherwise the worker keeps working where it is).
     */
    private void chooseSpotLeg(Mover m) {
        Spots sp = m.spots;
        m.waypoints.clear();
        double fromX = m.x;
        double fromY = m.y;
        int here = sp.nearest(m.x, m.y, -1);
        if (graph.laneDistance(m.x, m.y) < WALK_CLEARANCE_M) {
            // Off the clear floor (start pose, or placed by a demo control): step out to the nearest spot first.
            double[] p = sp.points.get(here);
            m.waypoints.add(new double[] {p[0], p[1]});
            fromX = p[0];
            fromY = p[1];
        }
        int area = sp.area.get(here);
        if (sp.byArea.size() > 1 && m.rnd.nextInt(CROSS_AREA_ONE_IN) == 0) {
            int destArea = m.rnd.nextInt(sp.byArea.size() - 1);
            if (destArea >= area) {
                destArea++;
            }
            List<Integer> members = sp.byArea.get(destArea);
            double[] dest = sp.points.get(members.get(m.rnd.nextInt(members.size())));
            double[] exit = sp.points.get(sp.nearest(dest[0], dest[1], area));
            m.waypoints.add(new double[] {exit[0], exit[1]});
            m.waypoints.add(new double[] {dest[0], dest[1]});
        } else {
            List<Integer> members = sp.byArea.get(area);
            for (int attempt = 0; attempt < 8; attempt++) {
                double[] dest = sp.points.get(members.get(m.rnd.nextInt(members.size())));
                if (walkClear(fromX, fromY, dest[0], dest[1])) {
                    m.waypoints.add(new double[] {dest[0], dest[1]});
                    break;
                }
            }
        }
        startLeg(m);
    }

    private boolean walkClear(double ax, double ay, double bx, double by) {
        double len = Math.hypot(bx - ax, by - ay);
        int n = (int) Math.ceil(len / SAMPLE_M);
        for (int i = 0; i <= n; i++) {
            double t = n == 0 ? 0 : (double) i / n;
            if (graph.laneDistance(ax + (bx - ax) * t, ay + (by - ay) * t) < WALK_CLEARANCE_M) {
                return false;
            }
        }
        return true;
    }

    /**
     * Dwell point of a storage worker that walked to {@code dest}: the middle of an aisle edge leaving {@code dest},
     * offset {@link #RACK_FACE_OFFSET_M} towards a rack face (inside the home zone). Null when {@code dest} has no aisle
     * edge or no offset point lies in the home zone.
     */
    private double[] rackFace(Mover m, Node dest) {
        List<Node> along = new ArrayList<>();
        for (int i : graph.neighbours(dest.index())) {
            Node n = graph.node(i);
            if (n.y() == dest.y()) {
                along.add(n);
            }
        }
        if (along.isEmpty()) {
            return null;
        }
        Node other = along.get(m.rnd.nextInt(along.size()));
        double midX = (dest.x() + other.x()) / 2.0;
        double sign = m.rnd.nextBoolean() ? 1.0 : -1.0;
        Box home = m.spec.home();
        for (int k = 0; k < 2; k++, sign = -sign) {
            double y = dest.y() + sign * RACK_FACE_OFFSET_M;
            if (home == null || home.contains(midX, y)) {
                // Walk along the aisle to the middle of the edge, then step to the rack face.
                m.waypoints.add(new double[] {midX, dest.y()});
                return new double[] {midX, y};
            }
        }
        return null;
    }

    /** Whether a worker is within {@link #YIELD_RADIUS_M} of the forklift or of its next metres of route. */
    private boolean workerAhead(Mover m) {
        if (workerPositions.length == 0) {
            return false;
        }
        // A yielding forklift moves off only once walking workers are well clear of it (hysteresis: a walking worker's
        // last published pose can lag it by a few metres).
        if (workerNear(m.x, m.y, YIELD_RADIUS_M) || m.yielding && walkingWorkerNear(m.x, m.y, RESUME_RADIUS_M)) {
            return true;
        }
        double px = m.x;
        double py = m.y;
        double budget = YIELD_LOOKAHEAD_M;
        for (double[] w : m.waypoints) {
            double len = Math.hypot(w[0] - px, w[1] - py);
            double reach = Math.min(len, budget);
            for (double s = SAMPLE_M; s <= reach + 1e-9; s += SAMPLE_M) {
                if (workerNear(px + (w[0] - px) * s / len, py + (w[1] - py) * s / len, YIELD_RADIUS_M)) {
                    return true;
                }
            }
            budget -= len;
            if (budget <= 0) {
                break;
            }
            px = w[0];
            py = w[1];
        }
        return false;
    }

    /**
     * Whether a walking worker should wait: its next {@link #WORKER_LOOKAHEAD_M} m of path enter or stay in the lane
     * band (closer than {@link #WALK_CLEARANCE_M} to a lane) while a moving forklift is within
     * {@link #WORKER_WAIT_RADIUS_M} of it. Stopped (or yielding) forklifts do not hold a worker up.
     */
    private boolean forkliftMovingNear(Mover m) {
        boolean anyMoving = false;
        for (double[] f : forkliftPositions) {
            if (f[2] > 0 && Math.hypot(f[0] - m.x, f[1] - m.y) <= WORKER_WAIT_RADIUS_M + WORKER_LOOKAHEAD_M) {
                anyMoving = true;
                break;
            }
        }
        if (!anyMoving) {
            return false;
        }
        double px = m.x;
        double py = m.y;
        double budget = WORKER_LOOKAHEAD_M;
        boolean inBand = graph.laneDistance(px, py) < WALK_CLEARANCE_M;
        for (double[] w : m.waypoints) {
            if (inBand || budget <= 0) {
                break;
            }
            double len = Math.hypot(w[0] - px, w[1] - py);
            double reach = Math.min(len, budget);
            for (double s = SAMPLE_M; s <= reach + 1e-9 && !inBand; s += SAMPLE_M) {
                inBand = graph.laneDistance(px + (w[0] - px) * s / len, py + (w[1] - py) * s / len)
                        < WALK_CLEARANCE_M;
            }
            budget -= len;
            px = w[0];
            py = w[1];
        }
        if (!inBand) {
            return false;
        }
        for (double[] f : forkliftPositions) {
            if (f[2] > 0 && Math.hypot(f[0] - m.x, f[1] - m.y) <= WORKER_WAIT_RADIUS_M) {
                return true;
            }
        }
        return false;
    }

    private boolean walkingWorkerNear(double x, double y, double radius) {
        for (double[] w : workerPositions) {
            if (w[2] > 0 && Math.hypot(w[0] - x, w[1] - y) <= radius) {
                return true;
            }
        }
        return false;
    }

    private boolean workerNear(double x, double y, double radius) {
        for (double[] w : workerPositions) {
            if (Math.hypot(w[0] - x, w[1] - y) <= radius) {
                return true;
            }
        }
        return false;
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
