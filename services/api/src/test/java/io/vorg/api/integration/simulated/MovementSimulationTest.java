package io.vorg.api.integration.simulated;

import static io.vorg.api.platform.identity.seed.DemoIds.SITE_BLR;
import static io.vorg.api.platform.identity.seed.DemoIds.TENANT_A;
import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.integration.simulated.AisleGraph.Box;
import io.vorg.api.integration.simulated.MovementSimulation.Spec;
import io.vorg.api.integration.simulated.MovementSimulation.State;
import io.vorg.api.integration.simulated.MovementSimulation.TruckPlan;
import io.vorg.api.world.seed.BengaluruWarehouse01;
import io.vorg.api.world.seed.WorldSeedPlan;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MovementSimulationTest {

    private static final WorldSeedPlan PLAN = BengaluruWarehouse01.build(TENANT_A, SITE_BLR);
    private static final SimWorld WORLD = SimWorld.fromPlan(PLAN);
    static final AisleGraph GRAPH = AisleGraph.forWarehouse(WORLD);

    static List<Spec> specs() {
        Map<UUID, WorldSeedPlan.Zone> zones = new HashMap<>();
        PLAN.zones().forEach(z -> zones.put(z.id(), z));
        List<Spec> out = new ArrayList<>();
        for (WorldSeedPlan.Entity e : PLAN.entities()) {
            switch (e.type()) {
                case "FORKLIFT" -> out.add(new Spec(e.id(), e.code(), "FORKLIFT", e.x().doubleValue(),
                        e.y().doubleValue(), e.headingDeg().doubleValue(), e.status(), null, null));
                case "WORKER" -> {
                    WorldSeedPlan.Rect b = zones.get(e.zoneId()).bounds();
                    out.add(new Spec(e.id(), e.code(), "WORKER", e.x().doubleValue(), e.y().doubleValue(),
                            e.headingDeg().doubleValue(), e.status(), new Box(b.minX().doubleValue(),
                                    b.minY().doubleValue(), b.maxX().doubleValue(), b.maxY().doubleValue()), null));
                }
                case "TRUCK" -> {
                    SimWorld.Truck truck = WORLD.trucks().stream().filter(t -> t.code().equals(e.code())).findFirst()
                            .orElseThrow();
                    TruckPlan plan = null;
                    if (e.attributes().get("dock_name") == null && truck.dockName() != null) {
                        SimWorld.Dock d = WORLD.dock(truck.dockName());
                        plan = new TruckPlan(d.x(), "INBOUND".equals(d.direction()) ? d.y() - 8 : d.y() + 8);
                    }
                    out.add(new Spec(e.id(), e.code(), "TRUCK", e.x().doubleValue(), e.y().doubleValue(),
                            e.headingDeg().doubleValue(), e.status(), null, plan));
                }
                default -> { }
            }
        }
        return out;
    }

    private static List<List<State>> run(long seed, int steps, double dt) {
        MovementSimulation sim = new MovementSimulation(seed, GRAPH, specs());
        List<List<State>> trace = new ArrayList<>();
        for (int i = 0; i < steps; i++) {
            trace.add(sim.step(dt));
        }
        return trace;
    }

    @Test
    void sameSeedYieldsTheSameTrajectoryExactly() {
        assertThat(run(42, 900, 0.5)).isEqualTo(run(42, 900, 0.5));
    }

    @Test
    void aPlacedEntityStaysPutDuringTheHoldAndThenResumes() {
        List<Spec> all = specs();
        MovementSimulation sim = new MovementSimulation(11, GRAPH, all);
        UUID forklift = all.stream().filter(s -> s.type().equals("FORKLIFT")).findFirst().orElseThrow().id();
        sim.place(forklift, 12.5, 33.0, 90.0, "MOVING", 20.0);
        for (int i = 0; i < 30; i++) { // 15 s of simulated time, inside the 20 s hold
            State s = sim.step(0.5).stream().filter(x -> x.id().equals(forklift)).findFirst().orElseThrow();
            assertThat(s.x()).isEqualTo(12.5);
            assertThat(s.y()).isEqualTo(33.0);
            assertThat(s.speedMps()).isZero();
        }
        boolean moved = false;
        for (int i = 0; i < 400 && !moved; i++) { // after the hold it wanders again from the placed pose
            State s = sim.step(0.5).stream().filter(x -> x.id().equals(forklift)).findFirst().orElseThrow();
            moved = s.x() != 12.5 || s.y() != 33.0;
        }
        assertThat(moved).isTrue();
    }

    @Test
    void differentSeedsYieldDifferentTrajectories() {
        assertThat(run(42, 300, 0.5)).isNotEqualTo(run(43, 300, 0.5));
    }

    @Test
    void entityStreamsAreIndependentOfEachOther() {
        // Each entity draws from its own stream: removing an entity changes no other entity it does not interact with.
        // Forklifts and workers interact (they yield to each other, ADR-56); forklifts among themselves, workers among
        // themselves and trucks with anyone do not.
        List<Spec> all = specs();
        assertSameTrajectories(all, all.stream().filter(s -> !s.type().equals("TRUCK")).toList(), "FORKLIFT", "WORKER");
        List<Spec> forklifts = all.stream().filter(s -> s.type().equals("FORKLIFT")).toList();
        assertSameTrajectories(forklifts, forklifts.subList(1, forklifts.size()), "FORKLIFT");
        List<Spec> workers = all.stream().filter(s -> s.type().equals("WORKER")).toList();
        assertSameTrajectories(workers, workers.subList(1, workers.size()), "WORKER");
    }

    private static void assertSameTrajectories(List<Spec> a, List<Spec> b, String... types) {
        Set<String> codes = new HashSet<>();
        b.forEach(s -> codes.add(s.code()));
        Set<String> wanted = Set.of(types);
        MovementSimulation sa = new MovementSimulation(7, GRAPH, a);
        MovementSimulation sb = new MovementSimulation(7, GRAPH, b);
        for (int i = 0; i < 400; i++) {
            List<State> ta = sa.step(0.5).stream().filter(s -> wanted.contains(s.type()) && codes.contains(s.code()))
                    .toList();
            List<State> tb = sb.step(0.5).stream().filter(s -> wanted.contains(s.type())).toList();
            assertThat(ta).isEqualTo(tb);
        }
    }

    @Test
    void aForkliftStopsForAWorkerOnItsRouteAndNeverPassesItWhileMoving() {
        List<Spec> all = specs();
        UUID forklift = all.stream().filter(s -> s.code().equals("FL-01")).findFirst().orElseThrow().id();
        UUID worker = all.stream().filter(s -> s.code().equals("W-028")).findFirst().orElseThrow().id();
        List<Spec> pair = all.stream().filter(s -> s.id().equals(forklift) || s.id().equals(worker)).toList();
        MovementSimulation sim = new MovementSimulation(4, GRAPH, pair);
        sim.place(worker, 25.0, 31.0, 0.0, "WORKING", 50.0); // standing in aisle y = 31
        sim.place(forklift, 1.0, 31.0, 0.0, "MOVING", 0.0);
        sim.directTo(forklift, 49.0, 31.0, "LOADING", 10.0);
        boolean stopped = false;
        for (int i = 0; i < 90; i++) { // 45 s, inside the worker's hold
            Map<UUID, State> now = new HashMap<>();
            sim.step(0.5).forEach(s -> now.put(s.id(), s));
            State f = now.get(forklift);
            double d = Math.hypot(f.x() - 25.0, f.y() - 31.0);
            if (f.speedMps() > 0) {
                assertThat(d).as("moving forklift at %.2f,%.2f", f.x(), f.y()).isGreaterThan(3.0);
            }
            assertThat(f.x()).isLessThan(25.0);
            stopped |= f.speedMps() == 0 && f.status().equals("MOVING");
        }
        assertThat(stopped).isTrue();
    }

    @Test
    void workersOutsideTheStorageAislesWorkClearOfTheForkliftLanes() {
        List<Spec> specs = specs();
        Box storageA = new Box(0, 16, 60, 64);
        Set<UUID> clearZoneWorkers = new HashSet<>();
        specs.stream().filter(s -> s.home() != null && !s.home().equals(storageA))
                .forEach(s -> clearZoneWorkers.add(s.id()));
        assertThat(clearZoneWorkers).hasSize(27);
        MovementSimulation sim = new MovementSimulation(6, GRAPH, specs);
        int working = 0;
        for (int i = 0; i < 2400; i++) {
            for (State s : sim.step(0.5)) {
                if (clearZoneWorkers.contains(s.id()) && s.status().equals("WORKING")) {
                    working++;
                    assertThat(GRAPH.laneDistance(s.x(), s.y())).as("%s at %.2f,%.2f", s.code(), s.x(), s.y())
                            .isGreaterThanOrEqualTo(MovementSimulation.WALK_CLEARANCE_M - 1e-9);
                }
            }
        }
        assertThat(working).isGreaterThan(10_000);
    }

    @Test
    void laneDistanceMeasuresToTheNearestLane() {
        assertThat(GRAPH.laneDistance(19.0, 25.0)).isEqualTo(0.0); // on aisle y = 25
        assertThat(GRAPH.laneDistance(19.0, 27.0)).isEqualTo(2.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(GRAPH.laneDistance(30.0, 78.0)).isEqualTo(6.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void movementIsPhysicallyPlausible() {
        double dt = 0.5;
        List<List<State>> trace = run(1, 1200, dt);
        Map<UUID, State> previous = new HashMap<>();
        Map<String, Double> travelled = new HashMap<>();
        for (State s : trace.get(0)) {
            previous.put(s.id(), s);
        }
        for (List<State> step : trace) {
            for (State s : step) {
                assertThat(s.x()).isBetween(-1.0, 121.0);
                assertThat(s.headingDeg()).isGreaterThanOrEqualTo(0.0).isLessThan(360.0);
                assertThat(Double.isFinite(s.x()) && Double.isFinite(s.y())).isTrue();
                State p = previous.get(s.id());
                double d = Math.hypot(s.x() - p.x(), s.y() - p.y());
                double vmax = switch (s.type()) {
                    case "FORKLIFT" -> 2.6;
                    case "WORKER" -> 1.4;
                    default -> 3.5;
                };
                assertThat(d).as("%s step distance", s.code()).isLessThanOrEqualTo(vmax * dt + 1e-6);
                travelled.merge(s.code(), d, Double::sum);
                previous.put(s.id(), s);
            }
        }
        long movingForklifts = travelled.entrySet().stream()
                .filter(e -> e.getKey().startsWith("FL-") && e.getValue() > 50.0).count();
        assertThat(movingForklifts).isEqualTo(10);
        assertThat(travelled.entrySet().stream().filter(e -> e.getKey().startsWith("W-") && e.getValue() > 10.0)
                .count()).isGreaterThanOrEqualTo(25);
    }

    @Test
    void forkliftsAndWorkersNeverEnterARack() {
        MovementSimulation sim = new MovementSimulation(3, GRAPH, specs());
        for (int i = 0; i < 2400; i++) {
            for (State s : sim.step(0.5)) {
                if (s.type().equals("TRUCK")) {
                    continue;
                }
                for (SimWorld.Facility r : WORLD.racks()) {
                    boolean inside = Math.abs(s.x() - r.x()) < 4.5 - 1e-6 && Math.abs(s.y() - r.y()) < 0.6 - 1e-6;
                    assertThat(inside).as("%s inside rack %s at %.2f,%.2f", s.code(), r.code(), s.x(), s.y())
                            .isFalse();
                }
            }
        }
    }

    @Test
    void workersStayInsideTheirHomeZone() {
        List<Spec> specs = specs();
        Map<UUID, Box> home = new HashMap<>();
        specs.stream().filter(s -> s.home() != null).forEach(s -> home.put(s.id(), s.home()));
        MovementSimulation sim = new MovementSimulation(5, GRAPH, specs);
        for (int i = 0; i < 1200; i++) {
            for (State s : sim.step(0.5)) {
                Box b = home.get(s.id());
                if (b != null) {
                    assertThat(b.contains(s.x(), s.y())).as("%s at %.2f,%.2f", s.code(), s.x(), s.y()).isTrue();
                }
            }
        }
    }

    @Test
    void trucksThatStartDockedStayAndWaitingTrucksCycleThroughTheYard() {
        MovementSimulation sim = new MovementSimulation(11, GRAPH, specs());
        Map<String, Set<String>> statuses = new HashMap<>();
        Map<String, double[]> start = new HashMap<>();
        sim.snapshot().stream().filter(s -> s.type().equals("TRUCK"))
                .forEach(s -> start.put(s.code(), new double[] {s.x(), s.y()}));
        for (int i = 0; i < 2400; i++) {
            for (State s : sim.step(0.5)) {
                if (s.type().equals("TRUCK")) {
                    statuses.computeIfAbsent(s.code(), k -> new HashSet<>()).add(s.status());
                    if (s.status().equals("DOCKED") && (s.code().equals("TR-01") || s.code().equals("TR-02")
                            || s.code().equals("TR-03") || s.code().equals("TR-04"))) {
                        assertThat(s.x()).isEqualTo(start.get(s.code())[0]);
                        assertThat(s.y()).isEqualTo(start.get(s.code())[1]);
                    }
                }
            }
        }
        assertThat(statuses.get("TR-01")).containsExactly("DOCKED");
        assertThat(statuses.get("TR-05")).contains("WAITING", "ARRIVING", "DOCKED", "DEPARTING");
        assertThat(statuses.get("TR-06")).contains("WAITING", "ARRIVING", "DOCKED", "DEPARTING");
    }

    @Test
    void directToSendsAForkliftToATaskLocation() {
        MovementSimulation sim = new MovementSimulation(2, GRAPH, specs());
        UUID fl = specs().stream().filter(s -> s.code().equals("FL-03")).findFirst().orElseThrow().id();
        SimWorld.Facility rack = WORLD.racks().get(10);
        sim.step(0.5);
        sim.directTo(fl, rack.x(), rack.y(), "LOADING", 15);
        boolean arrived = false;
        for (int i = 0; i < 1200 && !arrived; i++) {
            State s = sim.step(0.5).stream().filter(x -> x.id().equals(fl)).findFirst().orElseThrow();
            arrived = Math.hypot(s.x() - rack.x(), s.y() - rack.y()) < 1e-6 && s.status().equals("LOADING");
        }
        assertThat(arrived).isTrue();
    }

    @Test
    void aisleGraphIsConnectedAndCoversTheYardLanes() {
        assertThat(GRAPH.size()).isGreaterThan(80);
        int last = GRAPH.size() - 1;
        for (int target : new int[] {last, last / 2, 1}) {
            assertThat(GRAPH.path(0, target)).as("path 0 -> %d", target).hasSizeGreaterThan(1);
        }
        // Every dock has a lane node right in front of it.
        for (SimWorld.Dock d : WORLD.docks()) {
            double laneY = d.direction().equals("INBOUND") ? AisleGraph.YARD_LANE_SOUTH : AisleGraph.YARD_LANE_NORTH;
            assertThat(GRAPH.nearest(d.x(), laneY).x()).isEqualTo(d.x());
        }
    }

    @Test
    void emissionPolicyBoundsTheEventRateAndSilencesStandingEntities() {
        MovementSimulation sim = new MovementSimulation(9, GRAPH, specs());
        EmissionPolicy policy = new EmissionPolicy(2, 0.25, 5.0);
        policy.baseline(sim.snapshot());
        int ticks = 1800; // 30 minutes at one tick per second
        int emitted = 0;
        Map<String, Integer> perEntity = new HashMap<>();
        for (int t = 1; t <= ticks; t++) {
            for (State s : policy.select(t, sim.step(1.0))) {
                emitted++;
                perEntity.merge(s.code(), 1, Integer::sum);
            }
        }
        double perSecond = emitted / (double) ticks;
        // 46 movable entities at most every 2nd tick would be 23/s; standing and docked ones emit nothing.
        assertThat(perSecond).isLessThan(23.0);
        assertThat(perEntity).doesNotContainKeys("TR-01", "TR-02", "TR-03", "TR-04");
        assertThat(perEntity.values()).allSatisfy(n -> assertThat(n).isLessThanOrEqualTo(ticks / 2));
        assertThat(emitted).isGreaterThan(ticks / 10);
    }

    @Test
    void emissionPolicyEmitsStatusChangesAndSettlesAtRest() {
        UUID id = UUID.randomUUID();
        EmissionPolicy policy = new EmissionPolicy(1, 1.0, 90.0);
        policy.baseline(List.of(new State(id, "X", "FORKLIFT", 0, 0, 0, 0, 0, "IDLE")));
        assertThat(policy.select(1, List.of(new State(id, "X", "FORKLIFT", 0, 0, 0, 0, 0, "IDLE")))).isEmpty();
        assertThat(policy.select(2, List.of(new State(id, "X", "FORKLIFT", 0.3, 0, 0, 0, 2, "MOVING")))).hasSize(1);
        assertThat(policy.select(3, List.of(new State(id, "X", "FORKLIFT", 0.6, 0, 0, 0, 2, "MOVING")))).isEmpty();
        // Came to rest 0.1 m past the last emitted pose: below minMove but published once to settle.
        assertThat(policy.select(4, List.of(new State(id, "X", "FORKLIFT", 0.4, 0, 0, 0, 0, "MOVING")))).hasSize(1);
        assertThat(policy.select(5, List.of(new State(id, "X", "FORKLIFT", 0.4, 0, 0, 0, 0, "MOVING")))).isEmpty();
    }
}
