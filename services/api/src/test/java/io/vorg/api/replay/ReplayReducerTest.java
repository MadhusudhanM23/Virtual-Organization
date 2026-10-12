package io.vorg.api.replay;

import static org.assertj.core.api.Assertions.assertThat;

import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DataMode;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.replay.ReplayModel.EntityState;
import io.vorg.api.replay.ReplayModel.Key;
import io.vorg.api.replay.ReplayModel.Pose;
import io.vorg.api.replay.ReplayModel.RecordedContext;
import io.vorg.api.replay.ReplayModel.ReplayState;
import io.vorg.api.replay.ReplayModel.WorldVersionInfo;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Determinism and ordering properties of the pure replay reducer (REQ-063, ADR-45). */
class ReplayReducerTest {

    private static final UUID TENANT = UUID.fromString("018f0000-0000-7000-8000-00000000a001");
    private static final UUID SITE = UUID.fromString("018f0000-0000-7000-8000-00000000a021");
    private static final UUID FLOOR = UUID.fromString("018f0000-0000-7000-8000-0000000f0001");
    private static final Instant T0 = Instant.parse("2026-10-12T09:00:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ReplayReducer reducer = new ReplayReducer(Duration.ofMinutes(15));
    private final ReplayCodec codec = new ReplayCodec();
    private final Map<UUID, Long> sequences = new HashMap<>();
    private long idCounter;

    private final List<UUID> forklifts = List.of(id(1), id(2), id(3));
    private final List<UUID> workers = List.of(id(11), id(12));

    // ---- tests ----

    @Test
    void sameInputGivesTheSameBytes() {
        List<EventEnvelope> events = scenario();
        byte[] a = codec.canonical(foldAll(base(), events).toState(T0.plusSeconds(600), null));
        byte[] b = codec.canonical(foldAll(base(), events).toState(T0.plusSeconds(600), null));
        assertThat(a).isEqualTo(b);
        assertThat(ReplayCodec.gzip(a)).isEqualTo(ReplayCodec.gzip(b));
    }

    @Test
    void inputOrderDoesNotMatter() {
        List<EventEnvelope> events = scenario();
        String expected = codec.sha256(foldAll(base(), events).toState(T0.plusSeconds(600), null));
        Random rnd = new Random(42);
        for (int i = 0; i < 20; i++) {
            List<EventEnvelope> shuffled = new ArrayList<>(events);
            Collections.shuffle(shuffled, rnd);
            assertThat(codec.sha256(foldAll(base(), shuffled).toState(T0.plusSeconds(600), null)))
                    .isEqualTo(expected);
        }
    }

    @Test
    void poseLatticeIsIndependentOfApplicationOrder() {
        List<EventEnvelope> poses = scenario().stream().filter(e -> e.eventType().endsWith("_POSITION_UPDATED"))
                .toList();
        String expected = codec.sha256(foldAll(base(), poses).toState(T0.plusSeconds(600), null));
        Random rnd = new Random(43);
        for (int i = 0; i < 20; i++) {
            List<EventEnvelope> shuffled = new ArrayList<>(poses);
            Collections.shuffle(shuffled, rnd);
            ReplayReducer.Fold fold = reducer.fold(base());
            shuffled.forEach(fold::apply); // deliberately not in replay order
            assertThat(codec.sha256(fold.toState(T0.plusSeconds(600), null))).isEqualTo(expected);
        }
    }

    @Test
    void snapshotPlusDeltasEqualsOneFold() {
        List<EventEnvelope> events = scenario();
        Instant split = T0.plusSeconds(150);
        Instant end = T0.plusSeconds(600);
        List<EventEnvelope> before = events.stream().filter(e -> !e.occurredAt().isAfter(split)).toList();
        List<EventEnvelope> after = events.stream().filter(e -> e.occurredAt().isAfter(split)).toList();
        ReplayState snapshot = foldAll(base(), before).toState(split, null);
        ReplayState restored = codec.readState(codec.canonical(snapshot)); // persisted round trip
        assertThat(codec.canonical(restored)).isEqualTo(codec.canonical(snapshot));
        // the late re-read of events already in the snapshot is a no-op (overlap of ADR-45)
        List<EventEnvelope> deltas = new ArrayList<>(after);
        deltas.addAll(before.subList(Math.max(0, before.size() - 10), before.size()));
        ReplayState viaSnapshot = foldAll(restored, deltas).toState(end, null);
        ReplayState oneFold = foldAll(base(), events).toState(end, null);
        assertThat(codec.sha256(viaSnapshot)).isEqualTo(codec.sha256(oneFold));
    }

    @Test
    void equalOccurredAtFirstIngestedWins() {
        UUID f = forklifts.get(0);
        Instant t = T0.plusSeconds(5);
        EventEnvelope first = pose(f, t, 10, 10, "MOVING");
        EventEnvelope second = pose(f, t, 20, 20, "MOVING");
        assertThat(second.sequence()).isGreaterThan(first.sequence());
        for (List<EventEnvelope> order : List.of(List.of(first, second), List.of(second, first))) {
            ReplayReducer.Fold fold = reducer.fold(base());
            order.forEach(fold::apply);
            assertThat(fold.entityMap().get(f).pose().x()).isEqualTo(10.0);
        }
    }

    @Test
    void genesisValueWinsATieAndNeverMovesBackwards() {
        UUID f = forklifts.get(0);
        ReplayReducer.Fold fold = reducer.fold(base());
        Instant seeded = fold.entityMap().get(f).pose().occurredAt();
        assertThat(fold.apply(pose(f, seeded, 99, 99, "MOVING"))).isEqualTo(ReplayReducer.Outcome.IGNORED);
        assertThat(fold.apply(pose(f, seeded.minusSeconds(1), 98, 98, "MOVING")))
                .isEqualTo(ReplayReducer.Outcome.IGNORED);
        assertThat(fold.entityMap().get(f).pose().x()).isEqualTo(1.0);
    }

    @Test
    void lateEventIsVisibleAtItsTimeButNotAfterANewerOne() {
        UUID f = forklifts.get(1);
        EventEnvelope newer = pose(f, T0.plusSeconds(20), 30, 30, "MOVING");
        EventEnvelope late = pose(f, T0.plusSeconds(10), 15, 15, "IDLE"); // ingested after the newer one
        ReplayReducer.Fold fold = reducer.fold(base());
        fold.apply(newer);
        fold.apply(late);
        assertThat(fold.entityMap().get(f).pose().x()).isEqualTo(30.0);
        // replay at an instant between the two shows the late pose (event-time semantics)
        ReplayReducer.Fold mid = reducer.fold(base());
        mid.applyAll(List.of(newer, late).stream().filter(e -> !e.occurredAt().isAfter(T0.plusSeconds(15))).toList());
        assertThat(mid.entityMap().get(f).pose().x()).isEqualTo(15.0);
        assertThat(mid.entityMap().get(f).status()).isEqualTo("IDLE");
    }

    @Test
    void incidentFieldsAreCommutativeAcrossSequenceInversion() {
        UUID inc = id(500);
        List<EventEnvelope> lifecycle = new ArrayList<>();
        lifecycle.add(incidentDetected(inc, T0.plusSeconds(30), "HIGH", 2.4));
        lifecycle.add(incidentStatus(inc, T0.plusSeconds(30), "INCIDENT_UNCONFIRMED", "DETECTED", "UNCONFIRMED"));
        lifecycle.add(incidentStatus(inc, T0.plusSeconds(60), "INCIDENT_CONFIRMED", "UNCONFIRMED", "CONFIRMED"));
        // a late alert merged after the confirmation (higher sequence, earlier occurred_at)
        lifecycle.add(incidentLinked(inc, T0.plusSeconds(45), "CRITICAL", 1.1, 2));
        String expected = codec.sha256(foldAll(base(), lifecycle).toState(T0.plusSeconds(120), null));
        // applyAll folds in occurred_at order (link seq 4 before confirm seq 3); here in sequence order instead
        ReplayReducer.Fold fold = reducer.fold(base());
        lifecycle.forEach(fold::apply);
        ReplayState state = fold.toState(T0.plusSeconds(120), null);
        assertThat(codec.sha256(state)).isEqualTo(expected);
        ReplayModel.IncidentState i = state.incidents().get(0);
        assertThat(i.status()).isEqualTo("CONFIRMED");
        assertThat(i.severity()).isEqualTo("CRITICAL");
        assertThat(i.alertCount()).isEqualTo(2);
        assertThat(i.minDistanceM()).isEqualTo(1.1);
        // split before the confirmation, the late link arriving after the snapshot still applies once
        ReplayState snap = foldAll(base(), lifecycle.subList(0, 2)).toState(T0.plusSeconds(50), null);
        ReplayReducer.Fold rest = reducer.fold(snap);
        rest.applyAll(lifecycle);
        rest.applyAll(lifecycle); // duplicates are ignored
        assertThat(codec.sha256(rest.toState(T0.plusSeconds(120), null))).isEqualTo(expected);
    }

    @Test
    void terminalItemsLeaveTheViewAfterTheRecentWindow() {
        UUID task = id(700);
        List<EventEnvelope> events = List.of(taskAssigned(task, T0.plusSeconds(1)),
                taskStatus(task, T0.plusSeconds(2), "TASK_ACCEPTED", "ASSIGNED", "ACCEPTED"),
                taskStatus(task, T0.plusSeconds(3), "TASK_COMPLETED", "ACCEPTED", "COMPLETED"));
        ReplayReducer.Fold fold = foldAll(base(), events);
        ReplayModel.TaskState t = fold.taskMap().get(task);
        assertThat(t.status()).isEqualTo("COMPLETED");
        assertThat(reducer.visible(t, T0.plusSeconds(3).plus(Duration.ofMinutes(14)))).isTrue();
        assertThat(reducer.visible(t, T0.plusSeconds(3).plus(Duration.ofMinutes(15)))).isFalse();
        assertThat(fold.toState(T0.plus(Duration.ofMinutes(20)), null).tasks()).isEmpty();
    }

    @Test
    void replayOrderIsOccurredAtThenSequenceThenEventId() {
        UUID f = forklifts.get(2);
        EventEnvelope a = pose(f, T0.plusSeconds(2), 1, 1, null);
        EventEnvelope b = pose(f, T0.plusSeconds(1), 2, 2, null);
        EventEnvelope c = pose(forklifts.get(0), T0.plusSeconds(1), 3, 3, null);
        List<EventEnvelope> ordered = ReplayReducer.ordered(List.of(a, b, c));
        assertThat(ordered.get(2)).isEqualTo(a);
        assertThat(ordered.get(0).occurredAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(ReplayReducer.compareUuids(UUID.fromString("80000000-0000-0000-0000-000000000000"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"))).isPositive();
    }

    // ---- scenario ----

    private ReplayReducer.Fold foldAll(ReplayState base, List<EventEnvelope> events) {
        ReplayReducer.Fold fold = reducer.fold(base);
        fold.applyAll(events);
        return fold;
    }

    private List<EventEnvelope> scenario() {
        sequences.clear();
        Random rnd = new Random(7);
        List<EventEnvelope> out = new ArrayList<>();
        for (int s = 1; s <= 300; s++) {
            Instant t = T0.plusSeconds(s);
            for (UUID f : forklifts) {
                if (rnd.nextInt(3) == 0) {
                    out.add(pose(f, t, rnd.nextInt(100) + 0.1234, rnd.nextInt(60) + 0.5, "MOVING"));
                }
            }
            if (s % 7 == 0) {
                out.add(pose(workers.get(s % 2), t, rnd.nextInt(100), rnd.nextInt(60), "WORKING"));
            }
        }
        // a late pose (ingested last, occurred early) and a tie
        out.add(pose(forklifts.get(0), T0.plusSeconds(100), 5, 5, "IDLE"));
        out.add(pose(forklifts.get(1), T0.plusSeconds(50), 6, 6, "IDLE"));
        UUID task = id(800);
        out.add(taskAssigned(task, T0.plusSeconds(10)));
        out.add(taskStatus(task, T0.plusSeconds(40), "TASK_ACCEPTED", "ASSIGNED", "ACCEPTED"));
        out.add(taskStatus(task, T0.plusSeconds(200), "TASK_STARTED", "ACCEPTED", "STARTED"));
        UUID inc = id(900);
        out.add(incidentDetected(inc, T0.plusSeconds(120), "HIGH", 2.5));
        out.add(incidentStatus(inc, T0.plusSeconds(120), "INCIDENT_UNCONFIRMED", "DETECTED", "UNCONFIRMED"));
        out.add(incidentLinked(inc, T0.plusSeconds(125), "HIGH", 1.8, 2));
        out.add(incidentStatus(inc, T0.plusSeconds(260), "INCIDENT_CONFIRMED", "UNCONFIRMED", "CONFIRMED"));
        out.add(alert(id(950), T0.plusSeconds(120)));
        return out;
    }

    private ReplayState base() {
        List<EntityState> entities = new ArrayList<>();
        int i = 0;
        for (UUID f : forklifts) {
            entities.add(entity(f, "FORKLIFT", "FL-0" + (++i)));
        }
        for (UUID w : workers) {
            entities.add(entity(w, "WORKER", "W-0" + (++i)));
        }
        entities.sort((a, b) -> ReplayReducer.compareUuids(a.id(), b.id()));
        RecordedContext recorded = new RecordedContext(T0, new WorldVersionInfo(1, "abc", "seed", "SYNTHETIC"),
                List.of());
        return new ReplayState(ReplayModel.FORMAT_VERSION, SITE, T0, recorded, entities, List.of(), List.of(),
                List.of());
    }

    private EntityState entity(UUID id, String type, String code) {
        Instant seeded = T0.minusSeconds(3600);
        Key key = new Key(seeded, null);
        return new EntityState(id, type, code, code, SITE, FLOOR, null, "IDLE",
                new Pose(1.0, 2.0, 0.0, 0.0, seeded, "SYSTEM", "SYNTHETIC", 1.0), JSON.createObjectNode(),
                "SYNTHETIC", key, key, key);
    }

    private EventEnvelope pose(UUID entity, Instant at, double x, double y, String status) {
        ObjectNode p = JSON.createObjectNode();
        p.put("entity_code", "X");
        if (status != null) {
            p.put("status", status);
        }
        String type = forklifts.contains(entity) ? "FORKLIFT" : "WORKER";
        return envelope(type, entity, type + "_POSITION_UPDATED", at, p,
                new EventLocation(SITE, FLOOR, null, new EventLocation.Position(x, y, 0), 90.0));
    }

    private EventEnvelope taskAssigned(UUID task, Instant at) {
        ObjectNode p = JSON.createObjectNode();
        p.put("task_type", "PICK");
        p.put("priority", 2);
        p.put("instruction", "Pick 4 cartons");
        p.set("from", JSON.createObjectNode().put("label", "R-01").put("x", 1.0).put("y", 2.0));
        p.set("to", JSON.createObjectNode().put("label", "D-01").put("x", 3.0).put("y", 4.0));
        return envelope("TASK", task, "TASK_ASSIGNED", at, p, EventLocation.site(SITE));
    }

    private EventEnvelope taskStatus(UUID task, Instant at, String type, String from, String to) {
        ObjectNode p = JSON.createObjectNode().put("task_type", "PICK").put("from_status", from).put("to_status", to);
        return envelope("TASK", task, type, at, p, EventLocation.site(SITE));
    }

    private EventEnvelope incidentDetected(UUID inc, Instant at, String severity, double distance) {
        ObjectNode p = JSON.createObjectNode().put("incident_type", "PROXIMITY").put("title", "Forklift close")
                .put("severity", severity).put("status", "DETECTED").put("distance_m", distance);
        p.set("forklift", JSON.createObjectNode().put("entity_id", forklifts.get(0).toString()).put("code", "FL-01"));
        p.set("worker", JSON.createObjectNode().put("entity_id", workers.get(0).toString()).put("code", "W-04"));
        return envelope("INCIDENT", inc, "INCIDENT_DETECTED", at, p,
                new EventLocation(SITE, FLOOR, null, new EventLocation.Position(10.12345, 20, 0), null));
    }

    private EventEnvelope incidentStatus(UUID inc, Instant at, String type, String from, String to) {
        ObjectNode p = JSON.createObjectNode().put("incident_type", "PROXIMITY").put("from_status", from)
                .put("to_status", to).put("severity", "HIGH");
        return envelope("INCIDENT", inc, type, at, p, EventLocation.site(SITE));
    }

    private EventEnvelope incidentLinked(UUID inc, Instant at, String severity, double distance, int count) {
        ObjectNode p = JSON.createObjectNode().put("alert_severity", severity).put("severity", severity)
                .put("distance_m", distance).put("alert_count", count);
        return envelope("INCIDENT", inc, "INCIDENT_ALERT_LINKED", at, p, EventLocation.site(SITE));
    }

    private EventEnvelope alert(UUID alertId, Instant at) {
        ObjectNode p = JSON.createObjectNode().put("alert_kind", "INITIAL").put("severity", "HIGH")
                .put("distance_m", 2.5);
        p.set("forklift", JSON.createObjectNode().put("entity_id", forklifts.get(0).toString()).put("code", "FL-01"));
        p.set("worker", JSON.createObjectNode().put("entity_id", workers.get(0).toString()).put("code", "W-04"));
        return envelope("PROXIMITY_ALERT", alertId, "PROXIMITY_ALERT_RAISED", at, p,
                new EventLocation(SITE, FLOOR, null, new EventLocation.Position(10, 20, 0), null));
    }

    private EventEnvelope envelope(String aggType, UUID aggId, String type, Instant at, ObjectNode payload,
            EventLocation location) {
        long seq = sequences.merge(aggId, 1L, Long::sum);
        return new EventEnvelope(id(100000 + (++idCounter)), TENANT, aggType, aggId, type, seq, at, at,
                EventSource.SIMULATION, DataMode.SIMULATED, ActorType.SERVICE, "svc:test", location, id(1),
                null, 1, payload, 1.0, null);
    }

    private static UUID id(long n) {
        return new UUID(0x018f000000007000L, 0x8000000000000000L | n);
    }
}
