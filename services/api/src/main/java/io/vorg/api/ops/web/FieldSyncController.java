package io.vorg.api.ops.web;

import io.vorg.api.ops.FieldSyncService;
import io.vorg.api.ops.FieldSyncService.EvidenceContent;
import io.vorg.api.ops.FieldSyncService.EvidenceMeta;
import io.vorg.api.ops.ImageSniffer;
import io.vorg.api.ops.ImageSniffer.Kind;
import io.vorg.api.ops.TaskQueryService;
import io.vorg.api.ops.TaskQueryService.TaskView;
import io.vorg.api.platform.authz.Action;
import io.vorg.api.platform.authz.PolicyContext;
import io.vorg.api.platform.authz.ResourceRef;
import io.vorg.api.platform.command.CommandAck;
import io.vorg.api.platform.command.CommandGateway;
import io.vorg.api.platform.command.CommandSpec;
import io.vorg.api.platform.identity.Subject;
import io.vorg.api.platform.web.ApiProblemException;
import io.vorg.api.platform.web.ApiProblemException.FieldError;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Field-sync endpoints for the offline Operator PWA (REQ-120, docs/api/field-sync.md, ADR-75..77). Every write is a
 * command through the gateway (Idempotency-Key, EXECUTE_STANDARD_COMMAND on the task's site for the assignee, audit,
 * row and event in one transaction). Evidence is size-bounded and must arrive without EXIF/GPS; scans never overwrite
 * a quantity; urgent records are advisory and execute nothing.
 */
@RestController
public class FieldSyncController {

    public static final int MAX_EVIDENCE_BYTES = 2 * 1024 * 1024;
    private static final Set<String> URGENT_CATEGORIES = Set.of("EMERGENCY", "SAFETY", "EQUIPMENT", "OTHER");

    public record EvidenceRequest(UUID clientRef, String contentType, String dataBase64, String sha256,
            Instant capturedAt, String note) {}

    public record InventoryScanRequest(UUID siteId, UUID taskId, String sku, String locationCode,
            Integer countedQuantity, Long baseInventoryVersion, Instant capturedAt, List<UUID> evidenceRefs,
            String note) {}

    public record UrgentRecordRequest(UUID siteId, UUID taskId, String category, String message,
            String lastKnownInstruction, Instant capturedAt) {}

    public record EvidenceList(List<EvidenceMeta> items) {}

    private final FieldSyncService field;
    private final TaskQueryService queries;
    private final CommandGateway gateway;

    public FieldSyncController(FieldSyncService field, TaskQueryService queries, CommandGateway gateway) {
        this.field = field;
        this.queries = queries;
        this.gateway = gateway;
    }

    @PostMapping("/api/tasks/{id}/evidence")
    public ResponseEntity<CommandAck> attachEvidence(@PathVariable UUID id,
            @RequestHeader(name = "Idempotency-Key", required = false) String key,
            @RequestBody(required = false) EvidenceRequest body, @AuthenticationPrincipal Subject subject) {
        List<FieldError> errors = new ArrayList<>();
        if (body == null) {
            throw invalid(List.of(new FieldError("body", "is required")));
        }
        if (body.clientRef() == null) {
            errors.add(new FieldError("client_ref", "is required"));
        }
        if (body.capturedAt() == null || body.capturedAt().isAfter(Instant.now().plus(Duration.ofDays(1)))) {
            errors.add(new FieldError("captured_at", "is required and not in the future"));
        }
        byte[] bytes = null;
        if (body.dataBase64() == null || body.dataBase64().isBlank()) {
            errors.add(new FieldError("data_base64", "is required"));
        } else if (body.dataBase64().length() > (MAX_EVIDENCE_BYTES / 3 + 1) * 4 + 16) {
            throw new ApiProblemException(HttpStatus.CONTENT_TOO_LARGE, "Evidence too large",
                    "Evidence photos are limited to " + MAX_EVIDENCE_BYTES + " bytes.", "evidence_too_large",
                    Map.of("max_bytes", MAX_EVIDENCE_BYTES));
        } else {
            try {
                bytes = Base64.getDecoder().decode(body.dataBase64());
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError("data_base64", "is not valid base64"));
            }
        }
        String note = body.note() == null ? null : body.note().trim();
        if (note != null && note.length() > 300) {
            errors.add(new FieldError("note", "must be at most 300 characters"));
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        if (bytes.length == 0 || bytes.length > MAX_EVIDENCE_BYTES) {
            throw new ApiProblemException(HttpStatus.CONTENT_TOO_LARGE, "Evidence too large",
                    "Evidence photos are 1 to " + MAX_EVIDENCE_BYTES + " bytes.", "evidence_too_large",
                    Map.of("max_bytes", MAX_EVIDENCE_BYTES));
        }
        Kind kind = ImageSniffer.kind(bytes);
        if (kind == Kind.UNKNOWN || (body.contentType() != null && !kind.mime().equals(body.contentType()))) {
            throw invalid(List.of(new FieldError("content_type", "must be image/jpeg, image/png or image/webp and "
                    + "match the file")));
        }
        String actual = sha256(bytes);
        if (body.sha256() == null || !actual.equals(body.sha256().toLowerCase(Locale.ROOT))) {
            throw invalid(List.of(new FieldError("sha256", "does not match the uploaded bytes")));
        }
        if (ImageSniffer.hasExif(bytes, kind)) {
            throw new ApiProblemException(HttpStatus.UNPROCESSABLE_ENTITY, "Metadata present",
                    "The photo still carries EXIF/GPS metadata. Strip it on the device before upload.",
                    "exif_present", Map.of());
        }
        TaskView task = queries.find(id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("task_id", id.toString());
        payload.put("client_ref", body.clientRef().toString());
        payload.put("content_type", kind.mime());
        payload.put("size_bytes", bytes.length);
        payload.put("sha256", actual);
        payload.put("captured_at", body.capturedAt().toString());
        if (note != null && !note.isEmpty()) {
            payload.put("note", note);
        }
        CommandSpec spec = new CommandSpec("TASK_ATTACH_EVIDENCE", key, subject, Action.EXECUTE_STANDARD_COMMAND,
                ResourceRef.site(subject.tenantId(), task.siteId()), PolicyContext.assignedTo(task.assignedActorRef()),
                payload);
        byte[] content = bytes;
        String n = note == null || note.isEmpty() ? null : note;
        return ResponseEntity.ok(gateway.execute(spec, () -> field.attachEvidence(subject.tenantId(), task.siteId(),
                id, body.clientRef(), kind.mime(), content, actual, n, body.capturedAt(), subject.actorRef())));
    }

    @GetMapping("/api/tasks/{id}/evidence")
    public EvidenceList listEvidence(@PathVariable UUID id, @AuthenticationPrincipal Subject subject) {
        queries.get(subject, id); // policy-scoped visibility: 403/404 as for the task itself
        return new EvidenceList(field.listEvidence(id));
    }

    @GetMapping("/api/evidence/{evidenceId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID evidenceId, @AuthenticationPrincipal Subject subject) {
        EvidenceContent content = field.evidenceContent(evidenceId);
        queries.get(subject, content.taskId());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(content.contentType()))
                .cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .body(content.bytes());
    }

    @PostMapping("/api/field/inventory-scans")
    public ResponseEntity<CommandAck> inventoryScan(@RequestHeader(name = "Idempotency-Key", required = false)
            String key, @RequestBody(required = false) InventoryScanRequest body,
            @AuthenticationPrincipal Subject subject) {
        List<FieldError> errors = new ArrayList<>();
        if (body == null || body.siteId() == null) {
            errors.add(new FieldError("site_id", "is required"));
        }
        String sku = body == null || body.sku() == null ? "" : body.sku().trim();
        String loc = body == null || body.locationCode() == null ? "" : body.locationCode().trim();
        if (sku.isEmpty() || sku.length() > 60) {
            errors.add(new FieldError("sku", "is required and at most 60 characters"));
        }
        if (loc.isEmpty() || loc.length() > 60) {
            errors.add(new FieldError("location_code", "is required and at most 60 characters"));
        }
        if (body == null || body.countedQuantity() == null || body.countedQuantity() < 0
                || body.countedQuantity() > 1_000_000) {
            errors.add(new FieldError("counted_quantity", "is required, 0 to 1000000"));
        }
        if (body == null || body.baseInventoryVersion() == null || body.baseInventoryVersion() < 0) {
            errors.add(new FieldError("base_inventory_version", "is required, the version the operator scanned against"));
        }
        if (body == null || body.capturedAt() == null) {
            errors.add(new FieldError("captured_at", "is required"));
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        requireSiteAndTask(body.siteId(), body.taskId());
        List<UUID> refs = body.evidenceRefs() == null ? List.of() : body.evidenceRefs();
        String note = body.note() == null ? null : body.note().trim();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site_id", body.siteId().toString());
        payload.put("sku", sku);
        payload.put("location_code", loc);
        payload.put("counted_quantity", body.countedQuantity());
        payload.put("base_inventory_version", body.baseInventoryVersion());
        payload.put("captured_at", body.capturedAt().toString());
        payload.put("evidence_refs", refs.stream().map(UUID::toString).toList());
        if (body.taskId() != null) {
            payload.put("task_id", body.taskId().toString());
        }
        CommandSpec spec = new CommandSpec("FIELD_INVENTORY_SCAN", key, subject, Action.EXECUTE_STANDARD_COMMAND,
                ResourceRef.site(subject.tenantId(), body.siteId()), PolicyContext.assignedTo(subject.actorRef()),
                payload);
        return ResponseEntity.ok(gateway.execute(spec, () -> field.recordInventoryScan(subject.tenantId(),
                body.siteId(), body.taskId(), sku, loc, body.countedQuantity(), body.baseInventoryVersion(),
                body.capturedAt(), refs, note == null || note.isEmpty() ? null : note, subject.actorRef())));
    }

    @PostMapping("/api/field/urgent-records")
    public ResponseEntity<CommandAck> urgentRecord(@RequestHeader(name = "Idempotency-Key", required = false)
            String key, @RequestBody(required = false) UrgentRecordRequest body,
            @AuthenticationPrincipal Subject subject) {
        List<FieldError> errors = new ArrayList<>();
        if (body == null || body.siteId() == null) {
            errors.add(new FieldError("site_id", "is required"));
        }
        String category = body == null || body.category() == null ? "" : body.category().trim().toUpperCase(Locale.ROOT);
        if (!URGENT_CATEGORIES.contains(category)) {
            errors.add(new FieldError("category", "must be one of EMERGENCY, EQUIPMENT, OTHER, SAFETY"));
        }
        String message = body == null || body.message() == null ? "" : body.message().trim();
        if (message.isEmpty() || message.length() > 1000) {
            errors.add(new FieldError("message", "is required and at most 1000 characters"));
        }
        String last = body == null || body.lastKnownInstruction() == null ? null : body.lastKnownInstruction().trim();
        if (last != null && last.length() > 500) {
            errors.add(new FieldError("last_known_instruction", "must be at most 500 characters"));
        }
        if (body == null || body.capturedAt() == null) {
            errors.add(new FieldError("captured_at", "is required"));
        }
        if (!errors.isEmpty()) {
            throw invalid(errors);
        }
        requireSiteAndTask(body.siteId(), body.taskId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("site_id", body.siteId().toString());
        payload.put("category", category);
        payload.put("message", message);
        payload.put("captured_at", body.capturedAt().toString());
        if (body.taskId() != null) {
            payload.put("task_id", body.taskId().toString());
        }
        if (last != null && !last.isEmpty()) {
            payload.put("last_known_instruction", last);
        }
        CommandSpec spec = new CommandSpec("FIELD_URGENT_RECORD", key, subject, Action.EXECUTE_STANDARD_COMMAND,
                ResourceRef.site(subject.tenantId(), body.siteId()), PolicyContext.assignedTo(subject.actorRef()),
                payload);
        String finalCategory = category;
        String instruction = last == null || last.isEmpty() ? null : last;
        return ResponseEntity.ok(gateway.execute(spec, () -> field.fileUrgentRecord(subject.tenantId(),
                body.siteId(), body.taskId(), finalCategory, message, instruction, body.capturedAt(),
                subject.actorRef())));
    }

    private void requireSiteAndTask(UUID siteId, UUID taskId) {
        if (!field.siteExists(siteId)) {
            throw new ApiProblemException(HttpStatus.NOT_FOUND, "Not found", "The requested site was not found.");
        }
        if (taskId != null && !field.taskBelongsToSite(taskId, siteId)) {
            throw invalid(List.of(new FieldError("task_id", "is not a task of this site")));
        }
    }

    private static ApiProblemException invalid(List<FieldError> errors) {
        return new ApiProblemException(HttpStatus.BAD_REQUEST, "Validation failed",
                "One or more fields are invalid.", errors);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
