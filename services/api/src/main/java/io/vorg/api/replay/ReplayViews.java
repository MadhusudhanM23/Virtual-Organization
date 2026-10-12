package io.vorg.api.replay;

import io.vorg.api.replay.ReplayModel.AlertState;
import io.vorg.api.replay.ReplayModel.DockState;
import io.vorg.api.replay.ReplayModel.EntityState;
import io.vorg.api.replay.ReplayModel.IncidentState;
import io.vorg.api.replay.ReplayModel.Party;
import io.vorg.api.replay.ReplayModel.Place;
import io.vorg.api.replay.ReplayModel.Pose;
import io.vorg.api.replay.ReplayModel.Position;
import io.vorg.api.replay.ReplayModel.RecordedContext;
import io.vorg.api.replay.ReplayModel.TaskState;
import io.vorg.api.replay.ReplayModel.WorldVersionInfo;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Wire shapes of the replay API (ADR-46). Every item carries {@code lens: "REPLAY"} next to its original
 * {@code data_mode}; entities use the twin entity shape (without the live {@code version} counter). The view of a fold at
 * an instant is a pure function of the fold, the instant and the floor filter.
 */
public final class ReplayViews {

    private ReplayViews() {}

    public static final String LENS = "REPLAY";

    public record EntityView(UUID id, String entityType, String code, String name, UUID siteId, UUID floorId,
            UUID zoneId, String status, Pose pose, JsonNode attributes, String dataMode, String lens) {}

    /** A playback delta of one entity: only what replay reconstructs. */
    public record EntityDelta(UUID id, UUID floorId, UUID zoneId, String status, Pose pose, String lens) {}

    public record TaskView(UUID id, String taskType, String status, Integer priority, String title, Place from,
            Place to, UUID assignedEntityId, UUID assignedActorRef, String sourceSystem, String sourceRecordId,
            String dataMode, Instant createdAt, Instant statusChangedAt, int issuesReported, String lens) {}

    public record IncidentView(UUID id, String incidentType, String status, String severity, String title,
            Party forklift, Party worker, UUID floorId, UUID zoneId, Position position, double minDistanceM,
            int alertCount, Instant detectedAt, Instant lastAlertAt, UUID assignedActorRef, String dataMode,
            String source, Instant statusChangedAt, int notesCount, int estopRequests, String lens) {}

    public record AlertView(UUID id, UUID eventId, Instant occurredAt, String alertKind, String severity,
            double distanceM, Party forklift, Party worker, UUID floorId, UUID zoneId, Position position,
            String dataMode, String source, String lens) {}

    public record DockView(UUID id, UUID floorId, UUID zoneId, String name, String dockType, String status,
            String dataMode, String lens, String replayTreatment) {}

    public record Recorded(Instant recordedAt, String replayTreatment, WorldVersionInfo worldVersion,
            List<DockView> docks) {}

    public record Summary<T>(int total, Map<String, Integer> byStatus, List<T> items) {}

    /** The reconstructed content at an instant; its canonical JSON is what {@code state_hash} covers. */
    public record Content(UUID siteId, Instant at, UUID floorId, Recorded recorded, List<EntityView> entities,
            Summary<TaskView> tasks, Summary<IncidentView> incidents, List<AlertView> alerts) {}

    public static Content content(ReplayReducer reducer, ReplayReducer.Fold fold, UUID siteId, Instant at,
            UUID floorId, RecordedContext recorded) {
        List<EntityView> entities = new ArrayList<>();
        for (EntityState e : fold.entities()) {
            if (onFloor(e.floorId(), floorId)) {
                entities.add(entity(e));
            }
        }
        List<TaskView> tasks = new ArrayList<>();
        for (TaskState t : fold.tasks()) {
            if (reducer.visible(t, at)) {
                tasks.add(task(t));
            }
        }
        List<IncidentView> incidents = new ArrayList<>();
        for (IncidentState i : fold.incidents()) {
            if (reducer.visible(i, at) && onFloor(i.floorId(), floorId)) {
                incidents.add(incident(i));
            }
        }
        List<AlertView> alerts = new ArrayList<>();
        for (AlertState a : fold.alerts()) {
            if (reducer.visible(a, at) && onFloor(a.floorId(), floorId)) {
                alerts.add(alert(a));
            }
        }
        return new Content(siteId, at, floorId, recorded(recorded, floorId), entities,
                summary(tasks, TaskView::status), summary(incidents, IncidentView::status), alerts);
    }

    static boolean onFloor(UUID itemFloor, UUID filter) {
        return filter == null || Objects.equals(itemFloor, filter);
    }

    public static Recorded recorded(RecordedContext r, UUID floorId) {
        if (r == null) {
            return new Recorded(null, "RECORDED", null, List.of());
        }
        List<DockView> docks = new ArrayList<>();
        for (DockState d : r.docks()) {
            if (onFloor(d.floorId(), floorId)) {
                docks.add(new DockView(d.id(), d.floorId(), d.zoneId(), d.name(), d.dockType(), d.status(),
                        d.dataMode(), LENS, "RECORDED"));
            }
        }
        return new Recorded(r.recordedAt(), "RECORDED", r.worldVersion(), docks);
    }

    private static <T> Summary<T> summary(List<T> items, java.util.function.Function<T, String> status) {
        Map<String, Integer> counts = new TreeMap<>();
        for (T item : items) {
            counts.merge(status.apply(item), 1, Integer::sum);
        }
        return new Summary<>(items.size(), counts, items);
    }

    public static EntityView entity(EntityState e) {
        return new EntityView(e.id(), e.entityType(), e.code(), e.name(), e.siteId(), e.floorId(), e.zoneId(),
                e.status(), e.pose(), e.attributes(), e.dataMode(), LENS);
    }

    public static EntityDelta delta(EntityState e) {
        return new EntityDelta(e.id(), e.floorId(), e.zoneId(), e.status(), e.pose(), LENS);
    }

    public static TaskView task(TaskState t) {
        return new TaskView(t.id(), t.taskType(), t.status(), t.priority(), t.title(), t.from(), t.to(),
                t.assignedEntityId(), t.assignedActorRef(), t.sourceSystem(), t.sourceRecordId(), t.dataMode(),
                t.createdAt(), t.statusChangedAt(), t.issuesReported(), LENS);
    }

    public static IncidentView incident(IncidentState i) {
        return new IncidentView(i.id(), i.incidentType(), i.status(), i.severity(), i.title(), i.forklift(),
                i.worker(), i.floorId(), i.zoneId(), i.position(), i.minDistanceM(), i.alertCount(), i.detectedAt(),
                i.lastAlertAt(), i.assignedActorRef(), i.dataMode(), i.source(), i.statusChangedAt(), i.notesCount(),
                i.estopRequests(), LENS);
    }

    public static AlertView alert(AlertState a) {
        return new AlertView(a.id(), a.eventId(), a.occurredAt(), a.alertKind(), a.severity(), a.distanceM(),
                a.forklift(), a.worker(), a.floorId(), a.zoneId(), a.position(), a.dataMode(), a.source(), LENS);
    }
}
