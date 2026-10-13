package io.vorg.api.integration.simulated;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.integration.simulated.MovementSimulation.State;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.safety.ProximityDetector;
import io.vorg.api.safety.ProximityDetector.Pending;
import io.vorg.api.safety.ProximityDetector.Pose;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Organic proximity-alert rate of the live movement simulation (ADR-56): the simulation, the emission policy and the
 * unchanged proximity rule chained the way the live loop chains them (1 s ticks, the {@code vo.live.movement.*} rate
 * control defaults, the {@code vo.safety.proximity.*} rule defaults), without a database. Before ADR-56 the Bengaluru
 * site raised 720 to 804 initial alerts per simulated hour with these seeds (one every 4 to 5 s); the separation
 * behaviour must keep that to a handful per hour while still leaving genuine near-misses.
 */
class MovementProximityRateTest {

    private static final UUID SITE = UUID.fromString("018f0000-0000-7000-8000-00000000a021");
    private static final UUID FLOOR = UUID.fromString("018f0000-0000-7000-8000-0000000000f0");
    private static final Instant T0 = Instant.parse("2026-10-12T08:00:00Z");
    private static final long[] SEEDS = {20261012L, 1L, 2L, 3L};

    /** Initial proximity alerts raised over {@code seconds} of simulated time (one tick per second). */
    static int organicAlerts(long seed, int seconds) {
        MovementSimulation sim = new MovementSimulation(seed, MovementSimulationTest.GRAPH,
                MovementSimulationTest.specs());
        EmissionPolicy policy = new EmissionPolicy(4, 0.5, 5.0); // vo.live.movement defaults
        policy.baseline(sim.snapshot());
        ProximityDetector detector = new ProximityDetector(ProximityDetector.Config.defaults());
        int alerts = 0;
        for (int tick = 1; tick <= seconds; tick++) {
            Instant at = T0.plusSeconds(tick);
            for (State s : policy.select(tick, sim.step(1.0))) {
                if (s.type().equals("TRUCK")) {
                    continue;
                }
                UUID eventId = UUID.nameUUIDFromBytes((tick + "|" + s.code()).getBytes(StandardCharsets.UTF_8));
                Pose pose = new Pose(s.id(), s.type(), s.code(), SITE, FLOOR, null, s.x(), s.y(), s.z(),
                        s.headingDeg(), s.speedMps(), at, eventId, DataMode.SIMULATED, EventSource.SIMULATION, 1.0);
                for (Pending p : detector.observe(pose)) {
                    if (p.alert().kind().equals(ProximityDetector.KIND_INITIAL)) {
                        alerts++;
                    }
                    detector.commit(p);
                }
            }
        }
        return alerts;
    }

    @Test
    void organicAlertsAreAHandfulPerHourButNotAbsent() {
        int total = 0;
        for (long seed : SEEDS) {
            int perHour = organicAlerts(seed, 3600);
            System.out.println("Organic proximity alerts in one simulated hour, seed " + seed + ": " + perHour);
            assertThat(perHour).as("seed %d", seed).isLessThanOrEqualTo(8);
            total += perHour;
        }
        // Genuine near-misses still happen: the rule is exercised, not silenced.
        assertThat(total).isGreaterThanOrEqualTo(SEEDS.length);
    }

    @Test
    void theDefaultSeedStaysQuietForHalfAnHour() {
        assertThat(organicAlerts(20261012L, 1800)).isLessThanOrEqualTo(5);
    }

    @Test
    void theAlertCountIsDeterministic() {
        assertThat(organicAlerts(7L, 900)).isEqualTo(organicAlerts(7L, 900));
    }
}
