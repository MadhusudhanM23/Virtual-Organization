package io.vorg.api.replay;

import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.replay.ReplayModel.AlertState;
import io.vorg.api.replay.ReplayModel.EntityState;
import io.vorg.api.replay.ReplayModel.IncidentState;
import io.vorg.api.replay.ReplayModel.Key;
import io.vorg.api.replay.ReplayModel.Party;
import io.vorg.api.replay.ReplayModel.Place;
import io.vorg.api.replay.ReplayModel.Pose;
import io.vorg.api.replay.ReplayModel.Position;
import io.vorg.api.replay.ReplayModel.RecordedContext;
import io.vorg.api.replay.ReplayModel.ReplayState;
import io.vorg.api.replay.ReplayModel.TaskState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The pure replay reducer (ADR-45): folds state-delta events into a {@link ReplayState}. No clock, no randomness, no I/O;
 * the same base state and the same events give the same state, whatever order the events are handed over in.
 *
 * <ul>
 *   <li>{@code <TYPE>_POSITION_UPDATED}: per-field lattice keyed by {@code (occurred_at, sequence)} for the pose (with
 *       zone), the floor and the status - exactly the live projection's rule (never backwards in time, first ingested
 *       wins a tie), but independent of the application order. Values use the twin's storage precision (3 decimals).</li>
 *   <li>{@code TASK_*} and {@code INCIDENT_*}: each event is applied once (sequence above the aggregate's high-water mark
 *       or in its skipped list); status follows the highest-sequence status event; counters, severity, distance and times
 *       are commutative (maximum / higher rank / minimum / latest).</li>
 *   <li>{@code PROXIMITY_ALERT_RAISED}: added once to the recent alerts.</li>
 * </ul>
 * Items that are terminal (task COMPLETED/FAILED/CANCELLED, incident CLOSED) or alerts for longer than the recent window
 * are invisible at an instant and are dropped from a snapshot taken at it.
 */
public final class ReplayReducer {

    public static final String POSITION_SUFFIX = "_POSITION_UPDATED";
    public static final String ALERT_EVENT = "PROXIMITY_ALERT_RAISED";
    public static final Set<String> TASK_TERMINAL = Set.of("COMPLETED", "FAILED", "CANCELLED");
    public static final Set<String> TASK_STATUS_EVENTS = Set.of("TASK_ASSIGNED", "TASK_ACCEPTED", "TASK_STARTED",
            "TASK_PAUSED", "TASK_RESUMED", "TASK_COMPLETED", "TASK_FAILED", "TASK_CANCELLED");
    public static final Set<String> INCIDENT_STATUS_EVENTS = Set.of("INCIDENT_DETECTED", "INCIDENT_UNCONFIRMED",
            "INCIDENT_CONFIRMED", "INCIDENT_REJECTED", "INCIDENT_RESOLVED", "INCIDENT_CLOSED");
    private static final List<String> SEVERITY_RANK = List.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final int MAX_SKIPPED = 1000;

    /** The replay order {@code (occurred_at, sequence (null first), event_id)}; event ids compare like PostgreSQL uuids. */
    public static final Comparator<EventEnvelope> REPLAY_ORDER = Comparator.comparing(EventEnvelope::occurredAt)
            .thenComparingLong(e -> e.sequence() == null ? 0L : e.sequence())
            .thenComparing(EventEnvelope::eventId, ReplayReducer::compareUuids);

    private final Duration recentWindow;

    public ReplayReducer(Duration recentWindow) {
        if (recentWindow == null || recentWindow.isNegative()) {
            throw new IllegalArgumentException("recent window must be >= 0");
        }
        this.recentWindow = recentWindow;
    }

    public Duration recentWindow() {
        return recentWindow;
    }

    /** Whether the reducer consumes this event type at all (the replay-authoritative types plus proximity alerts). */
    public static boolean isReplayEvent(String eventType) {
        return eventType.endsWith(POSITION_SUFFIX) || eventType.startsWith("TASK_")
                || eventType.startsWith("INCIDENT_") || ALERT_EVENT.equals(eventType);
    }

    public static List<EventEnvelope> ordered(Collection<EventEnvelope> events) {
        List<EventEnvelope> out = new ArrayList<>(events);
        out.sort(REPLAY_ORDER);
        return out;
    }

    public static int compareUuids(UUID a, UUID b) {
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }

    public Fold fold(ReplayState base) {
        return new Fold(base);
    }

    // ---- visibility ----------------------------------------------------------------------------------------------

    public boolean visible(TaskState t, Instant at) {
        return !TASK_TERMINAL.contains(t.status()) || recent(t.statusChangedAt(), at);
    }

    public boolean visible(IncidentState i, Instant at) {
        return !"CLOSED".equals(i.status()) || recent(i.statusChangedAt(), at);
    }

    public boolean visible(AlertState a, Instant at) {
        return recent(a.occurredAt(), at);
    }

    private boolean recent(Instant t, Instant at) {
        return t != null && t.isAfter(at.minus(recentWindow));
    }

    /** What one {@link Fold#apply} did with an event. */
    public enum Outcome { APPLIED, IGNORED, SKIPPED }

    /** A mutable fold over a base state. Not thread-safe; one per reconstruction. */
    public final class Fold {

        private final ReplayState base;
        private final TreeMap<UUID, EntityState> entities = new TreeMap<>(ReplayReducer::compareUuids);
        private final TreeMap<UUID, TaskState> tasks = new TreeMap<>(ReplayReducer::compareUuids);
        private final TreeMap<UUID, IncidentState> incidents = new TreeMap<>(ReplayReducer::compareUuids);
        private final TreeMap<UUID, AlertState> alerts = new TreeMap<>(ReplayReducer::compareUuids);
        private final Set<UUID> changedEntities = new TreeSet<>(ReplayReducer::compareUuids);
        private final Set<UUID> changedTasks = new TreeSet<>(ReplayReducer::compareUuids);
        private final Set<UUID> changedIncidents = new TreeSet<>(ReplayReducer::compareUuids);
        private final Set<UUID> changedAlerts = new TreeSet<>(ReplayReducer::compareUuids);
        private int applied;
        private int ignored;
        private int skipped;

        private Fold(ReplayState base) {
            this.base = base;
            base.entities().forEach(e -> entities.put(e.id(), e));
            base.tasks().forEach(t -> tasks.put(t.id(), t));
            base.incidents().forEach(i -> incidents.put(i.id(), i));
            base.alerts().forEach(a -> alerts.put(a.id(), a));
        }

        public int applied() {
            return applied;
        }

        public int ignored() {
            return ignored;
        }

        public int skipped() {
            return skipped;
        }

        /** Applies events in replay order (the input order does not matter). */
        public void applyAll(Collection<EventEnvelope> events) {
            for (EventEnvelope e : ordered(events)) {
                apply(e);
            }
        }

        public Outcome apply(EventEnvelope e) {
            Outcome o = dispatch(e);
            switch (o) {
                case APPLIED -> applied++;
                case IGNORED -> ignored++;
                case SKIPPED -> skipped++;
            }
            return o;
        }

        private Outcome dispatch(EventEnvelope e) {
            String type = e.eventType();
            if (type.endsWith(POSITION_SUFFIX)) {
                return pose(e);
            }
            if (ALERT_EVENT.equals(type)) {
                return alert(e);
            }
            if ("TASK".equals(e.aggregateType()) && type.startsWith("TASK_")) {
                return task(e);
            }
            if ("INCIDENT".equals(e.aggregateType()) && type.startsWith("INCIDENT_")) {
                return incident(e);
            }
            return Outcome.SKIPPED;
        }

        // ---- poses ----

        private Outcome pose(EventEnvelope e) {
            EntityState cur = entities.get(e.aggregateId());
            EventLocation loc = e.location();
            if (cur == null || loc == null || loc.position() == null) {
                return Outcome.SKIPPED;
            }
            Key key = new Key(e.occurredAt(), e.sequence());
            Pose pose = cur.pose();
            UUID zone = cur.zoneId();
            Key poseKey = cur.poseKey();
            boolean changed = false;
            if (key.beats(cur.poseKey())) {
                pose = new Pose(round3(loc.position().x()), round3(loc.position().y()), round3(loc.position().z()),
                        round3(loc.headingDeg() == null ? 0.0 : loc.headingDeg()), e.occurredAt(), e.source().name(),
                        e.dataMode().name(), e.confidence() == null ? null : round3(e.confidence()));
                zone = loc.zoneId();
                poseKey = key;
                changed = true;
            }
            UUID floor = cur.floorId();
            Key floorKey = cur.floorKey();
            if (loc.floorId() != null && key.beats(cur.floorKey())) {
                floor = loc.floorId();
                floorKey = key;
                changed = true;
            }
            String status = cur.status();
            Key statusKey = cur.statusKey();
            JsonNode p = e.payload();
            if (p != null && p.hasNonNull("status") && key.beats(cur.statusKey())) {
                status = p.get("status").asString();
                statusKey = key;
                changed = true;
            }
            if (!changed) {
                return Outcome.IGNORED;
            }
            EntityState next = new EntityState(cur.id(), cur.entityType(), cur.code(), cur.name(), cur.siteId(), floor,
                    zone, status, pose, cur.attributes(), cur.dataMode(), poseKey, floorKey, statusKey);
            if (!next.equals(cur)) {
                entities.put(next.id(), next);
                changedEntities.add(next.id());
            }
            return Outcome.APPLIED;
        }

        // ---- alerts ----

        private Outcome alert(EventEnvelope e) {
            if (alerts.containsKey(e.aggregateId())) {
                return Outcome.IGNORED;
            }
            JsonNode p = e.payload();
            EventLocation loc = e.location();
            if (p == null || !p.hasNonNull("forklift") || !p.hasNonNull("worker")) {
                return Outcome.SKIPPED;
            }
            AlertState a = new AlertState(e.aggregateId(), e.eventId(), e.occurredAt(), text(p, "alert_kind"),
                    text(p, "severity"), round3(p.path("distance_m").asDouble()), party(p.get("forklift")),
                    party(p.get("worker")), loc == null ? null : loc.floorId(), loc == null ? null : loc.zoneId(),
                    position(loc), e.dataMode().name(), e.source().name());
            alerts.put(a.id(), a);
            changedAlerts.add(a.id());
            return Outcome.APPLIED;
        }

        // ---- tasks ----

        private Outcome task(EventEnvelope e) {
            TaskState cur = tasks.get(e.aggregateId());
            JsonNode p = e.payload();
            if (cur == null) {
                if (!"TASK_ASSIGNED".equals(e.eventType())) {
                    return Outcome.SKIPPED;
                }
                long seq = e.sequence() == null ? 1L : e.sequence();
                TaskState created = new TaskState(e.aggregateId(), text(p, "task_type"), "ASSIGNED",
                        p.hasNonNull("priority") ? p.get("priority").asInt() : null, text(p, "instruction"),
                        place(p.get("from")), place(p.get("to")), uuid(p, "assigned_entity_id"),
                        uuid(p, "assigned_actor_ref"), text(p, "source_system"), text(p, "source_record_id"),
                        e.dataMode().name(), e.occurredAt(), e.occurredAt(), seq, 0, seq, gaps(0L, seq));
                tasks.put(created.id(), created);
                changedTasks.add(created.id());
                return Outcome.APPLIED;
            }
            Long seq = e.sequence();
            if (seq != null && !fresh(seq, cur.lastSequence(), cur.skippedSequences())) {
                return Outcome.IGNORED;
            }
            long s = seq == null ? cur.lastSequence() : seq;
            String status = cur.status();
            Instant changedAt = cur.statusChangedAt();
            long statusSeq = cur.statusSequence();
            int issues = cur.issuesReported();
            if ("TASK_ISSUE_REPORTED".equals(e.eventType())) {
                issues++;
            } else if (TASK_STATUS_EVENTS.contains(e.eventType()) && s > statusSeq) {
                String to = p.hasNonNull("to_status") ? p.get("to_status").asString()
                        : e.eventType().substring("TASK_".length());
                status = to;
                changedAt = e.occurredAt();
                statusSeq = s;
            }
            TaskState next = new TaskState(cur.id(), cur.taskType(), status, cur.priority(), cur.title(), cur.from(),
                    cur.to(), cur.assignedEntityId(), cur.assignedActorRef(), cur.sourceSystem(),
                    cur.sourceRecordId(), cur.dataMode(), cur.createdAt(), changedAt, statusSeq, issues,
                    Math.max(cur.lastSequence(), s), afterApply(seq, cur.lastSequence(), cur.skippedSequences()));
            tasks.put(next.id(), next);
            changedTasks.add(next.id());
            return Outcome.APPLIED;
        }

        // ---- incidents ----

        private Outcome incident(EventEnvelope e) {
            IncidentState cur = incidents.get(e.aggregateId());
            JsonNode p = e.payload();
            String type = e.eventType();
            if (cur == null) {
                if (!"INCIDENT_DETECTED".equals(type)) {
                    return Outcome.SKIPPED;
                }
                EventLocation loc = e.location();
                long seq = e.sequence() == null ? 1L : e.sequence();
                IncidentState created = new IncidentState(e.aggregateId(), text(p, "incident_type"), "DETECTED",
                        text(p, "severity"), text(p, "title"), party(p.get("forklift")), party(p.get("worker")),
                        loc == null ? null : loc.floorId(), loc == null ? null : loc.zoneId(), position(loc),
                        round3(p.path("distance_m").asDouble()), 1, e.occurredAt(), e.occurredAt(), null, 0L,
                        e.dataMode().name(), e.source().name(), e.occurredAt(), seq, 0, 0, seq, gaps(0L, seq));
                incidents.put(created.id(), created);
                changedIncidents.add(created.id());
                return Outcome.APPLIED;
            }
            Long seq = e.sequence();
            if (seq != null && !fresh(seq, cur.lastSequence(), cur.skippedSequences())) {
                return Outcome.IGNORED;
            }
            long s = seq == null ? cur.lastSequence() : seq;
            String status = cur.status();
            Instant changedAt = cur.statusChangedAt();
            long statusSeq = cur.statusSequence();
            String severity = cur.severity();
            double minDistance = cur.minDistanceM();
            int alertCount = cur.alertCount();
            Instant lastAlertAt = cur.lastAlertAt();
            UUID assignee = cur.assignedActorRef();
            long assignedSeq = cur.assignedSequence();
            int notes = cur.notesCount();
            int estops = cur.estopRequests();
            switch (type) {
                case "INCIDENT_ALERT_LINKED" -> {
                    severity = higher(severity, text(p, "alert_severity"));
                    severity = higher(severity, text(p, "severity"));
                    if (p.hasNonNull("distance_m")) {
                        minDistance = Math.min(minDistance, round3(p.get("distance_m").asDouble()));
                    }
                    alertCount = Math.max(alertCount, p.hasNonNull("alert_count") ? p.get("alert_count").asInt()
                            : alertCount + 1);
                    lastAlertAt = latest(lastAlertAt, e.occurredAt());
                }
                case "INCIDENT_ASSIGNED" -> {
                    if (s > assignedSeq) {
                        assignee = uuid(p, "assignee_actor_ref");
                        assignedSeq = s;
                    }
                }
                case "INCIDENT_NOTE_ADDED" -> notes++;
                case "INCIDENT_ESTOP_REQUESTED" -> estops++;
                case "INCIDENT_DETECTED" -> {
                    severity = higher(severity, text(p, "severity"));
                    lastAlertAt = latest(lastAlertAt, e.occurredAt());
                }
                default -> { }
            }
            if (INCIDENT_STATUS_EVENTS.contains(type) && s > statusSeq) {
                status = p.hasNonNull("to_status") ? p.get("to_status").asString()
                        : type.substring("INCIDENT_".length());
                changedAt = e.occurredAt();
                statusSeq = s;
            }
            IncidentState next = new IncidentState(cur.id(), cur.incidentType(), status, severity, cur.title(),
                    cur.forklift(), cur.worker(), cur.floorId(), cur.zoneId(), cur.position(), minDistance, alertCount,
                    cur.detectedAt(), lastAlertAt, assignee, assignedSeq, cur.dataMode(), cur.source(), changedAt,
                    statusSeq, notes, estops, Math.max(cur.lastSequence(), s),
                    afterApply(seq, cur.lastSequence(), cur.skippedSequences()));
            incidents.put(next.id(), next);
            changedIncidents.add(next.id());
            return Outcome.APPLIED;
        }

        // ---- results ----

        /** The folded state as a snapshot at {@code asOf}: items invisible at {@code asOf} are dropped. */
        public ReplayState toState(Instant asOf, RecordedContext recorded) {
            List<TaskState> t = new ArrayList<>();
            tasks.values().stream().filter(x -> visible(x, asOf)).forEach(t::add);
            List<IncidentState> i = new ArrayList<>();
            incidents.values().stream().filter(x -> visible(x, asOf)).forEach(i::add);
            List<AlertState> a = new ArrayList<>();
            alerts.values().stream().filter(x -> visible(x, asOf)).forEach(a::add);
            return new ReplayState(ReplayModel.FORMAT_VERSION, base.siteId(), asOf,
                    recorded == null ? base.recorded() : recorded, List.copyOf(entities.values()), List.copyOf(t),
                    List.copyOf(i), List.copyOf(a));
        }

        public Collection<EntityState> entities() {
            return entities.values();
        }

        public Collection<TaskState> tasks() {
            return tasks.values();
        }

        public Collection<IncidentState> incidents() {
            return incidents.values();
        }

        public Collection<AlertState> alerts() {
            return alerts.values();
        }

        public Map<UUID, EntityState> entityMap() {
            return entities;
        }

        public Map<UUID, TaskState> taskMap() {
            return tasks;
        }

        public Map<UUID, IncidentState> incidentMap() {
            return incidents;
        }

        public Map<UUID, AlertState> alertMap() {
            return alerts;
        }

        /** Ids changed since the last call (for playback deltas), then clears the change sets. */
        public Changes drainChanges() {
            Changes c = new Changes(List.copyOf(changedEntities), List.copyOf(changedTasks),
                    List.copyOf(changedIncidents), List.copyOf(changedAlerts));
            changedEntities.clear();
            changedTasks.clear();
            changedIncidents.clear();
            changedAlerts.clear();
            return c;
        }
    }

    public record Changes(List<UUID> entities, List<UUID> tasks, List<UUID> incidents, List<UUID> alerts) {}

    // ---- helpers -------------------------------------------------------------------------------------------------

    static boolean fresh(long seq, long last, List<Long> skipped) {
        return seq > last || skipped.contains(seq);
    }

    /** The skipped list after applying {@code seq}: holes below it are remembered, a filled hole is removed. */
    static List<Long> afterApply(Long seq, long last, List<Long> skipped) {
        if (seq == null) {
            return skipped;
        }
        if (seq <= last) {
            List<Long> out = new ArrayList<>(skipped);
            out.remove(seq);
            return List.copyOf(out);
        }
        List<Long> out = new ArrayList<>(skipped);
        out.addAll(gaps(last, seq));
        return List.copyOf(out);
    }

    /** Sequences strictly between {@code last} and {@code seq} (bounded, so a corrupt sequence cannot explode). */
    static List<Long> gaps(long last, long seq) {
        if (seq - last <= 1) {
            return List.of();
        }
        List<Long> out = new ArrayList<>();
        for (long s = Math.max(last + 1, seq - MAX_SKIPPED); s < seq; s++) {
            out.add(s);
        }
        return List.copyOf(out);
    }

    static String higher(String a, String b) {
        if (b == null) {
            return a;
        }
        if (a == null) {
            return b;
        }
        return SEVERITY_RANK.indexOf(b) > SEVERITY_RANK.indexOf(a) ? b : a;
    }

    static Instant latest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        return b != null && b.isAfter(a) ? b : a;
    }

    public static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String text(JsonNode p, String field) {
        return p != null && p.hasNonNull(field) ? p.get(field).asString() : null;
    }

    private static UUID uuid(JsonNode p, String field) {
        String v = text(p, field);
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Party party(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        return new Party(uuid(n, "entity_id"), text(n, "code"));
    }

    private static Place place(JsonNode n) {
        if (n == null || n.isNull()) {
            return null;
        }
        return new Place(text(n, "label"), round3(n.path("x").asDouble()), round3(n.path("y").asDouble()));
    }

    private static Position position(EventLocation loc) {
        if (loc == null || loc.position() == null) {
            return null;
        }
        return new Position(round3(loc.position().x()), round3(loc.position().y()), round3(loc.position().z()));
    }
}
