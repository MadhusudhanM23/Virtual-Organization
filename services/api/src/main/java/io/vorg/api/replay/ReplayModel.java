package io.vorg.api.replay;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * The replay state that snapshots persist and the reducer folds events into (ADR-44, ADR-45). Plain immutable records:
 * the canonical JSON of a {@link ReplayState} (fields in declaration order, lists sorted by id) is what is stored,
 * compressed, and hashed, so the same state always has the same bytes.
 */
public final class ReplayModel {

    private ReplayModel() {}

    /** Snapshot format; bump when the persisted shape changes incompatibly. */
    public static final int FORMAT_VERSION = 1;

    /**
     * Ordering key of a pose/floor/status value: the {@code occurred_at} and per-aggregate sequence of the event that set
     * it ({@code seq} is null for a value read from the live projection at genesis).
     */
    public record Key(Instant at, Long seq) {

        /**
         * Whether a value with this key replaces one with {@code current}: a later {@code occurred_at} wins; on equal
         * {@code occurred_at} the lower sequence (the first ingested, as in the live projection) wins; a genesis value
         * ({@code seq == null}) is never replaced by an event of the same instant.
         */
        public boolean beats(Key current) {
            if (current == null) {
                return true;
            }
            int c = at.compareTo(current.at());
            if (c != 0) {
                return c > 0;
            }
            return seq != null && current.seq() != null && seq < current.seq();
        }
    }

    public record Position(double x, double y, double z) {}

    /** Same fields as the twin API pose ({@code TwinQueryService.Pose}). */
    public record Pose(double x, double y, double z, double headingDeg, Instant occurredAt, String source,
            String dataMode, Double spatialConfidence) {}

    public record EntityState(UUID id, String entityType, String code, String name, UUID siteId, UUID floorId,
            UUID zoneId, String status, Pose pose, JsonNode attributes, String dataMode, Key poseKey, Key floorKey,
            Key statusKey) {}

    public record Place(String label, double x, double y) {}

    public record TaskState(UUID id, String taskType, String status, Integer priority, String title, Place from,
            Place to, UUID assignedEntityId, UUID assignedActorRef, String sourceSystem, String sourceRecordId,
            String dataMode, Instant createdAt, Instant statusChangedAt, long statusSequence, int issuesReported,
            long lastSequence, List<Long> skippedSequences) {}

    public record Party(UUID entityId, String code) {}

    public record IncidentState(UUID id, String incidentType, String status, String severity, String title,
            Party forklift, Party worker, UUID floorId, UUID zoneId, Position position, double minDistanceM,
            int alertCount, Instant detectedAt, Instant lastAlertAt, UUID assignedActorRef, long assignedSequence,
            String dataMode, String source, Instant statusChangedAt, long statusSequence, int notesCount,
            int estopRequests, long lastSequence, List<Long> skippedSequences) {}

    public record AlertState(UUID id, UUID eventId, Instant occurredAt, String alertKind, String severity,
            double distanceM, Party forklift, Party worker, UUID floorId, UUID zoneId, Position position,
            String dataMode, String source) {}

    public record DockState(UUID id, UUID floorId, UUID zoneId, String name, String dockType, String status,
            String dataMode) {}

    public record WorldVersionInfo(int version, String contentHash, String label, String dataMode) {}

    /** Context no event describes; shown in replay as recorded at the snapshot, never reconstructed. */
    public record RecordedContext(Instant recordedAt, WorldVersionInfo worldVersion, List<DockState> docks) {}

    public record ReplayState(int formatVersion, UUID siteId, Instant asOf, RecordedContext recorded,
            List<EntityState> entities, List<TaskState> tasks, List<IncidentState> incidents,
            List<AlertState> alerts) {}
}
