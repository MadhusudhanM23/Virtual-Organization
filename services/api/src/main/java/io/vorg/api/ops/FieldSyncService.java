package io.vorg.api.ops;

import io.vorg.api.platform.Ids;
import io.vorg.api.platform.command.CommandResult;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EnvelopeCodec;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.web.ApiProblemException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Field-sync writes and reads (REQ-120, ADR-75..77): task evidence uploaded after the device was offline, inventory
 * scans checked against the mirrored inventory version, and urgent advisory records. Every write method runs inside
 * the command gateway's transaction: the row and its event commit together or not at all. Nothing here changes an
 * authoritative quantity, a task status or a machine.
 */
@Service
public class FieldSyncService {

    public static final String EVIDENCE_EVENT = "TASK_EVIDENCE_ATTACHED";
    public static final String FIELD_AGGREGATE = "FIELD_RECORD";
    public static final String SCAN_EVENT = "INVENTORY_SCAN_RECORDED";
    public static final String EXCEPTION_EVENT = "INVENTORY_EXCEPTION_RECORDED";
    public static final String URGENT_EVENT = "URGENT_FIELD_RECORD_FILED";

    public record EvidenceMeta(UUID id, UUID taskId, UUID clientRef, String contentType, int sizeBytes, String sha256,
            String note, Instant capturedAt, Instant uploadedAt, String syncState) {}

    public record EvidenceContent(UUID taskId, String contentType, byte[] bytes) {}

    private final JdbcClient jdbc;
    private final DomainEvents events;
    private final EnvelopeCodec codec;

    public FieldSyncService(JdbcClient jdbc, DomainEvents events, EnvelopeCodec codec) {
        this.jdbc = jdbc;
        this.events = events;
        this.codec = codec;
    }

    /** Stores one evidence photo; a repeat of the same (task, client_ref) returns the stored row ({@code duplicate}). */
    public CommandResult attachEvidence(UUID tenantId, UUID siteId, UUID taskId, UUID clientRef, String contentType,
            byte[] bytes, String sha256, String note, Instant capturedAt, UUID uploader) {
        Optional<UUID> existing = jdbc.sql("select id from ops.task_evidence where task_id = :t and client_ref = :c")
                .param("t", taskId).param("c", clientRef).query(UUID.class).optional();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task_id", taskId.toString());
        result.put("client_ref", clientRef.toString());
        result.put("sha256", sha256);
        result.put("size_bytes", bytes.length);
        if (existing.isPresent()) {
            result.put("evidence_id", existing.get().toString());
            result.put("duplicate", true);
            result.put("sync_state", "SYNCED");
            return CommandResult.of(result);
        }
        UUID id = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("evidence_id", id.toString());
        payload.put("client_ref", clientRef.toString());
        payload.put("content_type", contentType);
        payload.put("size_bytes", bytes.length);
        payload.put("sha256", sha256);
        payload.put("captured_at", capturedAt.toString());
        EventEnvelope event = events.record(EventDraft.of(TaskService.AGGREGATE, taskId, EVIDENCE_EVENT, payload)
                .source(EventSource.API).location(EventLocation.site(siteId)));
        jdbc.sql("""
                insert into ops.task_evidence (id, tenant_id, site_id, task_id, client_ref, content_type, size_bytes,
                    sha256, content, note, captured_at, uploaded_by, event_id)
                values (:id, :tenant, :site, :task, :ref, :ct, :size, :sha, :content, :note, :captured, :by, :event)
                """)
                .param("id", id).param("tenant", tenantId).param("site", siteId).param("task", taskId)
                .param("ref", clientRef).param("ct", contentType).param("size", bytes.length).param("sha", sha256)
                .param("content", bytes).param("note", note).param("captured", Timestamp.from(capturedAt))
                .param("by", uploader).param("event", event.eventId()).update();
        result.put("evidence_id", id.toString());
        result.put("duplicate", false);
        result.put("sync_state", "SYNCED");
        result.put("event_id", event.eventId().toString());
        result.put("sequence", event.sequence());
        return CommandResult.of(result);
    }

    public List<EvidenceMeta> listEvidence(UUID taskId) {
        return jdbc.sql("""
                select id, task_id, client_ref, content_type, size_bytes, sha256, note, captured_at, uploaded_at
                from ops.task_evidence where task_id = :t order by uploaded_at, id
                """).param("t", taskId)
                .query((rs, n) -> new EvidenceMeta(rs.getObject("id", UUID.class), rs.getObject("task_id", UUID.class),
                        rs.getObject("client_ref", UUID.class), rs.getString("content_type"), rs.getInt("size_bytes"),
                        rs.getString("sha256"), rs.getString("note"),
                        rs.getTimestamp("captured_at").toInstant(), rs.getTimestamp("uploaded_at").toInstant(),
                        "SYNCED"))
                .list();
    }

    public EvidenceContent evidenceContent(UUID evidenceId) {
        return jdbc.sql("select task_id, content_type, content from ops.task_evidence where id = :id")
                .param("id", evidenceId)
                .query((rs, n) -> new EvidenceContent(rs.getObject("task_id", UUID.class),
                        rs.getString("content_type"), rs.getBytes("content")))
                .optional().orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, "Not found",
                        "The requested evidence was not found."));
    }

    /**
     * Records an inventory scan. The authoritative quantity is never touched: when the inventory version the operator
     * scanned against still equals the mirrored one the scan is recorded, otherwise (changed or unknown record) an
     * inventory exception carrying the local evidence is recorded for a person to resolve.
     */
    public CommandResult recordInventoryScan(UUID tenantId, UUID siteId, UUID taskId, String sku, String locationCode,
            int countedQuantity, long baseVersion, Instant capturedAt, List<UUID> evidenceRefs, String note,
            UUID reporter) {
        String recordId = "INV-" + sku + "@" + locationCode;
        Optional<Long> current = jdbc.sql("""
                select max(source_version) from integ.mirror_records
                where site_id = :site and record_kind = 'INVENTORY' and source_record_id = :rid
                """).param("site", siteId).param("rid", recordId).query(Long.class).optional()
                .filter(v -> v != null && v > 0);
        String reason = current.isEmpty() ? "inventory_record_unknown"
                : current.get() != baseVersion ? "inventory_version_changed" : null;
        boolean exception = reason != null;
        String kind = exception ? "INVENTORY_EXCEPTION" : "INVENTORY_SCAN";
        UUID id = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("record_id", id.toString());
        payload.put("sku", sku);
        payload.put("location_code", locationCode);
        payload.put("counted_quantity", countedQuantity);
        payload.put("base_inventory_version", baseVersion);
        current.ifPresent(v -> payload.put("current_inventory_version", v));
        payload.put("captured_at", capturedAt.toString());
        payload.put("evidence_refs", evidenceRefs.stream().map(UUID::toString).toList());
        if (taskId != null) {
            payload.put("task_id", taskId.toString());
        }
        if (reason != null) {
            payload.put("reason", reason);
        }
        if (note != null) {
            payload.put("note", note);
        }
        EventEnvelope event = events.record(EventDraft.of(FIELD_AGGREGATE, id, exception ? EXCEPTION_EVENT
                : SCAN_EVENT, payload).source(EventSource.API).location(EventLocation.site(siteId)));
        insertRecord(id, tenantId, siteId, kind, taskId, payload, capturedAt, reporter, event.eventId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record_id", id.toString());
        result.put("kind", kind);
        result.put("outcome", exception ? "EXCEPTION" : "RECORDED");
        result.put("applied", !exception);
        if (reason != null) {
            result.put("reason", reason);
        }
        current.ifPresent(v -> result.put("current_inventory_version", v));
        result.put("quantity_overwritten", false);
        result.put("event_id", event.eventId().toString());
        result.put("sequence", event.sequence());
        return CommandResult.of(result);
    }

    /** Files an urgent, advisory record. It never starts, stops or changes anything (BLUEPRINT section 12, ADR-77). */
    public CommandResult fileUrgentRecord(UUID tenantId, UUID siteId, UUID taskId, String category, String message,
            String lastKnownInstruction, Instant capturedAt, UUID reporter) {
        UUID id = Ids.newId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("record_id", id.toString());
        payload.put("category", category);
        payload.put("message", message);
        payload.put("captured_at", capturedAt.toString());
        payload.put("advisory", true);
        payload.put("executed", false);
        if (lastKnownInstruction != null) {
            payload.put("last_known_instruction", lastKnownInstruction);
        }
        if (taskId != null) {
            payload.put("task_id", taskId.toString());
        }
        EventEnvelope event = events.record(EventDraft.of(FIELD_AGGREGATE, id, URGENT_EVENT, payload)
                .source(EventSource.API).location(EventLocation.site(siteId)));
        insertRecord(id, tenantId, siteId, "URGENT", taskId, payload, capturedAt, reporter, event.eventId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("record_id", id.toString());
        result.put("kind", "URGENT");
        result.put("advisory", true);
        result.put("executed", false);
        result.put("event_id", event.eventId().toString());
        result.put("sequence", event.sequence());
        return CommandResult.of(result);
    }

    public boolean siteExists(UUID siteId) {
        return jdbc.sql("select count(*) from world.sites where id = :id").param("id", siteId).query(Long.class)
                .single() > 0;
    }

    public boolean taskBelongsToSite(UUID taskId, UUID siteId) {
        return jdbc.sql("select count(*) from ops.tasks where id = :t and site_id = :s").param("t", taskId)
                .param("s", siteId).query(Long.class).single() > 0;
    }

    private void insertRecord(UUID id, UUID tenantId, UUID siteId, String kind, UUID taskId,
            Map<String, Object> payload, Instant capturedAt, UUID reporter, UUID eventId) {
        jdbc.sql("""
                insert into ops.field_records (id, tenant_id, site_id, kind, task_id, payload, captured_at,
                    reported_by, event_id)
                values (:id, :tenant, :site, :kind, :task, cast(:payload as jsonb), :captured, :by, :event)
                """)
                .param("id", id).param("tenant", tenantId).param("site", siteId).param("kind", kind)
                .param("task", taskId).param("payload", codec.write(payload))
                .param("captured", Timestamp.from(capturedAt)).param("by", reporter).param("event", eventId).update();
    }
}
