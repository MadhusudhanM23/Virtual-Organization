package io.vorg.api.integration.core;

import io.vorg.api.platform.Ids;
import io.vorg.api.platform.events.ActorType;
import io.vorg.api.platform.events.DomainEvents;
import io.vorg.api.platform.events.EnvelopeCodec;
import io.vorg.api.platform.events.EventDraft;
import io.vorg.api.platform.events.EventEnvelope;
import io.vorg.api.platform.events.EventLocation;
import io.vorg.api.platform.events.EventSource;
import io.vorg.api.platform.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Applies adapter batches to the labeled mirrors (REQ-041). Rules, per record:
 * <ul>
 *   <li>structurally invalid: rejected, {@code DATA_QUALITY} finding (nothing is stored);</li>
 *   <li>unknown record: created as version N, revision 1;</li>
 *   <li>newer source version: applied as a new revision (the previous version stays in {@code mirror_revisions});</li>
 *   <li>same version and same content hash: duplicate, ignored (idempotent replays);</li>
 *   <li>same version, different content: {@code VERSION_CONFLICT} finding, mirror unchanged;</li>
 *   <li>older version: {@code STALE_VERSION} finding, mirror unchanged.</li>
 * </ul>
 * After a record is applied it is reconciled against the twin (a shipment that says a truck is at a dock that the twin
 * reports free yields a {@code RECONCILIATION_DIFF} finding). Findings are unique per (record, version, type) and each
 * new finding is announced as an {@code INTEGRATION_FINDING_RECORDED} event; each applied record as
 * {@code MIRROR_RECORD_UPDATED}. The whole batch runs in one transaction with the tenant bound.
 */
@Service
public class MirrorService {

    public static final String MIRROR_AGGREGATE = "MIRROR_RECORD";
    public static final String MIRROR_EVENT = "MIRROR_RECORD_UPDATED";
    public static final String FINDING_AGGREGATE = "INTEGRATION_FINDING";
    public static final String FINDING_EVENT = "INTEGRATION_FINDING_RECORDED";

    /** Counts of what happened to a batch. */
    public record IngestSummary(int received, int created, int updated, int duplicates, int stale, int conflicts,
            int rejected, int reconciliationDiffs) {

        public int findings() {
            return stale + conflicts + rejected + reconciliationDiffs;
        }
    }

    private static final class Counts {
        int received, created, updated, duplicates, stale, conflicts, rejected, diffs;

        IngestSummary summary() {
            return new IngestSummary(received, created, updated, duplicates, stale, conflicts, rejected, diffs);
        }
    }

    private record Existing(UUID id, long version, String hash) {}

    private final JdbcClient jdbc;
    private final DomainEvents events;
    private final EnvelopeCodec codec;
    private final TransactionTemplate tx;
    private final ObjectProvider<MirrorRecordListener> listeners;

    public MirrorService(JdbcClient jdbc, DomainEvents events, EnvelopeCodec codec,
            PlatformTransactionManager txManager, ObjectProvider<MirrorRecordListener> listeners) {
        this.jdbc = jdbc;
        this.events = events;
        this.codec = codec;
        this.tx = new TransactionTemplate(txManager);
        this.listeners = listeners;
    }

    public IngestSummary ingest(UUID tenantId, UUID siteId, SourceAdapter adapter, SourceBatch batch) {
        return TenantContext.callWith(tenantId, () -> tx.execute(status -> {
            Counts counts = new Counts();
            for (SourceRecord record : batch.records()) {
                counts.received++;
                apply(tenantId, siteId, adapter, record, counts);
            }
            return counts.summary();
        }));
    }

    private void apply(UUID tenantId, UUID siteId, SourceAdapter adapter, SourceRecord record, Counts counts) {
        String system = adapter.sourceSystem();
        List<String> errors = RecordValidator.validate(record);
        if (!errors.isEmpty()) {
            counts.rejected++;
            finding(tenantId, siteId, adapter, record, "DATA_QUALITY", "ERROR",
                    Map.of("violations", errors, "outcome", "REJECTED"));
            return;
        }
        String hash = hash(record.payload());
        Optional<Existing> existing = jdbc.sql("""
                select id, source_version, content_hash from integ.mirror_records
                where tenant_id = :t and source_system = :s and record_kind = :k and source_record_id = :r for update
                """)
                .param("t", tenantId).param("s", system).param("k", record.kind().name())
                .param("r", record.sourceRecordId())
                .query((rs, n) -> new Existing(rs.getObject("id", UUID.class), rs.getLong("source_version"),
                        rs.getString("content_hash").trim()))
                .optional();

        if (existing.isEmpty()) {
            UUID id = Ids.newId();
            insertMirror(tenantId, siteId, adapter, record, hash, id);
            insertRevision(tenantId, id, record, hash);
            counts.created++;
            applied(tenantId, siteId, adapter, record, hash, id, true);
            reconcile(tenantId, siteId, adapter, record, counts);
            return;
        }
        Existing e = existing.get();
        if (record.sourceVersion() > e.version()) {
            jdbc.sql("""
                    update integ.mirror_records set source_version = :v, source_updated_at = :u,
                        payload = cast(:p as jsonb), content_hash = :h, revisions = revisions + 1,
                        last_applied_at = now(), updated_at = now() where id = :id
                    """)
                    .param("v", record.sourceVersion()).param("u", ts(record)).param("p", json(record))
                    .param("h", hash).param("id", e.id()).update();
            insertRevision(tenantId, e.id(), record, hash);
            counts.updated++;
            applied(tenantId, siteId, adapter, record, hash, e.id(), false);
            reconcile(tenantId, siteId, adapter, record, counts);
        } else if (record.sourceVersion() == e.version()) {
            if (e.hash().equals(hash)) {
                counts.duplicates++;
            } else {
                counts.conflicts++;
                finding(tenantId, siteId, adapter, record, "VERSION_CONFLICT", "ERROR",
                        Map.of("stored_hash", e.hash(), "incoming_hash", hash, "outcome", "KEPT_STORED"));
            }
        } else {
            counts.stale++;
            finding(tenantId, siteId, adapter, record, "STALE_VERSION", "INFO",
                    Map.of("stored_version", e.version(), "incoming_version", record.sourceVersion(),
                            "outcome", "IGNORED"));
        }
    }

    private void insertMirror(UUID tenantId, UUID siteId, SourceAdapter adapter, SourceRecord record, String hash,
            UUID id) {
        jdbc.sql("""
                insert into integ.mirror_records (id, tenant_id, site_id, source_system, record_kind,
                    source_record_id, source_version, source_updated_at, payload, content_hash, data_mode)
                values (:id, :t, :site, :s, :k, :r, :v, :u, cast(:p as jsonb), :h, :dm)
                """)
                .param("id", id).param("t", tenantId).param("site", siteId).param("s", adapter.sourceSystem())
                .param("k", record.kind().name()).param("r", record.sourceRecordId())
                .param("v", record.sourceVersion()).param("u", ts(record)).param("p", json(record))
                .param("h", hash).param("dm", adapter.dataMode().name()).update();
    }

    private void insertRevision(UUID tenantId, UUID mirrorId, SourceRecord record, String hash) {
        jdbc.sql("""
                insert into integ.mirror_revisions (id, tenant_id, mirror_id, source_version, source_updated_at,
                    payload, content_hash)
                values (:id, :t, :m, :v, :u, cast(:p as jsonb), :h)
                """)
                .param("id", Ids.newId()).param("t", tenantId).param("m", mirrorId)
                .param("v", record.sourceVersion()).param("u", ts(record)).param("p", json(record))
                .param("h", hash).update();
    }

    private void applied(UUID tenantId, UUID siteId, SourceAdapter adapter, SourceRecord record, String hash,
            UUID mirrorId, boolean created) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("source_system", adapter.sourceSystem());
        payload.put("record_kind", record.kind().name());
        payload.put("source_record_id", record.sourceRecordId());
        payload.put("source_version", record.sourceVersion());
        payload.put("change", created ? "CREATED" : "UPDATED");
        payload.put("content_hash", hash);
        events.record(EventDraft.of(MIRROR_AGGREGATE, mirrorId, MIRROR_EVENT, payload)
                .source(EventSource.SIMULATION).dataMode(adapter.dataMode())
                .actor(ActorType.SERVICE, "svc:adapter:" + adapter.sourceSystem())
                .occurredAt(record.sourceUpdatedAt()).location(EventLocation.site(siteId)));
        JsonNode tree = codec.toTree(record.payload());
        for (MirrorRecordListener listener : listeners.orderedStream().toList()) {
            listener.onApplied(new MirrorRecordListener.Applied(tenantId, siteId, mirrorId, adapter.sourceSystem(),
                    record.kind(), record.sourceRecordId(), record.sourceVersion(), record.sourceUpdatedAt(), tree,
                    created, adapter.dataMode()));
        }
    }

    /** Reconciles an applied record against the twin; currently: shipments vs. dock status. */
    private void reconcile(UUID tenantId, UUID siteId, SourceAdapter adapter, SourceRecord record, Counts counts) {
        if (record.kind() != RecordKind.SHIPMENT) {
            return;
        }
        Object state = record.payload().get("state");
        Object dock = record.payload().get("dock_name");
        if (state == null || dock == null || !RecordValidator.DOCKED_STATES.contains(state.toString())) {
            return;
        }
        Optional<String> twinStatus = jdbc.sql("select status from world.docks where site_id = :s and name = :n")
                .param("s", siteId).param("n", dock.toString()).query(String.class).optional();
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("shipment_state", state.toString());
        detail.put("dock_name", dock.toString());
        detail.put("truck_code", String.valueOf(record.payload().get("truck_code")));
        if (twinStatus.isEmpty()) {
            detail.put("difference", "dock is unknown to the twin");
        } else if (!"OCCUPIED".equals(twinStatus.get())) {
            detail.put("twin_dock_status", twinStatus.get());
            detail.put("difference", "source reports a truck at the dock but the twin dock is not OCCUPIED");
        } else {
            return;
        }
        if (finding(tenantId, siteId, adapter, record, "RECONCILIATION_DIFF", "WARNING", detail)) {
            counts.diffs++;
        }
    }

    /** Inserts a finding once per (record, version, type) and announces it; returns true when it is new. */
    private boolean finding(UUID tenantId, UUID siteId, SourceAdapter adapter, SourceRecord record, String type,
            String severity, Map<String, Object> detail) {
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                insert into integ.findings (id, tenant_id, site_id, source_system, record_kind, source_record_id,
                    source_version, finding_type, severity, detail, data_mode)
                values (:id, :t, :site, :s, :k, :r, :v, :type, :sev, cast(:d as jsonb), :dm)
                on conflict (tenant_id, source_system, record_kind, source_record_id, source_version, finding_type)
                do nothing
                """)
                .param("id", id).param("t", tenantId).param("site", siteId).param("s", adapter.sourceSystem())
                .param("k", record.kind().name()).param("r", record.sourceRecordId())
                .param("v", record.sourceVersion()).param("type", type).param("sev", severity)
                .param("d", codec.write(detail)).param("dm", adapter.dataMode().name()).update();
        if (inserted == 0) {
            return false;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("finding_type", type);
        payload.put("severity", severity);
        payload.put("source_system", adapter.sourceSystem());
        payload.put("record_kind", record.kind().name());
        payload.put("source_record_id", record.sourceRecordId());
        payload.put("source_version", record.sourceVersion());
        payload.put("detail", detail);
        EventEnvelope event = events.record(EventDraft.of(FINDING_AGGREGATE, id, FINDING_EVENT, payload)
                .source(EventSource.SIMULATION).dataMode(adapter.dataMode())
                .actor(ActorType.SERVICE, "svc:adapter:" + adapter.sourceSystem())
                .location(EventLocation.site(siteId)));
        jdbc.sql("update integ.findings set event_id = :e where id = :id")
                .param("e", event.eventId()).param("id", id).update();
        return true;
    }

    private static OffsetDateTime ts(SourceRecord record) {
        return OffsetDateTime.ofInstant(record.sourceUpdatedAt(), ZoneOffset.UTC);
    }

    private String json(SourceRecord record) {
        return codec.write(record.payload());
    }

    /** SHA-256 of the key-sorted JSON payload (the codec orders map entries), so equal content hashes equal. */
    String hash(Map<String, Object> payload) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(codec.write(payload).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
