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
    private static final AisleGraph GRAPH = AisleGraph.forWarehouse(WORLD);

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
    void differentSeedsYieldDifferentTrajectories() {
        assertThat(run(42, 300, 0.5)).isNotEqualTo(run(43, 300, 0.5));
    }

    @Test
    void entityStreamsAreIndependentOfEachOther() {
        // Removing all workers must not change any forklift trajectory.
        List<Spec> all = specs();
        List<Spec> noWorkers = all.stream().filter(s -> !s.type().equals("WORKER")).toList();
        MovementSimulation a = new MovementSimulation(7, GRAPH, all);
        MovementSimulation b = new MovementSimulation(7, GRAPH, noWorkers);
        for (int i = 0; i < 400; i++) {
            List<State> sa = a.step(0.5).stream().filter(s -> s.type().equals("FORKLIFT")).toList();
            List<State> sb = b.step(0.5).stream().filter(s -> s.type().equals("FORKLIFT")).toList();
            assertThat(sa).isEqualTo(sb);
        }
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
