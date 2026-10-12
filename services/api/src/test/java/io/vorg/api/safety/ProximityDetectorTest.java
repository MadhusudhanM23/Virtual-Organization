package io.vorg.api.safety;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.safety.ProximityDetector.Config;
import io.vorg.api.safety.ProximityDetector.Pending;
import io.vorg.api.safety.ProximityDetector.Pose;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProximityDetectorTest {

    private static final Instant T0 = Instant.parse("2026-10-12T10:00:00Z");
    private static final UUID SITE = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final UUID FLOOR = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID FLOOR2 = UUID.fromString("00000000-0000-7000-8000-000000000003");
    private static final UUID FORKLIFT = UUID.fromString("00000000-0000-7000-8000-0000000000f1");
    private static final UUID WORKER = UUID.fromString("00000000-0000-7000-8000-0000000000a1");
    private static final UUID WORKER2 = UUID.fromString("00000000-0000-7000-8000-0000000000a2");

    private int seq;

    private Pose forklift(double x, double y, double speed, long seconds) {
        return pose(FORKLIFT, "FORKLIFT", FLOOR, x, y, speed, seconds, DataMode.SIMULATED);
    }

    private Pose worker(UUID id, double x, double y, long seconds) {
        return pose(id, "WORKER", FLOOR, x, y, 0.0, seconds, DataMode.SIMULATED);
    }

    private Pose pose(UUID id, String type, UUID floor, double x, double y, double speed, long seconds,
            DataMode mode) {
        return new Pose(id, type, type.substring(0, 1) + "-" + id.toString().substring(34), SITE, floor, null, x, y,
                0.0, 0.0, speed, T0.plusSeconds(seconds), new UUID(0, ++seq), mode, EventSource.SIMULATION, 1.0);
    }

    private static List<Pending> feed(ProximityDetector d, Pose pose) {
        List<Pending> hits = d.observe(pose);
        hits.forEach(d::commit);
        return hits;
    }

    @Test
    void movingForkliftWithinThresholdRaisesOneHighAlertImmediately() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        assertThat(feed(d, worker(WORKER, 10, 10, 0))).isEmpty();
        assertThat(feed(d, forklift(20, 10, 2.0, 1))).isEmpty(); // 10 m away
        assertThat(feed(d, forklift(14, 10, 2.0, 2))).isEmpty(); // 4 m away: outside 3 m
        List<Pending> hits = feed(d, forklift(12.5, 10, 2.0, 3)); // 2.5 m
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).alert().kind()).isEqualTo("INITIAL");
        assertThat(hits.get(0).alert().severity()).isEqualTo("HIGH");
        assertThat(hits.get(0).alert().distanceM()).isEqualTo(2.5);
        assertThat(hits.get(0).alert().occurredAt()).isEqualTo(T0.plusSeconds(3));
        // staying close raises no second alert (pair is active)
        assertThat(feed(d, forklift(12.4, 10, 2.0, 4))).isEmpty();
    }

    @Test
    void thresholdIsInclusiveAndJustOutsideDoesNotAlert() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, worker(WORKER, 0, 0, 0));
        assertThat(feed(d, forklift(3.001, 0, 2.0, 1))).isEmpty();
        assertThat(feed(d, forklift(3.0, 0, 2.0, 2))).hasSize(1);
    }

    @Test
    void aStationaryForkliftNeverAlerts() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, worker(WORKER, 0, 0, 0));
        assertThat(feed(d, forklift(1, 0, 0.0, 1))).isEmpty();
        assertThat(feed(d, forklift(1, 0, 0.29, 2))).isEmpty();
    }

    @Test
    void aWorkerWalkingIntoAMovingForkliftAlertsToo() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, forklift(10, 10, 1.5, 0));
        assertThat(feed(d, worker(WORKER, 20, 10, 1))).isEmpty();
        assertThat(feed(d, worker(WORKER, 11, 10, 2))).hasSize(1);
    }

    @Test
    void differentFloorsNeverPair() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, pose(WORKER, "WORKER", FLOOR2, 10, 10, 0.0, 0, DataMode.SIMULATED));
        assertThat(feed(d, forklift(10.5, 10, 2.0, 1))).isEmpty();
    }

    @Test
    void aStalePoseIsNotPaired() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, worker(WORKER, 10, 10, 0));
        assertThat(feed(d, forklift(10.5, 10, 2.0, 60))).isEmpty(); // worker pose is 60 s old (> 10 s)
    }

    @Test
    void hysteresisKeepsThePairActiveUntilItIsClearlyApart() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, worker(WORKER, 0, 0, 0));
        assertThat(feed(d, forklift(2.9, 0, 2.0, 1))).hasSize(1);
        // wobbling around the threshold (3.0 .. 3.9) is still the same active pair: no alert
        assertThat(feed(d, forklift(3.5, 0, 2.0, 2))).isEmpty();
        assertThat(feed(d, forklift(2.8, 0, 2.0, 3))).isEmpty();
        assertThat(feed(d, forklift(3.9, 0, 2.0, 4))).isEmpty();
        // clearly apart (>= 4.0) resets; the cooldown (20 s) still suppresses a quick re-alert
        assertThat(feed(d, forklift(6.0, 0, 2.0, 5))).isEmpty();
        assertThat(feed(d, forklift(2.0, 0, 2.0, 8))).isEmpty();
    }

    @Test
    void afterCooldownTheSameApproachAlertsAgain() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, worker(WORKER, 0, 0, 0));
        assertThat(feed(d, forklift(2.9, 0, 2.0, 1))).hasSize(1);
        assertThat(feed(d, forklift(8.0, 0, 2.0, 2))).isEmpty(); // apart again
        feed(d, worker(WORKER, 0, 0, 18)); // keep the worker pose fresh
        assertThat(feed(d, forklift(2.9, 0, 2.0, 19))).isEmpty(); // 18 s after the first alert: cooling down
        assertThat(feed(d, forklift(2.9, 0, 2.0, 22))).hasSize(1); // 21 s after the first alert: cooled
    }

    @Test
    void getsOneEscalationWhenTheDistanceBecomesCritical() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, worker(WORKER, 0, 0, 0));
        List<Pending> first = feed(d, forklift(2.5, 0, 2.0, 1));
        assertThat(first.get(0).alert().severity()).isEqualTo("HIGH");
        List<Pending> esc = feed(d, forklift(1.2, 0, 1.0, 2));
        assertThat(esc).hasSize(1);
        assertThat(esc.get(0).alert().kind()).isEqualTo("ESCALATION");
        assertThat(esc.get(0).alert().severity()).isEqualTo("CRITICAL");
        assertThat(feed(d, forklift(1.0, 0, 1.0, 3))).isEmpty(); // only one escalation
    }

    @Test
    void anApproachStraightIntoTheCriticalZoneIsCriticalOnTheFirstAlert() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, worker(WORKER, 0, 0, 0));
        List<Pending> hits = feed(d, forklift(1.0, 0, 2.0, 1));
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).alert().severity()).isEqualTo("CRITICAL");
        assertThat(feed(d, forklift(0.8, 0, 2.0, 2))).isEmpty();
    }

    @Test
    void anUncommittedAlertIsRaisedAgainOnRetry() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, worker(WORKER, 0, 0, 0));
        Pose near = forklift(2.0, 0, 2.0, 1);
        assertThat(d.observe(near)).hasSize(1); // emission "failed": no commit
        assertThat(d.observe(near)).hasSize(1); // redelivery raises it again
    }

    @Test
    void anOlderPoseNeverReplacesANewerOne() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, worker(WORKER, 0, 0, 5));
        feed(d, forklift(9, 0, 2.0, 6));
        assertThat(feed(d, forklift(1.0, 0, 2.0, 2))).isEmpty(); // late, older than the stored pose
    }

    @Test
    void twoWorkersGiveTwoIndependentPairs() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, worker(WORKER, 0, 0, 0));
        feed(d, worker(WORKER2, 0, 2, 0));
        List<Pending> hits = feed(d, forklift(1.5, 1, 2.0, 1));
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).alert().alertId()).isNotEqualTo(hits.get(1).alert().alertId());
    }

    @Test
    void provenanceDerivesFromTheInputs() {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        feed(d, pose(WORKER, "WORKER", FLOOR, 0, 0, 0.0, 0, DataMode.SYNTHETIC));
        Pending mixed = feed(d, forklift(1.0, 0, 2.0, 1)).get(0);
        assertThat(mixed.alert().dataMode()).isEqualTo(DataMode.MIXED);
        ProximityDetector d2 = new ProximityDetector(Config.defaults());
        feed(d2, worker(WORKER, 0, 0, 0));
        assertThat(feed(d2, forklift(1.0, 0, 2.0, 1)).get(0).alert().dataMode()).isEqualTo(DataMode.SIMULATED);
    }

    /** The same event stream always yields the same alerts, including ids (replay determinism). */
    @Test
    void theSameStreamYieldsIdenticalAlerts() {
        List<Pose> stream = new ArrayList<>();
        stream.add(worker(WORKER, 5, 5, 0));
        stream.add(worker(WORKER2, 30, 5, 0));
        for (int i = 0; i < 40; i++) {
            if (i % 5 == 0) {
                stream.add(worker(WORKER, 5, 5, 1 + i));
                stream.add(worker(WORKER2, 30, 5, 1 + i));
            }
            stream.add(forklift(40 - i * 0.9, 5, i % 7 == 6 ? 0.0 : 2.0, 1 + i));
        }
        List<Pending> a = run(stream);
        List<Pending> b = run(stream);
        assertThat(a).isNotEmpty().isEqualTo(b);
    }

    private static List<Pending> run(List<Pose> stream) {
        ProximityDetector d = new ProximityDetector(Config.defaults());
        List<Pending> all = new ArrayList<>();
        for (Pose p : stream) {
            all.addAll(feed(d, p));
        }
        return all;
    }
}
