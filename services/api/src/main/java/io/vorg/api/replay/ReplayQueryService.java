package io.vorg.api.replay;

import io.vorg.api.platform.authz.SiteAccess;
import io.vorg.api.platform.events.EnvelopeCodec;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import io.vorg.api.platform.web.PageParams;
import io.vorg.api.replay.ReplayModel.AlertState;
import io.vorg.api.replay.ReplayModel.EntityState;
import io.vorg.api.replay.ReplayModel.IncidentState;
import io.vorg.api.replay.ReplayModel.TaskState;
import io.vorg.api.replay.ReplayStore.SnapshotMeta;
import io.vorg.api.replay.ReplayViews.AlertView;
import io.vorg.api.replay.ReplayViews.Content;
import io.vorg.api.replay.ReplayViews.EntityDelta;
import io.vorg.api.replay.ReplayViews.EntityView;
import io.vorg.api.replay.ReplayViews.IncidentView;
import io.vorg.api.replay.ReplayViews.Recorded;
import io.vorg.api.replay.ReplayViews.Summary;
import io.vorg.api.replay.ReplayViews.TaskView;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Replay reads (REQ-061, REQ-063, ADR-45, ADR-46): the replayable range, the reconstructed state at an instant, playback
 * frames and the ordered event history. Every call first requires {@code VIEW_OPERATIONAL_STATE} on the whole site
 * (ADR-31 status codes, denials audited), then runs in a <b>read-only</b> repeatable-read transaction: replay never
 * writes - no events, no outbox rows, no projection or snapshot changes - and never re-executes commands or side
 * effects. The reconstruction is a pure function of (snapshot, events, instant, floor filter).
 */
@Service
public class ReplayQueryService {

    public static final String REPLAY = "REPLAY";

    public record SnapshotInfo(UUID snapshotId, Instant snapshotAt, String trigger, String origin,
            Integer worldVersion, int entityCount, int taskCount, int incidentCount, int alertCount, int sizeBytes) {}

    public record RangeResponse(String lens, UUID siteId, boolean replayAvailable, Instant earliestAt,
            Instant latestAt, Instant settledUntil, Instant latestEventAt, String snapshotInterval, String retention,
            String recentWindow, int snapshotCount, List<SnapshotInfo> snapshots) {}

    public record ReconstructedFrom(UUID snapshotId, Instant snapshotAt, String origin, int eventsRead,
            int eventsApplied, int eventsIgnored, int eventsSkipped) {}

    public record NotReconstructed(String domain, String treatment, String note) {}

    public record StateResponse(String lens, String dataMode, UUID siteId, Instant at, UUID floorId,
            ReconstructedFrom reconstructedFrom, Recorded recorded, List<EntityView> entities,
            Summary<TaskView> tasks, Summary<IncidentView> incidents, List<AlertView> alerts,
            List<NotReconstructed> notReconstructed, String stateHash) {}

    public record Removed(List<UUID> tasks, List<UUID> incidents, List<UUID> alerts) {}

    public record Frame(Instant at, List<EntityDelta> entities, List<TaskView> tasks, List<IncidentView> incidents,
            List<AlertView> alerts, Removed removed) {}

    public record Keyframe(Instant at, Recorded recorded, List<EntityView> entities, List<TaskView> tasks,
            List<IncidentView> incidents, List<AlertView> alerts, String stateHash) {}

    public record FramesResponse(String lens, String dataMode, UUID siteId, Instant from, Instant to, long stepMs,
            UUID floorId, ReconstructedFrom reconstructedFrom, Keyframe keyframe, int frameCount,
            List<Frame> frames, int eventsApplied, Instant nextFrom) {}

    public record EventItem(String replayRole, JsonNode event) {}

    public record EventsResponse(String lens, UUID siteId, Instant from, Instant to, String scope,
            List<EventItem> items, String nextCursor) {}

    /** A fold at an instant together with its inputs. */
    public record Reconstruction(SnapshotMeta base, ReplayModel.ReplayState baseState, ReplayReducer.Fold fold,
            int eventsRead) {

        public ReconstructedFrom from() {
            return new ReconstructedFrom(base.id(), base.asOf(), base.origin(), eventsRead, fold.applied(),
                    fold.ignored(), fold.skipped());
        }
    }

    static final List<NotReconstructed> NOT_RECONSTRUCTED = List.of(
            new NotReconstructed("DOCKS", "RECORDED",
                    "Dock states are shown as recorded at the base snapshot; no dock events exist to replay."),
            new NotReconstructed("WORLD_MODEL", "RECORDED",
                    "The world version is the one recorded at the base snapshot."),
            new NotReconstructed("MIRRORED_ORDERS_INVENTORY", "RECORDED",
                    "Mirrored WMS/TMS data is external: it is listed from the captured integration events"
                            + " (GET /api/replay/events?scope=all) and never reconstructed."));

    private final SiteAccess access;
    private final ReplayStore store;
    private final SnapshotService snapshots;
    private final ReplayReducer reducer;
    private final ReplayCodec codec;
    private final EnvelopeCodec envelopes;
    private final ReplayProperties props;
    private final Clock clock;
    private final TransactionTemplate readOnly;

    public ReplayQueryService(SiteAccess access, ReplayStore store, SnapshotService snapshots, ReplayReducer reducer,
            ReplayCodec codec, EnvelopeCodec envelopes, ReplayProperties props, Clock clock,
            PlatformTransactionManager txManager) {
        this.access = access;
        this.store = store;
        this.snapshots = snapshots;
        this.reducer = reducer;
        this.codec = codec;
        this.envelopes = envelopes;
        this.props = props;
        this.clock = clock;
        this.readOnly = new TransactionTemplate(txManager);
        this.readOnly.setReadOnly(true);
        this.readOnly.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    // ---- range ---------------------------------------------------------------------------------------------------

    public RangeResponse range(Subject subject, UUID siteId) {
        access.requireView(subject, siteId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        return readOnly.execute(s -> {
            List<SnapshotMeta> list = store.list(siteId, 500);
            int count = (int) store.count(siteId);
            Instant earliest = store.earliest(siteId).map(SnapshotMeta::asOf).orElse(null);
            List<SnapshotInfo> infos = list.stream().map(m -> new SnapshotInfo(m.id(), m.asOf(), m.trigger(),
                    m.origin(), m.worldVersion(), m.entityCount(), m.taskCount(), m.incidentCount(), m.alertCount(),
                    m.sizeBytes())).toList();
            return new RangeResponse(REPLAY, siteId, earliest != null, earliest, earliest == null ? null : now,
                    earliest == null ? null : now.minus(props.ingestOverlap()),
                    store.latestEventAt(siteId).orElse(null), props.snapshots().interval().toString(),
                    props.snapshots().retention().toString(), props.recentWindow().toString(), count, infos);
        });
    }

    // ---- state ---------------------------------------------------------------------------------------------------

    public StateResponse state(Subject subject, UUID siteId, String atParam, UUID floorId) {
        access.requireView(subject, siteId);
        Instant at = parseInstant("at", atParam, true);
        requireNotFuture("at", at);
        return readOnly.execute(s -> {
            Reconstruction r = reconstruct(siteId, at, null);
            Content c = ReplayViews.content(reducer, r.fold(), siteId, at, floorId, r.baseState().recorded());
            return new StateResponse(REPLAY, REPLAY, siteId, at, floorId, r.from(), c.recorded(), c.entities(),
                    c.tasks(), c.incidents(), c.alerts(), NOT_RECONSTRUCTED, "sha256:" + codec.sha256(ReplayViews.hashable(c)));
        });
    }

    /**
     * Reconstructs the site at {@code at} from the latest snapshot with {@code as_of <= at} (or the given one) plus the
     * ordered events (ADR-45). Must run inside a transaction with the tenant bound. Package-visible for tests.
     */
    Reconstruction reconstruct(UUID siteId, Instant at, UUID baseSnapshotId) {
        SnapshotMeta base;
        if (baseSnapshotId != null) {
            base = store.find(siteId, baseSnapshotId).filter(m -> !m.asOf().isAfter(at))
                    .orElseThrow(() -> invalid("snapshot_id", "no such snapshot at or before the instant"));
        } else {
            base = store.latestAtOrBefore(siteId, at).orElseThrow(() -> noHistory(siteId, at));
        }
        ReplayModel.ReplayState state = snapshots.load(base).state();
        List<EventEnvelope> events = store.eventsFrom(siteId, base.asOf(), at,
                base.asOf().minus(props.lateHorizon()), base.takenAt().minus(props.ingestOverlap()));
        ReplayReducer.Fold fold = reducer.fold(state);
        fold.applyAll(events);
        return new Reconstruction(base, state, fold, events.size());
    }

    /** The canonical content and its hash at an instant (tests: compare reconstructions from different bases). */
    public Content content(UUID siteId, Instant at, UUID floorId, UUID baseSnapshotId) {
        return readOnly.execute(s -> {
            Reconstruction r = reconstruct(siteId, at, baseSnapshotId);
            return ReplayViews.content(reducer, r.fold(), siteId, at, floorId, r.baseState().recorded());
        });
    }

    public String hash(Content content) {
        return "sha256:" + codec.sha256(ReplayViews.hashable(content));
    }

    // ---- frames --------------------------------------------------------------------------------------------------

    public FramesResponse frames(Subject subject, UUID siteId, String fromParam, String toParam, Long stepMsParam,
            UUID floorId) {
        access.requireView(subject, siteId);
        Instant from = parseInstant("from", fromParam, true);
        Instant to = parseInstant("to", toParam, true);
        long stepMs = stepMsParam == null ? 1000L : stepMsParam;
        List<FieldError> errors = new ArrayList<>();
        if (!from.isBefore(to)) {
            errors.add(new FieldError("to", "must be after from"));
        }
        if (stepMs < props.frames().minStepMs() || stepMs > props.frames().maxStepMs()) {
            errors.add(new FieldError("step_ms", "must be between " + props.frames().minStepMs() + " and "
                    + props.frames().maxStepMs()));
        }
        if (!errors.isEmpty()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                    "One or more parameters are invalid.", errors);
        }
        requireNotFuture("to", to);
        Duration step = Duration.ofMillis(stepMs);
        return readOnly.execute(s -> frames(siteId, from, to, step, floorId));
    }

    private FramesResponse frames(UUID siteId, Instant from, Instant to, Duration step, UUID floorId) {
        Reconstruction r = reconstruct(siteId, from, null);
        ReplayReducer.Fold fold = r.fold();
        Content key = ReplayViews.content(reducer, fold, siteId, from, floorId, r.baseState().recorded());
        Keyframe keyframe = new Keyframe(from, key.recorded(), key.entities(), key.tasks().items(),
                key.incidents().items(), key.alerts(), "sha256:" + codec.sha256(ReplayViews.hashable(key)));
        fold.drainChanges();

        long maxSteps = props.frames().maxFrames();
        Instant effTo = to;
        Instant capByFrames = from.plus(step.multipliedBy(maxSteps));
        if (capByFrames.isBefore(effTo)) {
            effTo = capByFrames;
        }
        int maxEvents = props.frames().maxEvents();
        List<EventEnvelope> events = store.eventsBetween(siteId, from, effTo, maxEvents + 1);
        if (events.size() > maxEvents) {
            Instant overflow = events.get(maxEvents).occurredAt();
            long k = (Duration.between(from, overflow).toNanos() - 1) / step.toNanos();
            if (k < 1) {
                throw invalid("step_ms", "more than " + maxEvents + " events fall into one step; use a smaller step_ms");
            }
            Instant cut = from.plus(step.multipliedBy(k));
            effTo = cut;
            events = events.stream().filter(e -> !e.occurredAt().isAfter(cut)).toList();
        }

        Set<UUID> visTasks = ids(key.tasks().items().stream().map(TaskView::id).toList());
        Set<UUID> visIncidents = ids(key.incidents().items().stream().map(IncidentView::id).toList());
        Set<UUID> visAlerts = ids(key.alerts().stream().map(AlertView::id).toList());
        List<Frame> frames = new ArrayList<>();
        int idx = 0;
        int appliedBefore = fold.applied();
        for (long i = 1; ; i++) {
            Instant t = from.plus(step.multipliedBy(i));
            if (t.isAfter(effTo)) {
                t = effTo;
            }
            while (idx < events.size() && !events.get(idx).occurredAt().isAfter(t)) {
                fold.apply(events.get(idx++));
            }
            ReplayReducer.Changes changes = fold.drainChanges();
            Frame frame = frame(fold, t, floorId, changes, visTasks, visIncidents, visAlerts);
            if (frame != null) {
                frames.add(frame);
            }
            if (!t.isBefore(effTo)) {
                break;
            }
        }
        Instant nextFrom = effTo.isBefore(to) ? effTo : null;
        return new FramesResponse(REPLAY, REPLAY, siteId, from, effTo, step.toMillis(), floorId, r.from(), keyframe,
                frames.size(), frames, fold.applied() - appliedBefore, nextFrom);
    }

    /** The delta at {@code t}; updates the visible-id sets; null when nothing changed. */
    private Frame frame(ReplayReducer.Fold fold, Instant t, UUID floorId, ReplayReducer.Changes changes,
            Set<UUID> visTasks, Set<UUID> visIncidents, Set<UUID> visAlerts) {
        List<EntityDelta> entities = new ArrayList<>();
        for (UUID id : changes.entities()) {
            EntityState e = fold.entityMap().get(id);
            if (e != null && ReplayViews.onFloor(e.floorId(), floorId)) {
                entities.add(ReplayViews.delta(e));
            }
        }
        Set<UUID> changedTasks = ids(changes.tasks());
        List<TaskView> tasks = new ArrayList<>();
        List<UUID> removedTasks = new ArrayList<>();
        Set<UUID> nowTasks = ids(List.of());
        for (TaskState x : fold.tasks()) {
            if (reducer.visible(x, t)) {
                nowTasks.add(x.id());
                if (changedTasks.contains(x.id()) || !visTasks.contains(x.id())) {
                    tasks.add(ReplayViews.task(x));
                }
            }
        }
        visTasks.stream().filter(id -> !nowTasks.contains(id)).forEach(removedTasks::add);
        visTasks.clear();
        visTasks.addAll(nowTasks);

        Set<UUID> changedIncidents = ids(changes.incidents());
        List<IncidentView> incidents = new ArrayList<>();
        List<UUID> removedIncidents = new ArrayList<>();
        Set<UUID> nowIncidents = ids(List.of());
        for (IncidentState x : fold.incidents()) {
            if (reducer.visible(x, t) && ReplayViews.onFloor(x.floorId(), floorId)) {
                nowIncidents.add(x.id());
                if (changedIncidents.contains(x.id()) || !visIncidents.contains(x.id())) {
                    incidents.add(ReplayViews.incident(x));
                }
            }
        }
        visIncidents.stream().filter(id -> !nowIncidents.contains(id)).forEach(removedIncidents::add);
        visIncidents.clear();
        visIncidents.addAll(nowIncidents);

        List<AlertView> alerts = new ArrayList<>();
        List<UUID> removedAlerts = new ArrayList<>();
        Set<UUID> nowAlerts = ids(List.of());
        for (AlertState x : fold.alerts()) {
            if (reducer.visible(x, t) && ReplayViews.onFloor(x.floorId(), floorId)) {
                nowAlerts.add(x.id());
                if (!visAlerts.contains(x.id())) {
                    alerts.add(ReplayViews.alert(x));
                }
            }
        }
        visAlerts.stream().filter(id -> !nowAlerts.contains(id)).forEach(removedAlerts::add);
        visAlerts.clear();
        visAlerts.addAll(nowAlerts);

        if (entities.isEmpty() && tasks.isEmpty() && incidents.isEmpty() && alerts.isEmpty() && removedTasks.isEmpty()
                && removedIncidents.isEmpty() && removedAlerts.isEmpty()) {
            return null;
        }
        return new Frame(t, entities, tasks, incidents, alerts,
                new Removed(removedTasks, removedIncidents, removedAlerts));
    }

    private static Set<UUID> ids(List<UUID> ids) {
        Set<UUID> out = new TreeSet<>(ReplayReducer::compareUuids);
        out.addAll(ids);
        return out;
    }

    // ---- events --------------------------------------------------------------------------------------------------

    public EventsResponse events(Subject subject, UUID siteId, String fromParam, String toParam, String scopeParam,
            Integer limitParam, String cursor) {
        access.requireView(subject, siteId);
        Instant from = parseInstant("from", fromParam, true);
        Instant to = parseInstant("to", toParam, true);
        if (!from.isBefore(to)) {
            throw invalid("to", "must be after from");
        }
        String scope = scopeParam == null || scopeParam.isBlank() ? "replayable" : scopeParam.trim().toLowerCase();
        if (!Set.of("replayable", "all").contains(scope)) {
            throw invalid("scope", "must be replayable or all");
        }
        int limit = PageParams.limit(limitParam);
        Cursor after = Cursor.decode(cursor);
        return readOnly.execute(s -> {
            List<EventEnvelope> rows = store.page(siteId, from, to, "all".equals(scope),
                    after == null ? null : after.at(), after == null ? null : after.seq(),
                    after == null ? null : after.id(), limit + 1);
            boolean more = rows.size() > limit;
            List<EventEnvelope> page = more ? rows.subList(0, limit) : rows;
            List<EventItem> items = new ArrayList<>(page.size());
            for (EventEnvelope e : page) {
                items.add(new EventItem(ReplayReducer.isReplayEvent(e.eventType()) ? "RECONSTRUCTED" : "RECORDED",
                        envelopes.toNode(e)));
            }
            String next = null;
            if (more) {
                EventEnvelope last = page.get(page.size() - 1);
                next = Cursor.encode(last.occurredAt(), last.sequence() == null ? 0L : last.sequence(),
                        last.eventId());
            }
            return new EventsResponse(REPLAY, siteId, from, to, scope, items, next);
        });
    }

    /** Opaque ascending keyset cursor over {@code (occurred_at, sequence, event_id)} (micros precision). */
    record Cursor(Instant at, long seq, UUID id) {

        static String encode(Instant at, long seq, UUID id) {
            String raw = ChronoUnit.MICROS.between(Instant.EPOCH, at) + "|" + seq + "|" + id;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }

        static Cursor decode(String cursor) {
            if (cursor == null || cursor.isBlank()) {
                return null;
            }
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|");
                return new Cursor(Instant.EPOCH.plus(Long.parseLong(p[0]), ChronoUnit.MICROS), Long.parseLong(p[1]),
                        UUID.fromString(p[2]));
            } catch (RuntimeException ex) {
                throw invalid("cursor", "is not a valid cursor");
            }
        }
    }

    // ---- validation ----------------------------------------------------------------------------------------------

    static Instant parseInstant(String field, String value, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw invalid(field, "is required (an ISO-8601 UTC instant such as 2026-10-12T09:30:00Z)");
            }
            return null;
        }
        try {
            return Instant.parse(value.trim()).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeParseException ex) {
            throw invalid(field, "must be an ISO-8601 UTC instant such as 2026-10-12T09:30:00Z");
        }
    }

    private void requireNotFuture(String field, Instant at) {
        if (at.isAfter(clock.instant())) {
            throw invalid(field, "must not be in the future (replay is history; use the live twin for now)");
        }
    }

    private ApiProblemException noHistory(UUID siteId, Instant at) {
        return store.earliest(siteId)
                .map(e -> invalid("at", "is before the earliest replayable instant " + e.asOf()))
                .orElseGet(() -> invalid("at", "no replay snapshot exists for this site yet"));
    }

    static ApiProblemException invalid(String field, String message) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more parameters are invalid.", List.of(new FieldError(field, message)));
    }
}
